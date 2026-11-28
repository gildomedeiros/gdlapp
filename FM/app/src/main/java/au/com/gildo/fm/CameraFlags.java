package au.com.gildo.fm;

import static au.com.gildo.fm.ConnectionFlag.*;

/** Each flag keeps its own evidence. A successful zoom cannot confirm a recording. */
final class CameraFlags {
    private static final ConnectionFlag WAITING = pending("Waiting for reply");
    volatile ConnectionFlag link = WAITING, events = WAITING, zoom = WAITING;
    volatile ConnectionFlag recording = idle("Idle");

    void endpointChanged(boolean recordingMayBeActive, boolean tracking) {
        if (!recordingMayBeActive) recording = tracking ? pending("Start requested") : idle("Idle");
        link = events = zoom = WAITING;
    }

    void noNetwork(boolean disabled, boolean active) {
        ConnectionFlag cause = disabled ? idle("Disabled") : failed("No camera Wi-Fi");
        link = events = zoom = cause;
        if (active || recording.level() == Level.GOOD)
            recording = failed(disabled ? "Camera disabled" : "Unconfirmed · no Wi-Fi");
    }

    void recordingEvent(String action, boolean active) {
        if ("start".equals(action)) recording = good("Recording confirmed");
        else if ("stop".equals(action)) recording = active ? pending("Stopped · Start pending") : idle("Stopped");
    }

    void eventFailure() {
        events = failed("Poll failed");
        if (recording.level() == Level.GOOD)
            recording = failed("Last recording status lost");
    }

    void startAccepted() { recording = pending("Start accepted · awaiting event"); }
    void stopFailed(String host) { recording = failed("Stop unconfirmed · " + host); }
}
