package au.com.gildo.fm;

import java.util.List;

/** GPS input driven by the on-screen pad. Polled on the same worker as receiver input. */
final class GpsPadSimulator {
    static final int DEFAULT_RATE_HZ = 1;
    static final String RATE_PREF = "Simulator raw fixes per second";
    private int rateHz = DEFAULT_RATE_HZ;
    private long lastFixAt;
    static final String DEFAULT_STEP = "2";
    private static final long UINT32 = 0xffffffffL;

    enum Move {
        UP_LEFT("↖", "Up-left, northwest", 315), UP("↑", "Up, north", 0), UP_RIGHT("↗", "Up-right, northeast", 45),
        LEFT("←", "Left, west", 270), RIGHT("→", "Right, east", 90),
        DOWN_LEFT("↙", "Down-left, southwest", 225), DOWN("↓", "Down, south", 180), DOWN_RIGHT("↘", "Down-right, southeast", 135);

        final String arrow, label;
        final int bearing;

        Move(String arrow, String label, int bearing) {
            this.arrow = arrow;
            this.label = label;
            this.bearing = bearing;
        }
    }

    record Tap(long number, GoTo.Point position) {}
    record Playback(long number, long pressedAt, List<GoTo.Point> fixes, boolean measurePan) {}
    record ModeStart(Long selectedAt, SurferModes.Mode timedOutMode, long limitMs) {}
    record Sample(String line, long lastTap, Playback playback, int playbackFix, SurferModes.Mode mode,
                  ModeStart modeStart) {
        boolean firstPlaybackFix() { return playbackFix == 1; }
    }

    final String id = java.util.UUID.randomUUID().toString().substring(0, 12);
    private final GoTo.Point landmark;
    private GoTo.Point position;
    private long emitted, nextFixAt, taps;
    private Playback playback;
    private SurferModes.Continuous continuous;
    private Long pendingModeSelectedAt;
    private long modeStartedAt = -1, modeLimitMs;
    private long playbacks, firstReplyMs = -1;
    private int playbackIndex;

    GpsPadSimulator(double latitude, double longitude) {
        if (!Geo.valid(latitude, longitude)) throw new IllegalArgumentException("Invalid landmark coordinates");
        landmark = new GoTo.Point(latitude, longitude, Double.NaN);
        position = landmark;
    }

    synchronized void setRateHz(int rate) {
        if (rate != 1 && rate != 2) throw new IllegalArgumentException("Select 1 or 2 raw fixes per second");
        if (rate == rateHz) return;
        rateHz = rate;
        if (nextFixAt != 0) nextFixAt = lastFixAt + 1000 / rateHz;
    }

    synchronized Tap move(Move move, double metres) {
        if (!Double.isFinite(metres) || metres <= 0)
            throw new IllegalArgumentException("Enter a positive offset in metres");
        playback = null;
        continuous = null;
        pendingModeSelectedAt = null;
        position = Geo.destination(position, move.bearing, metres);
        return new Tap(++taps, position);
    }

    synchronized SurferModes.Mode mode() { return continuous == null ? null : continuous.mode; }

    synchronized boolean selectMode(SurferModes.Mode mode, double bearing, long selectedAt, long limitMs) {
        if (mode == mode()) return false;
        continuous = mode == null ? null : new SurferModes.Continuous(mode, position, bearing);
        pendingModeSelectedAt = mode == null ? null : selectedAt;
        modeStartedAt = -1;
        modeLimitMs = limitMs;
        playback = null;
        return true;
    }

    synchronized Playback apply(List<GoTo.Point> prepared, long pressedAt, boolean measurePan) {
        continuous = null;
        pendingModeSelectedAt = null;
        playback = new Playback(++playbacks, pressedAt, prepared, measurePan);
        playbackIndex = 0;
        firstReplyMs = -1;
        nextFixAt = 0;
        return playback;
    }

    synchronized boolean recordFirstReply(Playback receivedPlayback, long replyMs) {
        if (receivedPlayback == null || playback != receivedPlayback || !playback.measurePan()
                || firstReplyMs >= 0 || replyMs < playback.pressedAt()) return false;
        firstReplyMs = replyMs - playback.pressedAt();
        return true;
    }

    synchronized String playbackStatus() {
        if (playback == null) return "";
        int total = playback.fixes().size();
        String progress = playbackIndex < total ? "Playing fixes · " + playbackIndex + "/" + total : "Holding the final fix";
        if (!playback.measurePan()) return progress;
        return progress + "\nApply to first pan Start reply · " + (firstReplyMs < 0 ? "waiting" : firstReplyMs + " ms");
    }

    synchronized Sample poll(long now) {
        if (now < nextFixAt) return null;
        boolean first = emitted == 0;
        int routeIndex = 0;
        ModeStart modeStart = null;
        if (playback != null && playbackIndex < playback.fixes().size()) {
            position = playback.fixes().get(playbackIndex++);
            routeIndex = playbackIndex;
        } else if (!first && continuous != null) {
            if (modeLimitMs > 0 && modeStartedAt >= 0 && now - modeStartedAt >= modeLimitMs) {
                modeStart = new ModeStart(null, continuous.mode, modeLimitMs);
                continuous = new SurferModes.Continuous(SurferModes.Mode.SITTING, position, continuous.bearing);
                modeLimitMs = 0;
            } else {
                position = continuous.advance();
                if (rateHz == 1) position = continuous.advance();
                if (pendingModeSelectedAt != null) {
                    modeStartedAt = now;
                    modeStart = new ModeStart(pendingModeSelectedAt, null, modeLimitMs);
                    pendingModeSelectedAt = null;
                }
            }
        }
        GoTo.Point fix = first && playback == null ? landmark : position;
        lastFixAt = now;
        long intervalMs = 1000 / rateHz;
        long scheduled = routeIndex > 1 || (!first && continuous != null)
            ? nextFixAt + intervalMs : now + intervalMs;
        nextFixAt = scheduled > now ? scheduled : now + intervalMs;
        String line = "RX,GPS," + (++emitted & UINT32) + "," + (now & UINT32)
            + "," + fix.lat() + "," + fix.lon() + ",1,1,0,0,0";
        long lastTap = first || playback != null || continuous != null ? 0 : taps;
        return new Sample(line, lastTap, playback, routeIndex, mode(), modeStart);
    }
}
