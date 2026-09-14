# GDL Modular Re-acquire v0.15.12.11

## v0.15.12.11

Combined is the default; PRODUCTION is removed from the selector and saved PRODUCTION selections migrate while retaining saved timings/settings. Spotlight exit now waits for configurable pin persistence (800 ms default) or no pink inside a previously pink-confirmed solid green tracking box (3000 ms default). Fresh frames confirm expiry; pink return cancels absence, and missing/ambiguous/changed boxes disarm the pink timer. Settings are in milliseconds. Actual timing depends on capture/processing cadence.

Logs include observations, timer state, cancellation and exit reasons at `Documents/GDL/<run>/diagnostic.log`. Native-resolution CV, rectangle acquisition and nonblocking evidence remain. Build and host checks passed; new absence detection has not been validated on a device failure sequence.

## v0.15.12.10

Repackaged restored .9 behaviour: rectangle acquisition, 1000 ms default cooldown, nonblocking saving and diagnostic logs. The earlier rejected .10 tap change is not included. Logs: Documents/GDL/<run>/diagnostic.log.

## v0.15.12.9

Evidence queue admission no longer waits. When all three slots are occupied, the image is skipped, logged and counted in status messages while detection continues. Capture callbacks time out after 10 seconds; late callbacks cannot change a newer capture. Processing releases capture state and restores the overlay in finally, including error paths. A detector still executing after 10 seconds is reported as slow, not replaced with a concurrent detector/gesture run.

Persistent diagnostics: `Documents/GDL/<run>/diagnostic.log`, plus backup under the app's external-files `diagnostics/<run>/diagnostic.log` (internal files fallback). This is a separate directory from Pictures evidence: include the matching log with the run ZIP. Logs record wall/elapsed timestamps, frame IDs, capture events, processing/stage timing, save queue skips/write timing, statuses and gesture requests/Android results. A separate 256-entry nonblocking queue bounds logging; overflow is counted in the next written entry. Storage hangs/process termination can still prevent final log entries from being persisted. Public log creation depends on Android MediaStore support; the app-local copy is written first. Logs append after service restart.

No CV resizing, acquisition geometry or recovery-rule changes in this version. Host tests cover a deliberately blocked writer, queue drain, callback timeout/late arrival and processing isolation; device validation remains required.

## v0.15.12.8

Combined acquisition now draws a 300 ms top-left to bottom-right rectangle centred on the freshly validated green-plus marker. Width and height are each 3× the marker (9× area), symmetrically reduced at preview edges. Android completion means awaiting Spotlight control, not confirmed DJI selection. Combined default retry cooldown is 1000 ms; existing saved settings are preserved. Other diagnostic/standalone tap presets retain their gestures.

Native resolution is preserved. Pink HSV work is limited to existing candidate association windows; native pixels are reused across plus/pink/control/pin evaluation. Plus HSV has a necessary RGB prefilter, with the existing final HSV predicate unchanged. Pin template offsets are computed once per evaluation, retaining its scan coverage and thresholds. Red-control search already uses its fixed ROI; removed per-pixel neighbour allocations. Evidence compression/writes use a serial background queue with at most three retained full-size bitmap copies; queue saturation blocks rather than drops evidence. Counts represent queued images; asynchronous write failures are reported. Wait for saving to finish before copying results.

`GDL_PERF` logcat records capture/queue, plus, pink, acquisition, control, pin, processing, processing-start-to-dispatch and evidence-write milliseconds. These are diagnostic timings, not achieved-FPS guarantees. Device comparison against .7 is still required; no device performance improvement or DJI rectangle acceptance has been verified.

## v0.15.12.7

Native-resolution CV; green X/dotted-box/pin matching; guarded red-X exit with fresh-frame verification before recovery. Existing single configurable gimbal attempt retained. Build passed; limited screenshot checks passed, device validation remains required. See GDL_CURRENT_STATE.md for scope and limitations.

## v0.15.12.6 acquisition retry correction

Persistent validated plus candidates can be tapped again after the existing cooldown using the current screenshot coordinates. Each candidate frame checks the existing Spotlight red-X/chevron detector before tapping; visible control suppresses the tap. Android completion reports awaiting Spotlight control, not confirmed selection. Existing CV thresholds, recovery arming/countdown and single configurable gimbal attempt remain unchanged. Real-device validation remains required.

