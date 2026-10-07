from pathlib import Path
import json

root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
def edit(p,old,new):
    s=p.read_text(encoding='utf-8'); assert old in s,(p,old[:100]); p.write_text(s.replace(old,new),encoding='utf-8')

# Fresh Wave line schema and source names, with no compatibility aliases.
paths=list((root/'SampleCode-V5').glob('**/src/**/*.java'))+list((root/'tools').glob('*.ps1'))
for p in paths:
    s=p.read_text(encoding='utf-8')
    for a,b in [('vt_shorelines.json','vt_wave_lines.json'),('SHORELINES','WAVE_LINES'),('SHORELINE','WAVE_LINE'),('Shorelines','Wave lines'),('Shoreline','WaveLine'),('shorelines','waveLines'),('shoreline','waveLine')]: s=s.replace(a,b)
    # Human-readable labels use spaces; identifiers and JSON retain camel case.
    for a,b in [('Selected waveLine','Selected wave line'),('saved waveLines','saved wave lines'),('a waveLine','a wave line'),('the waveLine','the wave line'),('waveLine points','wave line points'),('waveLine name','wave line name'),('waveLine file','wave line file'),('WaveLine setup','Wave line setup'),('WaveLine orientation','Wave line orientation'),('WaveLine A/B','Wave line A/B'),('WaveLine name','Wave line name'),('WaveLine capture','Wave line capture'),('100 waveLines','100 wave lines')]: s=s.replace(a,b)
    p.write_text(s,encoding='utf-8')
    if 'Shoreline' in p.name:p.rename(p.with_name(p.name.replace('Shoreline','WaveLine')))
assets=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets'
(assets/'vt_shorelines.json').rename(assets/'vt_wave_lines.json')
p=assets/'vt_wave_lines.json';p.write_text(p.read_text().replace('shorelines','waveLines'))
p=assets/'vt_settings.json';s=p.read_text().replace('shorelineId','waveLineId').replace('"maxExcursionMetres": 300','"maxExcursionMetres": 300,\n  "returnBoundaryStandOffMetres": 10');p.write_text(s)

p=src/'ComeToMeSettings.java'
edit(p,'public final double maxExcursionMetres;','public final double maxExcursionMetres, returnBoundaryStandOffMetres;')
edit(p,'        if(!inRange(maxExcursionMetres,10,1000))', '''        this(enabled,filming,width,start,end,endMs,inactivityMs,margin,durationMs,closePitchDeg,longPitchDeg,
                maxYawRate,yawAcceleration,maxMovementSpeed,maxExcursionMetres,10);
    }
    public ComeToMeSettings(boolean enabled,double filming,double width,double start,double end,long endMs,long inactivityMs,
            double margin,long durationMs,double closePitchDeg,double longPitchDeg,double maxYawRate,double yawAcceleration,
            double maxMovementSpeed,double maxExcursionMetres,double returnBoundaryStandOffMetres) {
        if(!inRange(returnBoundaryStandOffMetres,1,100) || returnBoundaryStandOffMetres>=maxExcursionMetres-1)
            throw new IllegalArgumentException("returnBoundaryStandOffMetres must be 1..100 and below maxExcursionMetres - 1");
        this.returnBoundaryStandOffMetres=returnBoundaryStandOffMetres;
        if(!inRange(maxExcursionMetres,10,1000))''')
edit(p,'yawAcceleration,speed,maxExcursionMetres);','yawAcceleration,speed,maxExcursionMetres,returnBoundaryStandOffMetres);')
p=src/'VtSettingsConfig.java'
edit(p,'double excursion=optional(o,"maxExcursionMetres",300,10,1000);','double excursion=optional(o,"maxExcursionMetres",300,10,1000);\n        double standOff=optional(o,"returnBoundaryStandOffMetres",10,1,100);')
edit(p,'"maxExcursionMetres","routePlanningAllowanceMetres"}', '"maxExcursionMetres","routePlanningAllowanceMetres","returnBoundaryStandOffMetres"}')
edit(p,'MetresPerSecond",.1,5),excursion);','MetresPerSecond",.1,5),excursion,standOff);')

p=src/'ComeToMeController.java'
edit(p,'private boolean runEnabled;','''private boolean runEnabled;
    public double returnTargetLat=Double.NaN,returnTargetLon=Double.NaN,returnTargetRemaining=Double.NaN;
    public double returnBoundaryDistance=Double.NaN;
    public boolean returnPending;
    private long pendingReturnSince=-1;
    private boolean softReturn(){return positioning!=null;}
    private double boundaryAt(AimingSession.Inputs in){
        return positioning.boundaryDistance(in.lat,in.lon,initialCentralLat,initialCentralLon);
    }''')
