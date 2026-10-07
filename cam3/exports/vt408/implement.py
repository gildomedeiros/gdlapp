from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
base=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
def edit(path,old,new):
 s=path.read_text(); assert old in s,(path,old[:100]);path.write_text(s.replace(old,new))
p=base/'AngleRoutePlanner.java'
edit(p,'c.boundary=Math.min(p.boundaryDistance(aLat,aLon,anchorLat,anchorLon),p.boundaryDistance(bLat,bLon,anchorLat,anchorLon));','double startBoundary=p.boundaryDistance(aLat,aLon,anchorLat,anchorLon);\n        double endBoundary=p.boundaryDistance(bLat,bLon,anchorLat,anchorLon);\n        c.boundary=Math.min(startBoundary,endBoundary);')
edit(p,'if(!Double.isFinite(c.boundary)||c.boundary< -1e-6)c.reason="route_central_boundary";','if(!boundaryLegAllowed(startBoundary,endBoundary))c.reason="route_central_boundary";')
edit(p,'    public static Check planningCheck(','''    /** All waypoint endpoints are permitted; a beachward origin may recover monotonically. */
    public static boolean boundaryLegAllowed(double start,double end) {
        return Double.isFinite(start)&&Double.isFinite(end)&&end>=-1e-6
            &&(start>=-1e-6||end>start+1e-6);
    }
    public static Check planningCheck(''')
old='''        if(!start.reason.equals("none")){
            if(start.reason.equals("route_central_boundary"))start.reason="start_beachward_of_boundary";
            else if(start.reason.equals("route_planning_clearance")||start.reason.equals("route_surfer_clearance"))start.reason="start_inside_planning_clearance";
            result.failure(start,0);return result;
        }'''
new='''        // A beachward origin is not a waypoint: validate its excursion and radius separately.
        if(start.excursion>p.excursionStop+1e-6) {
            start.reason="route_excursion_limit";result.failure(start,0);return result;
        }
        if(start.clearance+1e-6<p.planningClearance()) {
            start.reason="start_inside_planning_clearance";result.failure(start,0);return result;
        }
        if(!Double.isFinite(start.boundary)) {result.failure(start,0);return result;}'''
edit(p,old,new)
c=base/'ComeToMeController.java'
edit(c,'            if(returnBoundaryDistance< -1e-6) {reason="return_start_beachward_of_boundary";event=reason;return;}\n            if(returnBoundaryDistance<=settings.returnBoundaryStandOffMetres) {','            if(returnBoundaryDistance>=-1e-6 && returnBoundaryDistance<=settings.returnBoundaryStandOffMetres) {')
edit(c,'''            double fraction=1-settings.returnBoundaryStandOffMetres/returnBoundaryDistance;
            double[] toCentral=positioning.shore.offset(initialCentralLat,initialCentralLon,in.lat,in.lon);
            double[] target=positioning.shore.point(in.lat,in.lon,toCentral[0]*fraction,toCentral[1]*fraction);''','''            double[] target;
            if(returnBoundaryDistance<0) {
                // Recovery starts outside the permitted half-plane: nearest point on stand-off line.
                double recovery=settings.returnBoundaryStandOffMetres-returnBoundaryDistance;
                target=positioning.shore.point(in.lat,in.lon,
                    positioning.shore.seaNorth*recovery,positioning.shore.seaEast*recovery);
            } else {
                double fraction=1-settings.returnBoundaryStandOffMetres/returnBoundaryDistance;
                double[] toCentral=positioning.shore.offset(initialCentralLat,initialCentralLon,in.lat,in.lon);
                target=positioning.shore.point(in.lat,in.lon,toCentral[0]*fraction,toCentral[1]*fraction);
            }''')
edit(c,'                if(returnBoundaryDistance< -1e-6) {reason="return_beachward_of_boundary";event=reason;return;}','                if(!returnLegAllowed(in)) {reason="return_boundary_or_excursion_blocked";event=reason;return;}')
edit(c,'if(returnDistance-returnProgress<=ComeToMeSettings.COMPLETION_TOLERANCE) {','if(returnDistance-returnProgress<=ComeToMeSettings.COMPLETION_TOLERANCE && (!softReturn()||boundaryAt(in)>=-1e-6)) {')
edit(c,'AngleRoutePlanner.Plan p=AngleRoutePlanner.plan(positioning,in,target,initialCentralLat,initialCentralLon,centralLat,centralLon);','AngleRoutePlanner.Plan p=AngleRoutePlanner.recover(positioning,in,target,in.target.lat,in.target.lon,in.target.time,initialCentralLat,initialCentralLon,centralLat,centralLon);')
edit(c,'if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE){','if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE && boundaryAt(in)>=-1e-6 && angleLegAllowed(in)){')
edit(c,'in.validate(now,true)!=null||expireAttempt(now)||in.target.time!=lastFix||boundaryAt(in)< -1e-6)return false;','in.validate(now,true)!=null||expireAttempt(now)||in.target.time!=lastFix||!returnLegAllowed(in))return false;')
edit(c,'if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE)return false;','if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE && boundaryAt(in)>=-1e-6)return false;')
edit(c,'if(central>=settings.excursionStop()||remaining<=ComeToMeSettings.COMPLETION_TOLERANCE)return false;','if(central>=settings.excursionStop()||(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE && (!angleApproach()||boundaryAt(in)>=-1e-6)))return false;')
edit(c,'    public boolean permits(AimingSession.Inputs in,long now,double requestedForward,double requestedRight) {','''    private boolean returnLegAllowed(AimingSession.Inputs in) {
        return AngleRoutePlanner.boundaryLegAllowed(boundaryAt(in),
            positioning.boundaryDistance(returnTargetLat,returnTargetLon,initialCentralLat,initialCentralLon))
            && YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon)<settings.excursionStop()
            && YawAimingMath.distance(returnTargetLat,returnTargetLon,centralLat,centralLon)<settings.excursionStop();
    }
    public boolean permits(AimingSession.Inputs in,long now,double requestedForward,double requestedRight) {''')
for path in [r/'SampleCode-V5/android-sdk-v5-sample/build.gradle',base/'WaveLineSetupUi.java',base.parent/'DefaultLayoutActivity.java']:
 s=path.read_text().replace('4.0.7','4.0.8').replace('versionCode 30','versionCode 31');path.write_text(s)
