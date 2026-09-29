package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.2: Exact reported-mode allowlist; APAS still requires all independent hover checks. */
public final class AimingFlightModes {
    private AimingFlightModes() { }
    public static boolean allows(String mode) {
        return "GPS_NORMAL".equals(mode) || "APAS".equals(mode) || "VIRTUAL_STICK".equals(mode);
    }
}
