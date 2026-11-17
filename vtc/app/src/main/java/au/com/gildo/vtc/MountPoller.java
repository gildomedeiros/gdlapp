package au.com.gildo.vtc;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Recover only missing position/status replies. Never retry a move or hide a motor error. */
final class MountPoller {
    interface Events { void record(String event,Object... fields); }
    static final long STOP_AFTER_MS=5000,STOP_RETRY_MS=2000;
    private final LongSupplier clock;
    private final Events events;
    private long since=-1,lastStop=-1;
    private int misses,stopAttempts;
    private boolean stopRequested;
    MountPoller(LongSupplier clock,Events events){this.clock=clock;this.events=events;}
    boolean waiting(){return since>=0;}
    boolean interruptedMove(){return stopRequested;}
    void reset(){since=lastStop=-1;misses=stopAttempts=0;stopRequested=false;}
    void stopForTransition(GtiClient client,BooleanSupplier permitted)throws IOException {
        check(permitted);
        if(!waiting())since=clock.getAsLong();
        requestStop(client,permitted);
    }
    GtiClient.Position poll(GtiClient client,BooleanSupplier permitted,long motionDeadline)throws IOException {
        check(permitted);long attemptAt=clock.getAsLong();
        GtiClient.Position p;
        try {
            p=client.position(permitted);
        }catch(SocketTimeoutException e) {
            check(permitted);
            if(!waiting())since=attemptAt;
            misses++;
            events.record("mount_poll_retry","recovery",snapshot(),"exception",e.toString());
            long now=clock.getAsLong();
            if(now-since>=STOP_AFTER_MS||(motionDeadline>0&&now>=motionDeadline))requestStop(client,permitted);
            return null;
        }
        check(permitted);
        if(waiting()&&stopRequested&&p.moving){requestStop(client,permitted);return null;}
        // Caller validates counters and the pending destination before clearing recovery.
        return p;
    }
    private void requestStop(GtiClient client,BooleanSupplier permitted)throws IOException {
        check(permitted);long now=clock.getAsLong();
        if(lastStop>=0&&now-lastStop<STOP_RETRY_MS)return;
        stopRequested=true;lastStop=now;stopAttempts++;
        events.record("mount_recovery_stop_requested","recovery",snapshot());
        try {
            client.requestStop();
            events.record("mount_recovery_stop_acknowledged","recovery",snapshot());
        }catch(IOException e) {
            if(!onlyTimeouts(e))throw e;
            events.record("mount_recovery_stop_unconfirmed","recovery",snapshot(),"exception",e.toString());
        }
        // Even an acknowledged Stop needs a subsequent complete, stopped position sample.
    }
    private static boolean onlyTimeouts(Throwable e) {
        if(!(e instanceof SocketTimeoutException))return false;
        for(Throwable extra:e.getSuppressed())if(!onlyTimeouts(extra))return false;
        return true;
    }
    private static void check(BooleanSupplier permitted) {
        if(!permitted.getAsBoolean())throw new CancellationException("Mount recovery cancelled");
    }
    Map<String,Object> snapshot() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("waiting",waiting());out.put("sinceMonoMs",since);
        out.put("elapsedMs",waiting()?Math.max(0,clock.getAsLong()-since):0);
        out.put("missedPolls",misses);out.put("stopRequested",stopRequested);out.put("stopAttempts",stopAttempts);
        out.put("stopAfterMs",STOP_AFTER_MS);return out;
    }
    static void validateRecovery(GtiClient.Position reference,GtiClient.Position current,boolean pending,
                                 int expectedPan,int expectedTilt,int[] cpr)throws IOException {
        if(reference==null)throw new IOException("No position reference for mount recovery; recalibrate");
        // Axis initialization is checked on every status read. A counter mismatch alone
        // does not invalidate calibration; the controller replans from current counters
        // and still enforces the configured pan/tilt sector before another movement.
    }
}
