package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** VT 3.6: Only a recently submitted retreat grants the larger measured-velocity allowance. */
public final class TranslationSpeedAllowance {
    private TranslationSpeedAllowance() { }
    public static double horizontalLimit(boolean translating,double recentRetreatSpeed) {
        return horizontalLimit(translating,ComeToMeSettings.DEFAULT_MAX_SPEED,recentRetreatSpeed);
    }
    // VT 3.6: Follow the selected approach/return cap, without coupling it to retreat configuration.
    public static double horizontalLimit(boolean translating,double movementSpeed,double recentRetreatSpeed) {
        if(!translating || !Double.isFinite(movementSpeed) || movementSpeed<0.1 || movementSpeed>ComeToMeSettings.HARD_MAX_SPEED) return 0.5;
        double cap=movementSpeed;
        if(Double.isFinite(recentRetreatSpeed) && recentRetreatSpeed>0 && recentRetreatSpeed<=RetreatSettings.MAX_SPEED)
            cap=Math.max(cap,recentRetreatSpeed);
        return cap+0.4;
    }
}
