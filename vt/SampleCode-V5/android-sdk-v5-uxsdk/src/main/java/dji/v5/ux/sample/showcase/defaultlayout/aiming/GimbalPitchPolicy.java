package dji.v5.ux.sample.showcase.defaultlayout.aiming;
public final class GimbalPitchPolicy {
    public double original=Double.NaN, target=Double.NaN;
    public boolean close;
    public String reason="idle";
    public void reset() { original=target=Double.NaN; close=false; reason="idle"; }
    public boolean update(double pitch,double distance,boolean fresh,double closeRangePitchDeg,double minimum,double maximum) {
        if(!Double.isFinite(pitch) || !Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum>=maximum) return false;
        if(!Double.isFinite(original)) { original=pitch; target=pitch; reason="baseline_saved"; }
        if(!fresh || !Double.isFinite(distance)) return false;
        boolean next=close ? distance<=35 : distance<30;
        if(next==close) return false;
        close=next; reason=close ? "below_30m" : "above_35m";
        target=Math.max(minimum,Math.min(maximum,close ? closeRangePitchDeg : original));
        return Math.abs(target-pitch)>0.2;
    }
}
