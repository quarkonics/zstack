package org.zstack.test.integration.kvm.vm

import org.springframework.http.HttpEntity
import org.zstack.compute.vm.VmSystemTags
import org.zstack.core.db.DatabaseFacade
import org.zstack.header.host.HostVO
import org.zstack.header.image.ImagePlatform
import org.zstack.header.network.service.NetworkServiceType
import org.zstack.header.vm.VmInstanceState
import org.zstack.header.vm.VmInstanceVO
import org.zstack.kvm.KVMAgentCommands
import org.zstack.kvm.KVMConstant
import org.zstack.network.securitygroup.SecurityGroupConstant
import org.zstack.network.service.virtualrouter.VirtualRouterConstant
import org.zstack.sdk.CreateSystemTagResult
import org.zstack.sdk.HostInventory
import org.zstack.sdk.InstanceOfferingInventory
import org.zstack.sdk.SystemTagInventory
import org.zstack.sdk.UpdateVmInstanceAction
import org.zstack.sdk.VmInstanceInventory
import org.zstack.test.integration.kvm.KvmTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.data.SizeUnit
import org.zstack.utils.gson.JSONObjectUtil

import static org.zstack.utils.CollectionDSL.e
import static org.zstack.utils.CollectionDSL.map

/**
 * Created by AlanJager on 2017/4/26.
 */
class ChangeVmCpuAndMemoryCase extends SubCase {
    EnvSpec env
    VmInstanceInventory vm
    DatabaseFacade dbf
    SystemTagInventory systemTagInventory

    @Override
    void clean() {
        env.delete()
    }

    @Override
    void setup() {
        useSpring(KvmTest.springSpec)
    }

    @Override
    void environment() {
        env = env {
            instanceOffering {
                name = "instanceOffering"
                memory = SizeUnit.GIGABYTE.toByte(2)
                cpu = 1
            }

            instanceOffering {
                name = "instanceOffering2"
                memory = SizeUnit.GIGABYTE.toByte(4)
                cpu = 2
            }

            diskOffering {
                name = "diskOffering"
                diskSize = SizeUnit.GIGABYTE.toByte(20)
            }

            sftpBackupStorage {
                name = "sftp"
                url = "/sftp"
                username = "root"
                password = "password"
                hostname = "localhost"

                image {
                    name = "image1"
                    url = "http://zstack.org/download/test.qcow2"
                }

                image {
                    name = "vr"
                    url = "http://zstack.org/download/vr.qcow2"
                }
            }

            zone {
                name = "zone"
                description = "test"

                cluster {
                    name = "cluster"
                    hypervisorType = "KVM"

                    kvm {
                        name = "kvm"
                        managementIp = "localhost"
                        username = "root"
                        password = "password"
                    }

                    attachPrimaryStorage("local")
                    attachL2Network("l2")
                }

                localPrimaryStorage {
                    name = "local"
                    url = "/local_ps"
                }

                l2NoVlanNetwork {
                    name = "l2"
                    physicalInterface = "eth0"

                    l3Network {
                        name = "l3"

                        service {
                            provider = VirtualRouterConstant.PROVIDER_TYPE
                            types = [NetworkServiceType.DHCP.toString(), NetworkServiceType.DNS.toString()]
                        }

                        service {
                            provider = SecurityGroupConstant.SECURITY_GROUP_PROVIDER_TYPE
                            types = [SecurityGroupConstant.SECURITY_GROUP_NETWORK_SERVICE_TYPE]
                        }

                        ip {
                            startIp = "192.168.100.10"
                            endIp = "192.168.100.100"
                            netmask = "255.255.255.0"
                            gateway = "192.168.100.1"
                        }
                    }

                    l3Network {
                        name = "pubL3"

                        ip {
                            startIp = "12.16.10.10"
                            endIp = "12.16.10.100"
                            netmask = "255.255.255.0"
                            gateway = "12.16.10.1"
                        }
                    }
                }

                virtualRouterOffering {
                    name = "vr"
                    memory = SizeUnit.MEGABYTE.toByte(512)
                    cpu = 2
                    useManagementL3Network("pubL3")
                    usePublicL3Network("pubL3")
                    useImage("vr")
                }

                attachBackupStorage("sftp")
            }

            vm {
                name = "vm"
                useInstanceOffering("instanceOffering")
                useImage("image1")
                useL3Networks("l3")
            }
        }
    }

