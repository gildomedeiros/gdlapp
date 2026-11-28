package au.com.gildo.fm;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CameraRecordingTest {
    private final CameraRecording recording = new CameraRecording();
    private final List<String> actions = new ArrayList<>();
    private long now;

    private final CameraRecording.Exchange camera = (method, path, body) -> {
        actions.add(body);
        return "{}";
    };

    @Test public void fmDefaultsToTwentyMinutesAndAcceptsContinuousRecording() {
        String[] settings=FmService.DEFAULTS.clone();
        assertEquals(20,new CameraSettings(settings).maxRecordingMinutes);
        settings[CameraSettings.FIRST]="0";
        assertEquals(0,new CameraSettings(settings).maxRecordingMinutes);
    }

    @Test public void startsOnceAndRollsAtTheLimit() throws Exception {
        recording.update(1, 1, () -> now, camera);
        now = 59999;
        recording.update(1, 1, () -> now, camera);
        assertEquals(List.of("{\"action\":\"start\"}"), actions);
        now = 60000;
        recording.update(1, 1, () -> now, camera);
        assertEquals(List.of("{\"action\":\"start\"}", "{\"action\":\"stop\"}", "{\"action\":\"start\"}"), actions);
        assertEquals(60000, recording.startedAt());
    }

    @Test public void usesCameraEventForExistingRecordingAndPreservesTimerOnDuplicate() throws Exception {
        recording.observe("{\"recbutton\":{\"action\":\"start\"}}", 0);
        recording.observe("{\"recbutton\":{\"status\":\"start\"}}", 40000);
        now = 55000;
        recording.update(1, 1, () -> now, camera);
        assertTrue(actions.isEmpty());
        now = 60000;
        recording.update(1, 1, () -> now, camera);
        assertEquals(List.of("{\"action\":\"stop\"}", "{\"action\":\"start\"}"), actions);
    }

    @Test public void zeroLimitAndStoppedAimingDoNotRoll() throws Exception {
        recording.update(1, 0, () -> now, camera);
        now = 90000;
        recording.update(1, 0, () -> now, camera);
        recording.update(-1, 0.5, () -> now, camera);
        assertEquals(List.of("{\"action\":\"start\"}"), actions);
    }

    @Test public void busyStartDoesNotChangeModeOrStopExistingVideo() throws Exception {
        assertThrows(IOException.class, () -> recording.update(1, 0.5, () -> now, (method, path, body) -> {
            actions.add(body);
            throw CameraRecording.HttpError.fromResponse(503, "{\"message\":\"Device busy\"}");
        }));
        now = 2999;
        recording.update(1, 0.5, () -> now, camera);
        assertEquals(List.of("{\"action\":\"start\"}"), actions);
        assertEquals(-1, recording.startedAt());
    }

    @Test public void entersMovieModeWhenCameraRequiresIt() throws Exception {
        recording.update(1, 0.5, () -> now, (method, path, body) -> {
            actions.add(body);
            if (actions.size() == 1) throw CameraRecording.HttpError.fromResponse(503, "{\"message\":\"Mode not supported\"}");
            return "{}";
        });
        assertEquals(List.of("{\"action\":\"start\"}", "{\"action\":\"on\"}", "{\"action\":\"start\"}"), actions);
        assertEquals(0, recording.startedAt());
    }

    @Test public void doesNotStartAgainAfterUnconfirmedStop() throws Exception {
        recording.observe("{\"recbutton\":{\"action\":\"start\"}}", 0);
        now = 60000;
        assertThrows(IOException.class, () -> recording.update(1, 1, () -> now, (method, path, body) -> {
            actions.add(body);
            throw new IOException("Timeout");
        }));
        assertEquals(List.of("{\"action\":\"stop\"}"), actions);
        assertEquals(0, recording.startedAt());
    }

    @Test public void startsNextClipWhenCameraAlreadyStoppedBeforeTheStopCommand() throws Exception {
        recording.observe("{\"recbutton\":{\"action\":\"start\"}}", 0);
        now = 60000;
        recording.update(1, 1, () -> now, (method, path, body) -> {
            actions.add(body);
            if (actions.size() == 1) throw CameraRecording.HttpError.fromResponse(400, "{\"message\":\"Invalid parameter\"}");
            return "{}";
        });
        assertEquals(List.of("{\"action\":\"stop\"}", "{\"action\":\"start\"}"), actions);
    }
}
