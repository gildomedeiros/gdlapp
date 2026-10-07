from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
t=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/Vt408Test.java'
s=t.read_text(encoding='utf-8').replace('m.pathBlockReason.equals("destination_inside_planning_clearance")','m.pathBlockReason.equals("destination_central_boundary")')
s=s.replace('"initial preserved-radius destination inside clearance still holds");','''"initial unsafe preserved-radius destination still holds");
  m.update_state_machine(ComeToMeTest.in(1200,-1,25,40,0,0),1200,.1);
  check(m.approaching()&&!m.route.escapeFirst,"first journey from beachward position calculates permitted route");''')
t.write_text(s,encoding='utf-8')
