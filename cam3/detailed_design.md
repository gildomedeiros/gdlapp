# cam3 detailed design index

Maintain one separate design file per release in the detailed_design directory, named detailed_design_vX.0.md (use the exact version when additional version components are needed). Preserve prior version designs. Each file must include detailed source-file and method changes, the file-labelled call hierarchy, safety/failure handling, validation, and explicit planned/implemented status. Comment every change to existing code with its CAM3 version, what changed and why.

- [v2.0 detailed design](detailed_design/detailed_design_v2.0.md) — implemented locally and build-verified GPS yaw aiming; no CV, no commanded translation, manual gimbal tilt. Device validation pending. Includes the implemented call hierarchy, original proposal and v1.0 observations.

The original design content is preserved in the v2.0 file alongside the implemented version details.

- [v2.1 detailed design](detailed_design/detailed_design_v2.1.md) — local aiming diagnostic logs, precise blocked-state reasons and user-controlled log export. Existing flight-control gates retained; device diagnosis pending.

- [v2.2 detailed design](detailed_design/detailed_design_v2.2.md) — implemented locally: APAS hover eligibility, enable/advanced confirmation, ownership callback logs, late-grant cleanup, readiness Details and STOP acknowledgement. Device validation pending; see implementation record and build results.
