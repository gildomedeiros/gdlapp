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
        check(!YawAimingMath.isBearingUsable(20, 10), "uncertain bearing rejected");
        check(!YawAimingMath.isBearingUsable(100, Double.NaN), "NaN rejected");
        check(YawAimingMath.isBearingUsable(100, 3), "separated target accepted");
        Fake f = new Fake();
        check(f.core.canStart(), "ready initially");
        f.core.startAiming(); f.core.startAiming();
        check(f.enables == 1, "double Start coalesced");
        f.core.tick(); check(f.sent.isEmpty(), "no send before grant");
        f.enable.complete(true); f.core.tick();
        check(f.sent.isEmpty() && f.advances == 0, "callback alone cannot enable advanced or send");
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
        check(f.core.state() == AimingSession.State.STOPPING, "stale phone cancels");
        f = new Fake(); f.aiming(); f.input = f.data(f.time - 1501, f.time, null, true); count = f.sent.size(); f.core.tick();
        check(f.core.state() == AimingSession.State.STOPPING && f.sent.size() == count, "stale aircraft sends no neutral");
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
        check(f.core.state() == AimingSession.State.STOPPED && f.sent.isEmpty(), "late enable settles without restarting");
        System.out.println("PASS: " + checks + " assertions (yaw math, input gates, ownership, callback races, failures)");
    }
}
