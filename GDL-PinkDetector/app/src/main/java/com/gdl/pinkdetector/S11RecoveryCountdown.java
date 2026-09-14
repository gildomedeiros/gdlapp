package com.gdl.pinkdetector;

/** Timing only: callers supply results from the existing detectors. */
public final class S11RecoveryCountdown {
    private long missingSinceMs = -1;

    public void reset() {
        missingSinceMs = -1;
    }

    public boolean update(long nowMs, boolean armed, boolean validatedPlus,
                          boolean controlVisible, long timeoutMs) {
        if (!armed || validatedPlus || controlVisible) {
            reset();
            return false;
        }
        if (missingSinceMs < 0) missingSinceMs = nowMs;
        return nowMs - missingSinceMs >= timeoutMs;
    }
}
