package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
// CAM3 v2.1: Best-effort read-outcome notifications; implementation never performs file I/O here.
import java.util.function.BiConsumer;
import dji.sdk.keyvalue.key.*;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.common.Velocity3D;
import dji.sdk.keyvalue.value.flightcontroller.*;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.KeyManager;

/**
 * CAM3 v2.0: Hardware reads (callback getValue), never freshen a cached read.
 * Each field retains its request-start time. Missing/unsupported keys block aiming.
 * Listeners additionally latch transient pilot/RTH events, even between control ticks.
 */
public final class AircraftAimingTelemetry {
    private final List<Slot<?>> slots = new ArrayList<>();
    // CAM3 v2.1: Name each diagnostic field explicitly; never serialize position coordinates.
    private final Slot<Boolean> connected = slot("connected", FlightControllerKey.KeyConnection);
    private final Slot<Boolean> flying = slot("isFlying", FlightControllerKey.KeyIsFlying);
    private final Slot<LocationCoordinate2D> position = slot("position", FlightControllerKey.KeyAircraftLocation);
    private final Slot<Double> heading = slot("heading", FlightControllerKey.KeyCompassHeading);
    private final Slot<FlightMode> mode = slot("flightMode", FlightControllerKey.KeyFlightMode);
    private final Slot<GPSSignalLevel> gps = slot("gpsLevel", FlightControllerKey.KeyGPSSignalLevel);
    private final Slot<Boolean> compassError = slot("compassError", FlightControllerKey.KeyCompassHasError);
    private final Slot<Velocity3D> velocity = slot("velocity", FlightControllerKey.KeyAircraftVelocity);
    private final Slot<Integer> leftH = slot("stickLeftHorizontal", RemoteControllerKey.KeyStickLeftHorizontal);
    private final Slot<Integer> leftV = slot("stickLeftVertical", RemoteControllerKey.KeyStickLeftVertical);
    private final Slot<Integer> rightH = slot("stickRightHorizontal", RemoteControllerKey.KeyStickRightHorizontal);
    private final Slot<Integer> rightV = slot("stickRightVertical", RemoteControllerKey.KeyStickRightVertical);
    // CAM3 v2.3: Report named pause versus permanent-stop events to the controller.
    private final BiConsumer<String, Boolean> unsafe;
    // CAM3 v2.3: Listener threads see lifecycle and independent connection/mode vetoes.
    private volatile boolean running;
    private volatile boolean connectionBlocked, modeBlocked;
    private volatile long generation;
    private long lastPoll;
    // CAM3 v2.1: Report read failures/recovery immediately, even between summary snapshots.
    private final BiConsumer<String, String> diagnostic;
    public AircraftAimingTelemetry(BiConsumer<String, Boolean> unsafe, BiConsumer<String, String> diagnostic) {
        this.unsafe = unsafe; this.diagnostic = diagnostic;
    }
    private void readOutcome(Slot<?> slot, String error) {
        if (!slot.error.equals(error)) {
            slot.error = error;
            try { diagnostic.accept(slot.name, error); } catch (RuntimeException ignored) { }
        }
    }
    // CAM3 v2.1: Retain field names and read failures without changing read timing or validity rules.
    private <T> Slot<T> slot(String name, DJIKeyInfo<T> info) {
        Slot<T> value = new Slot<>(name, KeyTools.createKey(info)); slots.add(value); return value;
    }
    public synchronized void start() {
        if (running) return;
        running = true; generation++; lastPoll = 0;
        // CAM3 v2.3: Fresh slot reads still gate neutral; discard previous listener lifecycle vetoes.
        connectionBlocked = false; modeBlocked = false;
        // CAM3 v2.3: Ignore detached callbacks; log exact key and values after latching the action.
        final long listeningGeneration = generation;
        KeyManager.getInstance().listen(connected.key, this, (old, value) -> {
            if (!running || generation != listeningGeneration) return;
            connectionBlocked = !Boolean.TRUE.equals(value);
            if (connectionBlocked) unsafe.accept("connection", false);
            diagnostic.accept("listener_connected", "old=" + old + " new=" + value);
        });
        KeyManager.getInstance().listen(mode.key, this, (old, value) -> {
            if (!running || generation != listeningGeneration) return;
            modeBlocked = !allowedMode(value);
            if (modeBlocked) unsafe.accept(value == null ? "telemetry" : modeReason(value), value != null);
            diagnostic.accept("listener_flightMode", "old=" + old + " new=" + value);
        });
        for (Slot<Integer> stick : java.util.Arrays.asList(leftH, leftV, rightH, rightV)) {
            KeyManager.getInstance().listen(stick.key, this, (old, value) -> {
                if (!running || generation != listeningGeneration) return;
                if (value == null || Math.abs((long) value) > 30) unsafe.accept(value == null ? "telemetry" : "pilot_stick", false);
                diagnostic.accept("listener_" + stick.name, "old=" + old + " new=" + value);
            });
        }
    }
    public synchronized void stop() {
        running = false; generation++;
        for (Slot<?> slot : slots) { slot.value = null; slot.time = 0; slot.pending = false; }
        KeyManager.getInstance().cancelListen(this);
    }
    public synchronized void poll() {
        long now = SystemClock.elapsedRealtime();
        if (!running || now - lastPoll < 500) return;
        lastPoll = now;
        for (Slot<?> slot : slots) read(slot, now, generation);
    }
    private <T> void read(Slot<T> slot, long requested, long token) {
        // CAM3 v2.3: Abandon an unanswered read after 2 s; old replies cannot replace newer data.
        if (slot.pending && requested - slot.requested < 2000) return;
        if (slot.pending) { slot.retrying = true; diagnostic.accept("retry_timeout_" + slot.name, "request=" + slot.requestId); }
        if (!slot.error.equals("none") && !slot.error.equals("not_read")) slot.retrying = true;
        long requestId = ++slot.requestId;
        slot.requested = requested;
        if (slot.retrying) diagnostic.accept("retry_request_" + slot.name, "request=" + requestId);
        slot.pending = true;
        try {
            KeyManager.getInstance().getValue(slot.key, new CommonCallbacks.CompletionCallbackWithParam<T>() {
                // CAM3 v2.1: Report SDK failure codes separately from absent values; no coordinates in errors.
                @Override public void onSuccess(T value) { accept(value, value == null ? "null_value" : "none"); }
                @Override public void onFailure(IDJIError error) { accept(null, error == null ? "unknown" : error.errorCode()); }
                private void accept(T value, String error) {
                    synchronized (AircraftAimingTelemetry.this) {
                        // CAM3 v2.3: Make superseded retries observable without accepting their data.
                        if (!running || generation != token || slot.requestId != requestId) {
                            diagnostic.accept("read_ignored_" + slot.name, "request=" + requestId + " superseded_or_detached");
                            return;
                        }
                        slot.pending = false;
                        slot.value = value; slot.time = requested;
                        // CAM3 v2.1: Each field exposes its last read outcome to the diagnostic snapshot.
                        readOutcome(slot, error);
                        if (slot.retrying) {
                            diagnostic.accept("retry_result_" + slot.name, "request=" + requestId + " error=" + error);
                            if ("none".equals(error)) slot.retrying = false;
                        }
                    }
                }
            });
        // CAM3 v2.1: Preserve exception type for diagnosis while retaining existing fail-closed behavior.
        } catch (RuntimeException ex) {
            slot.pending = false; slot.value = null; slot.time = 0; readOutcome(slot, ex.getClass().getSimpleName());
        }
    }
    private static boolean allowedMode(FlightMode value) {
        // CAM3 v2.2: One policy for snapshot, urgent listener and neutral; do not accept arbitrary modes.
        return AimingFlightModes.allows(value == null ? null : value.name());
    }
    // CAM3 v2.3: Preserve the aircraft operation as a permanent-stop reason.
    private static String modeReason(FlightMode value) {
        String name = value.name();
        return name.contains("LANDING") ? "landing" : name.contains("GO_HOME") ? "returning_home" : "flight_mode_rejected";
    }
    // CAM3 v2.2: All independently available aircraft blockers, not only the first failed gate.
    public synchronized String blockers(long now) {
        List<String> issues = new ArrayList<>();
        for (Slot<?> s : slots) {
            if (s.value == null) issues.add(s.name + " unavailable (" + s.error + ")");
            else if (s.time <= 0 || now < s.time || now - s.time > 1500) issues.add(s.name + " stale");
        }
        if (Boolean.FALSE.equals(connected.value)) issues.add("Aircraft disconnected");
        if (Boolean.FALSE.equals(flying.value)) issues.add("Aircraft not airborne");
        if (mode.value != null && !allowedMode(mode.value)) issues.add("Aiming not allowed in " + mode.value);
        if (gps.value != null && gps.value != GPSSignalLevel.LEVEL_4 && gps.value != GPSSignalLevel.LEVEL_5)
            issues.add("Aircraft GPS insufficient");
        if (Boolean.TRUE.equals(compassError.value)) issues.add("Compass error");
        for (Slot<Integer> s : java.util.Arrays.asList(leftH, leftV, rightH, rightV))
            if (s.value != null && Math.abs((long) s.value) > 30) issues.add("Pilot stick active: " + s.name);
        // CAM3 v2.2: Do not hide velocity/heading failures behind an earlier mode failure.
        if (heading.value != null && (!Double.isFinite(heading.value) || heading.value < -180 || heading.value > 360))
            issues.add("Heading invalid");
        Velocity3D v = velocity.value;
        if (v != null && (v.getX() == null || v.getY() == null || v.getZ() == null
                || !Double.isFinite(v.getX()) || !Double.isFinite(v.getY()) || !Double.isFinite(v.getZ())
                || Math.hypot(v.getX(), v.getY()) > 0.5 || Math.abs(v.getZ()) > 0.3))
            issues.add("Establish steady hover / velocity invalid");
        return "Flight mode: " + mode.value + "\n" + (issues.isEmpty() ? "Aircraft checks ready" : String.join("\n", issues));
    }
    public synchronized AimingSession.Inputs getSnapshot(AimingSession.Fix target) { return getSnapshot(target,false); }
    // Only recent authorized translation permits a small velocity allowance. Start/recovery
    // retain the original hover gate; vertical speed and pilot stick checks never change.
    public synchronized AimingSession.Inputs getSnapshot(AimingSession.Fix target,boolean translating) {
        long oldest = Long.MAX_VALUE;
        String problem = null;
        for (Slot<?> slot : slots) {
            oldest = Math.min(oldest, slot.time);
            if (slot.value == null) problem = "telemetry";
        }
        // An urgent RTH/landing event suppresses neutral even if older hardware reads still say Normal.
        // CAM3 v2.3: One healthy listener must not clear another listener's urgent veto.
        boolean neutral = !connectionBlocked && !modeBlocked && Boolean.TRUE.equals(connected.value) && Boolean.TRUE.equals(flying.value)
                && allowedMode(mode.value);
        // CAM3 v2.3: Known landing/RTH/ground state takes priority even if another field is missing.
        if (mode.value != null && !allowedMode(mode.value)) problem = modeReason(mode.value);
        else if (Boolean.FALSE.equals(flying.value)) problem = "not_airborne";
        if (problem == null) {
            if (!connected.value) problem = "connection";
            // CAM3 v2.1: Split the former combined message; accepted flight modes are unchanged.
            else if (!flying.value) problem = "not_airborne";
            else if (!allowedMode(mode.value)) problem = "flight_mode_rejected";
            else if (gps.value != GPSSignalLevel.LEVEL_4 && gps.value != GPSSignalLevel.LEVEL_5) problem = "aircraft_gps";
            else if (compassError.value) problem = "heading";
            else if (Math.abs((long) leftH.value) > 30 || Math.abs((long) leftV.value) > 30
                    || Math.abs((long) rightH.value) > 30 || Math.abs((long) rightV.value) > 30) problem = "pilot_stick";
            else {
                Velocity3D v = velocity.value;
                if (v.getX() == null || v.getY() == null || v.getZ() == null
                        || !Double.isFinite(v.getX()) || !Double.isFinite(v.getY()) || !Double.isFinite(v.getZ())
                        || Math.hypot(v.getX(), v.getY()) > (translating ? ComeToMeSettings.MAX_SPEED + 0.3 : 0.5) || Math.abs(v.getZ()) > 0.3) problem = "hover";
            }
        }
        LocationCoordinate2D p = position.value;
        return new AimingSession.Inputs(target,
                p == null || p.getLatitude() == null ? Double.NaN : p.getLatitude(),
                p == null || p.getLongitude() == null ? Double.NaN : p.getLongitude(),
                heading.value == null ? Double.NaN : heading.value, oldest, problem, neutral,
                velocity.value==null || velocity.value.getX()==null || velocity.value.getY()==null
                        ? Double.NaN : Math.hypot(velocity.value.getX(),velocity.value.getY()),
                velocity.value==null || velocity.value.getZ()==null ? Double.NaN : velocity.value.getZ());
    }
    // CAM3 v2.1: Immutable diagnostic copy; ages are excluded from the duplicate-suppression signature.
    public synchronized DiagnosticSnapshot diagnostics(long now, AimingSession.Fix target) {
        StringBuilder values = new StringBuilder(), ages = new StringBuilder();
        for (Slot<?> slot : slots) {
            Object value = slot == position ? (slot.value == null ? "unavailable" : "present") : slot.value;
            values.append(slot.name).append('=').append(value).append(" error=").append(slot.error).append(';');
            ages.append(slot.name).append("AgeMs=").append(slot.time <= 0 ? -1 : now - slot.time)
                    .append(" pending=").append(slot.pending).append(';');
        }
        return new DiagnosticSnapshot(getSnapshot(target), values.toString(), values + " " + ages);
    }
    public static final class DiagnosticSnapshot {
        public final String signature, detail;
        public final AimingSession.Inputs inputs;
        DiagnosticSnapshot(AimingSession.Inputs inputs, String signature, String detail) {
            this.inputs = inputs; this.signature = signature; this.detail = detail;
        }
    }
    private static final class Slot<T> {
        // CAM3 v2.1: Named read outcomes are diagnostic only; freshness still uses the existing time field.
        final String name;
        String error = "not_read";
        final DJIKey<T> key;
        T value;
        long time;
        boolean pending;
        // CAM3 v2.3: Per-key bounded retry identity, separate from accepted data freshness.
        long requestId, requested;
        boolean retrying;
        // CAM3 v2.1: Store the caller-supplied diagnostic label.
        Slot(String name, DJIKey<T> key) { this.name = name; this.key = key; }
    }
}
