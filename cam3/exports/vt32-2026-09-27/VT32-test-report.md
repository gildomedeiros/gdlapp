# VT 3.2 test analysis — 27 September 2026

Source: C:/Users/gildo/temp/3.2.zip. All times are Australia/Brisbane (UTC+10). Six log files contain three active runs; the other three contain no active aiming. Distances are GPS-derived horizontal distances. Commanded speed and measured aircraft speed are separate. Ride flags are software detections, not visual confirmation of wave rides.

## Findings

- All three active runs used 10 s qualification, 2 m/s maximum movement, 0.25 m/s² acceleration, 10 m slowdown and 1 m completion tolerance.
- 38 approaches started: 35 completed within tolerance, 3 interrupted by fast ride detection. Both returns completed within tolerance.
- All 37 completed navigation legs released navigation yaw to normal surfer aiming on their completion cycle. That does not imply the camera was already centered.
- Completed travel residuals were 0.825–0.996 m. The interval from the first sample at <=1.56 m remaining to completion was 1.158–4.202 s, much shorter than the earlier 3.1 example (18.8 s to cover the entire final 1.56 m). These are different runs, not a controlled comparison.
- Run 3 had 13 immediate re-approaches (101–105 ms after approach completion), run 2 had 3. Run 1 had two immediate approaches after return completion. Qualification is not a mandatory new dwell after every arrival.
- No PAUSED aiming cycles. One active packet gap exceeded 3 s, in run 1; saved approach navigation continued.
- The 249 m excursion stop was not exercised: maximum observed central-to-drone distance across these runs was 114.9 m.

## Settings and results

| Run / active period | Film / band / re-approach margin | Ride start/end; low-speed end; no-ride | Approaches complete/started | Returns complete | Median/max accepted packet gap |
|---|---|---|---|---|---|
| 1: 12:17:00.927–12:25:02.344 | 30 / 60 / 10 m | 10/5 km/h; 20 s; 10 min | 6/7 | 2 | 500 / 3499 ms |
| 2: 12:28:57.425–12:35:45.204 | 30 / 60 / 10 m | 18/5 km/h; 20 s; 10 min | 8/8 | 0 | 500 / 2024 ms |
| 3: 12:37:46.746–12:52:17.453 | 20 / 60 / 5 m | 18/5 km/h; 20 s; 15 min | 21/23 | 0 | 500 / 2500 ms |

## Remaining framing and navigation concerns

1. **Saved target versus current surfer position:** completing a saved approach does not guarantee current filming separation. Run 3 first approach ended at 12:38:57.551 with only 3.68 m horizontal separation despite a 20 m setting. During that approach it reached 0.54 m at 12:38:54.286. This is horizontal GPS separation only; the log analysis does not establish vertical clearance or physical contact. The movement planner continued its saved plan as designed.
2. **Repeated re-approach takes yaw back:** run 3 used a 25 m restart threshold (20+5). For example at 12:41:12.705 an approach completed with 56.41 m current separation, and another began at 12:41:12.808. Normal aiming had one cycle before navigation owned yaw again. This is existing threshold behaviour, not a stale-GPS pause.
3. **Alignment still takes time:** first approach alignment lasted 17.08, 18.83 and 18.12 s in runs 1–3 respectively, after qualification. The 10 s qualification does not include turning or travel.
4. **Return projected travel is not a central radius:** the two returns finished at 5.30 and 8.15 m actual GPS distance from central. Along-track residual was about 0.94 m each. Lateral drift is not corrected by the saved-heading/projection completion rule.
5. **Run 1 RTH release:** VT stopped at 12:25:02.403 for returning_home, then reported RELEASE_UNCONFIRMED at 12:25:07.419. This means release was not confirmed, not proof VT continued sending movement. Other runs ended with screen_closed.

## Software ride detections

| Run | Fast detection | Five-second-window confirmation | Detector ended | Peak fast speed |
|---|---|---|---|---|
| 1 | 12:20:36.777 | 12:20:39.730 | 12:21:22.760 | 13.9 km/h |
| 1 | 12:23:07.788 | 12:23:09.740 | 12:23:39.752 | 20.0 km/h |
| 1 | 12:24:54.253 | Not confirmed | Stopped before detector end | 10.6 km/h |
| 3 | 12:45:35.361 | Not confirmed | 12:46:40.345 | 24.0 km/h |
| 3 | 12:50:33.369 | Not confirmed | 12:51:34.849 | 20.9 km/h |

