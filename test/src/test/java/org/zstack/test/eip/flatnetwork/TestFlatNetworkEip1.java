package org.zstack.test.eip.flatnetwork;

import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.componentloader.ComponentLoader;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.header.identity.SessionInventory;
import org.zstack.header.network.l3.L3NetworkVO;
import org.zstack.header.vm.VmInstanceInventory;
import org.zstack.header.vm.VmNicInventory;
import org.zstack.header.vm.VmNicVO;
import org.zstack.kvm.KVMSystemTags;
import org.zstack.network.service.eip.EipInventory;
import org.zstack.network.service.flat.FlatEipBackend.ApplyEipCmd;
import org.zstack.network.service.flat.FlatEipBackend.DeleteEipCmd;
import org.zstack.network.service.flat.FlatEipBackend.EipTO;
import org.zstack.network.service.flat.FlatNetworkServiceSimulatorConfig;
import org.zstack.network.service.vip.VipVO;
import org.zstack.simulator.kvm.KVMSimulatorConfig;
import org.zstack.test.Api;
import org.zstack.test.ApiSenderException;
import org.zstack.test.DBUtil;
import org.zstack.test.WebBeanConstructor;
import org.zstack.test.deployer.Deployer;

/**
 * @author frank
 * @condition 1. create a vm
 * 2. set eip
 * <p>
 * confirm eip works
 * <p>
 * 3. detach the eip
 * <p>
 * confirm eip detached
 * <p>
 * 4. attach the eip
 * <p>
 * confirm the eip attached
 * <p>
 * 5. delete the eip
 * <p>
 * confirm the eip deleted
 */
public class TestFlatNetworkEip1 {
    Deployer deployer;
    Api api;
    ComponentLoader loader;
    CloudBus bus;
    DatabaseFacade dbf;
    SessionInventory session;
    FlatNetworkServiceSimulatorConfig fconfig;
    KVMSimulatorConfig kconfig;

    @Before
    public void setUp() throws Exception {
        DBUtil.reDeployDB();
        WebBeanConstructor con = new WebBeanConstructor();
        deployer = new Deployer("deployerXml/eip/TestFlatNetworkEip.xml", con);
        deployer.addSpringConfig("flatNetworkServiceSimulator.xml");
        deployer.addSpringConfig("flatNetworkProvider.xml");
        deployer.addSpringConfig("KVMRelated.xml");
        deployer.addSpringConfig("vip.xml");
        deployer.addSpringConfig("eip.xml");
        deployer.build();
        api = deployer.getApi();
        loader = deployer.getComponentLoader();
        fconfig = loader.getComponent(FlatNetworkServiceSimulatorConfig.class);
        kconfig = loader.getComponent(KVMSimulatorConfig.class);
        bus = loader.getComponent(CloudBus.class);
        dbf = loader.getComponent(DatabaseFacade.class);
        session = api.loginAsAdmin();
    }

    private String getBridgeName(String l3uuid) {
        L3NetworkVO l3 = dbf.findByUuid(l3uuid, L3NetworkVO.class);
        return KVMSystemTags.L2_BRIDGE_NAME.getTokenByResourceUuid(l3.getL2NetworkUuid(), KVMSystemTags.L2_BRIDGE_NAME_TOKEN);
    }

    @Test
    public void test() throws ApiSenderException {
        Assert.assertEquals(1, fconfig.applyEipCmds.size());
        ApplyEipCmd cmd = fconfig.applyEipCmds.get(0);
        EipTO to = cmd.eip;

        EipInventory eip = deployer.eips.get("eip");
        VipVO vip = dbf.findByUuid(eip.getVipUuid(), VipVO.class);

        VmNicVO nicvo = dbf.findByUuid(eip.getVmNicUuid(), VmNicVO.class);
        Assert.assertEquals(vip.getIp(), to.vip);
        Assert.assertEquals(nicvo.getIp(), to.nicIp);
        Assert.assertEquals(nicvo.getVmInstanceUuid(), to.vmUuid);
        Assert.assertEquals(nicvo.getMac(), to.nicMac);
        Assert.assertEquals(nicvo.getInternalName(), to.nicName);
        Assert.assertEquals(getBridgeName(nicvo.getL3NetworkUuid()), to.vmBridgeName);
        Assert.assertEquals(getBridgeName(vip.getL3NetworkUuid()), to.publicBridgeName);
        Assert.assertEquals(nicvo.getGateway(), to.nicGateway);
        Assert.assertEquals(nicvo.getNetmask(), to.nicNetmask);
        Assert.assertEquals(vip.getNetmask(), to.vipNetmask);
        Assert.assertEquals(vip.getGateway(), to.vipGateway);

        VmInstanceInventory vm = deployer.vms.get("TestVm");
        api.detachEip(eip.getUuid());

        Assert.assertEquals(1, fconfig.deleteEipCmds.size());
        DeleteEipCmd dcmd = fconfig.deleteEipCmds.get(0);
        to = dcmd.eip;
        Assert.assertEquals(vip.getIp(), to.vip);
        Assert.assertEquals(nicvo.getIp(), to.nicIp);
        Assert.assertEquals(nicvo.getVmInstanceUuid(), to.vmUuid);
        Assert.assertEquals(nicvo.getMac(), to.nicMac);
        Assert.assertEquals(nicvo.getInternalName(), to.nicName);
        Assert.assertEquals(getBridgeName(nicvo.getL3NetworkUuid()), to.vmBridgeName);
        Assert.assertEquals(getBridgeName(vip.getL3NetworkUuid()), to.publicBridgeName);
        Assert.assertEquals(nicvo.getGateway(), to.nicGateway);
        Assert.assertEquals(nicvo.getNetmask(), to.nicNetmask);
        Assert.assertEquals(vip.getNetmask(), to.vipNetmask);
        Assert.assertEquals(vip.getGateway(), to.vipGateway);

        // attach eip
        fconfig.applyEipCmds.clear();
        VmNicInventory nic = vm.getVmNics().get(0);
        eip = api.attachEip(eip.getUuid(), nic.getUuid());
        cmd = fconfig.applyEipCmds.get(0);
        to = cmd.eip;
        Assert.assertEquals(vip.getIp(), to.vip);
        Assert.assertEquals(nicvo.getIp(), to.nicIp);
        Assert.assertEquals(nicvo.getVmInstanceUuid(), to.vmUuid);
        Assert.assertEquals(nicvo.getMac(), to.nicMac);
        Assert.assertEquals(nicvo.getInternalName(), to.nicName);
        Assert.assertEquals(getBridgeName(nicvo.getL3NetworkUuid()), to.vmBridgeName);
        Assert.assertEquals(getBridgeName(vip.getL3NetworkUuid()), to.publicBridgeName);

        // delete eip
        fconfig.deleteEipCmds.clear();
        api.removeEip(eip.getUuid());
        dcmd = fconfig.deleteEipCmds.get(0);
        to = dcmd.eip;
        Assert.assertEquals(vip.getIp(), to.vip);
        Assert.assertEquals(nicvo.getIp(), to.nicIp);
        Assert.assertEquals(nicvo.getVmInstanceUuid(), to.vmUuid);
        Assert.assertEquals(nicvo.getMac(), to.nicMac);
        Assert.assertEquals(nicvo.getInternalName(), to.nicName);
        Assert.assertEquals(getBridgeName(nicvo.getL3NetworkUuid()), to.vmBridgeName);
        Assert.assertEquals(getBridgeName(vip.getL3NetworkUuid()), to.publicBridgeName);
    }
}
