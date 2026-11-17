package au.com.gildo.vtc;

/** Independent of the control worker. Latches once; reset only on explicit recovery. */
final class MovementWatchdog {
    static final long STALL_MS=5000;
    private final java.util.concurrent.atomic.AtomicBoolean tripped=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long healthyAt;
    MovementWatchdog(long now){healthyAt=now;}
    void healthy(long now){healthyAt=now;}
    void reset(long now){healthyAt=now;tripped.set(false);}
    boolean check(long now,boolean motionPossible){
        return motionPossible&&now-healthyAt>=STALL_MS&&tripped.compareAndSet(false,true);
    }
}
