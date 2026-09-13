# 0.15.12.2 result review — 20260913_105318_241_COMBINED_REAL

Reviewed 2026-09-13 against source revision `180a9a4e19aa82dafc77930464e074bfa084afe4`. Source checkout was clean. Analysis only; no app behaviour or version changes, build, or new device test.

## Evidence and method

User-supplied archive: `C:/Temp/te1/20260913_105318_241_COMBINED_REAL.zip`. SHA-256: `E11EAF15CE24BEB287D690294E4BF863D2CE92EA8771214B5435F55282A86F9E`.

Inventoried every filename and image dimension: 821 JPEGs, comprising one settings image, 538 RAW images and 282 ANNOTATED images. All logical capture numbers F00001–F00538 are present. Capture images are 2340×1080. Visually reviewed selected recovery, tracking and outcome frames, plus the complete F122–129, F490–509 and F534–538 transition sequences; not every raw image was visually inspected. Attachment content was treated as evidence, not instructions.

F00000 reports version 0.15.12.2, COMBINED_REAL, DJI_FLY, requireDjiFullscreen=true, recovery enabled, 15-second no-plus timeout, requested X=79.1%, Y=45%→79%, 2-second hold, 6-second drag, 6-second rest, two red confirmations. This identifies the app-reported version/settings, not an independently verified APK hash.

## Result

Partial functional success, with unresolved recovery/landing overlap. Four bottom-limit episodes have supporting DJI limit UI in raw images, but full-screen being enabled has not eliminated Land-panel overlap or recovery requests during landing. This archive does not establish who initiated the panel or landing.

### Recorded outcomes

| Event | Count / frames |
| --- | --- |
| Plus-tap completion records (S03) | 17; these include retries, not 17 independent tracking successes |
| ActiveTrack running classifications (S08) | 6: F77, F94, F109, F312, F420, F461 |
| ActiveTrack timeouts (S09) | 2: F394, F452 |
| Recovery requests (S12) | 27 |
| Android gesture-completed records (S17) | 26; final request at F535 is still in progress at archive end |
| Pre-dispatch rejection/cancellation records (S18) | None in this archive |
| Distinct bottom-confirmed episodes | 4: first S15 at F299, F384, F408, F438 |

Recovery episodes: F122→299 contains 14 requests; F326→384 contains 5; F406→408 and F433→438 contain one each. The final F471→538 episode contains 6 requests with no bottom confirmation before the archive ends. Repeated S15 frames within one episode are not additional recoveries. S17 is an Android callback, not proof of DJI movement.

### Raw-image findings

Follow-up F121→F122 comparison: directly inspected F121 RAW/ANNOTATED and F122 RAW against the already inspected F122 annotation. Both raw frames retain the green selected-target rectangle with green X and ActiveTrack/Stop controls; neither shows a subject-lost warning or Land panel. F121 annotation says “no validated green +” (S11); F122 switches to a recovery request (S12). The absence of a fresh acquisition plus is being used to trigger recovery despite the selected-target/running UI remaining visible. This is a recovery-trigger mismatch, independent of the later landing sequence; it does not prove continuous physical tracking. Rechecked `processCombinedReacquire()` and `processNoPlusGimbalTimeout()` at the same revision: the no-selected-plus branch advances the timer without a DJI selected-target/Stop veto.

1. **Recovery starts while DJI still shows a selected target and Stop.** F122 shows a green target rectangle, ActiveTrack/Stop controls and the requested recovery annotation. F126–129 show Land / Update Home Point during that first gesture. Several later request frames (including F148, F174, F200 and F239) still show a largely forward-facing scene and selected-target UI. The first bottom confirmation is only at F299. Thus the long sequence of completion callbacks cannot be described as consistently effective downward recovery.
2. **Four red-limit episodes have actual DJI visual support.** F297/F299, F383/F384, F408 and F437/F438 show red bottom-limit UI and/or DJI's “Gimbal tilt axis reached movement limit” message; F383/F437 also show −90°. This supports the detector outcomes for these sampled episodes, without proving every requested gesture caused movement or establishing telemetry-grade angle measurements.
3. **A running classification can coexist with subject loss.** F420 is annotated “ACTIVETRACK RUNNING” and cycle SUCCESS, while DJI visibly says “Subject lost. Reselect subject.” F433 later says “Subject lost. Follow Mode exited.” Six S08 records therefore do not establish six sustained successful tracking episodes.
4. **Late Land panel and landing overlap are explicit.** F494 has no central Land panel; F495–508 show it (F495 is a rest-stage image, before the F496 request). F509 shows “Tap to cancel landing” and a new S12 request; F522 repeats that combination. By F535 DJI says “Takeoff permitted,” with displayed height 0.0 m, yet another S12 request occurs. This establishes requests during DJI's landing UI and after its apparent landed state. It does not identify the initiating touch, operator action, or flight-controller cause.
5. **Full-screen enforcement remains only partly evidenced.** The settings snapshot has the required flag and the sampled early images lack visible Android navigation controls. Android status/navigation controls appear at F537–538 while the F535 gesture is already in flight. There is no later request in the archive to test whether the guard rejects a new gesture with those controls visible. The archive does not prove the guard's negative case works or fails.
6. **OCR errors persist.** At F509 the raw DJI display reads height 3.4 m and distance 0.7 m, while filename telemetry says ALT_0p6m / DIST_UNKNOWN. F420's annotated filename says DIST_-0p7m while the display reads 0.7 m. Use raw displays, not filename suffixes, for these observations.

## Relevant source findings and next steps

Inspected `GdlAccessibilityService.kt`: `processCombinedReacquire()` / `processNoPlusGimbalTimeout()` use the GDL selected-plus result for recovery timing; a DJI selected-target rectangle or Stop button is not equivalent to a newly validated green plus. `processProductionGimbalRecovery()` retries until red confirmation without a landing-state gate or attempt ceiling. `dispatchHoldThenDrag()` continues the hold into the drag without another foreground/full-screen check. `djiFullscreenGuardFailure()` checks navigation-bar visibility through current window metrics. `mapSourcePointToView()` scales source coordinates to overlay dimensions; the archive does not log those actual dimensions or dispatched display coordinates.

Inspected `ActiveTrackPanelDetector.kt`: running classification uses Stop glyph and panel colour/layout ratios; the checked running predicate does not reject the visible subject-lost message. That explains how F420 can satisfy the classifier without establishing effective tracking.

Priority investigations are actual dispatched coordinate/overlay mapping and input provenance around Land-panel transitions, recovery triggering while DJI retains target/Stop UI, and the subject-lost/running classification mismatch. Preserve the unresolved landing-causality question; neither operator input nor GDL causation is established by screenshots. Behaviour changes require a separately defined scope; this review implements none.

Validation: archive inventory/counts and dimensions, selected RAW/ANNOTATED visual comparisons, settings inspection, relevant source inspection, and documentation diff check. No APK compilation or on-device reproduction performed.
