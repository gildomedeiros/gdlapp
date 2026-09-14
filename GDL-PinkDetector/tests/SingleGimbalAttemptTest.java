package com.gdl.pinkdetector;

public final class SingleGimbalAttemptTest {
    public static void main(String[] args) {
        SingleGimbalAttempt attempt = new SingleGimbalAttempt();
        if (!attempt.begin() || !attempt.isUsed()) throw new AssertionError("first attempt");
        for (int i = 0; i < 1000; i++) {
            if (attempt.begin()) throw new AssertionError("automatic retry");
        }
        attempt.rearm();
        if (!attempt.begin() || attempt.begin()) throw new AssertionError("rearm");
        System.out.println("Single-attempt regressions passed");
    }
}
