from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt');w=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408')
b=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming';t=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
def edit(p,a,c):
 s=p.read_text(encoding='utf-8');assert a in s,(p,a[:80]);p.write_text(s.replace(a,c),encoding='utf-8')
edit(t/'Vt406Test.java','''  m.update_state_machine(ComeToMeTest.in(1100,14,0,40,0,0),1100,.1);
  check(m.recoveryStatus.equals("start_beachward_of_boundary"),"recovery failure current status");''','''  double anchor=m.initialCentralLat;m.initialCentralLat=30*DEG;
  m.update_state_machine(ComeToMeTest.in(1100,14,0,40,0,0),1100,.1);
  check(m.recoveryStatus.equals("destination_central_boundary"),"recovery failure current status");
  m.initialCentralLat=anchor;''')
edit(t/'Vt406Test.java','m.lastRecoveryResult.equals("start_beachward_of_boundary")','m.lastRecoveryResult.equals("destination_central_boundary")')
edit(t/'Vt408Test.java','check(m.approaching()&&m.route.escapeFirst,"initial calculation also supports escape");','check(!m.approaching()&&m.pathBlockReason.equals("destination_inside_planning_clearance"),"initial preserved-radius destination inside clearance still holds");')
edit(t/'Vt408Test.java','port.core.stopAiming();','port.core.stopAiming("user_stop");')
c=b/'ComeToMeController.java'
edit(c,'    private boolean returnLegAllowed(AimingSession.Inputs in) {','''    private boolean boundaryMotionAllowed(AimingSession.Inputs in,double f,double r) {
        double distance=boundaryAt(in);
        if(distance>1e-6)return true;
        double h=Math.toRadians(in.heading);
        double normal=f*(Math.cos(h)*positioning.shore.seaNorth+Math.sin(h)*positioning.shore.seaEast)
            +r*(-Math.sin(h)*positioning.shore.seaNorth+Math.cos(h)*positioning.shore.seaEast);
        return Double.isFinite(normal)&&(distance< -1e-6?normal>0:normal>=-1e-6);
    }
    private boolean returnLegAllowed(AimingSession.Inputs in) {''')
edit(c,'return Math.abs(requestedForward-v[0])<.01&&Math.abs(requestedRight-v[1])<.01;','return boundaryMotionAllowed(in,requestedForward,requestedRight)&&Math.abs(requestedForward-v[0])<.01&&Math.abs(requestedRight-v[1])<.01;')
edit(c,'return Math.abs(v[0]-requestedForward)<=.01&&Math.abs(v[1]-requestedRight)<=.01;','return (!angleApproach()||boundaryMotionAllowed(in,requestedForward,requestedRight))&&Math.abs(v[0]-requestedForward)<=.01&&Math.abs(v[1]-requestedRight)<=.01;')
edit(c,'forward=v[0];right=v[1];reason="moving_to_restart_target";','if(!boundaryMotionAllowed(in,v[0],v[1])) {reason="return_central_boundary";return;}\n                forward=v[0];right=v[1];reason="moving_to_restart_target";')
edit(b/'MovementCycleLog.java','"routeRecoveryAttempts",m.recoveryAttempts,','''"boundaryRecoveryFlow",m.positioning!=null&&session.cycleInputs!=null&&m.positioning.boundaryDistance(session.cycleInputs.lat,session.cycleInputs.lon,m.initialCentralLat,m.initialCentralLon)<-1e-6?
                    (m.returning()?"return_to_central":m.angleApproach()&&m.approaching()?"diagonal_come_to_me":"none"):"none",
                "currentBoundaryDistanceM",m.positioning!=null&&session.cycleInputs!=null?m.positioning.boundaryDistance(session.cycleInputs.lat,session.cycleInputs.lon,m.initialCentralLat,m.initialCentralLon):null,
                "routeRecoveryAttempts",m.recoveryAttempts,''')
