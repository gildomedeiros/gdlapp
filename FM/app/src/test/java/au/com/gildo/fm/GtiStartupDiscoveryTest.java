package au.com.gildo.fm;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GtiStartupDiscoveryTest {
    private static final DeviceDiscovery.Device SAVED=new DeviceDiscovery.Device("192.168.4.2","GTi");
    private static final DeviceDiscovery.Device OTHER=new DeviceDiscovery.Device("192.168.4.4","GTi");

    @Test public void keepsSavedMountWhenSeveralMountsReply() throws IOException {
        assertEquals(SAVED,DeviceDiscovery.selectStartupMount(List.of(OTHER,SAVED),SAVED.address()));
    }

    @Test public void usesTheOnlyMountFoundAtItsNewAddress() throws IOException {
        assertEquals(OTHER,DeviceDiscovery.selectStartupMount(List.of(OTHER),SAVED.address()));
    }

    @Test public void requiresAnExplicitChoiceIfSeveralUnsavedMountsReply() {
        IOException failure=assertThrows(IOException.class,()->DeviceDiscovery.selectStartupMount(
            List.of(OTHER,SAVED),"192.168.4.10"));
        assertTrue(failure.getMessage().contains("Several GTi mounts"));
    }

    @Test public void reportsThatNoMountReplied() {
        IOException failure=assertThrows(IOException.class,()->DeviceDiscovery.selectStartupMount(
            List.of(),SAVED.address()));
        assertTrue(failure.getMessage().contains("No GTi replied"));
    }
}
