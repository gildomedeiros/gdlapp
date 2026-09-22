package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.util.ArrayDeque;
/**
 * VT 3.0: GPS evidence only, with no movement or DJI calls.
 * The 1 s estimate enables responsive filming. Only 5 s confirmation authorizes a
 * ride-end return. A brief fast detection must not cancel the no-ride timer.
 */
public final class RideDetector {
    public static final long FAST_MS=1000, CONFIRM_MS=5000;
    public static final double JUMP_LIMIT_KMH=40;
    private final ArrayDeque<AimingSession.Fix> history=new ArrayDeque<>(), jumpHistory=new ArrayDeque<>();
    private AimingSession.Fix last;
    private long slowSince=-1;
    public boolean riding, confirmed, rejected, newPacket, repeatedCoordinates;
    public double fastSpeed=Double.NaN, confirmationSpeed=Double.NaN, jumpSpeed=Double.NaN;
    public long fastSpanMs, confirmationSpanMs, jumpSpanMs, slowMs, rejectedCount;
    public String event="none", evidence="warming_up", slowResetReason="none";
    /** New sessions/manual repositioning must not inherit a previous ride. */
    public void reset() { riding=confirmed=false; rejectedCount=0; clearEvidence("reset"); event="none"; }
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
    public void observe(AimingSession.Fix fix,ComeToMeSettings config) {
        event="none"; rejected=false; newPacket=false; slowResetReason="none";
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
        AimingSession.Fix fast=reference(history,fix,FAST_MS), confirm=reference(history,fix,CONFIRM_MS);
        fastSpanMs=fast==null ? 0 : fix.sampleTime-fast.sampleTime;
        confirmationSpanMs=confirm==null ? 0 : fix.sampleTime-confirm.sampleTime;
        fastSpeed=fastSpanMs>=FAST_MS && fastSpanMs<=2000 ? speed(fast,fix) : Double.NaN;
        confirmationSpeed=confirmationSpanMs>=CONFIRM_MS && confirmationSpanMs<=7000 ? speed(confirm,fix) : Double.NaN;
        evidence=Double.isFinite(fastSpeed) ? "valid" : "warming_up";
        if(!riding && Double.isFinite(fastSpeed) && fastSpeed>=config.rideStartKmh) {
            riding=true; slowSince=-1; slowMs=0; event="ride_started";
        }
        if(!confirmed && Double.isFinite(confirmationSpeed) && confirmationSpeed>=config.rideStartKmh) {
            riding=true; confirmed=true; event="ride_confirmed";
        }
        if(!Double.isFinite(fastSpeed)) {
            slowSince=-1; slowMs=0; slowResetReason="insufficient_evidence"; return;
        }
        if(riding && fastSpeed<config.rideEndKmh) {
            if(slowSince<0) slowSince=fix.time;
            slowMs=Math.max(0,fix.time-slowSince);
            if(slowMs>=config.rideEndMs) {
                event=confirmed ? "ride_ended" : "fast_ride_ended";
                riding=confirmed=false; slowSince=-1; slowMs=0;
            }
        } else {
            if(slowSince>=0) slowResetReason="speed_at_or_above_end_threshold";
            slowSince=-1; slowMs=0;
        }
    }
}