Author: Gil

## v0.15.12.5 Spotlight and one timed gimbal attempt

The combined real-tap preset now selects a validated green plus with pink and
leaves DJI in its default Spotlight mode. It no longer activates ActiveTrack or
taps the chevron. Existing standalone ActiveTrack diagnostic presets remain.

In S11, the large red X control with a visible upward chevron is checked about
once per second. It resets the countdown, as does a validated plus/pink detection.
Once recovery is armed by a successful validated tap, 15 seconds of absence
(configurable timeout) permits one gimbal attempt, subject to the existing guards.

Gimbal drag duration is configurable from 1000 to 30000 milliseconds, default
6000. The hold remains 2000 ms; coordinates remain X=79.1%, Y=45% to 79%.
There is no red-knob/angle detection and no automatic retry, including failed or
rejected attempts. Searching resumes afterward. Another successful validated tap
re-arms recovery; the isolated test requires restarting for another attempt.
Completion indicates an Android gesture outcome, not a calibrated angle.

VersionCode 44. Timing/single-attempt tests pass. New Spotlight chevron recognition
requires device validation; no RTH/landing recognition has been added.

## v0.15.12.4 S11 ActiveTrack monitoring

Combined mode now reuses the existing ActiveTrack panel detector while waiting
in S11 with no validated plus. Checks are limited to once per 1000 ms, on the
next available capture; actual cadence depends on capture/processing time.
The existing default 400 ms plus-search interval is unchanged.

After a successful validated green-plus/pink tap has armed recovery, its S11
countdown starts on the first observation of neither a validated plus nor an
ActiveTrack-running classification. Either positive detection clears the
countdown. Recovery can begin only after a full configured timeout (default
15 seconds) of absence, with existing guards still satisfied.

No new CV/OCR or detector thresholds. Already-active recovery, gestures and
standalone production behaviour are unchanged. The existing detector can still
classify a lingering Stop panel as running during a subject-lost warning;
this delays recovery until that classification clears. VersionCode 43.

## v0.15.12.3 last validated plus timer

Production recovery now measures the configured timeout (default 15 seconds)
from the most recent detection of a green plus with the required pink association,
using the existing detector. Detections during tap cooldown also refresh that time.
A successful validated-plus tap must still arm recovery first. A missing plus no
longer starts a separate countdown from the first missing frame. Guard failures
and dispatch rejection retain a timeout-length retry delay, separately from the
last detection timestamp.

No new CV/OCR logic or tracking-box detector is included. Already-active recovery,
gesture coordinates and timings are unchanged. A DJI tracking box does not refresh
the plus timer, so this change does not prevent the F121/F122 tracking-box case.

VersionCode 42. Debug APK compilation passed; device behaviour remains untested.

## v0.15.12.2 mandatory DJI full-screen requirement

“Require DJI Fly full-screen” is always On for every preset,
including existing saved settings. The DJI-specific guard applies when the
foreground target is DJI Fly; Gallery and other test targets remain usable.

Android versionCode is 41. Gesture coordinates, timings and detection thresholds
are unchanged. Source checks passed; APK compilation and device testing remain
unverified because the offline build could not resolve Android Gradle plugin 8.7.3.

## v0.15.12.1 gimbal gesture lane

Both the isolated gimbal test and production gimbal recovery now start their
continuous hold/down-drag at normalized `X=0.791`, the fixed DJI gimbal lane,
instead of the previous centre-screen `X=0.50`. This avoids placing the initial
long press on central landing/RTH controls. The existing `Y=0.45` to `Y=0.79`
path, two-second hold, six-second drag, six-second rest, and two-frame red-limit
confirmation are unchanged.

Gimbal CV now processes only red candidates around the fixed bottom-limit
region. The unused green/white knob classifications and full-height blue
search-strip annotation have been removed. Evidence keeps the red bottom-limit
region, any detected red knob, and the cyan requested gesture path. No
landing/RTH CV or landing lock is included.

## v0.15.12 recovery state and evidence correction

