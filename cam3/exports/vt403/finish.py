from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
base=Path('SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming')
def edit(path,old,new):
    p=root/path
    s=p.read_text(encoding='utf-8')
    assert old in s, (path,old[:80])
    p.write_text(s.replace(old,new),encoding='utf-8')
tests=Path(base.as_posix().replace('src/main/java','src/test/java'))
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
