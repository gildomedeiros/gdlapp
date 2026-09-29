package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.ArrayDeque;

/** CAM3 v2.7: New-fix bearing-change votes, independent of heading and flight commands. */
public final class DominantDirectionTracker {
    public static final long WINDOW_MS = 3000;
    private static final class Vote {
        final long time; final int direction;
        Vote(long time, int direction) { this.time=time; this.direction=direction; }
    }
    private final ArrayDeque<Vote> votes = new ArrayDeque<>();
    private AimingSession.Fix previous;
    private long lastNow = -1;
    public boolean newFix;
    public String lastVote = "none";
    public double bearingChange = Double.NaN;
    public void reset() { votes.clear(); previous=null; lastNow=-1; newFix=false; lastVote="none"; bearingChange=Double.NaN; }
    public void observe(AimingSession.Inputs in, long now) {
        newFix=false; lastVote="none"; bearingChange=Double.NaN;
        if (now < lastNow) reset();
        lastNow=now;
        while (!votes.isEmpty() && now-votes.peekFirst().time >= WINDOW_MS) votes.removeFirst();
        AimingSession.Fix f=in.target;
        if (f==null || !YawAimingMath.coordinateValid(f.lat,f.lon) || f.time<=0 || now<f.time || now-f.time>3000
                || !YawAimingMath.coordinateValid(in.lat,in.lon) || in.aircraftTime<=0
                || now<in.aircraftTime || now-in.aircraftTime>1500) {
            previous=null; return;
        }
        if (previous!=null && f.time==previous.time) return;
        newFix=true;
        if (previous!=null && (f.time<previous.time || f.time-previous.time>=WINDOW_MS)) {
            votes.clear(); previous=null;
        }
        if (previous!=null && YawAimingMath.distance(in.lat,in.lon,f.lat,f.lon)>0
                && YawAimingMath.distance(in.lat,in.lon,previous.lat,previous.lon)>0) {
            // Compare both target positions about the SAME current aircraft position: yaw or aircraft
            // translation alone cannot invent a surfer-motion vote. No prediction or coordinate smoothing.
            double before=YawAimingMath.bearingToTarget(in.lat,in.lon,previous.lat,previous.lon);
            double after=YawAimingMath.bearingToTarget(in.lat,in.lon,f.lat,f.lon);
            bearingChange=YawAimingMath.shortestHeadingError(after,before);
            if (Math.abs(bearingChange)>1e-7 && Math.abs(bearingChange)<179.9999999) {
                int sign=bearingChange>0 ? 1 : -1;
                votes.addLast(new Vote(f.time,sign)); lastVote=sign>0 ? "right" : "left";
            } else lastVote="neutral"; // identical bearing or ambiguous half-turn adds no directional vote
        } else lastVote="baseline";
        previous=f;
    }
    public int rightVotes() { int count=0; for(Vote v:votes) if(v.direction>0) count++; return count; }
    public int leftVotes() { int count=0; for(Vote v:votes) if(v.direction<0) count++; return count; }
    public int calculateDominantDirection() { int left=leftVotes(); return left>=3 && left>rightVotes() ? -1 : 1; }
    public boolean defaultRight() { return !(rightVotes()>=3 && rightVotes()>leftVotes()) && calculateDominantDirection()>0; }
    public static String name(int direction) { return direction>0 ? "right" : direction<0 ? "left" : "none"; }
}
