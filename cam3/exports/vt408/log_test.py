from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
p=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/Vt408Test.java'
s=p.read_text(encoding='utf-8')
method=''' static void log(String output)throws Exception {
  Vt407Test.Port port=new Vt407Test.Port();port.start();
  for(int i=0;i<650&&!port.core.movement.returning();i++)port.tick(-1,20,100,0,0);
  port.tick(-1,20,100,0,port.core.movement.returnHeading);
  FullSessionLog log=new FullSessionLog(name->java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(output,"vt408-log.jsonl")),error->{throw new AssertionError(error);},"4.0.8-test");
  log.enable();MovementCycleLog.record(log,port.core,port.time,port.core.cycleId,port.forward,port.right);log.disable("test");
  long end=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<end)Thread.sleep(5);
  check(!log.busy(),"boundary recovery diagnostic closes");
 }
'''
s=s.replace(' public static void main(String[] args){',method+' public static void main(String[] args)throws Exception{').replace('returns();session();System.out','returns();session();if(args.length>0)log(args[0]);System.out')
p.write_text(s,encoding='utf-8')
p=r/'tools/test-aiming.ps1';s=p.read_text(encoding='utf-8').replace("'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt408Test'\n","'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt408Test' $output\n")
s+='''
$v408=@(Get-Content (Join-Path $output 'vt408-log.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$row408=@($v408 | Where-Object event -eq 'movement_cycle')[0]
if($row408.boundaryRecoveryFlow -ne 'return_to_central' -or [Math]::Abs($row408.currentBoundaryDistanceM+1) -gt .02 -or $row408.returnBoundaryStandOffMetres -ne 10 -or $row408.submittedForwardMps -eq 0 -or $row408.returnTargetLatitude -le 0){throw 'VT408 boundary recovery log validation failed'}
Write-Output 'PASS: independent JSON parser verifies VT408 boundary recovery flow, distance, fixed target and submitted command'
'''
p.write_text(s,encoding='utf-8')
