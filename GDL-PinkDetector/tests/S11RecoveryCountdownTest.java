package com.gdl.pinkdetector;

/** Standalone regression test; no Android or external test dependencies. */
public final class S11RecoveryCountdownTest {
    private static void expect(boolean expected, boolean actual) {
        if (actual != expected) throw new AssertionError("Expected " + expected);
    }
    public static void main(String[] args) {
        S11RecoveryCountdown timer = new S11RecoveryCountdown();
        expect(false, timer.update(100000, false, false, false, 15000));
        expect(false, timer.update(200000, true, false, true, 15000));
        expect(false, timer.update(300000, true, false, true, 15000));
        expect(false, timer.update(301000, true, false, false, 15000));
        expect(false, timer.update(315999, true, false, false, 15000));
        expect(true, timer.update(316000, true, false, false, 15000));
        expect(false, timer.update(316100, true, true, false, 15000));
        expect(false, timer.update(316500, true, false, false, 15000));
        expect(false, timer.update(331499, true, false, false, 15000));
        expect(true, timer.update(331500, true, false, false, 15000));
        expect(false, timer.update(332000, true, false, true, 15000));
        expect(false, timer.update(333000, true, false, false, 15000));
        timer.reset();
        expect(false, timer.update(400000, true, false, false, 15000));
        expect(true, timer.update(415000, true, false, false, 15000));
        System.out.println("S11 recovery countdown regressions passed");
    }
}
