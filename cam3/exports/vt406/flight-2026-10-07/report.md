# VT 4.0.6 flight-log audit — 7 October 2026

Inputs:
- `C:/Users/gildo/temp/cam3_full_2026-10-07_080514_499_042b2a4f.jsonl`
- `C:/Users/gildo/temp/cam3_full_2026-10-07_090028_041_a2dcd7d5.jsonl`

Both headers report 4.0.6. There are 26,791 valid JSON records. The independent geometry, command and logging audit found zero failed assertions. This is an audit of recorded controller behaviour, not a claim that every flight condition was exercised.

## 08:05 flight

Actual configured settings: diagonal -45 degrees; filming distance 30 m; reapproach margin 5 m; movement cap 4 m/s; retreat threshold 19 m; retreat speed 2 m/s for 4 seconds; Come-to-me cooldown 5 seconds; planning allowance 2 m; excursion maximum 300 m, stop/planning radius 299 m. Therefore execution clearance is **19 m**, and planning clearance is **21 m**, not the earlier test's 25/27 m pair. The legacy extraPathClearanceMetres value is still 2 in saved JSON but correctly has zero runtime effect.

- 17 journeys planned: 14 direct and 3 around.
- 15 arrived; the recorded aircraft position at each arrival was 0.859–1.000 m from the saved endpoint, within the existing 1 m arrival tolerance.
- One journey was cancelled by retreat at 08:05:53.708.
- One journey was cancelled by the 300-second no-ride timer at 08:10:44.641. Automatic return then aligned, moved backward and completed at 08:11:18.670. The timer fired about 300 seconds after the first filming hold; it was not the separate movement-attempt timeout.
- Exactly one outcome is present for every planned journey. No duplicate outcomes or movement timeouts were recorded.
- All 23 planned legs, including around entry/exit legs, passed 21 m planning clearance, boundary and excursion checks. Minimum planned clearance was **21.0107 m**: approximately 2.01 m above the 19 m execution limit.
- 2,263 recorded nonzero approach cycles passed the independently calculated execution-clearance and boundary checks (2 cm numerical comparison tolerance). Captured S0, final destination and fix timestamp remained fixed within each journey.
- Five retreats completed their configured four-second periods. Recorded active commands stayed within the configured 2 m/s limit. Two new retreats began during Come-to-me cooldown; this is existing intended behaviour, since cooldown blocks approaches rather than renewed retreat.
- All diagonal rows have null projected-separation/alignment fields. Effective planning allowance and both distinct thresholds are logged correctly. All 2,322 cycles labelled as moving have cleared current recovery status/failed-segment fields.
- One unsuccessful boundary recovery attempt occurred on the first journey. Movement later continued on the same valid saved route, with current status cleared and historical recovery failure retained. No replacement route or outward escape was flown in these files.

## Observations

The existing boundary edge rule remains active. Initial reported position 0.87 cm beachward caused a short hold, then the first journey arrived.

After automatic return completed, the reported aircraft position was **1.477 m beachward of the original central boundary**, preventing a new positioning journey. `start_beachward_of_boundary` accounted for about 20 seconds of unpaused logged cycles across the flight, principally after return. Subsequent candidate destinations also failed the boundary check. This is the existing fixed-heading return/strict positioning-boundary interaction, not evidence that a positioning command crossed a permitted route boundary. The user previously elected to leave boundary handling unchanged.

Two short holds used `destination_inside_planning_clearance` when current distance was about 20.53 m and 20.11 m: the proposed alignment endpoint was inside 21 m. The controller held instead of moving outward to change the saved-distance policy. Both are consistent with the agreed wider-planning rule.

No logged control-loop exception, log overflow or new motion-limit violation was found. Replanning, outward escape, excursion-limit travel, 300-second journey expiry and both-direction recovery remain untested by this flight.

## 09:00 file

This contains 475 records over roughly five seconds, with no user Start, no planned journey and no active positioning commands. It confirms the version/log format but cannot validate another flight. Aircraft telemetry retry events are present; no flight-control conclusion should be drawn from this inactive capture.

## Evidence and limits

Supporting files: `results.json`, `routes.csv`, `blocks.csv`, `details.txt`. Geometric execution checks use the joined `aiming_cycle` aircraft snapshot. Final submission aircraft coordinates are not independently recorded, so this cannot establish continuous physical clearance between samples. No source or APK changes were made during this audit.