    @Override
    void test() {
        env.create {
            vm = env.inventoryByName("vm")

            dbf = bean(DatabaseFacade.class)

            systemTagInventory = createSystemTag {
                resourceUuid = vm.uuid
                resourceType = VmInstanceVO.class.getSimpleName()
                tag = VmSystemTags.INSTANCEOFFERING_ONLIECHANGE.instantiateTag(map(e(VmSystemTags.INSTANCEOFFERING_ONLINECHANGE_TOKEN, "true")))
            }

            testOnlineChangeCpuAndMemory()
            testChangeCpuAndMemoryWhenVmStopped()
            testChangeCpuWhenVmRunning()
            testChangeMemoryWhenVmRunning()
            testFailureCameoutAfterAllocateHostCapacityTheCapacityWillBeReturned()
            testDecreaseVmCpuAndMemoryReturnFail()
            testPlatformFailureWhenVmIsRunning()
            testPlatformWhenVmStopped()
        }
    }

    void testOnlineChangeCpuAndMemory() {
        InstanceOfferingInventory instanceOffering = env.inventoryByName("instanceOffering2")
        assert vm.getCpuNum() == 1
        assert vm.getMemorySize() == SizeUnit.GIGABYTE.toByte(2)

        KVMAgentCommands.IncreaseCpuCmd cmd = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_CPU) { KVMAgentCommands.IncreaseCpuResponse rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseCpuCmd.class)
            rsp.cpuNum = instanceOffering.cpuNum
            rsp.success = true
            return rsp
        }

        KVMAgentCommands.IncreaseMemoryCmd cmd2 = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_MEMORY) { KVMAgentCommands.IncreaseMemoryResponse rsp, HttpEntity<String> e ->
            cmd2 = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseMemoryCmd.class)
            rsp.memorySize = instanceOffering.memorySize
            rsp.success = true
            return rsp
        }

        assert VmSystemTags.INSTANCEOFFERING_ONLIECHANGE
                .getTokenByResourceUuid(vm.getUuid(), VmSystemTags.INSTANCEOFFERING_ONLINECHANGE_TOKEN) == "true"
        VmInstanceInventory result = changeInstanceOffering {
            vmInstanceUuid = vm.uuid
            instanceOfferingUuid = instanceOffering.uuid
        }

        retryInSecs {
            return {
                assert cmd != null
                assert cmd2 != null
                assert result.cpuNum == 2
                assert result.memorySize == SizeUnit.GIGABYTE.toByte(4)
            }
        }

        env.cleanAfterSimulatorHandlers()
    }

    void testChangeCpuAndMemoryWhenVmStopped() {
        InstanceOfferingInventory instanceOffering = env.inventoryByName("instanceOffering")

        KVMAgentCommands.IncreaseCpuCmd cmd = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_CPU) { KVMAgentCommands.IncreaseCpuResponse rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseCpuCmd.class)
            rsp.cpuNum = instanceOffering.cpuNum
            rsp.success = true
            return rsp
        }

        KVMAgentCommands.IncreaseMemoryCmd cmd2 = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_MEMORY) { KVMAgentCommands.IncreaseMemoryResponse rsp, HttpEntity<String> e ->
            cmd2 = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseMemoryCmd.class)
            rsp.memorySize = instanceOffering.memorySize
            rsp.success = true
            return rsp
        }

        stopVmInstance {
            uuid = vm.uuid
        }
        VmInstanceVO vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Stopped

        VmInstanceInventory result = changeInstanceOffering {
            vmInstanceUuid = vm.uuid
            instanceOfferingUuid = instanceOffering.uuid
        }
        assert result.cpuNum == 1
        assert result.memorySize == SizeUnit.GIGABYTE.toByte(2)

        env.cleanAfterSimulatorHandlers()
    }

    void testChangeCpuWhenVmRunning() {
        KVMAgentCommands.IncreaseCpuCmd cmd = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_CPU) { KVMAgentCommands.IncreaseCpuResponse rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseCpuCmd.class)
            rsp.cpuNum = 8
            rsp.success = true
            return rsp
        }

        startVmInstance {
            uuid = vm.uuid
        }
        VmInstanceVO vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Running

        VmInstanceInventory vmInstanceInventory = updateVmInstance {
            uuid = vm.uuid
            cpuNum = 8
        }

        retryInSecs {
            return {
                assert vmInstanceInventory.cpuNum == 8
                assert cmd != null
            }
        }

        env.cleanAfterSimulatorHandlers()
    }

    void testChangeMemoryWhenVmRunning() {
        KVMAgentCommands.IncreaseMemoryCmd cmd = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_MEMORY) { KVMAgentCommands.IncreaseMemoryResponse rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseMemoryCmd.class)
            rsp.memorySize = SizeUnit.GIGABYTE.toByte(8)
            rsp.success = true
            return rsp
        }

        VmInstanceVO vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Running

        updateVmInstance {
            uuid = vm.uuid
            memorySize = SizeUnit.GIGABYTE.toByte(8)
        }

        retryInSecs {
            vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
            return {
                assert vo.memorySize == SizeUnit.GIGABYTE.toByte(8)
                assert cmd != null
            }
        }

        env.cleanAfterSimulatorHandlers()
    }

    void testDecreaseVmCpuAndMemoryReturnFail() {
        UpdateVmInstanceAction updateVmInstanceAction = new UpdateVmInstanceAction()
        updateVmInstanceAction.uuid = vm.uuid
        updateVmInstanceAction.cpuNum = 1
        updateVmInstanceAction.sessionId = adminSession()
        UpdateVmInstanceAction.Result updateVmInstanceResult = updateVmInstanceAction.call()
        assert updateVmInstanceResult.error != null

        UpdateVmInstanceAction updateVmInstanceAction2 = new UpdateVmInstanceAction()
        updateVmInstanceAction2.uuid = vm.uuid
        updateVmInstanceAction2.memorySize = SizeUnit.GIGABYTE.toByte(2)
        updateVmInstanceAction2.sessionId = adminSession()
        UpdateVmInstanceAction.Result updateVmInstanceResult2 = updateVmInstanceAction2.call()
        assert updateVmInstanceResult2.error != null
    }

    void testFailureCameoutAfterAllocateHostCapacityTheCapacityWillBeReturned() {
        HostInventory host = env.inventoryByName("kvm")

        KVMAgentCommands.IncreaseCpuCmd cmd = null
        env.afterSimulator(KVMConstant.KVM_VM_ONLINE_INCREASE_CPU) { KVMAgentCommands.IncreaseCpuResponse rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, KVMAgentCommands.IncreaseCpuCmd.class)
            rsp.error = "on purpose"
            rsp.success = false
            return rsp
        }

        UpdateVmInstanceAction updateVmInstanceAction = new UpdateVmInstanceAction()
        updateVmInstanceAction.uuid = vm.uuid
        updateVmInstanceAction.cpuNum = 9
        updateVmInstanceAction.memorySize = SizeUnit.GIGABYTE.toByte(10)
        updateVmInstanceAction.sessionId = adminSession()
        UpdateVmInstanceAction.Result updateVmInstanceResult = updateVmInstanceAction.call()
        assert updateVmInstanceResult.error != null

        HostVO vo
        retryInSecs {
            vo = dbFindByUuid(host.uuid, HostVO.class)
            return {
                assert vo.getCapacity().getAvailableCpu() == vo.getCapacity().getTotalCpu() - 10
                assert vo.getCapacity().getAvailableMemory() == vo.getCapacity().getTotalMemory() - SizeUnit.GIGABYTE.toByte(8) - SizeUnit.MEGABYTE.toByte(512)
                assert cmd != null
            }
        }

        env.cleanAfterSimulatorHandlers()
    }

    void testUpdateCpuOrMemoryWhenVMisUnknownOrDestroy() {
        VmInstanceVO vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        vo.setState(VmInstanceState.Unknown)
        dbf.updateAndRefresh(vo)
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Unknown
        UpdateVmInstanceAction updateVmInstanceAction = new UpdateVmInstanceAction()
        updateVmInstanceAction.uuid = vm.uuid
        updateVmInstanceAction.cpuNum = 20
        updateVmInstanceAction.sessionId = adminSession()
        UpdateVmInstanceAction.Result updateVmInstanceResult = updateVmInstanceAction.call()
        assert updateVmInstanceResult.error != null

        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        vo.setState(VmInstanceState.Destroyed)
        dbf.updateAndRefresh(vo)
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Destroyed
        UpdateVmInstanceAction updateVmInstanceAction2 = new UpdateVmInstanceAction()
        updateVmInstanceAction2.uuid = vm.uuid
        updateVmInstanceAction2.cpuNum = 20
        updateVmInstanceAction2.sessionId = adminSession()
        UpdateVmInstanceAction.Result updateVmInstanceResult2 = updateVmInstanceAction.call()
        assert updateVmInstanceResult2.error != null
    }

    void testPlatformFailureWhenVmIsRunning() {
        VmInstanceVO vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        vo.setPlatform(ImagePlatform.Windows.toString())
        dbf.updateAndRefresh(vo)
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.platform == ImagePlatform.Windows.toString()
        UpdateVmInstanceAction updateVmInstanceAction = new UpdateVmInstanceAction()
        updateVmInstanceAction.uuid = vm.uuid
        updateVmInstanceAction.cpuNum = 20
        updateVmInstanceAction.sessionId = adminSession()
        UpdateVmInstanceAction.Result updateVmInstanceResult = updateVmInstanceAction.call()
        assert updateVmInstanceResult.error != null


        vo.setPlatform(ImagePlatform.WindowsVirtio.toString())
        dbf.updateAndRefresh(vo)
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.platform == ImagePlatform.WindowsVirtio.toString()
        updateVmInstanceAction = new UpdateVmInstanceAction()
        updateVmInstanceAction.uuid = vm.uuid
        updateVmInstanceAction.cpuNum = 20
        updateVmInstanceAction.sessionId = adminSession()
        updateVmInstanceResult = updateVmInstanceAction.call()
        assert updateVmInstanceResult.error != null


        vo.setPlatform(ImagePlatform.Other.toString())
        dbf.updateAndRefresh(vo)
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.platform == ImagePlatform.Other.toString()
        updateVmInstanceAction = new UpdateVmInstanceAction()
        updateVmInstanceAction.uuid = vm.uuid
        updateVmInstanceAction.cpuNum = 20
        updateVmInstanceAction.sessionId = adminSession()
        updateVmInstanceResult = updateVmInstanceAction.call()
        assert updateVmInstanceResult.error != null
    }

    void testPlatformWhenVmStopped() {
        VmInstanceVO vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.platform == ImagePlatform.Other.toString()
        stopVmInstance {
            uuid = vm.uuid
        }
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Stopped

        updateVmInstance {
            uuid = vm.uuid
            cpuNum = 20
        }
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.cpuNum == 20

        startVmInstance {
            uuid = vm.uuid
        }
        vo = dbFindByUuid(vm.uuid, VmInstanceVO.class)
        assert vo.state == VmInstanceState.Running
    }
}