Production gimbal recovery starts disarmed on every Start. It arms only after a
real tap successfully completes on a validated green `+` selected through pink
association. This prevents the no-target timer from moving the gimbal during
initial setup or before the first acquisition.

Post-cycle recovery is now authorized while the combined coordinator is in
`S11_WAITING_FOR_NEXT_VALIDATED_PLUS`; the obsolete phase guard no longer
cancels that gesture. Command, dispatched, completed, failed and rejected
outcomes have distinct stage names. A rejected guard/map/dispatch clears the
reserved timer, so `S16` can only mean a real dispatched gesture is resting.

This version turns the tested GDL logic into reusable, configurable pipeline stages. Select a preset, adjust individual settings if needed, then press **Start**. Start validates and saves the displayed configuration before arming; the separate Apply Settings button has been removed.

The detailed `CURRENT FLOW` text beneath Start/Stop updates with the displayed combination and explains its guards, capture rate, enabled detectors, candidate selection, action, evidence policy and timing. Invalid combinations are identified before Start is pressed.

## Presets

### Pink detector test

- Foreground: Samsung Gallery video viewer.
- No green-plus detection and no tap.
- Saves every captured frame at 1 FPS.
- Searches the same central DJI camera area as the green-plus detector; the left and right rails are ignored.
- Every pink blob is outlined in yellow and labelled with its pink-pixel count.
- The strongest blob above the configurable minimum is green and labelled `SELECTED`.

Use a video containing yourself in the fluorescent-pink rashguard plus one or more smaller pink objects. The evidence images directly show whether GDL consistently selects you.

### Pink-to-+ association test

- Foreground: Samsung Gallery video viewer.
- Play a DJI Fly screen recording containing candidate `+` icons and pink.
- Detects all accepted pluses and scores the pink around each one.
- Marks the strongest associated plus with a simulated blue tap; no real touch is sent.
- Saves every annotated frame with each plus's pink count and weighted score.

### DJI reacquire (real tap)

- Foreground: DJI Fly only.
- Uses the successful v0.12.3 timing: 400 ms capture interval, 120 ms tap and 3-second cooldown.
- Detects validated pluses, ranks them by nearby pink, and immediately taps the strongest association from one frame.
- Minimum default: 8 associated pink pixels.
- Tap duration: 120 ms. Fresh search after a 3-second cooldown.
- Saves selected/tap evidence frames only.
- Requires DJI Fly to occupy the display with Android navigation controls hidden. A failed guard displays a yellow explanation and blocks capture and tapping.
- Optional production gimbal recovery can be enabled for this standalone preset.

The existing production preset continues to use pink association by default. For a one-surfer test, select `Validated + — no pink required` and turn off fluorescent-pink detection. GDL then taps the structurally strongest validated `+`; mandatory X rejection remains active.

### Raw DJI diagnostic

- Foreground: DJI Fly only.
- No detection and no tap.
- Saves every raw screenshot and a matching green threshold mask.

## Configurable Re-acquire stages

- Foreground app guard
- Require DJI Fly full-screen guard
- Green-plus detector enabled/disabled
- Pink detector enabled/disabled
- Candidate rule: strongest pink blob or strongest pink-associated plus
- Optional candidate rule: validated plus with no pink required
- Action: off, detection only, simulated tap, or real tap
- Save policy: none, decisions only, or every frame
- Re-acquire search rate (FPS; applies only while searching for +/pink)
- Retry cooldown
- Tap duration
- Minimum pink pixels
- Pink search radius
- Optional green mask

The DJI full-screen guard requires DJI Fly to be the foreground app and the
Android navigation bar to be hidden. It intentionally does not compare DJI's
accessibility-node bounds with the physical display because DJI Fly can omit
its own side control rails from those bounds even while genuinely full-screen.

## Select ActiveTrack

v0.15.0 added an independent, non-touching ActiveTrack panel test. Select the
`ActiveTrack panel test` preset and play a DJI Fly screen recording in Samsung
Gallery. Re-acquire is Off; the ActiveTrack action is `Simulated tap marker`.

The detector classifies each frame as:

