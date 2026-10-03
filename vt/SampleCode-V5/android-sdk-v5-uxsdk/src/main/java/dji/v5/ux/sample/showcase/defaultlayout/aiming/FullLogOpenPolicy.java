package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** One opening attempt per foreground entry or explicit enable. Busy close postpones the attempt. */
public final class FullLogOpenPolicy {
    public boolean wanted=true;
    private boolean pending;
    public void foregroundEntered(){pending=wanted;}
    public void request(boolean enable){wanted=enable;pending=enable;}
    public boolean shouldOpen(boolean foreground,boolean permission,boolean enabled,boolean busy){
        if(!foreground||!wanted||!pending||!permission||busy)return false;
        pending=false;return !enabled;
    }
}
