# cam3 detailed design index

Track outstanding work separately in [pending enhancements](ENHANCEMENTS.md). Version designs describe the scope and implementation of each release.

Maintain one separate design file per release in the detailed_design directory, named detailed_design_vX.0.md (use the exact version when additional version components are needed). Preserve prior version designs. Each file must include detailed source-file and method changes, the file-labelled call hierarchy, safety/failure handling, validation, and explicit planned/implemented status. Comment every change to existing code with its CAM3 version, what changed and why.

- [v2.0 detailed design](detailed_design/detailed_design_v2.0.md) — implemented locally and build-verified GPS yaw aiming; no CV, no commanded translation, manual gimbal tilt. Device validation pending. Includes the implemented call hierarchy, original proposal and v1.0 observations.

The original design content is preserved in the v2.0 file alongside the implemented version details.

- [v2.1 detailed design](detailed_design/detailed_design_v2.1.md) — local aiming diagnostic logs, precise blocked-state reasons and user-controlled log export. Existing flight-control gates retained; device diagnosis pending.

- [v2.2 detailed design](detailed_design/detailed_design_v2.2.md) — implemented locally: APAS hover eligibility, enable/advanced confirmation, ownership callback logs, late-grant cleanup, readiness Details and STOP acknowledgement. Device validation pending; see implementation record and build results.

- [v2.3 detailed design](detailed_design/detailed_design_v2.3.md) — implemented locally: recoverable pauses, 2-second recovery, pilot-stick pause, bounded telemetry read retries, shared 5 m minimum, recovery UI and diagnostics. Device validation pending.

- [v2.4 detailed design](detailed_design/detailed_design_v2.4.md) — implemented LoRa Wi-Fi target GPS by default, Phone GPS selector, duplicate/restart handling and accuracy-gate bypass for both sources. Existing yaw and pause/recovery controls retained. APK and aiming tests passed; full Android lint failed and device validation is pending.

For GPS/aiming behavior, use v2.4 together with the retained control policy in v2.3. The v2.3 phone-only source and 10 m accuracy requirement are historical and are superseded by v2.4.

- [v2.5 detailed design](detailed_design/detailed_design_v2.5.md) — current local release: Enable Full Log replaces Export; automatic Downloads/CAM3 JSONL sessions include coordinates/raw packets, while private minimal logs retain gaps, invalid data and control events. Existing aiming and compact layout retained. See its validation record and pending device checks.

- [v2.7 detailed design](detailed_design/detailed_design_v2.7.md) � worktree release based directly on v2.5: independent direction votes, nearby commitment with five-minute refresh, bounded speed gain, removal of the 5 m pause and per-cycle full logs. No v2.6 smoothing. Local v2.5 unchanged; device validation pending.
