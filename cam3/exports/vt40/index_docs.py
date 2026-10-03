from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
out=Path(__file__).parent/'files'
additions={
'ENHANCEMENTS.md':'''\n## VT 4.0 — fixed shoreline presets and JSON-only movement tuning

Implemented in the existing working tree (2026-10-03), versionName 4.0 / versionCode 23. Front and Sideways presets compute one fixed endpoint per fresh planning fix. Two-axis BODY velocity keeps surfer yaw/gimbal active. Alignment-only travel preserves the planning projected separation; wrong-side or retreat-circle-crossing routes hold. Retreat retains direct horizontal distance, body-backward timing and cooldown; saved returns retain navigation ownership.

All movement/ride/yaw-limit tuning moves to `vt_settings.json`, with no preference migration or tuning form. `vt_shorelines.json` stores named A/B lines and explicit seaward side. LoRa stationary A/B capture, manual entry, straight-line preview, Maps point links and saved selection are operational stopped-only UI. Existing JSON is preserved, all five files validate before control acquisition, and missing/malformed configuration visibly blocks Start. Both modes enforce filming >= retreat + 5 m. Default filming 28 m matches retreat 23 m; first movement requires selecting a shoreline.

Validation: complete `tools/test-aiming.ps1` suite passed, including 105 VT 4.0 checks and independently parsed movement logs. Offline `:sample:assembleDebug` succeeded. VT 3.8 APK preserved in `build/releases/VT-3.8.apk`; VT 4.0 APK in `build/releases/VT-4.0.apk`. Physical BODY axis direction and flight behavior remain unverified.

See [VT 4.0 behavior/setup](Docs/VT_4.0.md) and [VT 4.0 design](detailed_design/detailed_design_v4.0.md).
''',
'detailed_design.md':'''\n- [VT 4.0 detailed design](detailed_design/detailed_design_v4.0.md) — Front/Sideways fixed shoreline destinations, two-axis translation with surfer aiming, JSON-only tuning and saved LoRa shoreline capture. Complete regressions and offline APK build passed; aircraft verification remains pending. [Setup and behavior](Docs/VT_4.0.md).\n'''}
for name,addition in additions.items():
 (out/name).write_text((root/name).read_text(encoding='utf-8-sig')+addition,encoding='utf-8')
print('Updated existing documentation indexes without replacing prior notes')
