from pathlib import Path
import hashlib,json
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
out=Path(__file__).parent/'files'
pk='SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/'
test='SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/'
def edit(n,a,b):
 p=out/n;s=p.read_text(encoding='utf-8') if p.exists() else (root/n).read_text(encoding='utf-8-sig');assert a in s,(n,a[:60]);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s.replace(a,b),encoding='utf-8')
edit(pk+'AimingCycleLog.java','session.movement.approaching() ? "approach"','session.movement.approaching() && !session.movement.presetApproach() ? "approach"')
edit(pk+'AimingCycleLog.java','session.movement.returning()||session.movement.approaching()||!session.movement.riding','session.movement.returning()||(session.movement.approaching()&&!session.movement.presetApproach())||!session.movement.riding')
edit(pk+'AimingCycleLog.java','!session.movement.returning()&&!session.movement.approaching()&&session.movement.riding','!session.movement.returning()&&(!session.movement.approaching()||session.movement.presetApproach())&&session.movement.riding')
edit(pk+'ShorelineSetupUi.java','java.time.Instant.now().toString()','new java.text.SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ssXXX",Locale.US).format(new Date())')
edit(test+'Vt40Test.java','''        try(FullSessionLog log=new FullSessionLog(()->new java.io.FileOutputStream(output+"/vt40-test.jsonl"),()->p.time,32,256000)){
            MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);log.flush();
        }''','''        FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(Paths.get(output,"vt40-test.jsonl")),error->{throw new AssertionError(error);});log.enable();
        MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);log.disable("test");
        long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(!log.busy(),"VT40 writer closed");''')
for p in (root/test).glob('*.java'):
 s=p.read_text(encoding='utf-8-sig')
 if '\\"version\\":\\"3.8\\"' in s: edit(test+p.name,'\\"version\\":\\"3.8\\"','\\"version\\":\\"4.0\\"')
edit('tools/test-aiming.ps1',"-or $motionBytecode.Contains('setPitch')","-or !$motionBytecode.Contains('setPitch')")
edit('tools/test-aiming.ps1','"$source/RetreatJsonConfig.java" "$source/VtSessionConfig.java"','"$source/RetreatJsonConfig.java" "$source/VtJsonFields.java" "$source/VtSettingsConfig.java" "$source/ShorelineLibrary.java" "$source/VtSessionConfig.java"')
p=out/'tools/test-aiming.ps1';s=p.read_text(encoding='utf-8');s+='''
# VT 4.0: exercise production fixed shoreline destinations and both body velocity axes.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt40Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 4.0 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt40Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output
if ($LASTEXITCODE -ne 0) { throw 'VT 4.0 tests failed' }
$v40=@(Get-Content (Join-Path $output 'vt40-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'movement_cycle')
if($v40.Count -ne 1 -or $v40[0].positioningMode -ne 'front' -or $v40[0].yawPurpose -ne 'surfer' -or $v40[0].submittedRightMps -ge 0 -or $v40[0].fixedDestinationFixTime -le 0 -or $v40[0].shorelineId -ne 'beach'){throw 'VT40 independent log validation failed'}
Write-Output 'PASS: VT 4.0 independent parser validates preset, fixed destination, lateral submission and surfer yaw'
''';p.write_text(s,encoding='utf-8')
# Immutable assembled snapshot: old loader remains for regression tests, production uses load40.
p=out/pk/'VtSessionConfig.java';s=p.read_text(encoding='utf-8');s=s.replace('public ComeToMeSettings movement;','public final ComeToMeSettings movement;').replace('public ShorelinePositioning positioning;','public final ShorelinePositioning positioning;').replace('public String settingsJson,shorelinesJson;','public final String settingsJson,shorelinesJson;')
needle='''        retreatJson=r;rotationJson=y;gimbalJson=g;this.retreat=retreat;this.rotation=rotation;this.gimbal=gimbal;'''
s=s.replace(needle,'''        this(r,y,g,retreat,rotation,gimbal,null,null,null,null);
    }
    private VtSessionConfig(String r,String y,String g,RetreatSettings retreat,RotationSpeedCurve rotation,GimbalBandConfig gimbal,
            ComeToMeSettings movement,ShorelinePositioning positioning,String settings,String shorelines) {
        retreatJson=r;rotationJson=y;gimbalJson=g;this.retreat=retreat;this.rotation=rotation;this.gimbal=gimbal;
        this.movement=movement;this.positioning=positioning;settingsJson=settings;shorelinesJson=shorelines;''')
s=s.replace('result.movement=c.movement;result.positioning=positioning;result.settingsJson=settings;result.shorelinesJson=library;return result;',
'''return new VtSessionConfig(result.retreatJson,result.rotationJson,result.gimbalJson,result.retreat,result.rotation,result.gimbal,c.movement,positioning,settings,library);''');p.write_text(s,encoding='utf-8')
manifest=[]
for p in out.rglob('*'):
 if p.is_file():
  rel=p.relative_to(out).as_posix();original=root/rel
  manifest.append(dict(path=rel,originalSha256=hashlib.sha256(original.read_bytes()).hexdigest() if original.exists() else None,stagedSha256=hashlib.sha256(p.read_bytes()).hexdigest()))
(out.parent/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Reviewable staged files:',len(manifest))
