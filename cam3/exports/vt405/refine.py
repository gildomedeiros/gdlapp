from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
s=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
t=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
(t/'Vt405Test.java').write_text(Path('C:/Users/gildo/gdlapp/cam3/exports/vt405/Vt405Test.java').read_text())
p=s/'ComeToMeController.java';text=p.read_text()
text=text.replace('phase=Phase.APPROACHING; attemptSince=now;\n            approachStartLat','openJourney();phase=Phase.APPROACHING; attemptSince=now;\n            approachStartLat')
text=text.replace('approachAligned=true;phase=Phase.APPROACHING;attemptSince=now;event="fixed_destination_planned";','openJourney();approachAligned=true;phase=Phase.APPROACHING;attemptSince=now;event="fixed_destination_planned";')
text=text.replace('phase=Phase.HOLDING; reason="excursion_limit";','finishJourney("cancelled","excursion_limit");phase=Phase.HOLDING; reason="excursion_limit";')
text=text.replace('phase=Phase.HOLDING;reason="excursion_limit";','finishJourney("cancelled","excursion_limit");phase=Phase.HOLDING;reason="excursion_limit";')
p.write_text(text)
print('Recovery checks refined')
