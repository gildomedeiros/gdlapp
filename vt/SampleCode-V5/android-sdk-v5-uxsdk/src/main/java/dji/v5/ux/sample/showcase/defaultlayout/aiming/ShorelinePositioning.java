package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Immutable session preset. Positive separation is on the selected filming side. */
public final class ShorelinePositioning {
    public final ShorelineGeometry shore;
    public final String mode,side,shorelineId;
    public final double axisNorth,axisEast,otherNorth,otherEast,tolerance,retreatRadius;
    public ShorelinePositioning(ShorelineGeometry shore,String id,String mode,String side,double tolerance,double retreatRadius) {
        if(!"front".equals(mode)&&!"sideways".equals(mode))throw new IllegalArgumentException("mode must be front or sideways");
        if(!"left".equals(side)&&!"right".equals(side))throw new IllegalArgumentException("sidewaysSide must be left or right looking seaward");
        if(!Double.isFinite(tolerance)||tolerance<1||tolerance>20)throw new IllegalArgumentException("alignmentToleranceMetres 1–20");
        this.shore=shore;this.shorelineId=id;this.mode=mode;this.side=side;this.tolerance=tolerance;this.retreatRadius=retreatRadius;
        // Sideways side names mean left/right while looking seaward, independent of A/B ordering.
        if(mode.equals("front")) {axisNorth=-shore.seaNorth;axisEast=-shore.seaEast;}
        else {double sign=side.equals("left")?1:-1;axisNorth=sign*shore.seaEast;axisEast=-sign*shore.seaNorth;}
        otherNorth=-axisEast;otherEast=axisNorth;
    }
    public double[] measures(AimingSession.Inputs in) {
        double[] d=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon);
        return new double[]{d[0]*axisNorth+d[1]*axisEast,d[0]*otherNorth+d[1]*otherEast};
    }
    public double[] destination(AimingSession.Inputs in,double separation) {
        return shore.point(in.target.lat,in.target.lon,axisNorth*separation,axisEast*separation);
    }
    public boolean safePath(AimingSession.Inputs in,double lat,double lon,double centralLat,double centralLon) {
        if(YawAimingMath.distance(lat,lon,centralLat,centralLon)>ComeToMeSettings.EXCURSION_STOP ||
                YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon)>=ComeToMeSettings.EXCURSION_STOP)return false;
        double[] start=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon),end=shore.offset(lat,lon,in.target.lat,in.target.lon);
        return ShorelineGeometry.segmentDistance(start[0],start[1],end[0],end[1])>=retreatRadius;
    }
    public double[] velocity(AimingSession.Inputs in,double lat,double lon,double speed) {
        double b=Math.toRadians(YawAimingMath.bearingToTarget(in.lat,in.lon,lat,lon)-in.heading);
        return new double[]{speed*Math.cos(b),speed*Math.sin(b)};
    }
}
