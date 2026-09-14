package com.gdl.pinkdetector;
import java.util.concurrent.Semaphore;
public final class EvidenceAdmission {
    private final Semaphore slots = new Semaphore(3);
    public boolean tryAcquire() { return slots.tryAcquire(); }
    public void release() { slots.release(); }
}
