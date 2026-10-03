from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
base=Path('SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming')
def edit(path,old,new):
    p=root/path
    s=p.read_text(encoding='utf-8')
    assert old in s, (path,old[:80])
    p.write_text(s.replace(old,new),encoding='utf-8')
edit(base/'ComeToMeController.java','public String journeyKind="none";','''public String journeyKind="none";
    public double initialCentralLat=Double.NaN,initialCentralLon=Double.NaN;
    public String pathCheckStatus="not_checked",pathBlockReason="none";
    public String filmingSideStatus() {
        return positioning==null || !Double.isFinite(projectedSeparation) ? "unavailable"
            : projectedSeparation>0 ? "correct_side" : "wrong_side";
    }''')
edit(base/'ComeToMeController.java','centralLat=centralLon=bandLat=bandLon=bandBearing=Double.NaN;','''centralLat=centralLon=bandLat=bandLon=bandBearing=Double.NaN;
        initialCentralLat=initialCentralLon=projectedSeparation=alignmentError=Double.NaN;
        pathCheckStatus="not_checked";pathBlockReason="none";''')
edit(base/'ComeToMeController.java','event="none"; noRideTimerEvent="none"; speedJumpRejected=false; forward=right=0;','''event="none"; noRideTimerEvent="none"; speedJumpRejected=false; forward=right=0;
        pathCheckStatus="not_checked";pathBlockReason="none";''')
edit(base/'ComeToMeController.java','centralLat=in.lat; centralLon=in.lon; centralGeneration++; captureRequired=false;','''centralLat=in.lat; centralLon=in.lon; centralGeneration++; captureRequired=false;
            if(!Double.isFinite(initialCentralLat)) {initialCentralLat=in.lat;initialCentralLon=in.lon;}''')
edit(base/'ComeToMeController.java','if(!positioning.safePath(in,target[0],target[1],centralLat,centralLon)) {reason="alignment_path_or_destination_blocked";return;}','''pathBlockReason=positioning.pathBlockReason(in,target[0],target[1],centralLat,centralLon);
            pathCheckStatus=pathBlockReason.equals("none")?"allowed":"blocked";
            if(!pathBlockReason.equals("none")) {reason="alignment_path_or_destination_blocked";return;}''')
edit(base/'ShorelinePositioning.java','''        if(YawAimingMath.distance(lat,lon,centralLat,centralLon)>ComeToMeSettings.EXCURSION_STOP ||''','''        return pathBlockReason(in,lat,lon,centralLat,centralLon).equals("none");
    }
    public String pathBlockReason(AimingSession.Inputs in,double lat,double lon,double centralLat,double centralLon) {
        if(YawAimingMath.distance(lat,lon,centralLat,centralLon)>ComeToMeSettings.EXCURSION_STOP ||''')
edit(base/'ShorelinePositioning.java','>=ComeToMeSettings.EXCURSION_STOP)return false;','>=ComeToMeSettings.EXCURSION_STOP)return "excursion_limit";')
edit(base/'ShorelinePositioning.java','return ShorelineGeometry.segmentDistance(start[0],start[1],end[0],end[1])>=retreatRadius;','return ShorelineGeometry.segmentDistance(start[0],start[1],end[0],end[1])>=retreatRadius ? "none" : "retreat_clearance";')
edit(base/'RetreatController.java','public boolean active;','''public boolean active;
    // Keep a half-metre command reserve and reduce inward speed over a one-second horizon.
    public static final double BOUNDARY_RESERVE_METRES=.5,BOUNDARY_LOOKAHEAD_SECONDS=1;
    public boolean boundaryBlocked;
    public double boundarySeawardDistance=Double.NaN;
    public String boundaryStatus="inactive";
    private double boundaryCommand(ComeToMeController m,AimingSession.Inputs in) {
        if(m.positioning==null)return -settings.speed; // Legacy sessions have no shoreline.
        if(!Double.isFinite(m.initialCentralLat))return 0;
        ShorelineGeometry g=m.positioning.shore;
        double[] offset=g.offset(in.lat,in.lon,m.initialCentralLat,m.initialCentralLon);
        double distance=offset[0]*g.seaNorth+offset[1]*g.seaEast;
        double heading=Math.toRadians(in.heading);
        double backwardNormal=-Math.cos(heading)*g.seaNorth-Math.sin(heading)*g.seaEast;
        if(backwardNormal>=-1e-6)return -settings.speed; // Parallel or seaward is permitted.
        return -Math.min(settings.speed,Math.max(0,distance-BOUNDARY_RESERVE_METRES)/
            (-backwardNormal*BOUNDARY_LOOKAHEAD_SECONDS));
    }
    public double command(ComeToMeController m,AimingSession.Inputs in) {
        double value=active?boundaryCommand(m,in):0;
        boundarySeawardDistance=Double.NaN;
        if(m.positioning!=null && Double.isFinite(m.initialCentralLat)) {
            ShorelineGeometry g=m.positioning.shore;
            double[] offset=g.offset(in.lat,in.lon,m.initialCentralLat,m.initialCentralLon);
            boundarySeawardDistance=offset[0]*g.seaNorth+offset[1]*g.seaEast;
        }
        boolean blocked=active && m.positioning!=null && value==0;
        boundaryStatus=!active?"inactive":m.positioning==null?"not_applicable":
            !Double.isFinite(m.initialCentralLat)?"anchor_unavailable":blocked?"blocked":
            Math.abs(value)<settings.speed?"slowing":"allowed";
        if(blocked!=boundaryBlocked) {
            boundaryBlocked=blocked;
            emit(blocked?"retreat_boundary_blocked":"retreat_boundary_allowed",
                blocked?"central_boundary":"boundary_released");
        }
        return value;
    }''')
