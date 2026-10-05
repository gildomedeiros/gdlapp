from pathlib import Path
import shutil
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
stage=Path('C:/Users/gildo/gdlapp/cam3/exports/vt404')
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
p=src/'AngleRoutePlanner.java';s=p.read_text(encoding='utf-8')
# OFF: construct genuinely around the geometric circle, not a shortcut through C.
s=s.replace('exit[i]=check(p,ring[i][0],ring[i][1],end[0],end[1],sl,so,anchorLat,anchorLon,centralLat,centralLon);','''exit[i]=check(p,ring[i][0],ring[i][1],end[0],end[1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
            if(p.clearance()==0){
                double geometric=Math.min(startRadius,endRadius);
                if(entry[i].reason.equals("none")&&entry[i].clearance+1e-6<geometric)entry[i].reason="around_geometry";
                if(exit[i].reason.equals("none")&&exit[i].clearance+1e-6<geometric)exit[i].reason="around_geometry";
            }''')
p.write_text(s,encoding='utf-8')
shutil.copyfile(stage/'VT_4.0.4.md',root/'Docs/VT_4.0.4.md')
shutil.copyfile(Path('C:/Users/gildo/gdlapp/cam3/Docs/VT_4.0.4_detailed_design_draft.md'),root/'detailed_design/detailed_design_v4.0.4.md')
p=root/'detailed_design/detailed_design_v4.0.4.md';s=p.read_text(encoding='utf-8');s=s.replace('Clean discussion draft, 5 October 2026. Design only: implementation/build not yet approved.','Implementation authorized on 5 October 2026. Consolidated design; see Docs/VT_4.0.4.md for final defaults, ranges and implementation behavior.');s+='''

## Final implementation choices

Implementation authorized by user. New fields: positioningAngleDegrees [-90,90] default45; positioningAngleToleranceDegrees [.1,45] default5; extraPathClearanceMetres [0,50] default2; maxExcursionMetres [10,1000] default300. New fields are optional for old version1 files; missing excursion resolves300 with299 stop. Retreat-OFF around geometry uses min(start direct radius, destination radius); retreat-distance exclusion remains disabled. No positioning reserve/horizon setting. Around route uses72 evenly spaced exterior circle nodes (5 degree increments), independently validated entry, exterior legs and exit; the shortest complete clockwise/anticlockwise candidate is selected. Internal polygon construction accounts for1 m waypoint tolerance while actual next-leg geometry is checked again. Rejected direction logs retain a representative first failed leg and numeric evidence; they do not claim to enumerate every rejected polygon connection. These choices supersede earlier pending items in the discussion document.
''';p.write_text(s,encoding='utf-8')
print('VT404 release notes and final design saved')
