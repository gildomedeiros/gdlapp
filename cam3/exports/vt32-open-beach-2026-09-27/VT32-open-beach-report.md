# VT 3.2 open-beach test — 27 September 2026

Source: `3.2 open beach.zip`. All times Australia/Brisbane. Five logs contain three active VT sessions. No source code changed.

## Main result

Zero qualification wait and saved navigation are working. The largest Come to me blocker in runs 1 and 2 was the persistent ride-detected state. This is a software state, not evidence that a physical wave lasted that long.

## Actual settings

All runs: filming distance 20 m; re-approach margin 5 m (25 m threshold); lineup band 100 m total (±50 m); qualification 0 s; maximum movement command 2 m/s; arrival tolerance 1 m; ride start 18 km/h; ride end below 5 km/h for 20 continuous seconds; no-ride return timeout 20 minutes; ride-end return disabled.

## Run results

| Run | Active interval | Completed approaches | Other approaches | VT returns completed | Time blocked by ride state |
|---|---|---:|---|---:|---|
| 1 | 16:32:00.229–17:01:05.003 | 11 | None | 1 | 19.37 min (66.6%) |
| 2 | 17:05:00.149–17:28:00.661 | 13 | 1 interrupted by ride detection; 1 ongoing when screen closed | 0 | 14.44 min (62.8%) |
| 3 | 17:28:28.598–17:37:22.471 | 15 | 1 interrupted by DJI return-to-home | 0 | 0.00 min (0.0%) |

39 approaches completed, plus one completed VT return. All completed navigation ended with 0.83–1.00 m of saved projected travel remaining. Maximum submitted speed was 2 m/s; measured horizontal speed peaked around 2.11 m/s. Completion is based on saved travel, not live drone–surfer distance.

## Ride state that blocked new approaches

| Run | Ride state began | State ended | Elapsed | Confirmed by detector? |
|---|---|---|---|---|
| 1 | 16:38:55.396 | 16:59:14.989 | 20 min 19.6 s | No |
| 2 | 17:10:07.954 | 17:19:40.529 | 9 min 32.6 s | No |
| 2 | 17:23:00.066 | 17:27:54.089 | 4 min 54.0 s | 17:23:04.066 |

The first two fast detections never met the separate five-second confirmation criterion. Nevertheless, the fast ride flag continued to block Come to me until its end rule was met. Surfer aiming remained selected during ride waiting. Run 1’s ride-state interval includes 57.44 seconds of return navigation, which is excluded from its blocked-time percentage.

### Why the ride state persisted

The detector requires 20 continuous seconds of usable fast-speed evidence below 5 km/h. A reading at or above 5 km/h resets that timer. Insufficient speed evidence or stale GPS also clears accumulated slow time while preserving the ride flag. These counts are observed transitions from positive slow-time progress back to zero while the ride flag remained active:

| Reset cause | Run 1 | Run 2 |
|---|---:|---:|
| Speed at or above 5 km/h | 106 | 85 |
| Insufficient speed evidence | 37 | 17 |
| Stale GPS | 8 | 8 |

This does not establish whether individual speed spikes came from real movement or GPS error. It establishes why the software kept treating the surfer as riding.

## Return and handoff events

- **16:53:04.364:** run 1 started return to central because the 20-minute no-ride timer expired. It had started at 16:33:04.179 and had not been cancelled because no ride was confirmed. This was not a ride-end return.
- **16:54:01.804:** return finished with 0.927 m projected travel remaining; GPS distance to central was 5.40 m. Projected completion is not the same as radial distance to central.
- **16:59:14.989:** unconfirmed ride state ended; Come to me started immediately.
- **17:19:40.529:** run 2 unconfirmed ride state ended; Come to me started immediately.
- **17:23:04.066:** run 2 ride confirmed, cancelling its no-ride timer.
- **17:27:54.089:** confirmed ride ended; Come to me started immediately, with no ride-end return. That approach was still ongoing when the screen closed.

## GPS reception during active VT sessions

| Run | Accepted packets | Median packet interval | Longest interval | Intervals >3 s | Intervals ≤3 s | Active time with target age >3 s |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 2357 | 500.0 ms | 6.000 s | 33 | 98.6% | 33.58 s (1.9%) |
| 2 | 1775 | 500.0 ms | 15.005 s | 45 | 97.46% | 65.93 s (4.8%) |
| 3 | 896 | 500 ms | 11.001 s | 5 | 99.44% | 13.06 s (2.4%) |

Packet-interval percentages and time percentages use different denominators. These figures cover each whole active session, not a separately classified offshore subset. Time totals are integrated between approximately 100 ms log cycles.

Saved navigation continued through stale surfer GPS for approximately 1.95 s, 8.01 s and 3.98 s in runs 1–3. Waiting specifically for a fresh fix before a new approach totalled about 2.05 s, 12.52 s and 8.88 s. This was much smaller than ride-state blocking.

## Stops

Runs 1 and 3 stopped VT on DJI return-to-home at 17:01:05.009 and 17:37:22.574. Both later logged RELEASE_UNCONFIRMED: release confirmation was not obtained, which alone does not prove continued control commands. Run 2 stopped when the screen closed at 17:28:00.672.

## Conclusion

The next behavior to review is ride-state exit: removing ride-end return did not remove the ride flag’s block on Come to me. Zero qualification and wider bands cannot resolve that block. No changes have been made from this analysis.
