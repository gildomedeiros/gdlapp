# VT 4.0.1 — first-use shoreline setup and Full Log repair

VersionName 4.0.1 / versionCode 24. Corrects the reported VT 4.0 setup experience without changing flight geometry.

- Shorelines explicitly displays the selected profile or an empty-library explanation. Tap a saved name to select it directly; Review/Maps/manage remains separate. Successful save/selection refreshes the library and uses a confirmation toast, not an error dialog.
- Google Maps is accessible from the main Shorelines screen before capture. It opens the current target location when available, otherwise the general map. A/B point links remain in Review after capture/manual entry. Maps remains external; the straight A/B line and sea arrow are drawn in VT.
- Empty library, blank selection and deleted selected profiles generate a short first-use explanation with an Open Shorelines action. Configuration error dialogs are deduplicated. Manual-entry validation stays inline and retains entered values; the form scrolls with the keyboard.
- Full Log is requested ON by default. A busy asynchronous close no longer loses a foreground reopening request. Logging retries after close finishes, and permission grant enables it automatically. A failed opening produces one error per opening request instead of retrying every control tick. Explicit user OFF remains respected. The menu checkmark shows the requested state; Details reports the actual file status/errors.
- Folder help now names all five JSON files. Tuning forms remain removed by the agreed JSON-only design. No fake shoreline is seeded: coordinates and sea side must be captured/entered and selected.

Defaults: Come-to-me enabled, retreat enabled, Full Log requested enabled, mode Front, filming 28m, retreat 23m, GPS source LoRa. Phone GPS and touch lock start off; flight control requires explicit Start. No shoreline is selected until setup. Existing JSON is preserved, including user values that differ from defaults.

Validation: complete control/GPS/ride/return/retreat/config/log suite plus startup setup and asynchronous logging-policy regression cases. Android debug APK is built offline. Physical UI and flight behavior require device verification; screenshots alone do not prove Maps launch or storage writes succeeded on the device.