Run 2: no ride detections. The detector end includes the configured low-speed dwell, so it is not the physical end of a wave. Run 3 fast-only detections interrupted approaches but did not authorize a ride-end return.

## Full navigation timeline

Alignment time means first nonzero submitted movement minus approach/return entry. Travel time excludes initial alignment. End distances refer to current surfer GPS, not the saved target.

### Run 1

Log: `3.2/cam3_full_2026-09-27_121551_578_c7cbaab0.jsonl`

| Leg | Start | First movement | End | Align / moving elapsed | Planned travel | Outcome / remaining | Current surfer distance at end | Peak command / measured |
|---|---|---|---|---|---|---|---|
| 1 APPROACHING | 12:17:10.975 | 12:17:28.056 | 12:18:01.907 | 17.08 / 33.85 s | 46.18 m | approach_arrival_tolerance; 0.92 m | 20.64 m | 2.00 / 2.06 m/s |
| 2 APPROACHING | 12:18:41.756 | 12:18:44.176 | 12:18:58.447 | 2.42 / 14.27 s | 10.31 m | approach_arrival_tolerance; 0.98 m | 48.98 m | 1.41 / 1.48 m/s |
| 3 APPROACHING | 12:19:03.191 | 12:19:04.243 | 12:19:22.765 | 1.05 / 18.52 s | 18.80 m | approach_arrival_tolerance; 0.83 m | 33.20 m | 2.00 / 2.06 m/s |
| 4 APPROACHING | 12:19:40.727 | 12:19:45.346 | 12:19:58.484 | 4.62 / 13.14 s | 10.69 m | approach_arrival_tolerance; 0.84 m | 45.13 m | 1.45 / 1.40 m/s |
| 5 APPROACHING | 12:19:59.853 | 12:20:11.135 | 12:20:28.045 | 11.28 / 16.91 s | 16.32 m | approach_arrival_tolerance; 0.94 m | 39.10 m | 1.98 / 1.92 m/s |
| 6 RETURNING | 12:21:22.760 | 12:21:33.261 | 12:22:01.967 | 10.50 / 28.71 s | 38.20 m | return_arrival_tolerance; 0.94 m | 108.18 m | 2.00 / 2.02 m/s |
| 7 APPROACHING | 12:22:02.068 | 12:22:11.701 | 12:23:04.002 | 9.63 / 52.30 s | 78.18 m | approach_arrival_tolerance; 0.98 m | 32.36 m | 2.00 / 2.01 m/s |
| 8 RETURNING | 12:23:39.752 | 12:23:54.721 | 12:24:42.880 | 14.97 / 48.16 s | 75.44 m | return_arrival_tolerance; 0.94 m | 113.98 m | 2.00 / 2.10 m/s |
| 9 APPROACHING | 12:24:42.984 | 12:24:54.151 | 12:24:54.253 | 11.17 / 0.10 s | 83.98 m | ride_detected; plan cleared | 100.03 m | 0.03 / 0.30 m/s |

Fresh relative gimbal yaw range: -8.5° to 3.3°. This alone cannot diagnose manual panning or camera centering.

### Run 2

Log: `3.2/cam3_full_2026-09-27_122657_424_9b8bc2b4.jsonl`

