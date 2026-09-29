package dji.v5.ux.sample.showcase.defaultlayout.aiming;
public final class RecordingGate {
    public static String problem(Boolean recording,long sampledAt,long now) {
        if(recording==null || sampledAt<0 || now<sampledAt || now-sampledAt>2000) return "recording_unknown";
        return recording ? null : "recording_off";
    }
}
