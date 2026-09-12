# v0.12.3 foundation through v0.15.12.2 full-screen requirement

v0.15.12.2 makes the DJI full-screen requirement mandatory in preset defaults,
saved-setting reads/writes, the UI and the DJI-targeted service guard. Existing
saved Off values no longer bypass it. Gallery/other test targets remain exempt
from the DJI-specific check. This is statically reviewed behaviour; compilation
and device validation are still pending. No detector or gesture changes.

v0.15.12.1 moves both the isolated-test and production gimbal gesture from
normalized `X=0.50` to the fixed DJI gimbal lane at `X=0.791`. The vertical
path remains `Y=0.45` to `Y=0.79`, with the existing two-second hold and
six-second drag. Gimbal CV now processes only red candidates around the
bottom-limit region; unused white/green knob states and the full-height blue
search-strip annotation are removed. No landing/RTH CV or landing lock is
introduced.

v0.15.12 starts production recovery disarmed. A successfully completed real tap
on a validated green `+` selected by pink association arms it. Thereafter, the
no-selectable-target timeout applies in the post-cycle
`S11_WAITING_FOR_NEXT_VALIDATED_PLUS` state; a raw green `+` without the
configured pink association does not reset it. The post-success phase is
authorized to dispatch the gimbal gesture. Pre-dispatch failures clear the
reserved retry interval and receive explicit S18 outcome evidence, while S16
is reserved for the rest period after a genuinely dispatched gesture.

The strict detector path and its thresholds originate from `GDL-PinkDetector-v0.12.3.zip`.

SHA-256 of the original v0.12.3 detector source:

`c28f5a57f05c43e9cc679eb162796ed97316ca133baa3d6138cac90853414fc5`

Those rules include the tested DJI-green threshold, connected-component grouping, square geometry limits, fixed DJI UI exclusions, hollow border measurement, horizontal/vertical plus-axis requirement, diagonal/X rejection, confidence calculation and duplicate suppression.

v0.14.2 added a second path for frames where the hollow square and centre plus are disconnected. It pairs a square having at least two sides with 30% green coverage only with a smaller, centred orthogonal plus component.

v0.14.3 retains those detector rules and changes only the fixed-region guard for the tested Mini 3 Pro landscape layout. It ignores the left 10.7% and right 7.3% control rails. The full central camera view—including the top status row—is searchable, and the map is assumed to remain collapsed inside the left rail. X rejection remains mandatory.

v0.14.4 centralises that camera-area rule and also applies it to standalone pink-blob detection and pink-to-plus association. All three stages now use identical coverage boundaries.

v0.14.5 adds an opt-in `Validated + — no pink required` candidate rule without changing the existing pink-based flows. It also adds a configurable DJI full-screen guard, enabled by default for the production preset.

v0.14.6 removes only the redundant Apply Settings button; Start continues to validate and save all displayed settings before arming. It replaces the static help paragraph with a live, detailed explanation generated from the current setting combination. Detector, selection, evidence and tap behaviour are unchanged.

v0.14.7 corrects the DJI full-screen guard after Mini 3 Pro testing showed that DJI Fly's accessibility bounds can exclude DJI's own side rails. The guard now relies on the already-enforced DJI foreground-package check plus hidden Android navigation controls. It no longer rejects a genuinely full-screen DJI Fly window because of misleading accessibility-node bounds. Detector, selection, evidence, presets and tap behaviour are unchanged.

v0.14.8 corrects the on-screen version heading to match the package version. Runtime behaviour is unchanged from v0.14.7.

v0.15.0 freezes the existing green +, pink, association, search-area and real-tap production logic. It adds a separate ActiveTrack panel detector, settings group and `ActiveTrack panel test` preset. The new preset runs only in Samsung Gallery, turns Re-acquire Off, offers detection/simulated markers only, saves ActiveTrack evidence and cannot dispatch a real touch. Existing presets default Select ActiveTrack to Off and preserve their prior values.