| Leg | Start | First movement | End | Align / moving elapsed | Planned travel | Outcome / remaining | Current surfer distance at end | Peak command / measured |
|---|---|---|---|---|---|---|---|
| 1 APPROACHING | 12:29:07.471 | 12:29:26.302 | 12:30:07.342 | 18.83 / 41.04 s | 47.12 m | approach_arrival_tolerance; 0.96 m | 27.45 m | 2.00 / 1.97 m/s |
| 2 APPROACHING | 12:30:50.785 | 12:30:50.785 | 12:31:04.546 | 0.00 / 13.76 s | 11.92 m | approach_arrival_tolerance; 0.97 m | 47.43 m | 1.52 / 1.48 m/s |
| 3 APPROACHING | 12:31:04.651 | 12:31:10.283 | 12:31:28.149 | 5.63 / 17.87 s | 17.43 m | approach_arrival_tolerance; 0.87 m | 31.36 m | 2.00 / 1.97 m/s |
| 4 APPROACHING | 12:31:49.818 | 12:31:55.917 | 12:32:08.500 | 6.10 / 12.58 s | 10.22 m | approach_arrival_tolerance; 0.96 m | 42.32 m | 1.44 / 1.41 m/s |
| 5 APPROACHING | 12:32:08.614 | 12:32:19.495 | 12:32:35.206 | 10.88 / 15.71 s | 12.32 m | approach_arrival_tolerance; 0.92 m | 49.60 m | 1.61 / 1.42 m/s |
| 6 APPROACHING | 12:32:36.363 | 12:32:45.691 | 12:33:07.692 | 9.33 / 22.00 s | 21.17 m | approach_arrival_tolerance; 0.95 m | 44.17 m | 2.00 / 1.87 m/s |
| 7 APPROACHING | 12:33:07.796 | 12:33:17.098 | 12:33:35.426 | 9.30 / 18.33 s | 14.17 m | approach_arrival_tolerance; 0.97 m | 59.60 m | 1.76 / 1.73 m/s |
| 8 APPROACHING | 12:33:39.816 | 12:33:45.913 | 12:34:15.226 | 6.10 / 29.31 s | 35.12 m | approach_arrival_tolerance; 0.90 m | 38.52 m | 2.00 / 1.98 m/s |

Fresh relative gimbal yaw range: -5.8° to 4.8°. This alone cannot diagnose manual panning or camera centering.

### Run 3

Log: `3.2/cam3_full_2026-09-27_123633_969_59c8bfcd.jsonl`

