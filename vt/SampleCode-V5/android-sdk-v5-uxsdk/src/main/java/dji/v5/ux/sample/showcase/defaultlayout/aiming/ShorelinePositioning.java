package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Immutable session preset. Positive separation is on the selected filming side. */
public final class ShorelinePositioning {
    public final ShorelineGeometry shore;
    public final String mode,side,shorelineId;
    public final double axisNorth,axisEast,otherNorth,otherEast,tolerance,retreatRadius,angleDegrees,angleTolerance,extraClearance,excursionStop;
    public ShorelinePositioning(ShorelineGeometry shore,String id,String mode,String side,double tolerance,double retreatRadius) {
        this(shore,id,mode,side,tolerance,retreatRadius,45,5,2,249);
    }
    public ShorelinePositioning(ShorelineGeometry shore,String id,String mode,String side,double tolerance,double retreatRadius,
            double angle,double angleTolerance,double extra,double excursionStop) {
        if(!Double.isFinite(angle)||angle< -90||angle>90||!Double.isFinite(angleTolerance)||angleTolerance<.1||angleTolerance>45||
                !Double.isFinite(extra)||extra<0||extra>50||!Double.isFinite(excursionStop)||excursionStop<9||excursionStop>999)
            throw new IllegalArgumentException("Invalid angle positioning settings");
        angleDegrees=angle;this.angleTolerance=angleTolerance;extraClearance=extra;this.excursionStop=excursionStop;
        if(!"front".equals(mode)&&!"sideways".equals(mode)&&!"diagonal".equals(mode))throw new IllegalArgumentException("mode must be front, sideways or diagonal");
        if(!"left".equals(side)&&!"right".equals(side))throw new IllegalArgumentException("sidewaysSide must be left or right looking seaward");
        if(!Double.isFinite(tolerance)||tolerance<1||tolerance>20)throw new IllegalArgumentException("alignmentToleranceMetres 1–20");
        this.shore=shore;this.shorelineId=id;this.mode=mode;this.side=side;this.tolerance=tolerance;this.retreatRadius=retreatRadius;
        // Sideways side names mean left/right while looking seaward, independent of A/B ordering.
        if(mode.equals("front")) {axisNorth=-shore.seaNorth;axisEast=-shore.seaEast;}
        else if(diagonal()) {double a=Math.toRadians(angle);axisNorth=-shore.seaNorth*Math.cos(a)-shore.seaEast*Math.sin(a);
            axisEast=-shore.seaEast*Math.cos(a)+shore.seaNorth*Math.sin(a);}
        else {double sign=side.equals("left")?1:-1;axisNorth=sign*shore.seaEast;axisEast=-sign*shore.seaNorth;}
        otherNorth=-axisEast;otherEast=axisNorth;
    }
    public boolean diagonal(){return mode.equals("diagonal");}
    public double clearance(){return retreatRadius>0?retreatRadius+extraClearance:0;}
    public double currentAngle(AimingSession.Inputs in) {
        double[] d=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon);
        return Math.toDegrees(Math.atan2(-d[0]*shore.seaEast+d[1]*shore.seaNorth,-d[0]*shore.seaNorth-d[1]*shore.seaEast));
    }
    public double angleError(AimingSession.Inputs in){return YawAimingMath.shortestHeadingError(angleDegrees,currentAngle(in));}
    public boolean correctQuadrant(AimingSession.Inputs in){
        double[] d=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon);
        double w=-d[0]*shore.seaNorth-d[1]*shore.seaEast,r=-d[0]*shore.seaEast+d[1]*shore.seaNorth;
        return Math.abs(angleDegrees)==90?Math.signum(angleDegrees)*r>=-1e-6:
            angleDegrees==0?w>=-1e-6:w>=-1e-6&&Math.signum(angleDegrees)*r>=-1e-6;
    }
    public double boundaryDistance(double lat,double lon,double anchorLat,double anchorLon){
        double[] d=shore.offset(lat,lon,anchorLat,anchorLon);return d[0]*shore.seaNorth+d[1]*shore.seaEast;
    }
    public double[] measures(AimingSession.Inputs in) {
        double[] d=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon);
        return new double[]{d[0]*axisNorth+d[1]*axisEast,d[0]*otherNorth+d[1]*otherEast};
    }
    public double[] destination(AimingSession.Inputs in,double separation) {
        return shore.point(in.target.lat,in.target.lon,axisNorth*separation,axisEast*separation);
    }
    public boolean safePath(AimingSession.Inputs in,double lat,double lon,double centralLat,double centralLon) {
        return pathBlockReason(in,lat,lon,centralLat,centralLon).equals("none");
    }
    public String pathBlockReason(AimingSession.Inputs in,double lat,double lon,double centralLat,double centralLon) {
        if(YawAimingMath.distance(lat,lon,centralLat,centralLon)>excursionStop ||
                YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon)>=excursionStop)return "excursion_limit";
        double[] start=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon),end=shore.offset(lat,lon,in.target.lat,in.target.lon);
        return ShorelineGeometry.segmentDistance(start[0],start[1],end[0],end[1])>=retreatRadius ? "none" : "retreat_clearance";
    }
    public double[] velocity(AimingSession.Inputs in,double lat,double lon,double speed) {
        double b=Math.toRadians(YawAimingMath.bearingToTarget(in.lat,in.lon,lat,lon)-in.heading);
        return new double[]{speed*Math.cos(b),speed*Math.sin(b)};
    }
}
