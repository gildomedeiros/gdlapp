package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupWifiTest {
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