edit(base/'RetreatController.java','settings=config;active=false;','''boundaryBlocked=false;boundarySeawardDistance=Double.NaN;boundaryStatus="inactive";
        settings=config;active=false;''')
edit(base/'RetreatController.java','&& forward==-settings.speed','''&& forward<0 && Math.abs(forward)<=settings.speed
                && forward>=boundaryCommand(m,in)-.000001''')
edit(base/'RetreatController.java','return active ? String.format','return boundaryBlocked ? "Retreat blocked by central boundary" : active ? String.format')
edit(base/'AimingSession.java','cycleRequestedForward=retreat.active ? -retreat.settings.speed : movement.forward;','''double retreatForward=retreat.command(movement,in);
            cycleRequestedForward=retreat.active ? retreatForward : movement.forward;''')
edit(base/'MovementCycleLog.java','"fixedDestinationFixTime",m.destinationFixTime,','''"filmingSideStatus",m.filmingSideStatus(),"pathCheckStatus",m.pathCheckStatus,"pathBlockReason",m.pathBlockReason,
                "shorelineSeaSide",m.positioning==null?null:m.positioning.shore.seaSide,
                "seawardBearingDeg",m.positioning==null?null:(Math.toDegrees(Math.atan2(m.positioning.shore.seaEast,m.positioning.shore.seaNorth))+360)%360,
                "shorelineOrientationVerified",false,
                "retreatBoundaryLatitude",m.initialCentralLat,"retreatBoundaryLongitude",m.initialCentralLon,
                "retreatBoundarySeawardDistanceM",session.retreat.boundarySeawardDistance,
                "retreatBoundaryStatus",session.retreat.boundaryStatus,"retreatBoundaryBlocked",session.retreat.boundaryBlocked,
                "fixedDestinationFixTime",m.destinationFixTime,''')
edit(base/'RetreatLog.java','"staleLimitBlocked",r.staleLimitBlocked,','''"staleLimitBlocked",r.staleLimitBlocked,
            "boundaryLatitude",session.movement.initialCentralLat,"boundaryLongitude",session.movement.initialCentralLon,
            "boundarySeawardDistanceM",r.boundarySeawardDistance,"boundaryStatus",r.boundaryStatus,
            "boundaryBlocked",r.boundaryBlocked,"boundaryReserveM",RetreatController.BOUNDARY_RESERVE_METRES,
            "boundaryLookaheadSeconds",RetreatController.BOUNDARY_LOOKAHEAD_SECONDS,''')
