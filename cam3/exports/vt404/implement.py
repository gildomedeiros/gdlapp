from pathlib import Path
import json
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
pkg='dji/v5/ux/sample/showcase/defaultlayout/aiming'
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java'/pkg
def read(n): return (src/n).read_text(encoding='utf-8')
def write(n,s): (src/n).write_text(s,encoding='utf-8')
s=read('ComeToMeSettings.java').replace('public final double maxYawRate, yawAcceleration, maxMovementSpeed;', 'public final double maxYawRate, yawAcceleration, maxMovementSpeed;\n    public final double maxExcursionMetres;\n    public double excursionStop() {return maxExcursionMetres-COMPLETION_TOLERANCE;}')
needle='        if(!inRange(maxMovementSpeed,0.1,HARD_MAX_SPEED))'
s=s.replace(needle,'''        this(enabled,filming,width,start,end,endMs,inactivityMs,margin,durationMs,closePitchDeg,longPitchDeg,maxYawRate,yawAcceleration,maxMovementSpeed,250);
    }
    public ComeToMeSettings(boolean enabled,double filming,double width,double start,double end,long endMs,long inactivityMs,
            double margin,long durationMs,double closePitchDeg,double longPitchDeg,double maxYawRate,double yawAcceleration,
            double maxMovementSpeed,double maxExcursionMetres) {
        if(!inRange(maxExcursionMetres,10,1000))throw new IllegalArgumentException("maxExcursionMetres must be 10..1000");
        this.maxExcursionMetres=maxExcursionMetres;
'''+needle)
s=s.replace('maxYawRate,yawAcceleration,speed);','maxYawRate,yawAcceleration,speed,maxExcursionMetres);')
write('ComeToMeSettings.java',s)
s=read('VtSettingsConfig.java').replace('public final double alignmentTolerance;','public final double alignmentTolerance,angle,angleTolerance,extraClearance;')
s=s.replace('String side,double tolerance) {','String side,double tolerance,double angle,double angleTolerance,double extraClearance) {').replace('alignmentTolerance=tolerance;','alignmentTolerance=tolerance;this.angle=angle;this.angleTolerance=angleTolerance;this.extraClearance=extraClearance;')
s=s.replace('        VtJsonFields.keys(o,','''        double angle=optional(o,"positioningAngleDegrees",45,-90,90);
        double angleTolerance=optional(o,"positioningAngleToleranceDegrees",5,.1,45);
        double extra=optional(o,"extraPathClearanceMetres",2,0,50);
        double excursion=optional(o,"maxExcursionMetres",300,10,1000);
        JsonObject required=o.deepCopy();
        for(String k:new String[]{"positioningAngleDegrees","positioningAngleToleranceDegrees","extraPathClearanceMetres","maxExcursionMetres"})required.remove(k);
        VtJsonFields.keys(required,''')
s=s.replace('!mode.equals("sideways")','!mode.equals("sideways")&&!mode.equals("diagonal")').replace('mode must be front or sideways','mode must be front, sideways or diagonal')
s=s.replace('VtJsonFields.number(o,"maxMovementSpeedMetresPerSecond",.1,5));','VtJsonFields.number(o,"maxMovementSpeedMetresPerSecond",.1,5),excursion);')
s=s.replace('for both modes','for all modes').replace('VtJsonFields.number(o,"alignmentToleranceMetres",1,20));','VtJsonFields.number(o,"alignmentToleranceMetres",1,20),angle,angleTolerance,extra);')
s=s.replace('    public static VtSettingsConfig parse(','''    private static double optional(JsonObject o,String k,double value,double lo,double hi) {
        return o.has(k)?VtJsonFields.number(o,k,lo,hi):value;
    }
    public static VtSettingsConfig parse(''')
