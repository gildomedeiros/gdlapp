package com.gdl.pinkdetector;

/** Timeout can retire a waiting callback, never an executing detector. */
public final class CaptureTicket {
    private long sequence;
    private long active;
    private boolean processing;
    public synchronized long begin() {
        if (active != 0) return 0;
        active = ++sequence; processing = false; return active;
    }
    public synchronized boolean accept(long ticket) {
        if (active != ticket || ticket == 0 || processing) return false;
        processing = true; return true;
    }
    public synchronized boolean expire(long ticket) {
        if (active != ticket || ticket == 0 || processing) return false;
        active = 0; return true;
    }
    public synchronized boolean finish(long ticket) {
        if (active != ticket || ticket == 0) return false;
        active = 0; processing = false; return true;
    }
    public synchronized boolean isProcessing(long ticket) { return active == ticket && processing; }
}
