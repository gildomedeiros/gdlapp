from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
w=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408')
b=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
t=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
def edit(p,a,c):
 s=p.read_text(encoding='utf-8');assert a in s,(p,a[:80]);p.write_text(s.replace(a,c),encoding='utf-8')
for p in [b/'WaveLineSetupUi.java',b.parent/'DefaultLayoutActivity.java']:
 edit(p,'4.0.7','4.0.8')
edit(t/'Vt405Test.java','check(m.routeStatus.equals("blocked")&&m.forward==0&&m.right==0,"no permitted boundary recovery holds");','check(m.routeStatus.equals("traveling")&&Math.hypot(m.forward,m.right)>0,"VT408 permitted sea-side boundary recovery moves");')
edit(t/'Vt407Test.java','''  check(!m.returning()&&m.reason.equals("return_start_beachward_of_boundary")&&m.noRideTimerActive(),"beachward not valid restart");
  m.update_state_machine(ComeToMeTest.in(61100,1,0,100,0,0),61100,.1);
  check(m.reason.equals("return_already_in_restart_zone"),"retry once permitted");''','''  check(m.returning(),"VT408 beachward start recovers to fixed stand-off");
  m.update_state_machine(ComeToMeTest.in(61100,1,0,100,0,m.returnHeading),61100,.1);
  check(m.returning()&&Math.hypot(m.forward,m.right)>0,"recovery keeps saved stand-off target");''')
edit(t/'Vt407Test.java','''  check(m.returning()&&m.reason.equals("return_beachward_of_boundary")&&m.forward==0,"overshoot cannot be counted as restart");
  check(!m.permits(ComeToMeTest.in(61100,-1,0,100,0,0),61100,-1,0),"beachward submission veto");''','''  check(m.returning()&&Math.hypot(m.forward,m.right)>0,"VT408 overshoot recovers rather than falsely completing");
  check(m.permits(ComeToMeTest.in(61100,-1,0,100,0,0),61100,m.forward,m.right),"sea-side recovery submission permitted");''')
(t/'Vt408Test.java').write_text((w/'Vt408Test.java').read_text(encoding='utf-8'),encoding='utf-8')
p=r/'tools/test-aiming.ps1'
s=p.read_text(encoding='utf-8')
s+='''\n# VT 4.0.8: beachward route and direct soft-return recovery.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt408Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT408 compile failed' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt408Test'
if ($LASTEXITCODE -ne 0) { throw 'VT408 tests failed' }
'''
p.write_text(s,encoding='utf-8')