| Leg | Start | First movement | End | Align / moving elapsed | Planned travel | Outcome / remaining | Current surfer distance at end | Peak command / measured |
|---|---|---|---|---|---|---|---|
| 1 APPROACHING | 12:37:56.844 | 12:38:14.959 | 12:38:57.551 | 18.11 / 42.59 s | 60.37 m | approach_arrival_tolerance; 0.96 m | 3.68 m | 2.00 / 2.02 m/s |
| 2 APPROACHING | 12:39:11.289 | 12:39:23.819 | 12:39:33.150 | 12.53 / 9.33 s | 5.25 m | approach_arrival_tolerance; 0.96 m | 50.22 m | 0.80 / 0.78 m/s |
| 3 APPROACHING | 12:39:33.253 | 12:39:40.604 | 12:40:07.883 | 7.35 / 27.28 s | 30.22 m | approach_arrival_tolerance; 0.93 m | 23.33 m | 2.00 / 1.98 m/s |
| 4 APPROACHING | 12:40:18.851 | 12:40:27.226 | 12:40:37.183 | 8.38 / 9.96 s | 5.25 m | approach_arrival_tolerance; 0.91 m | 34.50 m | 0.92 / 0.80 m/s |
| 5 APPROACHING | 12:40:43.886 | 12:40:51.293 | 12:41:12.705 | 7.41 / 21.41 s | 23.27 m | approach_arrival_tolerance; 0.98 m | 56.41 m | 2.00 / 2.02 m/s |
| 6 APPROACHING | 12:41:12.808 | 12:41:24.225 | 12:41:51.995 | 11.42 / 27.77 s | 38.42 m | approach_arrival_tolerance; 0.83 m | 51.03 m | 2.00 / 2.00 m/s |
| 7 APPROACHING | 12:41:52.098 | 12:42:02.941 | 12:42:31.139 | 10.84 / 28.20 s | 31.03 m | approach_arrival_tolerance; 0.83 m | 41.48 m | 2.00 / 2.08 m/s |
| 8 APPROACHING | 12:42:31.242 | 12:42:39.420 | 12:43:01.521 | 8.18 / 22.10 s | 21.48 m | approach_arrival_tolerance; 0.99 m | 43.42 m | 2.00 / 1.93 m/s |
| 9 APPROACHING | 12:43:01.625 | 12:43:10.401 | 12:43:32.861 | 8.78 / 22.46 s | 23.42 m | approach_arrival_tolerance; 1.00 m | 53.42 m | 2.00 / 1.99 m/s |
| 10 APPROACHING | 12:43:32.964 | 12:43:44.310 | 12:44:12.516 | 11.35 / 28.21 s | 33.42 m | approach_arrival_tolerance; 0.94 m | 41.26 m | 2.00 / 2.00 m/s |
| 11 APPROACHING | 12:44:12.619 | 12:44:24.510 | 12:44:45.926 | 11.89 / 21.42 s | 21.26 m | approach_arrival_tolerance; 0.97 m | 33.28 m | 2.00 / 2.00 m/s |
| 12 APPROACHING | 12:44:54.367 | 12:44:55.322 | 12:45:15.664 | 0.95 / 20.34 s | 23.55 m | approach_arrival_tolerance; 0.97 m | 24.21 m | 2.00 / 2.02 m/s |
| 13 APPROACHING | 12:45:18.773 | 12:45:24.501 | 12:45:32.340 | 5.73 / 7.84 s | 5.21 m | approach_arrival_tolerance; 0.91 m | 28.72 m | 0.78 / 0.76 m/s |
| 14 APPROACHING | 12:45:32.445 | None | 12:45:35.361 | 2.92 / 0.00 s | 8.72 m | ride_detected; plan cleared | 34.65 m | 0.00 / 0.28 m/s |
| 15 APPROACHING | 12:46:50.426 | 12:46:55.749 | 12:47:15.709 | 5.32 / 19.96 s | 22.14 m | approach_arrival_tolerance; 0.98 m | 30.22 m | 2.00 / 2.10 m/s |
| 16 APPROACHING | 12:47:15.810 | 12:47:28.490 | 12:47:41.804 | 12.68 / 13.31 s | 10.22 m | approach_arrival_tolerance; 0.92 m | 8.82 m | 1.41 / 1.32 m/s |
| 17 APPROACHING | 12:48:10.853 | 12:48:10.853 | 12:48:21.701 | 0.00 / 10.85 s | 5.62 m | approach_arrival_tolerance; 0.83 m | 33.52 m | 0.92 / 0.91 m/s |
| 18 APPROACHING | 12:48:21.803 | 12:48:30.389 | 12:48:47.270 | 8.59 / 16.88 s | 13.52 m | approach_arrival_tolerance; 0.96 m | 29.17 m | 1.72 / 1.68 m/s |
| 19 APPROACHING | 12:48:47.372 | 12:48:54.951 | 12:49:07.234 | 7.58 / 12.28 s | 9.17 m | approach_arrival_tolerance; 0.99 m | 11.74 m | 1.28 / 1.25 m/s |
| 20 APPROACHING | 12:49:29.339 | 12:49:38.428 | 12:49:49.062 | 9.09 / 10.63 s | 5.76 m | approach_arrival_tolerance; 0.92 m | 48.91 m | 0.97 / 0.86 m/s |
| 21 APPROACHING | 12:49:49.163 | 12:49:56.329 | 12:50:19.346 | 7.17 / 23.02 s | 28.91 m | approach_arrival_tolerance; 0.96 m | 27.44 m | 2.00 / 1.98 m/s |
| 22 APPROACHING | 12:50:19.450 | 12:50:25.994 | 12:50:33.369 | 6.54 / 7.38 s | 7.44 m | ride_detected; plan cleared | 19.07 m | 1.09 / 1.06 m/s |
| 23 APPROACHING | 12:51:34.849 | 12:51:34.849 | 12:51:52.978 | 0.00 / 18.13 s | 18.36 m | approach_arrival_tolerance; 0.99 m | 19.99 m | 2.00 / 2.06 m/s |

Fresh relative gimbal yaw range: -6.9° to 6.5°. This alone cannot diagnose manual panning or camera centering.

## Scope

No code or settings changed. No video was supplied with this archive, so physical wave rides and image centering were not visually verified. Unrounded values and event detail are in analysis.json.