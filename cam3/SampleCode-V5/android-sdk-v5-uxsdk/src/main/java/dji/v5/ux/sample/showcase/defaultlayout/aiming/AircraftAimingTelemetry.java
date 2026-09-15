package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
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
    private final Slot<Boolean> connected = slot(FlightControllerKey.KeyConnection);
    private final Slot<Boolean> flying = slot(FlightControllerKey.KeyIsFlying);
    private final Slot<LocationCoordinate2D> position = slot(FlightControllerKey.KeyAircraftLocation);
    private final Slot<Double> heading = slot(FlightControllerKey.KeyCompassHeading);
    private final Slot<FlightMode> mode = slot(FlightControllerKey.KeyFlightMode);
    private final Slot<GPSSignalLevel> gps = slot(FlightControllerKey.KeyGPSSignalLevel);
    private final Slot<Boolean> compassError = slot(FlightControllerKey.KeyCompassHasError);
    private final Slot<Velocity3D> velocity = slot(FlightControllerKey.KeyAircraftVelocity);
    private final Slot<Integer> leftH = slot(RemoteControllerKey.KeyStickLeftHorizontal);
    private final Slot<Integer> leftV = slot(RemoteControllerKey.KeyStickLeftVertical);
    private final Slot<Integer> rightH = slot(RemoteControllerKey.KeyStickRightHorizontal);
    private final Slot<Integer> rightV = slot(RemoteControllerKey.KeyStickRightVertical);
    private final Runnable unsafe;
    private boolean running;
    private volatile boolean neutralBlocked;
    private long generation, lastPoll;
    public AircraftAimingTelemetry(Runnable unsafe) { this.unsafe = unsafe; }
    private <T> Slot<T> slot(DJIKeyInfo<T> info) {
        Slot<T> value = new Slot<>(KeyTools.createKey(info)); slots.add(value); return value;
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
                @Override public void onSuccess(T value) { accept(value); }
                @Override public void onFailure(IDJIError error) { accept(null); }
                private void accept(T value) {
                    synchronized (AircraftAimingTelemetry.this) {
                        if (!running || generation != token) return;
                        slot.pending = false;
                        slot.value = value; slot.time = requested;
                    }
                }
            });
        } catch (RuntimeException ex) { slot.pending = false; slot.value = null; slot.time = 0; }
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
            else if (!flying.value || !allowedMode(mode.value)) problem = "flight_state";
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
    private static final class Slot<T> {
        final DJIKey<T> key;
        T value;
        long time;
        boolean pending;
        Slot(DJIKey<T> key) { this.key = key; }
    }
}
