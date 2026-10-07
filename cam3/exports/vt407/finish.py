from pathlib import Path
import re
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
test=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
p=src/'ComeToMeController.java';s=p.read_text()
s=s.replace('if(!ride.confirmed && (returnPending || inactiveSince>=0 && now-inactiveSince>=settings.inactivityMs)) beginReturn("no_ride_timeout",in,now);', '''if(!ride.confirmed && (returnPending || inactiveSince>=0 && now-inactiveSince>=settings.inactivityMs)) {
            beginReturn("no_ride_timeout",in,now);
            if(!returning())return; // Keep completion/blocked restart neutral for this tick.
        }''')
p.write_text(s)
# Finish natural-language labels without altering schema keys or identifiers.
for name in ['WaveLineSetupUi.java','WaveLineLibrary.java','WaveLineCapture.java','VtSessionConfig.java','SharedConfigStorage.java','YawAimingController.java']:
    p=src/name;s=p.read_text()
    def labels(m):
        v=m.group(0)
        if ' ' in v:v=re.sub(r'\bwaveLine\b','wave line',v);v=re.sub(r'\bWaveLine\b','Wave line',v)
        return v
    s=re.sub(r'"(?:[^"\\]|\\.)*"',labels,s);p.write_text(s)

(test/'Vt407Test.java').write_text('''package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
public final class Vt407Test {
 static int checks;static final double DEG=ComeToMeTest.DEG;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void near(double a,double b,String why){check(Math.abs(a-b)<.02,why+" actual="+b);}
 static WaveLinePositioning preset(String mode){return new WaveLinePositioning(new WaveLineGeometry(0,-50*DEG,0,50*DEG,"leftOfAToB"),"wave","front".equals(mode)?"front":mode,"left",3,18,45,5,0,299,2);}
 static ComeToMeSettings settings(double standOff){return new ComeToMeSettings(true,28,50,99,.1,1000,60000,5,90000,-25,-10,15,8,4,300,standOff);}
 static ComeToMeController start(String mode,double standOff){
  ComeToMeController m=new ComeToMeController();m.start(settings(standOff),1000);m.positioning=preset(mode);
  m.update_state_machine(ComeToMeTest.in(1000,0,0,28,0,0),1000,.1);
  check(m.phase==ComeToMeController.Phase.HOLDING,"initial hold starts no-ride timer");return m;
 }
 static void trigger(ComeToMeController m,double n,double e){m.update_state_machine(ComeToMeTest.in(61000,n,e,100,0,0),61000,.1);}
 static void returns(){
  for(String mode:new String[]{"front","sideways","diagonal"}){
   // Sideways/diagonal fixtures use Front for the first hold, then switch preset.
   ComeToMeController m=start("front",10);m.positioning=preset(mode);trigger(m,40,20);
   check(m.returning()&&m.forward==0&&m.right==0,"neutral return transition "+mode);
   near(10,m.returnTargetLat/DEG,"target ten sea-side "+mode);near(5,m.returnTargetLon/DEG,"target on original return line");
   near(10,m.positioning.boundaryDistance(m.returnTargetLat,m.returnTargetLon,m.initialCentralLat,m.initialCentralLon),"boundary stand-off");
   double lat=m.returnTargetLat,lon=m.returnTargetLon,heading=m.returnHeading;
   AimingSession.Inputs in=ComeToMeTest.in(61100,40,22,100,0,heading);
   m.update_state_machine(in,61100,.1);check(m.returnAligned&&Math.hypot(m.forward,m.right)>0,"return movement");
   check(m.permits(in,61100,m.forward,m.right),"body velocity permitted");
   near(lat,m.returnTargetLat,"GPS deviation preserves target lat");near(lon,m.returnTargetLon,"GPS deviation preserves target lon");
   check(Math.abs(m.right)>.01,"lateral correction to fixed target");
   check(!m.permits(ComeToMeTest.in(61100,lat/DEG,lon/DEG,100,0,heading),61100,m.forward,m.right),"late arrival vetoes old command");
   m.pause(false,61200);m.update_state_machine(ComeToMeTest.in(61300,40,22,100,0,heading),61300,.1);
   near(lat,m.returnTargetLat,"pause preserves target");
   m.update_state_machine(ComeToMeTest.in(61400,lat/DEG,lon/DEG,100,0,heading),61400,.1);
   check(!m.returning()&&m.forward==0&&m.right==0,"arrival releases translation");
   near(0,m.initialCentralLat,"original anchor unchanged");
  }
  for(double n:new double[]{0,1,9.9,10}){
   ComeToMeController m=start("front",10);trigger(m,n,50);
   check(!m.returning()&&m.reason.equals("return_already_in_restart_zone"),"skip zone at "+n);
   check(m.forward==0&&m.right==0&&!m.returnPending,"skip neutral");
   check(!m.noRideTimerActive(),"skip consumes no-ride reset");
  }
  ComeToMeController m=start("front",10);trigger(m,-.02,0);
  check(!m.returning()&&m.reason.equals("return_start_beachward_of_boundary")&&m.noRideTimerActive(),"beachward not valid restart");
  m.update_state_machine(ComeToMeTest.in(61100,1,0,100,0,0),61100,.1);
  check(m.reason.equals("return_already_in_restart_zone"),"retry once permitted");
  m=start("front",15);trigger(m,40,20);near(15,m.returnTargetLat/DEG,"configurable15m");near(7.5,m.returnTargetLon/DEG,"15m return intersection");
  m=start("front",10);trigger(m,40,0);m.update_state_machine(ComeToMeTest.in(61100,-1,0,100,0,0),61100,.1);
  check(m.returning()&&m.reason.equals("return_beachward_of_boundary")&&m.forward==0,"overshoot cannot be counted as restart");
  check(!m.permits(ComeToMeTest.in(61100,-1,0,100,0,0),61100,-1,0),"beachward submission veto");
 }
 static void priority(){
  ComeToMeController m=start("front",10);trigger(m,40,0);
  RetreatController r=new RetreatController();r.start(new RetreatSettings(true,18,1000,3,1000));
  AimingSession.Inputs in=ComeToMeTest.in(61100,40,0,50,0,0);
  check(r.eligible(m)&&r.blocksApproach(m,in,61100),"retreat eligible during return");
  m.update_state_machine(in,61100,.1,true,r.blocksApproach(m,in,61100));r.update(m,in,61100);
  check(!m.returning()&&m.returnPending&&r.active,"return interrupted and pending");
  check(r.command(m,in)<0,"retreat owns movement");
  in=ComeToMeTest.in(62100,35,0,100,0,0);m.update_state_machine(in,62100,.1,true,r.blocksApproach(m,in,62100));r.update(m,in,62100);
  check(!r.active&&r.cooling(62100)&&m.returnPending,"cooldown retains pending reset");
  in=ComeToMeTest.in(63200,35,0,100,0,0);m.update_state_machine(in,63200,.1,true,r.blocksApproach(m,in,63200));r.update(m,in,63200);
  check(m.returning()&&!m.returnPending,"resume after cooldown");near(10,m.returnTargetLat/DEG,"resumed return same boundary offset");
  m.replaceApproachForRetreat();m.update_state_machine(ComeToMeTest.in(361001,35,0,100,0,0),361001,.1,true,true);
  check(m.phase==ComeToMeController.Phase.STOPPED,"retreat does not renew return deadline");
  m=start("front",10);trigger(m,40,0);m.replaceApproachForRetreat();m.pause(true,61200);
  check(!m.returnPending&&m.captureRequired&&Double.isNaN(m.returnTargetLat),"manual intervention clears pending reset");
 }
 static void config(String assets)throws Exception{
  String raw=Files.readString(Paths.get(assets,"vt_settings.json"));
  near(10,VtSettingsConfig.parse(raw,RetreatSettings.defaults()).movement.returnBoundaryStandOffMetres,"default config10");
  String absent=raw.replaceAll(",?\\\\s*\\\"returnBoundaryStandOffMetres\\\"\\\\s*:\\\\s*[-0-9.]+","");
  near(10,VtSettingsConfig.parse(absent,RetreatSettings.defaults()).movement.returnBoundaryStandOffMetres,"optional10");
  for(String bad:new String[]{"0","101","null","\\\"10\\\""}){
   Vt40Test.fails(()->VtSettingsConfig.parse(raw.replace("\\\"returnBoundaryStandOffMetres\\\": 10","\\\"returnBoundaryStandOffMetres\\\": "+bad),RetreatSettings.defaults()),"bad stand-off");checks++;
  }
  near(15,settings(15).withMaxMovementSpeed(3).returnBoundaryStandOffMetres,"speed copy preserves stand-off");
  Vt40Test.fails(()->VtSettingsConfig.parse(raw.replace("waveLineId","shorelineId"),RetreatSettings.defaults()),"old key rejected");checks++;
  WaveLineLibrary.parse(Files.readString(Paths.get(assets,"vt_wave_lines.json")));checks++;
  Vt40Test.fails(()->WaveLineLibrary.parse("{\\\"version\\\":1,\\\"shorelines\\\":[]}"),"old library rejected");checks++;
  check(!Files.exists(Paths.get(assets,"vt_shorelines.json")),"old asset absent");
 }
 public static void main(String[] args)throws Exception{returns();priority();config(args[0]);System.out.println("PASS: VT4.0.7 "+checks+" soft return, retreat and fresh Wave line checks");}
}
''')
p=root/'tools/test-aiming.ps1'
with p.open('a') as f:f.write('''
# VT 4.0.7: boundary stand-off return, retreat interruption and fresh Wave line schema.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt407Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT407 compile failed' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt407Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets')
if ($LASTEXITCODE -ne 0) { throw 'VT407 tests failed' }
''')
doc='''# VT 4.0.7

VT's automatic no-ride return now stops at a saved restart point before reaching central. Retreat can interrupt a return. Shoreline configuration is renamed to Wave line, with fresh files and no migration.

## Soft return

`returnBoundaryStandOffMetres` defaults to **10 m**. Valid values are finite numbers from 1 to 100, below `maxExcursionMetres - 1`. The no-ride timer, return speed limit, slowdown slope and five-minute attempt deadline remain in effect.

The boundary remains the line through the original first central capture, parallel to the configured Wave line. Its positive/sea side comes from the selected Wave line orientation. The Wave line can be over water; it supplies direction, not the physical beach location. Neither the boundary nor the original anchor is moved during return.

At return start, VT intersects the straight route from the current drone position toward the original central capture with a line 10 m sea-side of the boundary. That intersection is the fixed return target. Heading alignment is followed by forward/right BODY velocity toward this saved target; telemetry deviations cause correction toward the same target. Completion uses direct horizontal distance to the target, within the existing 1 m arrival tolerance, and requires a reported position on the permitted side. Thus the 10 m value is a planned stand-off, not a guarantee of exactly 10 m physical stopping distance.

| Starting position relative to original boundary | Behavior |
|---|---|
| 40 m sea-side, 20 m sideways from original central | Return target: 10 m sea-side, 5 m sideways; neutral transition, align, then move to saved target |
| Between boundary and configured 10 m sea-side line, including endpoints | Skip translation, consume reset, continue aiming; ordinary positioning is reconsidered on the next tick |
| Beachward of boundary | Hold, aim and log `return_start_beachward_of_boundary`; do not accept as restart zone |
| Reported position becomes beachward during return | Hold and log `return_beachward_of_boundary`; do not declare arrival |
| Return target violates excursion limit | Hold with pending reset and log `return_target_excursion_limit` |

The boundary is not made into a new blanket prohibition on every aircraft operation. Existing positioning and retreat boundary rules retain their meaning. Aircraft RTH behavior is unchanged. This change governs VT automatic no-ride return only. Legacy controllers without a Wave line preset retain their original return algorithm.

## Retreat priority

The current surfer's direct horizontal separation controls retreat, independently of captured S0 used by an angle journey.

| Situation | VT 4.0.7 behavior |
|---|---|
| Surfer inside retreat threshold before return starts | Retreat takes priority; return waits |
| Current surfer enters retreat threshold while returning | Neutralize return, preserve pending reset and original return deadline; existing retreat owns translation |
| Retreat or cooldown active | Keep reset pending; aiming continues |
| Retreat/cooldown ends with usable GPS and no current confirmed ride | Reconsider reset from current aircraft position; compute a new fixed restart target if needed |
| Already in restart zone after retreat | Consume reset with no return movement |
| Pending return reaches original five-minute deadline | Stop navigation with `movement_timeout`; do not renew deadline |
| Pilot intervention, Stop or control loss | Existing cancellation rules clear saved/pending return |

Retreat speed, duration, cooldown, stale-GPS allowance and its existing boundary checks are unchanged. An inactive timed-retreat configuration does not create a new return-clearance circle. Automatic return retains the existing live retreat policy rather than adding S0 routing to return.

## Fresh Wave line configuration

Use `waveLineId` in `vt_settings.json`. Use **`vt_wave_lines.json`** with root array **`waveLines`**. Java classes, UI labels and current log fields use Wave line names, including `effectiveWaveLinesJson` and `waveLineId`. Geometry, A/B capture, selected sea side, Front/Sideways/Diagonal angle meanings and validation retain their behavior.

Old `shorelineId` / `shorelines` keys are rejected, and `vt_shorelines.json` is not loaded or migrated. Start with a fresh configuration folder, or explicitly replace the settings and Wave line library with the new schema. Capture/select a Wave line before Start. Existing old user files are not deleted. The bundled Wave line library is empty and the selected ID is blank.

## Retained angle behavior

Default angle +45°, range −90°..+90°, angle tolerance 5°. Default route planning allowance remains 2 m; execution checks use the retreat threshold without adding that allowance. Frozen S0/P3, alternate-direction route recovery, outward escape, excursion default 300 m and all existing distance/angle triggers remain unchanged. No additional motion smoothing is introduced.

## Diagnostics and validation

Movement logs add `returnBoundaryStandOffMetres`, `returnTargetLatitude`, `returnTargetLongitude`, `returnTargetRemainingM`, `returnBoundaryDistanceM` and `returnPending`. Transition reasons identify skipped return, beachward holds, excursion holds and retreat interruption. Full regression suite covers existing modes and routing plus focused soft-return, retreat/deadline and fresh-schema cases. Desktop checks and APK build do not replace an aircraft flight test.
'''
(root/'Docs/VT_4.0.7.md').write_text(doc,encoding='utf-8')
(root/'detailed_design/detailed_design_v4.0.7.md').write_text(doc,encoding='utf-8')
p=root/'ENHANCEMENTS.md'
with p.open('a',encoding='utf-8') as f:f.write('\n\n## VT 4.0.7\n\n- Soft no-ride return to a fixed target 10 m sea-side of the original boundary; configurable `returnBoundaryStandOffMetres`.\n- Retreat interrupts return; pending reset resumes after cooldown with its original deadline.\n- Fresh Wave line UI/configuration/source/log naming (`waveLineId`, `vt_wave_lines.json`, `waveLines`), without migration.\n- Retain 2 m route planning allowance and existing execution clearance. See `Docs/VT_4.0.7.md`.\n')
print('Added VT407 regression cases, release notes and detailed design')