edit(Path('SampleCode-V5/android-sdk-v5-sample/build.gradle'),'versionCode 25','versionCode 26')
edit(Path('SampleCode-V5/android-sdk-v5-sample/build.gradle'),'versionName "4.0.2"','versionName "4.0.3"')
edit(base/'FullSessionLog.java','"4.0.2"','"4.0.3"')
tests=Path(str(base).replace('src/main/java','src/test/java'))
edit(tests/'MovementLogTest.java','4.0.2','4.0.3')
edit(tests/'Vt40Test.java','public static void main(String[] args)throws Exception {','''static void retreatBoundary() {
        for(String mode:new String[]{"front","sideways"}) {
            ComeToMeController m=planner(mode,"left",0,0,40,0);
            RetreatController r=new RetreatController((e,w)->{});r.start(new RetreatSettings(true,18,2000,4,5000));
            r.active=true;r.deadline=10000;
            near(-4,r.command(m,ComeToMeTest.in(2000,10,0,20,0,0)),"full retreat outside boundary "+mode);
            near(-.5,r.command(m,ComeToMeTest.in(2000,1,0,10,0,0)),"shoreward braking near boundary "+mode);
            check(!r.permits(m,ComeToMeTest.in(2000,1,0,10,0,0),2000,-4),"final snapshot vetoes full inward speed");
            near(0,r.command(m,ComeToMeTest.in(2000,0,0,10,0,0)),"at boundary blocked");
            check(r.boundaryBlocked,"blocked flag");
            near(-4,r.command(m,ComeToMeTest.in(2000,0,0,-10,0,180)),"surfer shoreward permits seaward retreat");
            near(-4,r.command(m,ComeToMeTest.in(2000,-2,0,-10,0,180)),"already shoreward permits recovery");
            near(0,r.command(m,ComeToMeTest.in(2000,-2,0,10,0,0)),"already shoreward blocks further inward retreat");
            near(-4,r.command(m,ComeToMeTest.in(2000,0,0,0,10,90)),"parallel retreat allowed");
            m.pause(true,2100);m.update_state_machine(ComeToMeTest.in(2200,-20,0,40,0,0),2200,.1);
            near(0,m.initialCentralLat,"manual reposition preserves original boundary");near(-20,m.centralLat/DEG,"return central recaptured");
            m.start(settings(),2300);m.positioning=preset(mode,"left");m.update_state_machine(ComeToMeTest.in(2400,10,0,40,0,0),2400,.1);
            near(10,m.initialCentralLat/DEG,"new Start resets boundary");
        }
        ComeToMeController m=planner("front","left",40,0,20,0);
        check(m.filmingSideStatus().equals("wrong_side")&&m.pathCheckStatus.equals("not_checked"),"wrong side distinct from path checks");
        m=planner("front","left",0,15,15,0);
        check(m.filmingSideStatus().equals("correct_side")&&m.pathBlockReason.equals("retreat_clearance"),"retreat clearance logged distinctly");
        m=planner("front","left",0,0,300,0);check(m.pathBlockReason.equals("excursion_limit"),"excursion reason distinct");
        Port p=new Port();p.start();p.tick(100,0,15,10,15,0);
        check(p.core.retreat.active && p.forward==0 && p.right==0,"real session blocks boundary retreat");
        check(p.core.cycleDecision.equals("aligned_zero")||p.core.cycleDecision.equals("surfer_correction"),"surfer yaw remains owner when blocked");
        p.tick(100,0,15,-10,15,180);check(p.forward==-3,"real session permits seaward retreat after crossing");
    }
    public static void main(String[] args)throws Exception {retreatBoundary();''')
(root/'Docs/VT_4.0.3.md').write_text('''# VT 4.0.3

Retreat alone observes a boundary parallel to the selected shoreline through the first central capture of the explicit Start session. Manual reposition can recapture the return central but cannot move this boundary. Front and Sideways use the same boundary. No JSON schema changes.

Backward shoreward commands slow over a one-second projection horizon with a 0.5 m command reserve; at or beyond this reserve, further shoreward retreat is neutral. Parallel/seaward retreat remains permitted. Final submission independently rechecks the permitted speed against the latest aircraft position and heading. This is a GPS command boundary, not an obstacle sensor or guarantee against physical drift/GPS error. Retreat distance, period/cooldown timing and yaw ownership remain unchanged; a blocked period still runs and may repeat. Direct separation can remain below the retreat threshold. Come-to-me, alignment and no-ride return do not use this boundary.

UI status: Retreat blocked by central boundary. Full logs add boundary anchor, signed seaward distance, status, reserve and horizon, plus blocked/released transitions. Movement logs distinguish filmingSideStatus, pathCheckStatus and pathBlockReason (retreat_clearance/excursion_limit), configured shoreline sea side and seaward bearing. shorelineOrientationVerified=false indicates VT does not independently verify the configured direction against physical sea/land. Path checks are reported for the planning cycle; other cycles show not_checked.
''',encoding='utf-8')
print('Applied VT 4.0.3 changes')
