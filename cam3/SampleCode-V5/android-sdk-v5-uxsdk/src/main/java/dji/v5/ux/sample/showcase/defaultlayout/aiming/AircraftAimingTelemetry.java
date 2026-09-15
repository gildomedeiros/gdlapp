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
    private final Runnable unsafe;
    private boolean running;
    private volatile boolean neutralBlocked;
    private long generation, lastPoll;
    // CAM3 v2.1: Report read failures/recovery immediately, even between summary snapshots.
    private final BiConsumer<String, String> diagnostic;
    public AircraftAimingTelemetry(Runnable unsafe, BiConsumer<String, String> diagnostic) {
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
        KeyManager.getInstance().listen(connected.key, this, (old, value) -> {
            if (!Boolean.TRUE.equals(value)) { neutralBlocked = true; unsafe.run(); }
        });
        KeyManager.getInstance().listen(mode.key, this, (old, value) -> {
            neutralBlocked = !allowedMode(value);
            if (neutralBlocked) unsafe.run();
        });
        for (Slot<Integer> stick : java.util.Arrays.asList(leftH, leftV, rightH, rightV)) {
            KeyManager.getInstance().listen(stick.key, this, (old, value) -> {
                if (value == null || Math.abs((long) value) > 30) unsafe.run();
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
        if (slot.pending) return;
        slot.pending = true;
        try {
            KeyManager.getInstance().getValue(slot.key, new CommonCallbacks.CompletionCallbackWithParam<T>() {
                // CAM3 v2.1: Report SDK failure codes separately from absent values; no coordinates in errors.
                @Override public void onSuccess(T value) { accept(value, value == null ? "null_value" : "none"); }
                @Override public void onFailure(IDJIError error) { accept(null, error == null ? "unknown" : error.errorCode()); }
                private void accept(T value, String error) {
                    synchronized (AircraftAimingTelemetry.this) {
                        if (!running || generation != token) return;
                        slot.pending = false;
                        slot.value = value; slot.time = requested;
                        // CAM3 v2.1: Each field exposes its last read outcome to the diagnostic snapshot.
                        readOutcome(slot, error);
                    }
                }
            });
        // CAM3 v2.1: Preserve exception type for diagnosis while retaining existing fail-closed behavior.
        } catch (RuntimeException ex) {
            slot.pending = false; slot.value = null; slot.time = 0; readOutcome(slot, ex.getClass().getSimpleName());
        }
    }
    private static boolean allowedMode(FlightMode value) {
        return value == FlightMode.GPS_NORMAL || value == FlightMode.VIRTUAL_STICK;
    }
    public synchronized AimingSession.Inputs getSnapshot(AimingSession.Fix target) {
        long oldest = Long.MAX_VALUE;
        String problem = null;
        for (Slot<?> slot : slots) {
            oldest = Math.min(oldest, slot.time);
            if (slot.value == null) problem = "telemetry";
        }
        // An urgent RTH/landing event suppresses neutral even if older hardware reads still say Normal.
        boolean neutral = !neutralBlocked && Boolean.TRUE.equals(connected.value) && Boolean.TRUE.equals(flying.value)
                && allowedMode(mode.value);
        if (problem == null) {
            if (!connected.value) problem = "connection";
            // CAM3 v2.1: Split the former combined message; accepted flight modes are unchanged.
            else if (!flying.value) problem = "not_airborne";
            else if (!allowedMode(mode.value)) problem = "flight_mode_rejected";
            else if (gps.value != GPSSignalLevel.LEVEL_4 && gps.value != GPSSignalLevel.LEVEL_5) problem = "aircraft_gps";
            else if (compassError.value) problem = "heading";
            else if (Math.abs((long) leftH.value) > 30 || Math.abs((long) leftV.value) > 30
                    || Math.abs((long) rightH.value) > 30 || Math.abs((long) rightV.value) > 30) problem = "takeover";
            else {
                Velocity3D v = velocity.value;
                if (v.getX() == null || v.getY() == null || v.getZ() == null
                        || !Double.isFinite(v.getX()) || !Double.isFinite(v.getY()) || !Double.isFinite(v.getZ())
                        || Math.hypot(v.getX(), v.getY()) > 0.5 || Math.abs(v.getZ()) > 0.3) problem = "hover";
            }
        }
        LocationCoordinate2D p = position.value;
        return new AimingSession.Inputs(target,
                p == null || p.getLatitude() == null ? Double.NaN : p.getLatitude(),
                p == null || p.getLongitude() == null ? Double.NaN : p.getLongitude(),
                heading.value == null ? Double.NaN : heading.value, oldest, problem, neutral);
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
        // CAM3 v2.1: Store the caller-supplied diagnostic label.
        Slot(String name, DJIKey<T> key) { this.name = name; this.key = key; }
    }
}