- `EXPANDED PANEL`: green outline and blue marker over the proposed ActiveTrack control;
- `ACTIVETRACK ALREADY SELECTED`: no proposed tap;
- `ACTIVETRACK RUNNING`: the combined Manual/Parallel upper panel, yellow ActiveTrack lower-left control and word-shaped red Stop control are present; no tap;
- `COLLAPSED PANEL`: green outline and blue marker over the proposed upward chevron, with the large red X outlined as an exclusion;
- `ACTIVETRACK CONTROLS NOT FOUND`: no proposed tap.

Evidence files use the same frame/stage naming convention documented below.
v0.15.1 adds **Real Accessibility tap** plus the separate
**DJI ActiveTrack (real tap)** preset. That preset requires foreground DJI Fly,
requires the full-screen guard, keeps Re-acquire Off, and uses the panel states
validated in v0.15.0:

- expanded Spotlight panel: tap the centre of the ActiveTrack section;
- collapsed panel: tap only the upward chevron, then reassess later frames;
- ActiveTrack already selected: no tap;
- unknown: no tap;
- a proposed point inside the detected red X exclusion: forcibly block it.

v0.15.3 keeps that standalone preset and adds configurable bounds: an 8-second
cycle timeout, at most two ActiveTrack-section taps and at most two chevron
taps. In v0.15.9 these stable control states capture at 1 FPS. Unknown waits for the next frame. Success,
timeout or a tap limit locks the standalone cycle until GDL is restarted.

The red control is never treated as a target. A square, diagonal white glyph
validates the collapsed X; a wide word-shaped glyph validates Stop. Both red
boxes are mandatory exclusions. The running state requires Stop together with
the dark Manual/Parallel panel and yellow ActiveTrack control.

The new **DJI reacquire + ActiveTrack (real tap)** preset is the only combined
flow. It reuses the unchanged validated Re-acquire pipeline, taps the strongest
pink-associated validated +, then starts the bounded ActiveTrack cycle. A
validated running screen ends all touches. GDL must first observe that the old
+ is absent; a later newly validated + is the only event that starts another
combined cycle.

In v0.15.12, production gimbal recovery defaults On for this combined preset but
remains disarmed until the first successfully tapped pink-associated green `+`.
After that acquisition, 15 seconds without another validated pink-associated
green `+` causes GDL to perform a
two-second hold plus six-second downward drag, waits six seconds for DJI's wheel
to disappear, and repeats until two red bottom-limit frames are confirmed.
Green `+` is deliberately ignored while this recovery sequence is active.
v0.15.12.1 preserves that flow but moves the gesture from centre-screen
`X=0.50` to the fixed gimbal-lane `X=0.791`.

v0.15.4 uses unobstructed frame 279 as the normal running reference. The
Manual/Parallel dark-ratio threshold is 0.30, while red Stop and yellow
ActiveTrack remain mandatory. The temporary obstacle-warning frame remains a
valid obstructed variation, not the baseline.

The **ActiveTrack panel test** now enables **Auto-rearm completed simulated
test** by default. After SUCCESS and a non-expanded state, a later expanded
Spotlight panel begins a new simulated cycle without restarting GDL. This does
not change either real-tap preset. Terminal evidence also freezes elapsed time
at SUCCESS, TIMEOUT or a tap limit.

v0.15.5 keeps validating the unchanged Re-acquire pipeline while the combined
flow awaits ActiveTrack. Except for a positively confirmed running panel, a
newly selected validated + immediately returns the coordinator to Re-acquire.
The + is revalidated and retried at the configured cooldown (800 ms by default
in the combined preset) without an attempt limit. The ActiveTrack timeout starts
only after the + is absent. ActiveTrack-section and chevron tap limits remain
separate and unchanged.

Every Start creates a new timestamped preset folder and writes
`F00000_S00_SELECTED_SETTINGS.txt`; if Android refuses a text file in the
Pictures collection, GDL writes an equivalent settings JPEG. The global
**Hard-save every captured frame** switch saves a raw copy of every successful
screenshot independently of the selected preset and normal evidence policy.

