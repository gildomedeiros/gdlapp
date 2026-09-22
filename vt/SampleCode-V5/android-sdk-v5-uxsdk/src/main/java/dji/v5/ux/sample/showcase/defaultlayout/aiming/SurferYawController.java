package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/**
 * VT 3.0: shortest-route surfer yaw; navigation must never use this reverse block.
 * Direction memory is separate from output rate. A zero command does not erase the
 * commitment and accidentally allow an opposite command on the next control cycle.
 */
public final class SurferYawController {
    public static final long REVERSE_BLOCK_MS=2000;
    public int permittedDirection, requestedDirection;
    public long lastPermittedAt=-1, remainingMs;
    public boolean blocked;
    public double desiredRate;
    public void reset() {
        permittedDirection=requestedDirection=0; lastPermittedAt=-1; remainingMs=0;
        blocked=false; desiredRate=0;
    }
    public double calculate(double error,double previous,double dt,boolean riding,long now) {
        if(!Double.isFinite(error)||!Double.isFinite(previous)||!Double.isFinite(dt)||dt<=0||dt>.5)
            throw new IllegalArgumentException("Invalid surfer yaw input");
        if(lastPermittedAt>now) reset();
        double effective=Math.max(0,Math.abs(error)-(riding ? 0 : YawAimingMath.ALIGNMENT_DEGREES));
        requestedDirection=effective==0 ? 0 : error>0 ? 1 : -1;
        blocked=false; desiredRate=0;
        remainingMs=lastPermittedAt<0 ? 0 : Math.max(0,REVERSE_BLOCK_MS-(now-lastPermittedAt));
        // Aligned and blocked opposite requests never refresh the permitted-direction clock.
        if(requestedDirection==0) return 0;
        desiredRate=requestedDirection*Math.min(YawAimingMath.MAX_RATE,effective*.5);
        if(permittedDirection!=0 && requestedDirection!=permittedDirection && remainingMs>0) {
            blocked=true; return 0;
        }
        permittedDirection=requestedDirection; lastPermittedAt=now; remainingMs=REVERSE_BLOCK_MS;
        if(error*previous<0) return 0; // Do not prolong the old turn while reversing through zero.
        double step=YawAimingMath.MAX_ACCELERATION*dt;
        return previous+Math.max(-step,Math.min(step,desiredRate-previous));
    }
    public static String directionName(int d) { return d>0 ? "right" : d<0 ? "left" : "none"; }
}