edit(p,'clearApproach(); clearReturn(); speedJumpRejected=false;', 'clearApproach(); clearReturn(); returnPending=false;pendingReturnSince=-1; speedJumpRejected=false;')
edit(p,'            clearApproach(); clearReturn();\n            captureRequired=true;', '            clearApproach(); clearReturn();returnPending=false;pendingReturnSince=-1;\n            captureRequired=true;')
edit(p,'attemptElapsedMs=attemptSince<0 ? 0 : Math.max(0,now-attemptSince);','attemptElapsedMs=returnPending&&pendingReturnSince>=0 ? Math.max(0,now-pendingReturnSince) : attemptSince<0 ? 0 : Math.max(0,now-attemptSince);')
edit(p,'(phase==Phase.APPROACHING || phase==Phase.RETURNING) && attemptElapsedMs', '(phase==Phase.APPROACHING || phase==Phase.RETURNING || returnPending) && attemptElapsedMs')
edit(p,'phase=Phase.STOPPED; reason="movement_timeout"; forward=right=0;', 'phase=Phase.STOPPED; reason="movement_timeout";returnPending=false;pendingReturnSince=-1; forward=right=0;')
edit(p,'        finishJourney("cancelled",cause);\n        clearApproach();', '''        if(softReturn()) {
            returnBoundaryDistance=boundaryAt(in);
            if(returnBoundaryDistance<0) {reason="return_start_beachward_of_boundary";event=reason;return;}
            if(returnBoundaryDistance<=settings.returnBoundaryStandOffMetres) {
                finishJourney("cancelled",cause);clearApproach();clearReturn();
                returnBoundaryDistance=boundaryAt(in);returnReason=cause;finishReturn("return_already_in_restart_zone");return;
            }
        }
        long resumedSince=returnPending?pendingReturnSince:-1;
        finishJourney("cancelled",cause);
        clearApproach();''')
edit(p,'        returnProgress=0;\n        if(returnDistance==0)', '''        returnProgress=0;
        if(softReturn()) {
            returnBoundaryDistance=boundaryAt(in);
            double fraction=1-settings.returnBoundaryStandOffMetres/returnBoundaryDistance;
            double[] toCentral=positioning.shore.offset(initialCentralLat,initialCentralLon,in.lat,in.lon);
            double[] target=positioning.shore.point(in.lat,in.lon,toCentral[0]*fraction,toCentral[1]*fraction);
            returnTargetLat=target[0];returnTargetLon=target[1];
            returnDistance=returnTargetRemaining=YawAimingMath.distance(in.lat,in.lon,returnTargetLat,returnTargetLon);
            returnBearing=YawAimingMath.bearingToTarget(in.lat,in.lon,returnTargetLat,returnTargetLon);
            returnHeading=(returnBearing+180)%360;
            if(YawAimingMath.distance(returnTargetLat,returnTargetLon,centralLat,centralLon)>=settings.excursionStop()) {
                returnPending=true;pendingReturnSince=resumedSince>=0?resumedSince:now;
                phase=Phase.WAITING;reason="return_target_excursion_limit";event=reason;return;
            }
        }
        returnPending=false;pendingReturnSince=-1;
        if(returnDistance==0)''')
edit(p,'phase=Phase.RETURNING; reason=cause; attemptSince=now;', 'phase=Phase.RETURNING; reason=cause; attemptSince=resumedSince>=0?resumedSince:now;')
edit(p,'        if(retreatBlocked && !returning()) { reason="retreat_or_cooldown"; return; }', '''        if(retreatBlocked) {
            if(returning())replaceApproachForRetreat();
            reason="retreat_or_cooldown";return;
        }''')
edit(p,'!ride.confirmed && inactiveSince>=0 && now-inactiveSince>=settings.inactivityMs', '!ride.confirmed && (returnPending || inactiveSince>=0 && now-inactiveSince>=settings.inactivityMs)')
edit(p,'            returnProgress=projectedReturnProgress(in);', '''            if(softReturn()) {
                returnBoundaryDistance=boundaryAt(in);
                returnTargetRemaining=YawAimingMath.distance(in.lat,in.lon,returnTargetLat,returnTargetLon);
                returnProgress=returnDistance-returnTargetRemaining;
                if(returnBoundaryDistance<0) {reason="return_beachward_of_boundary";event=reason;return;}
            } else returnProgress=projectedReturnProgress(in);''')
