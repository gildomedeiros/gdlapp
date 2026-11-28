package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupWifiTest {
    @Test public void startupProgressFollowsBothSearchesOnlyOnFirstHotspotConnection() {
        StartupWifi wifi=new StartupWifi();
        assertEquals("",wifi.progress());
        wifi.networkChanged(true);
        assertFalse(wifi.takeDiscovery(false));
        assertEquals("",wifi.progress());
        assertTrue(wifi.takeDiscovery(true));
        assertEquals("Finding GTi…",wifi.progress());
        assertTrue(wifi.startCanon(wifi.routeRevision(),true));
        assertEquals("Finding Canon SX…",wifi.progress());
        wifi.finishDiscovery(wifi.routeRevision());
        assertEquals("",wifi.progress());
        wifi.networkChanged(false);
        wifi.networkChanged(true);
        assertTrue(wifi.takeDiscovery(true));
        assertTrue(wifi.startCanon(wifi.routeRevision(),true));
        assertEquals("",wifi.progress());
    }

    @Test public void pressingConnectToHotspotAgainShowsBothPhasesAndDiscardsOldResults() {
        StartupWifi wifi=new StartupWifi();
        wifi.networkChanged(true);
        assertTrue(wifi.takeDiscovery(true));
        long old=wifi.routeRevision();
        assertTrue(wifi.startCanon(old,true));
        wifi.hideProgress();
        wifi.request();
        wifi.networkChanged(true);
        assertTrue(wifi.takeDiscovery(true));
        assertEquals("Finding GTi…",wifi.progress());
        assertFalse(wifi.startCanon(old,true));
        assertTrue(wifi.startCanon(wifi.routeRevision(),true));
        wifi.finishDiscovery(old);
        assertEquals("Finding Canon SX…",wifi.progress());
    }

    @Test public void eachManualButtonShowsJustItsDeviceAndCanBeRepeated() {
        StartupWifi wifi=new StartupWifi();
        for(boolean mount:new boolean[]{true,false,false,true}) {
            assertTrue(wifi.canSearchDevice());
            wifi.startDeviceSearch(mount);
            assertFalse(wifi.canSearchDevice());
            assertEquals(mount?"Finding GTi…":"Finding Canon SX…",wifi.progress());
            assertEquals(!mount,wifi.findingCanon(wifi.routeRevision()));
            wifi.hideProgress();
            wifi.finishDiscovery(wifi.routeRevision());
            assertEquals("",wifi.progress());
        }
    }

    @Test public void manualCameraEndsDialogAfterMountAttempt() {
        StartupWifi wifi=new StartupWifi();
        wifi.networkChanged(true);
        wifi.takeDiscovery(true);
        assertFalse(wifi.startCanon(wifi.routeRevision(),false));
        assertFalse(wifi.findingCanon(wifi.routeRevision()));
        assertEquals("",wifi.progress());
    }

    @Test public void hiddenDialogAllowsCameraSearchAndNetworkLossEndsIt() {
        StartupWifi wifi=new StartupWifi();
        wifi.networkChanged(true);
        wifi.takeDiscovery(true);
        wifi.hideProgress();
        assertTrue(wifi.startCanon(wifi.routeRevision(),true));
        assertTrue(wifi.findingCanon(wifi.routeRevision()));
        assertEquals("",wifi.progress());
        wifi.networkChanged(false);
        assertFalse(wifi.findingCanon(wifi.routeRevision()));
        assertEquals("",wifi.progress());
    }

    @Test public void earlyConnectSupersedesOriginalScanAndDiscoversNewRouteOnce() {
        StartupWifi wifi=new StartupWifi();
        wifi.starting=true;
        assertTrue(wifi.canRequest(true));
        assertFalse(wifi.canUseMount(true));
        assertTrue(wifi.useOriginalRoute());
        wifi.request();
        assertFalse(wifi.canRequest(true));
        assertFalse(wifi.useOriginalRoute());
        wifi.networkChanged(true);
        assertFalse(wifi.takeDiscovery(wifi.canUseMount(true)));
        wifi.starting=false;
        assertTrue(wifi.takeDiscovery(wifi.canUseMount(true)));
        assertFalse(wifi.takeDiscovery(wifi.canUseMount(true)));
    }

    @Test public void pendingAndroidRequestStillBlocksMotorWorkAfterStartupEnds() {
        StartupWifi wifi=new StartupWifi();
        wifi.starting=true;
        wifi.request();
        wifi.starting=false;
        assertFalse(wifi.canUseMount(true));
        wifi.networkChanged(false);
        assertTrue(wifi.canRequest(true));
        assertFalse(wifi.takeDiscovery(wifi.canUseMount(true)));
        wifi.request();
        wifi.networkChanged(true);
        assertTrue(wifi.takeDiscovery(wifi.canUseMount(true)));
    }

    @Test public void busyServiceCannotSwitchWifiAndLostNetworkCancelsQueuedDiscovery() {
        StartupWifi wifi=new StartupWifi();
        assertFalse(wifi.canRequest(false));
        wifi.networkChanged(true);
        assertFalse(wifi.takeDiscovery(wifi.canUseMount(false)));
        wifi.networkChanged(false);
        assertFalse(wifi.takeDiscovery(wifi.canUseMount(true)));
        assertTrue(wifi.useOriginalRoute());
    }
}
