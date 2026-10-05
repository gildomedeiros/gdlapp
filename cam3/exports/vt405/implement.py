from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
def edit(path,old,new):
 s=path.read_text(encoding='utf-8');assert old in s,(path,old[:80]);path.write_text(s.replace(old,new),encoding='utf-8')
edit(src/'ShorelinePositioning.java','extraClearance=extra;','extraClearance=mode.equals("diagonal")?0:extra;')
edit(src/'VtSettingsConfig.java','"extraPathClearanceMetres",2,','"extraPathClearanceMetres",0,')
edit(root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_settings.json','"extraPathClearanceMetres": 2','"extraPathClearanceMetres": 0')
p=src/'AngleRoutePlanner.java'
edit(p,'public double length,failedClearance','public boolean escapeFirst;\n        public double length,failedClearance')
s=p.read_text();pos=s.rfind('\n}')
s=s[:pos]+'''
    /** Recovery keeps the captured surfer and destination; only aircraft origin is updated. */
    public static Plan recover(ShorelinePositioning p,AimingSession.Inputs live,double[] end,
            double sl,double so,long fixTime,double anchorLat,double anchorLon,double centralLat,double centralLon) {
        AimingSession.Inputs frozen=new AimingSession.Inputs(new AimingSession.Fix(sl,so,0,fixTime),
                live.lat,live.lon,live.heading,live.aircraftTime,live.problem,live.safeToNeutral);
        Plan ordinary=plan(p,frozen,end,anchorLat,anchorLon,centralLat,centralLon);
        if(!ordinary.reason.equals("start_inside_routing_clearance"))return ordinary;
        double[] start=p.shore.offset(live.lat,live.lon,sl,so);
        double radius=(p.clearance()+1.1)/Math.cos(Math.PI/N);
        double initial=Math.atan2(start[1],start[0]);Plan best=null;
        // Radial escape is tried first; other outward directions permit escape beside a boundary.
        for(int i=0;i<N;i++) {
            double a=initial+2*Math.PI*i/N;
            double[] out=p.shore.point(sl,so,radius*Math.cos(a),radius*Math.sin(a));
            Check check=escapeCheck(p,live.lat,live.lon,out[0],out[1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
            if(!check.reason.equals("none"))continue;
            AimingSession.Inputs from=new AimingSession.Inputs(frozen.target,out[0],out[1],live.heading,live.aircraftTime,null,true);
            Plan tail=plan(p,from,end,anchorLat,anchorLon,centralLat,centralLon);
            if(!tail.reason.equals("none"))continue;
            double length=YawAimingMath.distance(live.lat,live.lon,out[0],out[1])+tail.length;
            if(best!=null&&length>=best.length)continue;
            best=tail;double[][] points=new double[tail.points.length+1][];points[0]=out;
            System.arraycopy(tail.points,0,points,1,tail.points.length);best.points=points;best.length=length;
            best.escapeFirst=true;best.kind="escape_then_"+tail.kind;
        }
        if(best!=null)return best;
        ordinary.reason="no_permitted_outward_escape";return ordinary;
    }
    /** An escape may start inside the circle, but can never reduce separation along its leg. */
    public static Check escapeCheck(ShorelinePositioning p,double aLat,double aLon,double bLat,double bLon,
            double sl,double so,double anchorLat,double anchorLon,double centralLat,double centralLon) {
        Check c=check(p,aLat,aLon,bLat,bLon,sl,so,anchorLat,anchorLon,centralLat,centralLon);
        if(!c.reason.equals("none")&&!c.reason.equals("route_surfer_clearance"))return c;
        double[] a=p.shore.offset(aLat,aLon,sl,so),b=p.shore.offset(bLat,bLon,sl,so);
        double dot=a[0]*(b[0]-a[0])+a[1]*(b[1]-a[1]);
        c.reason=dot< -1e-6||Math.hypot(b[0],b[1])<=Math.hypot(a[0],a[1])+1e-6?
                "escape_not_outward":"none";
        return c;
    }
'''+s[pos:];p.write_text(s)
p=src/'ComeToMeController.java'
edit(p,'public int routeIndex;','''public int routeIndex;
    public long recoveryAttempts,lastRecoveryAt=-1,journeyId;
    public String recoveryStatus="none",lastJourneyBlock="none";
    private boolean journeyOpen;
    public java.util.function.Consumer<JourneyOutcome> outcomeListener=o->{};
    public static final class JourneyOutcome {
        public final long id,elapsedMs;public final String outcome,cause,lastBlock;
        public final double surferLat,surferLon,targetLat,targetLon;
        JourneyOutcome(ComeToMeController m,String outcome,String cause){id=m.journeyId;elapsedMs=m.attemptElapsedMs;
            this.outcome=outcome;this.cause=cause;lastBlock=m.lastJourneyBlock;
            surferLat=m.routeSurferLat;surferLon=m.routeSurferLon;targetLat=m.approachTargetLat;targetLon=m.approachTargetLon;}
    }
    private void openJourney(){journeyId++;journeyOpen=true;lastJourneyBlock="none";recoveryAttempts=0;lastRecoveryAt=-1;recoveryStatus="none";}
    private void finishJourney(String outcome,String cause){
        if(!journeyOpen)return;journeyOpen=false;
        try{outcomeListener.accept(new JourneyOutcome(this,outcome,cause));}catch(RuntimeException ignored){}
    }
    public void cancel(String cause){finishJourney("cancelled",cause);cancel();}''')
edit(p,'public void cancel() {','public void cancel() {\n        finishJourney("cancelled","session_reset");')
edit(p,'if(manual) {','if(manual) {\n            finishJourney("cancelled","pilot_intervention");')
edit(p,'phase=Phase.STOPPED; reason="movement_timeout";','finishJourney("timed_out","movement_timeout");\n            phase=Phase.STOPPED; reason="movement_timeout";')
edit(p,'if(!wasRiding && riding) {','if(!wasRiding && riding) {\n            finishJourney("cancelled","ride_detected");')
edit(p,'clearApproach(); clearReturn(); createBand(in,now);','finishJourney("cancelled",cause);\n        clearApproach(); clearReturn(); createBand(in,now);')
edit(p,'if(positioning!=null) {double[] measures=positioning.measures(in);projectedSeparation=measures[0];alignmentError=measures[1];}',
 '''if(positioning!=null) {
            if(angleApproach()){projectedSeparation=alignmentError=Double.NaN;}
            else {double[] measures=positioning.measures(in);projectedSeparation=measures[0];alignmentError=measures[1];}
        }''')
edit(p,'AngleRoutePlanner.Check c=AngleRoutePlanner.check(positioning,in.lat,in.lon,waypointLat(),waypointLon(),',
 '''AngleRoutePlanner.Check c=route!=null&&route.escapeFirst&&routeIndex==0?
            AngleRoutePlanner.escapeCheck(positioning,in.lat,in.lon,waypointLat(),waypointLon(),routeSurferLat,routeSurferLon,initialCentralLat,initialCentralLon,centralLat,centralLon):
            AngleRoutePlanner.check(positioning,in.lat,in.lon,waypointLat(),waypointLon(),''')
edit(p,'if(!c.reason.equals("none")){pathBlockReason=c.reason;failedSegment=routeIndex;',
 'if(!c.reason.equals("none")){pathCheckStatus="blocked";pathBlockReason=c.reason;lastJourneyBlock=c.reason;failedSegment=routeIndex;')
edit(p,'        return true;\n    }\n    private void updateAngle','        pathCheckStatus="allowed";pathBlockReason="none";return true;\n    }\n    private void updateAngle')
edit(p,'routeStatus="planned";approachAligned=true;', 'openJourney();routeStatus="planned";approachAligned=true;')
edit(p,'if(!angleLegAllowed(in)){reason=pathBlockReason;routeStatus="blocked";return;}',
 '''if(!angleLegAllowed(in)){
            reason=pathBlockReason;routeStatus="blocked";
            if(lastRecoveryAt<0||now-lastRecoveryAt>=1000){
                lastRecoveryAt=now;recoveryAttempts++;
                AngleRoutePlanner.Plan replacement=AngleRoutePlanner.recover(positioning,in,
                    new double[]{approachTargetLat,approachTargetLon},routeSurferLat,routeSurferLon,destinationFixTime,
                    initialCentralLat,initialCentralLon,centralLat,centralLon);
                clockwiseFailure=replacement.clockwiseFailure;anticlockwiseFailure=replacement.anticlockwiseFailure;
                clockwiseFailureValues=replacement.clockwiseFailureValues;anticlockwiseFailureValues=replacement.anticlockwiseFailureValues;
                if(replacement.reason.equals("none")){
                    route=replacement;routeIndex=0;approachDistance=replacement.length;approachProgress=0;
                    routeStatus="replanned";recoveryStatus=replacement.escapeFirst?"outward_escape_planned":"route_replanned";
                    reason=recoveryStatus;event="fixed_route_replanned";pathCheckStatus="allowed";pathBlockReason="none";
                }else{
                    recoveryStatus=replacement.reason;reason=replacement.reason;pathBlockReason=replacement.reason;
                    failedSegment=replacement.failedSegment;failedClearance=replacement.failedClearance;
                    failedBoundary=replacement.failedBoundary;failedExcursion=replacement.failedExcursion;
                }
            }
            return; // Stop before executing any replacement; preserve the original attempt clock.
        }''')
edit(p,'        hasFilmed=true;','        finishJourney("arrived","fixed_destination_arrived");\n        hasFilmed=true;')
edit(p,'public void replaceApproachForRetreat() {','public void replaceApproachForRetreat() {\n        finishJourney("cancelled","retreat");')
edit(src/'AimingSession.java','default void diagnostic(String event, String detail) { }','default void diagnostic(String event, String detail) { }\n        default void journeyOutcome(ComeToMeController.JourneyOutcome outcome) { }')
edit(src/'AimingSession.java','public AimingSession(Port port) { this.port = port; }','public AimingSession(Port port) { this.port = port; movement.outcomeListener=port::journeyOutcome; }')
edit(src/'AimingSession.java','movement.cancel();','movement.cancel(why);')
p=src/'MovementCycleLog.java'
edit(p,'"extraPathClearanceMetres",m.angleApproach()?m.positioning.extraClearance:null,','''"extraPathClearanceMetres",m.angleApproach()?0:null,
                "routeRecoveryAttempts",m.recoveryAttempts,"routeRecoveryStatus",m.recoveryStatus,
                "journeyId",m.journeyId,"lastJourneyBlockReason",m.lastJourneyBlock,
                "routeEscapeLeg",m.route!=null&&m.route.escapeFirst&&m.routeIndex==0,
                "currentSurferDirectDistanceM",m.distance,
                "capturedSurferDirectDistanceM",m.angleApproach()&&session.cycleInputs!=null&&Double.isFinite(m.routeSurferLat)?
                    YawAimingMath.distance(session.lastInputs.lat,session.lastInputs.lon,m.routeSurferLat,m.routeSurferLon):null,'''.replace('session.lastInputs.lat','session.cycleAircraftLatitude').replace('session.lastInputs.lon','session.cycleAircraftLongitude'))
# Locate aircraft fields used by cycle adapter instead of storing another telemetry copy.
text=p.read_text();text=text.replace('session.cycleAircraftLatitude','session.cycleInputs.lat').replace('session.cycleAircraftLongitude','session.cycleInputs.lon');p.write_text(text)
edit(p,'"projectedSeparationM",m.projectedSeparation,"comeToMeAlignmentErrorM",m.alignmentError,','"projectedSeparationM",m.angleApproach()?null:m.projectedSeparation,"comeToMeAlignmentErrorM",m.angleApproach()?null:m.alignmentError,')
p=src/'FullSessionLog.java'
edit(p,'private final Destination destination;','private final Destination destination;\n    private String appVersion="unknown";\n    public FullSessionLog(Destination destination, Consumer<String> error,String version){this(destination,error);appVersion=version;}')
edit(p,'"version", "4.0.3"','"version", appVersion')
p=src/'YawAimingController.java'
edit(p,'android.widget.Toast.LENGTH_LONG).show());\n        });','android.widget.Toast.LENGTH_LONG).show());\n        }, installedVersion(appContext));')
edit(p,'    @Override public void sendYaw(double rate)', '''    private static String installedVersion(android.content.Context context) {
        try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}
        catch(android.content.pm.PackageManager.NameNotFoundException e){return "unknown";}
    }
    @Override public void journeyOutcome(ComeToMeController.JourneyOutcome o) {
        fullLog.record("journey_outcome","session",session.sessionId(),"journeyId",o.id,
            "outcome",o.outcome,"reason",o.cause,"lastBlockReason",o.lastBlock,"elapsedMs",o.elapsedMs,
            "capturedSurferLatitude",o.surferLat,"capturedSurferLongitude",o.surferLon,
            "destinationLatitude",o.targetLat,"destinationLongitude",o.targetLon);
    }
    @Override public void sendYaw(double rate)''')
edit(root/'SampleCode-V5/android-sdk-v5-sample/build.gradle','versionCode 27','versionCode 28')
edit(root/'SampleCode-V5/android-sdk-v5-sample/build.gradle','versionName "4.0.4"','versionName "4.0.5"')
p=src.parent/'DefaultLayoutActivity.java'
edit(p,'VT 4.0.4 · JSON configuration','VT 4.0.5 · JSON configuration')
edit(p,'extraPathClearanceMetres adds route clearance when retreat is ON.','Diagonal routes use the retreat threshold with no extra buffer; legacy extraPathClearanceMetres is ignored. Blocked legs replan around the saved surfer position and preserve the destination and timeout.')
print('VT 4.0.5 changes applied')