edit(p,'            forward=-arrivalSpeed(returnDistance-returnProgress);', '''            if(softReturn()) {
                double[] v=positioning.velocity(in,returnTargetLat,returnTargetLon,arrivalSpeed(returnTargetRemaining));
                forward=v[0];right=v[1];reason="moving_to_restart_target";
            } else forward=-arrivalSpeed(returnDistance-returnProgress);''')
edit(p,'attemptSince=-1; inactiveSince=-1; returnAligned=false;', 'attemptSince=-1; inactiveSince=-1; returnAligned=false;returnPending=false;pendingReturnSince=-1;')
edit(p,'returnCompletionCentralDistance=Double.NaN; returnAligned=false;', 'returnCompletionCentralDistance=Double.NaN; returnAligned=false;\n        returnTargetLat=returnTargetLon=returnTargetRemaining=returnBoundaryDistance=Double.NaN;')
edit(p,'        finishJourney("cancelled","retreat");\n        clearApproach();', '''        if(returning()) {returnPending=true;pendingReturnSince=attemptSince;event="return_interrupted_retreat";}
        finishJourney("cancelled","retreat");
        clearApproach();''')
edit(p,'        if(!presetApproach()||returning())return requestedRight==0&&permits(in,now,requestedForward);', '''        if(returning()&&softReturn()) {
            double speed=Math.hypot(requestedForward,requestedRight);
            if(!Double.isFinite(speed)||speed>settings.maxMovementSpeed+.000001||!runEnabled||captureRequired||!returnAligned||
                    in.validate(now,true)!=null||expireAttempt(now)||in.target.time!=lastFix||boundaryAt(in)<0)return false;
            double remaining=YawAimingMath.distance(in.lat,in.lon,returnTargetLat,returnTargetLon);
            if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE)return false;
            double[] v=positioning.velocity(in,returnTargetLat,returnTargetLon,arrivalSpeed(remaining));
            return Math.abs(requestedForward-v[0])<.01&&Math.abs(requestedRight-v[1])<.01;
        }
        if(!presetApproach()||returning())return requestedRight==0&&permits(in,now,requestedForward);''')
# The single-axis submission API must also respect the new target.
edit(p,'        // VT 3.6: Enforce the configured approach/return cap independently of retreat speed.', '''        if(returning()&&softReturn())return permits(in,now,requested,0);
        // VT 3.6: Enforce the configured approach/return cap independently of retreat speed.''')
p=src/'RetreatController.java'
edit(p,'&& !m.returning() && m.phase', '&& m.phase')
edit(p,'(!m.returning() && close(in))','close(in)')
p=src/'AimingSession.java'
edit(p,'if(forward>0 && retreat.blocksApproach', 'if(forward!=0 && retreat.blocksApproach')
edit(p,'&&movement.approaching()&&retreat.blocksApproach', '&&(movement.approaching()||movement.returning())&&retreat.blocksApproach')
p=src/'MovementCycleLog.java'
edit(p,'"returnStartLatitude",m.returnStartLat,', '''"returnBoundaryStandOffMetres",c.returnBoundaryStandOffMetres,
                "returnTargetLatitude",m.returnTargetLat,"returnTargetLongitude",m.returnTargetLon,
                "returnTargetRemainingM",m.returnTargetRemaining,"returnBoundaryDistanceM",m.returnBoundaryDistance,
                "returnPending",m.returnPending,
                "returnStartLatitude",m.returnStartLat,''')
p=root/'SampleCode-V5/android-sdk-v5-sample/build.gradle';edit(p,'versionCode 29','versionCode 30');edit(p,'versionName "4.0.6"','versionName "4.0.7"')
for p in (root/'SampleCode-V5').glob('**/src/main/**/*.java'):
    s=p.read_text(encoding='utf-8').replace('VT 4.0.6','VT 4.0.7').replace('VT 4.0.1 · Wave lines','VT 4.0.7 · Wave lines')
    p.write_text(s,encoding='utf-8')
p=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/Vt35Test.java'
edit(p,'check(!f.core.retreat.eligible(f.core.movement),"existing saved return keeps navigation ownership");','check(f.core.retreat.eligible(f.core.movement),"retreat can interrupt a saved return");')
print('Implemented VT4.0.7 source and fresh Wave line schema')
