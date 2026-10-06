from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
s=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
b=Path('C:/Users/gildo/gdlapp/cam3/exports/vt406/before');b.mkdir(exist_ok=True)
def edit(p,old,new):
 text=p.read_text(encoding='utf-8');assert old in text,(str(p),old[:100]);backup=b/p.name
 if not backup.exists():backup.write_text(text,encoding='utf-8')
 p.write_text(text.replace(old,new),encoding='utf-8')
p=s/'ShorelinePositioning.java'
edit(p,'extraClearance,excursionStop;','extraClearance,excursionStop,planningAllowance;')
edit(p,'double angle,double angleTolerance,double extra,double excursionStop) {','''double angle,double angleTolerance,double extra,double excursionStop) {
        this(shore,id,mode,side,tolerance,retreatRadius,angle,angleTolerance,extra,excursionStop,2);
    }
    public ShorelinePositioning(ShorelineGeometry shore,String id,String mode,String side,double tolerance,double retreatRadius,
            double angle,double angleTolerance,double extra,double excursionStop,double planningAllowance) {
        if(!Double.isFinite(planningAllowance)||planningAllowance<0||planningAllowance>50)
            throw new IllegalArgumentException("routePlanningAllowanceMetres must be 0–50");
        this.planningAllowance=planningAllowance;''')
edit(p,'public double clearance(){','public double planningClearance(){return retreatRadius>0?clearance()+planningAllowance:0;}\n    public double clearance(){')
p=s/'VtSettingsConfig.java'
edit(p,'angleTolerance,extraClearance;','angleTolerance,extraClearance,planningAllowance;')
edit(p,'double angleTolerance,double extraClearance) {','double angleTolerance,double extraClearance,double planningAllowance) {')
edit(p,'this.extraClearance=extraClearance;','this.extraClearance=extraClearance;this.planningAllowance=planningAllowance;')
edit(p,'double excursion=optional','double planningAllowance=optional(o,"routePlanningAllowanceMetres",2,0,50);\n        double excursion=optional')
edit(p,'"extraPathClearanceMetres","maxExcursionMetres"','"extraPathClearanceMetres","maxExcursionMetres","routePlanningAllowanceMetres"')
edit(p,'angle,angleTolerance,extra);','angle,angleTolerance,extra,planningAllowance);')
edit(s/'VtSessionConfig.java','c.extraClearance,c.movement.excursionStop());','c.extraClearance,c.movement.excursionStop(),c.planningAllowance);')
edit(r/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_settings.json','"extraPathClearanceMetres": 0,','"extraPathClearanceMetres": 0,\n  "routePlanningAllowanceMetres": 2,')
p=s/'AngleRoutePlanner.java'
# Planning and runtime call different checks. Never change check()'s execution threshold.
text=p.read_text(encoding='utf-8');start=text.index('    public static Plan plan(');end=text.index('    /** Recovery keeps',start)
planning=text[start:end].replace('=check(p,','=planningCheck(p,').replace('p.clearance()+1.1','p.planningClearance()+1.1')
planning=planning.replace('destination.reason.equals("route_central_boundary")','destination.reason.equals("route_central_boundary")').replace('else if(destination.reason.equals("route_excursion_limit"))','else if(destination.reason.equals("route_planning_clearance"))destination.reason="destination_inside_planning_clearance";\n            else if(destination.reason.equals("route_excursion_limit"))')
planning=planning.replace('else if(start.reason.equals("route_surfer_clearance"))start.reason="start_inside_routing_clearance";','else if(start.reason.equals("route_planning_clearance")||start.reason.equals("route_surfer_clearance"))start.reason="start_inside_planning_clearance";')
text=text[:start]+planning+text[end:];(b/p.name).write_text(p.read_text(encoding='utf-8'),encoding='utf-8');p.write_text(text,encoding='utf-8')
edit(p,'    public static Plan plan(','''    public static Check planningCheck(ShorelinePositioning p,double aLat,double aLon,double bLat,double bLon,
            double sl,double so,double anchorLat,double anchorLon,double centralLat,double centralLon) {
        Check c=check(p,aLat,aLon,bLat,bLon,sl,so,anchorLat,anchorLon,centralLat,centralLon);
        if((c.reason.equals("none")||c.reason.equals("route_surfer_clearance"))&&c.clearance+1e-6<p.planningClearance())
            c.reason="route_planning_clearance";
        return c;
    }
    public static Plan plan(''')
edit(p,'!ordinary.reason.equals("start_inside_routing_clearance")','!ordinary.reason.equals("start_inside_planning_clearance")')
edit(p,'double radius=(p.clearance()+1.1)','double radius=(p.planningClearance()+1.1)')
p=s/'ComeToMeController.java'
edit(p,'recoveryStatus="none",lastJourneyBlock="none";','recoveryStatus="none",lastRecoveryResult="none",lastJourneyBlock="none";')
edit(p,'lastRecoveryAt=-1;recoveryStatus="none";}','lastRecoveryAt=-1;recoveryStatus=lastRecoveryResult="none";}')
edit(p,'            }\n            return; // Stop before executing any replacement;', '                lastRecoveryResult=recoveryStatus;\n            }\n            return; // Stop before executing any replacement;')
edit(p,'forward=v[0];right=v[1];reason="moving_saved_route_aiming_surfer";','''recoveryStatus="none";failedSegment=-1;failedClearance=failedBoundary=failedExcursion=Double.NaN;
        forward=v[0];right=v[1];reason="moving_saved_route_aiming_surfer";''')
p=s/'MovementCycleLog.java'
edit(p,'"routeRecoveryAttempts",m.recoveryAttempts,"routeRecoveryStatus",m.recoveryStatus,','''"routeRecoveryAttempts",m.recoveryAttempts,"routeRecoveryStatus",m.recoveryStatus,
                "lastRouteRecoveryResult",m.lastRecoveryResult,
                "routePlanningAllowanceMetres",m.angleApproach()?m.positioning.planningAllowance:null,
                "requiredPlanningClearanceMetres",m.angleApproach()?m.positioning.planningClearance():null,''')
edit(r/'SampleCode-V5/android-sdk-v5-sample/build.gradle','versionCode 28','versionCode 29')
edit(r/'SampleCode-V5/android-sdk-v5-sample/build.gradle','versionName "4.0.5"','versionName "4.0.6"')
edit(s.parent/'DefaultLayoutActivity.java','VT 4.0.5 · JSON configuration','VT 4.0.6 · JSON configuration')
edit(s.parent/'DefaultLayoutActivity.java','Diagonal routes use the retreat threshold with no extra buffer;','Diagonal execution uses the retreat threshold; routePlanningAllowanceMetres (default 2, range 0–50) adds clearance for planning every leg, not the execution stop limit;')
print('VT406 implementation applied')
