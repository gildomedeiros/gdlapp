package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.util.ArrayDeque;
/**
 * VT 3.3: short-window detection followed by a fixed monotonic duration.
 * No speed assessment during an active ride. GPS gaps cannot extend its deadline.
 */
public final class RideDetector {
    public static final long FAST_MS=1000, CONFIRM_MS=5000;
    public static final double JUMP_LIMIT_KMH=40;
    private final ArrayDeque<AimingSession.Fix> history=new ArrayDeque<>(), jumpHistory=new ArrayDeque<>();
    private AimingSession.Fix last;
    private long slowSince=-1;
    public long startedAt=-1, expiresAt=-1;
    private long rearmAfter=-1;
    public long remainingMs(long now) { return riding ? Math.max(0,expiresAt-now) : 0; }
    public boolean advance(long now) {
        if(!riding || expiresAt<0 || now<expiresAt) return false;
        riding=confirmed=false; rearmAfter=now;
        clearEvidence("timer_expired"); event="ride_expired"; return true;
    }
    public boolean riding, confirmed, rejected, newPacket, repeatedCoordinates;
    public double fastSpeed=Double.NaN, confirmationSpeed=Double.NaN, jumpSpeed=Double.NaN;
    public long fastSpanMs, confirmationSpanMs, jumpSpanMs, slowMs, rejectedCount;
    public String event="none", evidence="warming_up", slowResetReason="none";
    /** New sessions/manual repositioning must not inherit a previous ride. */
    public void reset() { riding=confirmed=false; startedAt=expiresAt=rearmAfter=-1; rejectedCount=0; clearEvidence("reset"); event="none"; }
    /** Missing data is never zero speed. Preserve a known ride across a safety pause. */
    public void clearEvidence(String reason) {
        history.clear(); jumpHistory.clear(); last=null; slowSince=-1; slowMs=0;
        fastSpeed=confirmationSpeed=jumpSpeed=Double.NaN;
        fastSpanMs=confirmationSpanMs=jumpSpanMs=0;
        rejected=newPacket=repeatedCoordinates=false; event="none"; evidence=reason; slowResetReason=reason;
    }
    /** Select the newest reference at least the requested age; never fabricate an exact 1 s fix. */
    private static AimingSession.Fix reference(ArrayDeque<AimingSession.Fix> points,AimingSession.Fix fix,long minimum) {
        AimingSession.Fix result=null;
        for(AimingSession.Fix point:points) {
            if(fix.sampleTime-point.sampleTime>=minimum) result=point; else break;
        }
        return result;
    }
    private static double speed(AimingSession.Fix a,AimingSession.Fix b) {
        return YawAimingMath.distance(a.lat,a.lon,b.lat,b.lon)*3600/(b.sampleTime-a.sampleTime);
    }
    /**
     * Keep fresh stationary packets: identical coordinates are needed to measure zero speed.
     * Jump protection uses a reference at least 1 s old, NOT the last repeated 0.5 s packet.
     * The protocol has no independent GPS-fix ID. This corrects packet timing without
     * pretending identical coordinates distinguish a duplicate from a stationary GPS fix.
     */
    public void observe(AimingSession.Fix fix,ComeToMeSettings config) { observe(fix,config,fix.time); }
    public void observe(AimingSession.Fix fix,ComeToMeSettings config,long now) {
        event="none"; rejected=false; newPacket=false; slowResetReason="none";
        if(riding || fix.time<=rearmAfter) return;
        if(last!=null && fix.time==last.time) return;
        if(last!=null && (fix.time<last.time || fix.time-last.time>3000 || fix.sampleTime<=last.sampleTime))
            clearEvidence("gap_or_clock_reset");
        newPacket=true; repeatedCoordinates=last!=null && fix.lat==last.lat && fix.lon==last.lon; last=fix;
        while(!jumpHistory.isEmpty() && fix.sampleTime-jumpHistory.peekFirst().sampleTime>3000) jumpHistory.removeFirst();
        AimingSession.Fix jumpBase=reference(jumpHistory,fix,FAST_MS);
        jumpSpanMs=jumpBase==null ? 0 : fix.sampleTime-jumpBase.sampleTime;
        jumpSpeed=jumpBase==null ? Double.NaN : speed(jumpBase,fix);
        // Raw rejected points remain here so a subsequent jump back can also be rejected.
        jumpHistory.addLast(fix);
        if(Double.isFinite(jumpSpeed) && jumpSpeed>JUMP_LIMIT_KMH) {
            history.clear(); fastSpeed=confirmationSpeed=Double.NaN; fastSpanMs=confirmationSpanMs=0;
            slowSince=-1; slowMs=0; rejected=true; rejectedCount++;
            evidence="jump_rejected"; slowResetReason="jump_rejected"; return;
        }
        history.addLast(fix);
        while(history.size()>1 && fix.sampleTime-history.peekFirst().sampleTime>7000) history.removeFirst();
        AimingSession.Fix fast=reference(history,fix,FAST_MS);
        fastSpanMs=fast==null ? 0 : fix.sampleTime-fast.sampleTime;
        confirmationSpanMs=0; // VT 3.3: no separate confirmation window.
        fastSpeed=fastSpanMs>=FAST_MS && fastSpanMs<=2000 ? speed(fast,fix) : Double.NaN;
        confirmationSpeed=Double.NaN;
        evidence=Double.isFinite(fastSpeed) ? "valid" : "warming_up";
        if(Double.isFinite(fastSpeed) && fastSpeed>=config.rideStartKmh) {
            riding=confirmed=true; startedAt=now; expiresAt=startedAt+config.rideDurationMs;
            slowSince=-1; slowMs=0; evidence="timer_active"; event="ride_started";
        }
    }
}