v0.15.1 adds a separate **DJI ActiveTrack (real tap)** preset after live DJI
Fly evidence validated expanded-panel and collapsed-chevron targeting. It keeps
Re-acquire Off, requires foreground DJI Fly plus the full-screen guard, never
touches unknown/already-selected states, and explicitly blocks any proposed
point inside the detected red X exclusion. Existing production Re-acquire logic
and preset values remain unchanged.

v0.15.3 adds the evidence-backed `ACTIVE_TRACK_RUNNING` state from the supplied
raw before/after sequence. Running requires three signals together: the dark
Manual/Parallel upper panel, yellow ActiveTrack lower-left control and a red
control with a wide white Stop glyph. The collapsed state requires a square
white glyph with both X diagonals. Red X, red Stop and an unclassified red
control are all locked exclusions and never touch targets.

The ActiveTrack cycle checks every 400 ms and defaults to an 8-second timeout,
two ActiveTrack taps and two chevron taps. Unknown only waits. Success, timeout,
tap limit or a guard failure prevents further cycle touches.

v0.15.3 also appends a **DJI reacquire + ActiveTrack (real tap)** preset without
altering the standalone presets. It coordinates the unchanged Re-acquire
pipeline with the independent ActiveTrack detector: a completed validated +
tap starts ActiveTrack selection; confirmed running ends it. The previous +
must be absent for at least one processed frame before a later validated + can
start another combined cycle.

v0.15.4 makes unobstructed frame 279 the primary running reference and lowers
only the running upper-panel darkness threshold from 0.42 to 0.30. Stop-glyph
and yellow-ActiveTrack validation remain mandatory, so the darkness change
cannot by itself declare success.

It also adds an opt-in simulated-test auto-rearm setting, enabled only by
default in the **ActiveTrack panel test** preset. After a successful simulated
cycle has observed a non-expanded state, a later expanded Spotlight panel
starts a fresh simulated cycle. Real presets do not auto-rearm this way.
SUCCESS, TIMEOUT and tap-limit evidence now retains the terminal elapsed time
instead of continuing to count while locked.

v0.15.5 changes only the settings/session/evidence infrastructure and combined
coordinator. Every Start owns a new timestamped preset folder, a selected-
settings snapshot and canonical frame/stage filenames. An opt-in global hard-
save switch saves every successfully captured raw screenshot for any preset.

During the combined ActiveTrack-confirmation phase, the unchanged
ReacquirePipeline continues to evaluate each eligible frame. Confirmed
`ACTIVE_TRACK_RUNNING` remains the positive terminal state; otherwise a newly
selected validated + immediately returns to Re-acquire and is retried at the
configured cooldown without an attempt limit. ActiveTrack timeout timing begins
only after no selected + is present. ActiveTrack-section and chevron limits are
unchanged and remain independent of + retries.

v0.15.10 keeps `GIMBAL_TEST` isolated but removes the manual trigger and normal
knob/scale movement gate. After the DJI foreground/full-screen guard passes, a
ten-second countdown runs. In v0.15.12.1 movement is one continuous gesture at
normalized gimbal-lane coordinate `(0.791, 0.45)`: hold for two seconds, then
drag to `Y=0.79` over six seconds without releasing. Each attempt is followed
by a six-second interval so DJI's wheel disappears. Red detection has priority
over gesture/interval states; two consecutive red frames latch completion and
prevent further motion.

The same gesture is available to `PRODUCTION` and `COMBINED_REAL` while they
are in Re-acquire. It defaults On for `COMBINED_REAL` and Off for the standalone
tap preset, with a configurable 15-second no-validated-+ timeout. Once recovery
starts, green + is intentionally ignored until the red
bottom limit is confirmed; high-rate green + plus pink association then resumes.
Re-acquire selection thresholds, X rejection, ActiveTrack logic and tap limits
are unchanged. Stable standalone Gimbal/ActiveTrack states remain at 1 FPS.
