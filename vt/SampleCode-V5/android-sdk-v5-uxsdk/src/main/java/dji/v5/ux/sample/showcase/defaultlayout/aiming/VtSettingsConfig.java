package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import com.google.gson.*;
/** VT 4.0: tuning is loaded from one JSON source, never preferences or a tuning form. */
public final class VtSettingsConfig {
    public final ComeToMeSettings movement;
    public final String mode,shorelineId,sidewaysSide;
    public final double alignmentTolerance;
    private VtSettingsConfig(ComeToMeSettings movement,String mode,String id,String side,double tolerance) {
        this.movement=movement;this.mode=mode;shorelineId=id;sidewaysSide=side;alignmentTolerance=tolerance;
    }
    public static VtSettingsConfig parse(String raw,RetreatSettings retreat) {
        JsonObject o=StrictConfigJson.object(raw);
        VtJsonFields.keys(o,"version","enabled","filmingSeparationMetres","reapproachMarginMetres","maxMovementSpeedMetresPerSecond",
            "lineupWidthMetres","rideStartKmh","rideDurationSeconds","noRideTimeoutSeconds","maxYawRateDegreesPerSecond",
            "yawAccelerationDegreesPerSecondSquared","mode","shorelineId","sidewaysSide","alignmentToleranceMetres");
        VtJsonFields.number(o,"version",1,1);
        String mode=VtJsonFields.string(o,"mode"),id=VtJsonFields.string(o,"shorelineId"),side=VtJsonFields.string(o,"sidewaysSide");
        if(!mode.equals("front")&&!mode.equals("sideways"))throw new IllegalArgumentException("mode must be front or sideways");
        if(!side.equals("left")&&!side.equals("right"))throw new IllegalArgumentException("sidewaysSide must be left or right, looking seaward");
        ComeToMeSettings c=new ComeToMeSettings(VtJsonFields.bool(o,"enabled"),VtJsonFields.number(o,"filmingSeparationMetres",10,200),
            VtJsonFields.number(o,"lineupWidthMetres",20,200),VtJsonFields.number(o,"rideStartKmh",1,100),.1,1000,
            Math.round(1000*VtJsonFields.number(o,"noRideTimeoutSeconds",60,7200)),VtJsonFields.number(o,"reapproachMarginMetres",0,200),
            Math.round(1000*VtJsonFields.number(o,"rideDurationSeconds",1,600)),-25,-10,
            VtJsonFields.number(o,"maxYawRateDegreesPerSecond",1,30),VtJsonFields.number(o,"yawAccelerationDegreesPerSecondSquared",.5,30),
            VtJsonFields.number(o,"maxMovementSpeedMetresPerSecond",.1,5));
        if(c.enabled&&retreat.enabled&&c.filmingDistance<retreat.minimumDistance+5)
            throw new IllegalArgumentException("filmingSeparationMetres must be >= retreat minimumDistanceMetres + 5 m for both modes; required "+(retreat.minimumDistance+5));
        return new VtSettingsConfig(c,mode,id,side,VtJsonFields.number(o,"alignmentToleranceMetres",1,20));
    }
}
