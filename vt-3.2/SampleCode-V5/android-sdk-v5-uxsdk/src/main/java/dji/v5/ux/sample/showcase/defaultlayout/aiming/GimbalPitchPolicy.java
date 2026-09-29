package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** Startup and distance transitions only; never continuously overrides the RC wheel. */
public final class GimbalPitchPolicy {
    public double target=Double.NaN;
    public boolean close;
    public String reason="idle";
    public void reset() { target=Double.NaN; close=false; reason="idle"; }
    public boolean update(double pitch,double distance,boolean fresh,double closeRangePitchDeg,double longRangePitchDeg,double minimum,double maximum) {
        if(!Double.isFinite(pitch) || !Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum>=maximum) return false;
        if(!fresh || !Double.isFinite(distance)) return false;
        if(!Double.isFinite(target)) {
            target=Math.max(minimum,Math.min(maximum,longRangePitchDeg));
            reason="startup_long_range";
            return Math.abs(target-pitch)>0.2;
        }
        boolean next=close ? distance<=35 : distance<30;
        if(next==close) return false;
        close=next; reason=close ? "below_30m" : "above_35m";
        target=Math.max(minimum,Math.min(maximum,close ? closeRangePitchDeg : longRangePitchDeg));
        return Math.abs(target-pitch)>0.2;
    }
}
