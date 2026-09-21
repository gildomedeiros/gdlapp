# VT 2.9

## Approach and rotation
- Qualify for 60 seconds in the lineup band. Save drone origin, initial bearing and separation minus filming distance once.
- If already within filming distance plus 2 m, enter holding without translation.
- Align once; hold the saved heading while moving forward. Heading corrections remain active, but the 3-degree gate does not repeatedly stop an aligned approach.
- Measure net displacement projected along the initial heading. Lateral drift and accumulated GPS steps do not count as progress.
- Band exits do not interrupt approach or replace its plan. A detected ride immediately cancels approach and restores surfer aiming.
- Stale-data pauses preserve the plan, require existing session recovery and re-alignment, and retain the original movement deadline.
- Movement state is updated before choosing exactly one yaw purpose: approach, return or surfer. Return saves the initial bearing toward central and holds the opposite nose heading. Completion uses projected travel, as described below.

## Timer and ride evidence
- No-ride timer starts at the first filming arrival, not at activation or initial approach. It keeps running across band exits, waiting, subsequent approaches and subsequent arrivals.
- Ride start or return initiation cancels it. The next filming arrival after return starts a fresh countdown. Manual reposition or Stop clears the old plan and countdown.
- Reject consecutive-fix implied speed above 40 km/h from ride evidence; clear speed history and slow confirmation. Neither a missing fix nor a rejected jump ends a known ride. Rebuild a fresh five-second window.
- The filter does not alter target coordinates used by aiming or lineup monitoring.

## Configuration and observability
- Filming distance 10–200 m, default 70 m. Previously saved values below 10 m are raised to 10 m without resetting other settings.
- Existing 1.0 m/s speed, 0.25 m/s² acceleration, final 5 m slowdown, 200 m excursion limit and 5-minute attempt deadline remain.
- Movement logs include approach origin, heading, planned travel, projected progress, initial-alignment latch, yaw purpose, no-ride timer state and rejected speed jumps. The screen identifies approach-heading control.
- No physical arrival guarantee: GPS error, wind, manual intervention, freshness checks and deadlines can stop an attempt. A timed-out attempt holds; it does not automatically return.

## Deferred technical debt before release
- Review RTH control-release confirmation (`RELEASE_UNCONFIRMED`). No authority/release behaviour changed in this version.

## Validation
Run tools/test-aiming.ps1 and an offline sample:assembleDebug build. Device behaviour still requires a controlled flight test.

## VT 2.9 update — versionCode 12
- At each return trigger, capture current drone position, bearing to the unchanged central point, and planned backward travel = current central distance minus 3 m (minimum zero).
- Align the tail once within 3 degrees; hold that heading while moving backward. No continuous bearing recomputation or repeated 3-degree translation gate after alignment.
- Measure signed net displacement projected along the saved bearing toward central. Sideways drift does not count; moving away produces negative progress.
- Stop and latch WAITING when projected progress reaches planned travel. Resume surfer aiming. This completes planned travel, not a guarantee of being inside the actual 3 m circle.
- If initially within 3 m, finish without movement. A safety pause preserves origin, heading, progress target and timeout; re-align before resuming. Manual reposition/Stop clears the plan.
- Preserve 1.0 m/s maximum, acceleration and slowdown, freshness and authority checks, and the 5-minute attempt deadline.
- New movement log fields: returnStartLatitude/Longitude, returnBearingDeg, returnPlannedTravelM, backwardProgressM, returnAligned and returnCompletionCentralDistanceM. returnHeadingDeg remains the saved nose heading; centralDistanceM remains live.
- Completion reasons: already_near_central or return_travel_completed. Timeout remains movement_timeout. Completion distance is retained so GPS-reported miss distance can be analysed after return.

## VT 2.9 update — versionCode 13
- Double the shared approach/return maximum speed to 1.0 m/s (3.6 km/h). This is a code constant, not a screen setting.
- Keep 0.25 m/s² acceleration and the final 5 m slowdown. Initial acceleration to maximum now takes approximately 4 seconds.
- Active-translation telemetry allowance follows maximum speed plus 0.3 m/s (now 1.3 m/s). Start/recovery hover and vertical-speed checks are unchanged.
- Movement logs include maxMovementSpeedMps and movementAccelerationMps2 to distinguish this build's motion settings.