write('VtSettingsConfig.java',s)
s=read('ShorelinePositioning.java').replace('tolerance,retreatRadius;','tolerance,retreatRadius,angleDegrees,angleTolerance,extraClearance,excursionStop;')
s=s.replace('        if(!"front".equals(mode)', '''        this(shore,id,mode,side,tolerance,retreatRadius,45,5,2,249);
    }
    public ShorelinePositioning(ShorelineGeometry shore,String id,String mode,String side,double tolerance,double retreatRadius,
            double angle,double angleTolerance,double extra,double excursionStop) {
        if(!Double.isFinite(angle)||angle< -90||angle>90||!Double.isFinite(angleTolerance)||angleTolerance<.1||angleTolerance>45||
                !Double.isFinite(extra)||extra<0||extra>50||!Double.isFinite(excursionStop)||excursionStop<9||excursionStop>999)
            throw new IllegalArgumentException("Invalid angle positioning settings");
        angleDegrees=angle;this.angleTolerance=angleTolerance;extraClearance=extra;this.excursionStop=excursionStop;
        if(!"front".equals(mode)''')
s=s.replace('&&!"sideways".equals(mode))','&&!"sideways".equals(mode)&&!"diagonal".equals(mode))').replace('mode must be front or sideways','mode must be front, sideways or diagonal')
s=s.replace('        else {double sign=', '''        else if(diagonal()) {double a=Math.toRadians(angle);axisNorth=-shore.seaNorth*Math.cos(a)-shore.seaEast*Math.sin(a);
            axisEast=-shore.seaEast*Math.cos(a)+shore.seaNorth*Math.sin(a);}
        else {double sign=''')
s=s.replace('ComeToMeSettings.EXCURSION_STOP','excursionStop')
s=s.replace('    public double[] measures(','''    public boolean diagonal(){return mode.equals("diagonal");}
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
    public double[] measures(''')
write('ShorelinePositioning.java',s)
s=read('VtSessionConfig.java').replace('result.retreat.enabled?result.retreat.minimumDistance:0);','result.retreat.enabled?result.retreat.minimumDistance:0,c.angle,c.angleTolerance,c.extraClearance,c.movement.excursionStop());')
write('VtSessionConfig.java',s)
s=read('RetreatController.java').replace('ComeToMeSettings.EXCURSION_STOP','m.settings().excursionStop()')
write('RetreatController.java',s)
s=read('ComeToMeController.java').replace('ComeToMeSettings.EXCURSION_STOP','settings.excursionStop()').replace('ComeToMeSettings.MAX_EXCURSION','settings.maxExcursionMetres')
s=s.replace('    public long destinationFixTime=-1;', '''    public long destinationFixTime=-1;
    public AngleRoutePlanner.Plan route;
    public int routeIndex;
    public double routeSurferLat=Double.NaN,routeSurferLon=Double.NaN,angleErrorDegrees=Double.NaN;
    public String routeStatus="none",clockwiseFailure="none",anticlockwiseFailure="none";
    public int failedSegment=-1;
    public double failedClearance=Double.NaN,failedBoundary=Double.NaN,failedExcursion=Double.NaN;
    public double waypointLat(){return route==null?approachTargetLat:route.points[routeIndex][0];}
    public double waypointLon(){return route==null?approachTargetLon:route.points[routeIndex][1];}
    public boolean angleApproach(){return positioning!=null&&positioning.diagonal();}
''')
s=s.replace('    private void updatePreset(AimingSession.Inputs in,long now,boolean freshSurfer) {','''    private void updatePreset(AimingSession.Inputs in,long now,boolean freshSurfer) {
        if(angleApproach()){updateAngle(in,now,freshSurfer);return;}''')
