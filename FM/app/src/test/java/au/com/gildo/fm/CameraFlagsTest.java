package au.com.gildo.fm;

import org.junit.Test;
import static au.com.gildo.fm.ConnectionFlag.Level.*;
import static org.junit.Assert.*;

public class CameraFlagsTest {
    @Test public void startReplyAndEmptyEventPollCannotClaimRecordingIsConfirmed() {
        var flags = new CameraFlags();
        flags.startAccepted();
        flags.recordingEvent("", true);
        assertEquals(PENDING, flags.recording.level());
        flags.recordingEvent("start", true);
        assertEquals(GOOD, flags.recording.level());
        flags.eventFailure();
        flags.recordingEvent("", true);
        assertEquals(FAILED, flags.recording.level());
        flags.recordingEvent("start", true);
        assertEquals(GOOD, flags.recording.level());
    }

    @Test public void oldCameraStopFailureSurvivesNewEndpointAndZoomRecovery() {
        var flags = new CameraFlags();
        flags.stopFailed("192.168.4.3:8080");
        flags.endpointChanged(true, true);
        flags.zoom = ConnectionFlag.good("Readback OK");
        assertEquals(FAILED, flags.recording.level());
        assertTrue(flags.recording.label().contains("192.168.4.3:8080"));
    }

    @Test public void wifiLossDuringTrackingLeavesRecordingUnconfirmed() {
        var flags = new CameraFlags();
        flags.recordingEvent("start", true);
        flags.noNetwork(false, true);
        assertEquals(FAILED, flags.link.level());
        assertEquals(FAILED, flags.recording.level());
        flags.recordingEvent("stop", false);
        assertEquals(IDLE, flags.recording.level());
    }
}
