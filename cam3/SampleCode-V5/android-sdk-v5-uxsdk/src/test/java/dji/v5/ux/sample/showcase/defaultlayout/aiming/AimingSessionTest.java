package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.ArrayList;
import java.util.List;

/** CAM3 v2.0: Standalone JVM regression harness; no aircraft, Android runtime or network required. */
public final class AimingSessionTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 0.00001, message + ": " + actual);
    }
    private static final class Fake implements AimingSession.Port {
        long time = 10000;
        AimingSession.Authority owner = new AimingSession.Authority(AimingSession.Owner.RC, false, false);
        AimingSession.Inputs input;
        AimingSession.Completion enable, disable;
        int enables, disables, advances;
        boolean throwSend, throwDisable;
        // CAM3 v2.1: Verify diagnostic-port failure cannot change flight-control outcomes.
        boolean throwDiagnostics;
        // CAM3 v2.2: Simulate reason-first takeover independently of the last state snapshot.
        boolean lost;
        public boolean controlLost() { return lost; }
        final List<String> diagnosticEvents = new ArrayList<>();
        public void diagnostic(String event, String detail) {
            if (throwDiagnostics) throw new IllegalStateException("logger unavailable");
            diagnosticEvents.add(event + " " + detail);
        }
        Runnable onRead;
        final List<Double> sent = new ArrayList<>();
        final AimingSession core = new AimingSession(this);
        Fake() { fresh(); }
        void fresh() { input = data(time, time, null, true); }
        AimingSession.Inputs data(long aircraft, long phone, String problem, boolean neutral) {
            return new AimingSession.Inputs(new AimingSession.Fix(0.001, 0, 3, phone),
                    0, 0, 90, aircraft, problem, neutral);
        }
        void advance(long delta) { time += delta; fresh(); core.tick(); }
        void aiming() {
            core.startAiming(); enable.complete(true);
            owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, false);
            core.tick();
            owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, true);
            core.tick(); advance(100);
            check(core.state() == AimingSession.State.AIMING, "started with confirmed ownership/mode");
        }
        public long now() { return time; }
        public AimingSession.Inputs inputs() { if (onRead != null) onRead.run(); return input; }
        public AimingSession.Authority authority() { return owner; }
        public void enable(AimingSession.Completion callback) { enables++; enable = callback; }
        public void advanced() { advances++; }
        public void disable(AimingSession.Completion callback) {
            disables++; if (throwDisable) throw new IllegalStateException(); disable = callback;
        }
        public void sendYaw(double rate) { if (throwSend) throw new IllegalStateException(); sent.add(rate); }
        void released() {
            owner = new AimingSession.Authority(AimingSession.Owner.RC, false, false);
            disable.complete(true); core.tick();
        }
    }
    public static void main(String[] args) {
        near(2, YawAimingMath.shortestHeadingError(1, 359), "wrap positive");
        near(-2, YawAimingMath.shortestHeadingError(359, 1), "wrap negative");
        near(90, YawAimingMath.bearingToTarget(0, 0, 0, 1), "east bearing");
        near(0, YawAimingMath.bearingToTarget(0, 0, 1, 0), "north bearing");
        near(0.4, YawAimingMath.calculateYawRate(90, 0, .1), "acceleration limit");
        near(8, YawAimingMath.calculateYawRate(90, 8, .1), "rate cap");
        near(0, YawAimingMath.calculateYawRate(1, 8, .1), "tolerance stops");
        near(0, YawAimingMath.calculateYawRate(-10, 8, .1), "direction reversal brakes");
        check(!YawAimingMath.isBearingUsable(0, 3), "directly below rejected");
        // CAM3 v2.3: Replace the former uncertainty-scaled minimum with explicit 5 m boundaries.
        check(YawAimingMath.isBearingUsable(20, 10), "valid accuracy at 20 m accepted");
        check(!YawAimingMath.isBearingUsable(4.99, 3), "below 5 m rejected");
        check(YawAimingMath.isBearingUsable(5, 3), "exactly 5 m accepted");
        check(!YawAimingMath.isBearingUsable(50, 10.01), "poor accuracy still rejected");
        check(!YawAimingMath.isBearingUsable(100, Double.NaN), "NaN rejected");
        check(YawAimingMath.isBearingUsable(100, 3), "separated target accepted");
        Fake f = new Fake();
        check(f.core.canStart(), "ready initially");
        f.core.startAiming(); f.core.startAiming();
        check(f.enables == 1, "double Start coalesced");
        f.core.tick(); check(f.sent.isEmpty(), "no send before grant");
        f.enable.complete(true); f.core.tick();
        // CAM3 v2.2: Enable success requests advanced; a subsequent state update is required to send.
        check(f.sent.isEmpty() && f.advances == 1, "callback requests advanced but cannot send");
        f.core.stopAiming("user_stop");
        check(!f.core.canStart() && f.core.state() == AimingSession.State.STOPPING,
                "old RC snapshot cannot confirm release of unobserved grant");
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, false);
        f.core.tick(); check(f.disables == 1 && f.sent.isEmpty(), "late grant released without aiming");
        f.released(); check(f.core.canStart(), "explicit restart permitted after release");

        f = new Fake(); f.core.startAiming(); f.core.stopAiming("user_stop");
        f.advance(6000); check(f.core.state() == AimingSession.State.RELEASE_UNCONFIRMED, "missing enable visibly unresolved");
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, true);
        f.enable.complete(true);
        check(f.disables == 1 && f.advances == 0, "grant after timeout still released");
        check(f.sent.stream().allMatch(rate -> rate == 0), "late enable never produces yaw");

        f = new Fake(); f.aiming();
        check(f.sent.get(f.sent.size() - 1) < 0, "shortest yaw direction");
        f.core.stopAiming("user_stop");
        near(0, f.sent.get(f.sent.size() - 1), "normal stop neutral");
        int count = f.sent.size(); f.advance(100);
        check(f.sent.size() == count && f.disables == 1, "no repeated sender during release");
        f.disable.complete(true);
        check(f.core.state() == AimingSession.State.RELEASE_UNCONFIRMED, "disable callback is not release observation");
        f.owner = new AimingSession.Authority(AimingSession.Owner.RC, false, false); f.core.tick();
        check(f.core.state() == AimingSession.State.STOPPED, "observed release settles");
        count = f.sent.size(); f.advance(100); check(count == f.sent.size(), "never auto resumes");

        f = new Fake(); f.aiming(); count = f.sent.size();
        f.owner = new AimingSession.Authority(AimingSession.Owner.OTHER, false, false); f.core.tick();
        check(f.sent.size() == count && f.disables == 0, "takeover sends no competing neutral or disable");

        f = new Fake(); f.aiming(); count = f.sent.size();
        f.input = f.data(f.time, f.time, "flight_state", false); f.core.tick();
        check(f.sent.size() == count && f.disables == 1, "RTH mode suppresses neutral while surrendering owned control");

        f = new Fake(); f.aiming(); f.input = f.data(f.time, f.time - 3001, null, true); f.core.tick();
        // CAM3 v2.3: Stale data pauses; the original session remains available for recovery.
        check(f.core.state() == AimingSession.State.PAUSED, "stale phone pauses");
        f = new Fake(); f.aiming(); f.input = f.data(f.time - 1501, f.time, null, true); count = f.sent.size(); f.core.tick();
        check(f.core.state() == AimingSession.State.PAUSED && f.sent.size() == count, "stale aircraft pauses without stale neutral");
        f = new Fake(); f.input = f.data(f.time, f.time + 1, null, true);
        check(!f.core.canStart(), "future fix rejected");

        f = new Fake(); f.aiming(); count = f.sent.size(); f.advance(501);
        check(f.core.state() == AimingSession.State.STOPPING && f.sent.size() == count + 1
                && f.sent.get(count) == 0, "stall recovery only requests neutral");

        f = new Fake(); f.aiming(); f.throwSend = true; f.core.tick();
        check(f.disables == 1 && f.core.state() == AimingSession.State.STOPPING, "send exception still attempts release");
        f = new Fake(); f.aiming(); f.throwDisable = true; f.core.stopAiming("user_stop");
        check(f.core.state() == AimingSession.State.RELEASE_UNCONFIRMED && !f.core.canStart(), "release exception visible and blocks Start");

        f = new Fake(); f.aiming(); count = f.sent.size();
        f.onRead = f.core::cancelImmediately; f.core.tick();
        check(f.sent.size() == count + 1 && f.sent.get(count) == 0, "cancellation during snapshot read blocks nonzero send");

        f = new Fake(); f.core.startAiming(); f.enable.complete(false);
        check(f.sent.isEmpty(), "failed start sends no yaw");
        f = new Fake(); f.aiming(); f.core.stopAiming("user_stop"); f.disable.complete(false);
        check(f.core.state() == AimingSession.State.RELEASE_UNCONFIRMED, "failed disable remains visible");
        f = new Fake(); f.core.startAiming(); f.enable.complete(true); f.core.stopAiming("user_stop");
        f.advance(6000);
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, false);
        f.core.tick();
        check(f.disables == 1 && f.sent.isEmpty(), "ownership arriving after callback and timeout is released");
        f = new Fake(); f.core.startAiming();
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, false);
        f.core.stopAiming("user_stop");
        check(f.disables == 1, "observed grant released even when enable callback is missing");
        f.released();
        check(f.core.state() == AimingSession.State.RELEASE_UNCONFIRMED && !f.core.canStart(),
                "missing enable callback still visibly prevents overlapping starts");
        f.enable.complete(true); f.core.tick();
        // CAM3 v2.2: Late enable after earlier release needs another bounded cleanup and new observation.
        check(f.core.state() == AimingSession.State.STOPPING && f.sent.isEmpty() && f.disables == 2,
                "late enable released again without restarting");
        f.released(); check(f.core.state() == AimingSession.State.STOPPED, "fresh release settles late grant");
        // CAM3 v2.1: Test the new observability while retaining every v2.0 control regression.
        f = new Fake(); f.aiming(); f.core.stopAiming("user_stop");
        check(f.diagnosticEvents.stream().anyMatch(e -> e.contains("STARTING -> AIMING") && e.contains("session=1")),
                "state transition includes session identity");
        check(f.diagnosticEvents.stream().anyMatch(e -> e.contains("reason=user_stop")), "original stop reason logged");
        f = new Fake(); f.throwDiagnostics = true; f.aiming(); f.core.stopAiming("user_stop");
        check(f.disables == 1 && f.core.state() == AimingSession.State.STOPPING, "throwing logger cannot prevent release");
        f = new Fake(); f.input = f.data(f.time, f.time, "not_airborne", false);
        check("not_airborne".equals(f.input.validate(f.time)) && !f.core.canStart(), "not airborne reason preserved");
        f.input = f.data(f.time, f.time, "flight_mode_rejected", false);
        check("flight_mode_rejected".equals(f.input.validate(f.time)) && !f.core.canStart(), "rejected mode distinct and still blocks");
        // CAM3 v2.2: Unknown-start, stale-state, callback ordering and cleanup regressions.
        for (String mode : new String[]{"APAS", "GPS_NORMAL", "VIRTUAL_STICK"})
            check(AimingFlightModes.allows(mode), "eligible mode " + mode);
        for (String mode : new String[]{null, "UNKNOWN", "AUTO_TAKE_OFF", "MOTOR_START", "GO_HOME", "AUTO_LANDING", "FORCE_LANDING", "GPS_SPORT", "AUTO_AVOIDANCE"})
            check(!AimingFlightModes.allows(mode), "ineligible mode " + mode);
        f = new Fake(); f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, false, false);
        check(f.core.canStart(), "unknown initial owner can request");
        f.core.startAiming(); f.enable.complete(true); f.core.tick();
        check(f.advances == 1 && f.sent.isEmpty(), "unknown enable success requests advanced only");
        f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, true, true); f.core.tick();
        check(f.core.state() == AimingSession.State.AIMING, "fresh advanced confirmation permits unknown owner");
        count = f.sent.size(); f.core.stopAiming("user_stop");
        check(f.disables == 1 && f.sent.size() == count, "unknown cleanup disables without neutral");
        f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, false, false);
        f.disable.complete(true); check(f.core.canStart(), "fresh disabled observation settles unknown cleanup");

        f = new Fake(); f.core.startAiming();
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, true);
        f.enable.complete(true); f.core.tick(); f.core.tick();
        check(f.sent.isEmpty(), "advanced true predating request cannot activate");
        f.advance(5001); check("advanced_timeout".equals(f.core.reason()), "advanced timeout identifies missing evidence");
        f = new Fake(); f.core.startAiming();
        f.owner = new AimingSession.Authority(AimingSession.Owner.RC, false, false); f.core.tick();
        check(f.core.state() != AimingSession.State.AIMING && f.sent.isEmpty(), "new RC state vetoes attempt");
        f = new Fake(); f.aiming(); count = f.sent.size(); f.lost = true; f.core.cancelImmediately(); f.core.tick();
        check(f.sent.size() == count && f.disables == 0, "reason-first takeover sends no competing cleanup");
        f = new Fake(); f.owner = new AimingSession.Authority(AimingSession.Owner.OTHER, false, false);
        check(!f.core.canStart(), "other owner cannot be acquired");
        f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, true, false);
        check(!f.core.canStart(), "unknown enabled session cannot be acquired");
        f = new Fake(); f.core.startAiming();
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, false);
        f.core.stopAiming("user_stop"); f.enable.complete(true); f.disable.complete(true);
        check(f.disables == 2 && f.sent.isEmpty(), "grant during pending release gets one later cleanup");
        f.released(); check(f.core.canStart(), "second release settles grant race");
        // CAM3 v2.2: Loss of advanced confirmation stops even without any positive owner report.
        f = new Fake(); f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, false, false);
        f.core.startAiming(); f.enable.complete(true); f.core.tick();
        f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, true, true); f.core.tick();
        count = f.sent.size();
        f.owner = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, true, false); f.core.tick();
        check(f.core.state() == AimingSession.State.STOPPING && f.sent.size() == count,
                "advanced loss with unknown owner stops without neutral");
        f = new Fake(); f.core.startAiming(); f.enable.complete(true); f.core.tick(); f.core.cancelImmediately();
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, true); f.core.tick();
        check(f.sent.stream().allMatch(rate -> rate == 0) && f.core.state() != AimingSession.State.AIMING,
                "Stop before advanced confirmation prevents activation");
        // CAM3 v2.3: Exercise pause/recovery with real tick intervals and independently timed callbacks.
        f = new Fake();
        check("telemetry".equals(f.data(f.time - 1501, f.time, "pilot_stick", false).validate(f.time)),
                "stale readings cannot hide behind a recoverable stick condition");
        check("landing".equals(f.data(f.time - 1501, f.time, "landing", false).validate(f.time)),
                "known landing still outranks stale readings");
        f = new Fake(); f.aiming(); count = f.sent.size();
        f.core.pauseImmediately("pilot_stick");
        check(!f.core.maySendYaw(), "urgent stick event immediately vetoes yaw");
        f.core.tick();
        check(f.core.state() == AimingSession.State.PAUSED && f.disables == 0, "stick pause retains session");
        check(f.sent.size() == count + 1 && f.sent.get(count) == 0, "pause submits one neutral");
        f.advance(100);
        for (int i = 0; i < 19; i++) f.advance(100);
        check(f.core.state() == AimingSession.State.PAUSED && f.sent.size() == count + 1, "no yaw before two healthy seconds");
        f.core.pauseImmediately("pilot_stick"); f.advance(100); f.advance(100);
        for (int i = 0; i < 19; i++) f.advance(100);
        check(f.core.state() == AimingSession.State.PAUSED, "another stick event resets timer");
        f.advance(100);
        check(f.core.state() == AimingSession.State.AIMING && f.enables == 1, "auto resume never re-enables control");
        check(Math.abs(f.sent.get(f.sent.size() - 1)) <= .0041, "resume starts yaw acceleration from zero");
        check(f.diagnosticEvents.stream().anyMatch(e -> e.startsWith("recovery_reset"))
                && f.diagnosticEvents.stream().anyMatch(e -> e.startsWith("resumed")), "reset and resume logged");

        for (String cause : new String[]{"distance", "gps", "gps_quality", "stale_gps", "heading", "aircraft_gps", "hover"}) {
            f = new Fake(); f.aiming(); f.input = f.data(f.time, f.time, cause, true); f.core.tick();
            check(f.core.state() == AimingSession.State.PAUSED, cause + " pauses");
            for (int i = 0; i < 21; i++) f.advance(100);
            check(f.core.state() == AimingSession.State.AIMING && f.disables == 0, cause + " recovers");
        }
        f = new Fake(); f.aiming(); f.input = f.data(f.time - 1501, f.time, null, false); f.core.tick();
        count = f.sent.size(); f.advance(100);
        for (int i = 0; i < 25; i++) f.advance(100);
        check(f.core.state() == AimingSession.State.PAUSED && f.sent.size() == count && f.advances == 2,
                "telemetry recovery waits for fresh control observation without repeated requests");
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, true);
        for (int i = 0; i < 21; i++) f.advance(100);
        check(f.core.state() == AimingSession.State.AIMING && f.enables == 1, "fresh telemetry and control recover existing session");

        for (String cause : new String[]{"user_stop", "landing", "returning_home"}) {
            f = new Fake(); f.aiming(); f.core.pauseImmediately("distance"); f.core.tick();
            f.core.stopAiming(cause); count = f.sent.size();
            for (int i = 0; i < 30; i++) f.advance(100);
            check(f.core.state() != AimingSession.State.AIMING && f.sent.size() == count, cause + " cancels recovery");
        }
        f = new Fake(); f.aiming(); f.input = f.data(f.time, f.time, "distance", true); f.core.tick();
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, false, false); f.core.tick();
        check(f.core.state() != AimingSession.State.PAUSED, "control loss outranks continuing distance failure");
        f = new Fake(); f.aiming(); f.core.pauseImmediately("distance"); f.core.tick(); f.advance(501);
        check("loop_stall".equals(f.core.reason()), "stalled recovery cancels instead of counting unseen time");

        f = new Fake(); f.core.startAiming(); f.core.pauseImmediately("pilot_stick"); f.core.tick();
        f.enable.complete(true); f.advance(100); f.advance(100);
        check(f.core.state() == AimingSession.State.PAUSED && f.disables == 0 && f.sent.isEmpty(), "paused acquisition awaits advanced callback");
        f.owner = new AimingSession.Authority(AimingSession.Owner.MSDK, true, true);
        for (int i = 0; i < 21; i++) f.advance(100);
        check(f.core.state() == AimingSession.State.AIMING && f.enables == 1, "paused acquisition can complete normally");
        f = new Fake(); f.core.startAiming(); f.core.pauseImmediately("distance"); f.core.tick();
        for (int i = 0; i < 51; i++) { f.time += 100; f.input = f.data(f.time, f.time, "distance", true); f.core.tick(); }
        check("start_timeout".equals(f.core.reason()) && !f.core.canStart(), "invalid inputs cannot hide enable timeout");
        System.out.println("PASS: " + checks + " assertions (yaw math, input gates, ownership, callback races, failures, recovery)");
    }
}