method='''    private boolean angleLegAllowed(AimingSession.Inputs in) {
        AngleRoutePlanner.Check c=AngleRoutePlanner.check(positioning,in.lat,in.lon,waypointLat(),waypointLon(),
            routeSurferLat,routeSurferLon,initialCentralLat,initialCentralLon,centralLat,centralLon);
        if(!c.reason.equals("none")){pathBlockReason=c.reason;failedSegment=routeIndex;
            failedClearance=c.clearance;failedBoundary=c.boundary;failedExcursion=c.excursion;return false;}
        return true;
    }
    private void updateAngle(AimingSession.Inputs in,long now,boolean freshSurfer) {
        projectedSeparation=Double.NaN;alignmentError=Double.NaN;
        angleErrorDegrees=positioning.angleError(in);
        if(!approaching()) {
            if(phase!=Phase.WAITING&&phase!=Phase.HOLDING)return;
            if(!freshSurfer){reason="waiting_fresh_planning_fix";return;}
            if(distance<1e-6){reason="coincident_position_hold";return;}
            boolean close=distance>approachStartThreshold()+.000001;
            if(!close&&Math.abs(angleErrorDegrees)<=positioning.angleTolerance+.000001){
                if(!hasFilmed)enterFilmingHold(now);else {phase=Phase.HOLDING;reason="within_distance_and_angle_tolerance";}return;}
            double radius=close?settings.filmingDistance:distance;
            double[] target=positioning.destination(in,radius);
            AngleRoutePlanner.Plan p=AngleRoutePlanner.plan(positioning,in,target,initialCentralLat,initialCentralLon,centralLat,centralLon);
            clockwiseFailure=p.clockwiseFailure;anticlockwiseFailure=p.anticlockwiseFailure;
            failedSegment=p.failedSegment;failedClearance=p.failedClearance;failedBoundary=p.failedBoundary;failedExcursion=p.failedExcursion;
            pathCheckStatus=p.reason.equals("none")?"allowed":"blocked";pathBlockReason=p.reason;
            if(!p.reason.equals("none")){reason=p.reason;routeStatus="blocked";return;}
            route=p;routeIndex=0;routeSurferLat=in.target.lat;routeSurferLon=in.target.lon;
            approachStartLat=in.lat;approachStartLon=in.lon;approachTargetLat=target[0];approachTargetLon=target[1];
            approachHeading=YawAimingMath.bearingToTarget(in.lat,in.lon,target[0],target[1]);
            approachDistance=p.length;approachProgress=0;destinationFixTime=in.target.time;
            journeyKind=close?"direct_distance_reapproach":"angle_alignment_preserve_direct_distance";
            routeStatus="planned";approachAligned=true;phase=Phase.APPROACHING;attemptSince=now;event="fixed_route_planned";reason=journeyKind;return;
        }
        double remaining=YawAimingMath.distance(in.lat,in.lon,waypointLat(),waypointLon());
        if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE){
            if(routeIndex==route.points.length-1){routeStatus="arrived";enterFilmingHold(now);reason="fixed_destination_arrived";return;}
            routeIndex++;routeStatus="waypoint_transition";reason="waypoint_transition";return;
        }
        if(centralDistance>=settings.excursionStop()){phase=Phase.HOLDING;reason="excursion_limit";routeStatus="blocked";attemptSince=-1;return;}
        if(!angleLegAllowed(in)){reason=pathBlockReason;routeStatus="blocked";return;}
        double total=remaining;for(int i=routeIndex+1;i<route.points.length;i++)total+=YawAimingMath.distance(route.points[i-1][0],route.points[i-1][1],route.points[i][0],route.points[i][1]);
        approachProgress=approachDistance-total;routeStatus="traveling";
        double[] v=positioning.velocity(in,waypointLat(),waypointLon(),arrivalSpeed(Math.min(remaining,settings.maxExcursionMetres-centralDistance)));
        // No positioning reserve/horizon: veto a shoreward command if already on the boundary line.
        double normal=v[0]*(Math.cos(Math.toRadians(in.heading))*positioning.shore.seaNorth+Math.sin(Math.toRadians(in.heading))*positioning.shore.seaEast)
            +v[1]*(-Math.sin(Math.toRadians(in.heading))*positioning.shore.seaNorth+Math.cos(Math.toRadians(in.heading))*positioning.shore.seaEast);
        if(positioning.boundaryDistance(in.lat,in.lon,initialCentralLat,initialCentralLon)<=0&&normal< -1e-6){reason="route_central_boundary";return;}
        forward=v[0];right=v[1];reason="moving_saved_route_aiming_surfer";
    }
'''
s=s.replace('    /** Record arrival once.',method+'    /** Record arrival once.')
s=s.replace('        approachTargetLat=approachTargetLon=Double.NaN;','''        route=null;routeIndex=0;routeStatus="none";routeSurferLat=routeSurferLon=angleErrorDegrees=Double.NaN;
        clockwiseFailure=anticlockwiseFailure="none";failedSegment=-1;failedClearance=failedBoundary=failedExcursion=Double.NaN;
        approachTargetLat=approachTargetLon=Double.NaN;''')