Real taps are intentionally permitted only with the DJI Fly foreground guard. One shared camera-area rule is used by green-plus detection, standalone pink-blob detection and pink-to-plus association. It ignores only the left and right black control rails; the entire central camera view, including the top status row, remains searchable. This configuration assumes the map stays collapsed inside the left rail. X rejection remains a locked safety rule inside the plus detector. A hollow square and its centred plus may be separate green components; GDL pairs them before selection. The fallback square needs at least two of its four sides to have 30% or greater green coverage.

## Gimbal movement test (v0.15.12.1)

This is a separate DJI Fly-only preset. Re-acquire and ActiveTrack remain Off,
so their existing pipelines are not involved or changed.

1. Start the preset and switch to full-screen DJI Fly.
2. The separate ten-second countdown begins only after the foreground/full-screen
   guard passes. Losing the guard resets the countdown.
3. GDL starts on the fixed DJI gimbal lane at normalized `(0.791, 0.45)`, holds
   for two seconds and, without releasing, drags to normalized `Y=0.79` over
   six seconds.
4. Only the red bottom-limit knob is detected. The white dashed scale and
   normal white/green knob states are not processed.
5. After every drag, GDL waits six seconds so DJI's wheel disappears, rechecks
   the guard, and repeats the same gesture if the red limit is not confirmed.
6. Red is checked before gesture/interval states. Two consecutive red detections
   in the fixed bottom-limit box stop the test with no upward drag.
7. This preset captures at 1 FPS and supports hard-save.

The test saves stage-named frames in its timestamped `GIMBAL_TEST` folder.
Decision annotations show the fixed red-limit strip and camera-area hold/drag
line. Telemetry filenames use only the standalone altitude and distance metre
columns; m/s is excluded and ambiguous OCR is written as `UNKNOWN`. A wider
crop plus a second contrast-normalized OCR pass improves bright-background reads.
The normal DJI foreground and full-screen guards are rechecked immediately
before every automated hold or drag.

## Saved evidence

Each Start writes to `Pictures/GDL/<timestamp>_<preset>/`. Every filename begins
with the captured-frame number and canonical stage:

- `S00`: settings or raw diagnostic;
- `S01`: waiting for a validated +;
- `S02`: validated + selected;
- `S03`: + tap completed or retry cooldown;
- `S04`: ActiveTrack unknown/check wait;
- `S05`: expanded ActiveTrack panel;
- `S06`: collapsed chevron;
- `S07`: ActiveTrack already selected;
- `S08`: ActiveTrack running confirmed;
- `S09`: ActiveTrack timeout or control-tap limit;
- `S10`: terminal result while the existing + must clear;
- `S11`: waiting for the next validated pink-associated + after a completed cycle;
- `S12`: production gimbal-recovery gesture requested (not yet dispatched);
- `S13`: production gimbal-recovery gesture in progress;
- `S14`: production red bottom-limit confirmation;
- `S15`: production red bottom limit reached; resume green `+` search;
- `S16`: six-second interval after a genuinely dispatched production gesture;
- `S17`: production gesture completion/failure callback;
- `S18`: production gesture cancelled/rejected before dispatch;
- `S20`: standalone gimbal-test ten-second countdown;
- `S22`: standalone hold/down-drag command;
- `S23`: standalone red bottom-limit confirmation;
- `S24`: red bottom limit confirmed; no further movement;
- `S26`: gimbal gesture or mandatory six-second interval;
- `S90`: blocked/error safety state.

Altitude and distance are read asynchronously from DJI's two bottom-left metre
fields at 1 FPS and added to evidence filenames. Unknown readings never delay
detection or saving. Examples are
`F00012_S05_AT_EXPANDED_ALT_1p1m_DIST_0p7m_RAW.jpg` and
`F00012_S05_AT_EXPANDED_ALT_1p1m_DIST_0p7m_ANNOTATED.jpg`.

- Yellow box: detected but not selected.
- Green box and `SELECTED`: winning candidate.
- Pink test labels: blob number and pink-pixel count.
- Association test labels: plus number, associated pink count and weighted score.
- Blue circle: simulated or dispatched tap position shown by the overlay.

## Safety

The app always starts disarmed. Arming includes a three-second delay. Keep direct manual control of the Mini 3 Pro; this build does not press DJI's GO button or command aircraft movement.
