package com.gdl.pinkdetector;

/** One attempt, including failed/rejected dispatches, until explicitly re-armed. */
public final class SingleGimbalAttempt {
    private boolean used;
    public synchronized boolean isUsed() { return used; }
    public synchronized boolean begin() {
        if (used) return false;
        used = true;
        return true;
    }
    public synchronized void rearm() { used = false; }
}
