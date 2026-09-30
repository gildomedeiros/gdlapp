package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** Stateful outward-only hysteresis; band selection is independent of filming distance. */
public final class GimbalBandPolicy {
    public double target=Double.NaN;
    public int band=-1,previousBand=-1;
    public String reason="idle";
    public void reset(){target=Double.NaN;band=previousBand=-1;reason="idle";}
    public boolean update(double actual,double distance,boolean fresh,GimbalBandConfig c,double min,double max) {
        if(!fresh || !Double.isFinite(distance) || distance<0 || !Double.isFinite(actual)
            || !Double.isFinite(min) || !Double.isFinite(max) || min>=max)return false;
        int next=band;
        if(next<0){next=0;while(next<c.size()-1 && distance>c.upper(next))next++;}
        else {
            while(next<c.size()-1 && distance>c.upper(next)+c.bufferMetres)next++;
            while(next>0 && distance<c.upper(next-1))next--;
        }
        if(next==band)return false;
        previousBand=band;band=next;
        reason=previousBand<0 ? "startup_band" : next>previousBand ? "distance_increased" : "distance_decreased";
        target=Math.max(min,Math.min(max,c.pitch(band)));
        return Math.abs(target-actual)>0.2;
    }
}
