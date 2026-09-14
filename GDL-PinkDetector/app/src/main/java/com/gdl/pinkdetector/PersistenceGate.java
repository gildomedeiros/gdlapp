package com.gdl.pinkdetector;

/** Uses fresh observations only. Unknown observations break continuity. */
public final class PersistenceGate {
    private long since = -1;
    public boolean update(long now, boolean present, long delay) {
        if (!present) { reset(); return false; }
        if (since < 0 || now < since) { since = now; return false; }
        return now - since >= delay;
    }
    public boolean pending() { return since >= 0; }
    public void reset() { since = -1; }
}