s=s.replace('double remaining=YawAimingMath.distance(in.lat,in.lon,approachTargetLat,approachTargetLon);\n        if(central>=', 'double remaining=YawAimingMath.distance(in.lat,in.lon,waypointLat(),waypointLon());\n        if(angleApproach()&&(route==null||!angleLegAllowed(in)))return false;\n        if(central>=')
s=s.replace('double[] v=positioning.velocity(in,approachTargetLat,approachTargetLon,arrivalSpeed(Math.min(remaining,settings.maxExcursionMetres-central)));','double[] v=positioning.velocity(in,waypointLat(),waypointLon(),arrivalSpeed(Math.min(remaining,settings.maxExcursionMetres-central)));')
s=s.replace('        if(positioning!=null)return String.format', '''        if(angleApproach())return String.format(Locale.US,"Come to me angle %.1f° · %s · %s\\nDirect horizontal %.1f m (filming %.1f m; approach > %.1f m) · angle error %.1f° (tolerance %.1f°)\\nRoute %s / %s · waypoint %d/%d · excursion %.1f/%.1f m",
            positioning.angleDegrees,phase,reason.replace('_',' '),distance,settings.filmingDistance,approachStartThreshold(),angleErrorDegrees,positioning.angleTolerance,
            route==null?"none":route.kind,routeStatus,route==null?0:routeIndex+1,route==null?0:route.points.length,centralDistance,settings.maxExcursionMetres);
        if(positioning!=null)return String.format''')
write('ComeToMeController.java',s)
s=read('MovementCycleLog.java').replace('ComeToMeSettings.MAX_EXCURSION','c.maxExcursionMetres').replace('ComeToMeSettings.EXCURSION_STOP','c.excursionStop()')
s=s.replace('"projectedSeparationM",m.projectedSeparation,','''"positioningAngleDegrees",m.angleApproach()?m.positioning.angleDegrees:null,
                "positioningAngleToleranceDegrees",m.angleApproach()?m.positioning.angleTolerance:null,
                "positioningAngleErrorDegrees",m.angleErrorDegrees,"distanceMeaning",m.angleApproach()?"direct_horizontal":"projected",
                "extraPathClearanceMetres",m.angleApproach()?m.positioning.extraClearance:null,
                "requiredPathClearanceMetres",m.angleApproach()?m.positioning.clearance():null,
                "routeKind",m.route==null?null:m.route.kind,"routeDirection",m.route==null?null:m.route.direction,
                "routeStatus",m.routeStatus,"routeWaypointIndex",m.routeIndex,"routeWaypoints",m.route==null?null:m.route.points,
                "routeSurferLatitude",m.routeSurferLat,"routeSurferLongitude",m.routeSurferLon,
                "clockwiseRejectedReason",m.clockwiseFailure,"anticlockwiseRejectedReason",m.anticlockwiseFailure,
                "failedSegmentIndex",m.failedSegment,"failedSegmentClearanceM",m.failedClearance,"failedSegmentBoundaryM",m.failedBoundary,"failedSegmentExcursionM",m.failedExcursion,
                "projectedSeparationM",m.projectedSeparation,''')
write('MovementCycleLog.java',s)
assets=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets'
p=assets/'vt_settings.json'; j=json.loads(p.read_text());j.update(positioningAngleDegrees=45,positioningAngleToleranceDegrees=5,extraPathClearanceMetres=2,maxExcursionMetres=300);p.write_text(json.dumps(j,indent=2)+'\n')
p=root/'SampleCode-V5/android-sdk-v5-sample/build.gradle';s=p.read_text().replace('versionCode 26','versionCode 27').replace('versionName "4.0.3"','versionName "4.0.4"');p.write_text(s)
print('VT404 source edits applied')
