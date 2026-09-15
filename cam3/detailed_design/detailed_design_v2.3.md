# cam3 v2.3 detailed design — shared minimum aiming distance

Status: PLANNED; not implemented. Manual v2.2 test modification recorded below.
Recorded: 2026-09-15 (Australia/Brisbane).
Workspace: C:/Users/gildo/gdlapp/cam3. Local only.

## User's manual change to v2.2

The user reported manually lowering the minimum aiming distance for testing. Inspection of the local YawAimingMath.java confirms the original distance condition is commented out and the following condition is active in isBearingUsable():

```java
// CAM3 v2.2 local test: Lower minimum distance to 10 m; close-range aiming may wander.
&& phoneAccuracy <= 10 && separation >= 10;
```

The finite-value checks and positive phone-accuracy check preceding this expression remain present. The original condition was:

```java
&& phoneAccuracy <= 10 && separation >= Math.max(20, 4 * (phoneAccuracy + 5));
```

This is a user-made source modification after the recorded v2.2 build. The earlier v2.2 APK hash/build validation does not validate this modification. No rebuild, installation or flight result for this manual change was verified during this documentation update. Preserve the user's edit during subsequent work.

Known mismatch: YawAimingController.phoneDetails() still calculates the old displayed/logged minimum. Consequently Details may show required 30 m at phone accuracy 2.5 m while the manually edited control check accepts separation >=10 m.

## Required next-version changes

1. Carry forward the user's **10 m minimum horizontal aiming distance**, replacing the prior accuracy-scaled distance rule.
2. Define the minimum once in YawAimingMath, for example MIN_AIMING_DISTANCE_METERS = 10.0. Use it in both eligibility and displayed/logged required distance.
3. Preserve phone accuracy >0 and <=10 m, finite-value checks, freshness checks, yaw rate/acceleration limits, zero translation, explicit Start, Stop and takeover protections.
4. Do not interpret 10 m as a validated safety boundary. Close-range GPS aiming can point incorrectly or wander; document this test limitation.
5. A user-adjustable settings UI is not included in this requirement; the agreed change is the fixed 10 m minimum and consistent reporting.

## Files and methods

Paths are relative to the workspace. Package root: SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/.

| File | Planned change |
|---|---|
| aiming/YawAimingMath.java | Introduce the shared minimum; isBearingUsable uses it. Preserve the intent of the user's manual change. |
| aiming/YawAimingController.java | phoneDetails uses the same minimum instead of duplicating the old formula; Details and blocker logs then agree with the control gate. |
| UXSDK src/test/java/.../aiming/AimingSessionTest.java | Update old distance-policy expectations; verify below 10 m rejects, exactly 10 m accepts with valid accuracy, and invalid/poor accuracy still rejects. |
| SampleCode-V5/android-sdk-v5-sample/build.gradle | Bump release metadata when v2.3 is implemented, not during this note-taking step. |
| aiming/AimingDiagnosticLogger.java and defaultlayout/DefaultLayoutActivity.java | Update log header/export filename when implementing the release. |

All existing-code modifications must have nearby CAM3 v2.3 comments explaining what changed and why. Preserve prior version design files; record actual implementation and validation in this file.

## Call hierarchy

```text
AimingSession.java: Inputs.validate()
  -> YawAimingMath.java: isBearingUsable(distance, accuracy)
     -> shared MIN_AIMING_DISTANCE_METERS (10 m)

YawAimingController.java: recordDiagnostics() / phoneDetails()
  -> same shared MIN_AIMING_DISTANCE_METERS
  -> Details snapshot and blocker log
```

## Validation when implemented

- Test boundary values around 10 m and retain invalid accuracy/coordinate/freshness checks.
- Update tests that assumed the previous conservative distance formula; do not silently retain obsolete expectations.
- Verify the displayed/logged required distance is 10 m and agrees with the eligibility check.
- Run the control/logger tests and compiled zero-translation checks, then build locally.
- Record device results separately; desktop validation cannot establish physical aiming accuracy or stop behavior.

This documentation update changes no application source and performs no build or installation.
