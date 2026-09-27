# VT 3.1 field-log analysis — 27 September 2026

Source: C:/Users/gildo/temp/3.1.zip. Times are Australia/Brisbane (+10:00). The user reported the first run had an accidentally disconnected LoRa antenna. Packet gaps alone cannot establish the precise disconnect/reconnect moments.

## Overview

12 recordings contain 6 active VT runs. All identify version 3.1. There are 24 started approaches: 23 reached the saved projected-travel completion and one was still underway when the screen closed. Four returns started: three completed and one was still underway when the screen closed. No pause events, movement timeout outcomes, excursion-limit outcomes or log-overflow events were found. Physical arrival precision is not established by the completion label.

## Settings actually used while active

| Recording start | Filming m | Band total m | Re-approach margin m | Ride start/end km/h | Ride end s | No-ride min | Approaches completed/started | Returns completed/started |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| 09:03:37 | 30 | 20 | 15 | 17/5 | 20 | 1 | 3/3 | 2/3 |
| 09:22:07 | 30 | 45 | 15 | 17/5 | 20 | 5 | 3/3 | 0/0 |
| 09:30:20 | 30 | 60 | 15 | 17/5 | 20 | 10 | 2/2 | 0/0 |
| 09:36:07 | 30 | 60 | 10 | 17/5 | 20 | 10 | 5/6 | 1/1 |
| 09:47:12 | 30 | 60 | 10 | 17/5 | 20 | 10 | 7/7 | 0/0 |
| 09:57:31 | 30 | 60 | 10 | 10/5 | 20 | 10 | 3/3 | 0/0 |

## GPS outage and qualification

- First-run accepted packet gap: 09:07:42.692 to 09:12:35.256, 292.565 seconds; next gap 3.477 seconds.
- Qualification continued through the outage and reached qualified_waiting_fresh_gps. VT did not create a new approach from that stale target.
- After the first recording, median packet interval was 0.5 seconds; the largest was 1.496 seconds. No interval exceeded 3 seconds. This test does not reproduce the prior weak offshore reception pattern.
- Four saved-navigation/old-GPS samples occurred at 09:12:38.348–09:12:38.656, all RETURNING alignment with zero backward command. This supports the navigation exception being active during alignment, but does NOT validate moving translation through a long outage.

## Remaining issues / behavior to review

1. **No-ride return was deferred during stale waiting.** First hold was 09:06:50.850 with a 60-second no-ride setting. The expected deadline was about 09:07:50.850, but return began at 09:12:35.279 when GPS returned: approximately 4 min 44 s later. During the outage the logged inactivityElapsedMs stayed at 54827. This is a remaining gap in stale waiting/holding processing, not a qualification reset.
2. **Completed travel is not current filming separation.** Live surfer distances at approach completion ranged 7.5–76.8 m against a 30 m filming setting. Saved-heading movement and changes in surfer position can produce this; the logs alone do not distinguish all true surfer motion from tracker position error. GPS-derived lateral displacement from the saved path reached 12.2 m. Along-heading completion excess was only about 0–0.03 m in the logged calculation; that is not physical positioning accuracy.
3. **Immediate re-approaches are possible.** At 09:40:36.724 an approach completed with live separation 50.9 m; 0.102 s later another started because the threshold was 40 m and qualification was already complete. Similar sequences occur in later runs.
4. **Return can be followed immediately by approach.** At 09:46:42.623 return completed; at 09:46:42.725 approach started. Qualification had accumulated during return. This is current behavior, not a newly observed timeout or pause.
5. **Known control-release confirmation debt remains.** RTH stopped VT at 09:34:34.335 and 10:06:14.937. RELEASE_UNCONFIRMED followed about five seconds later and persisted in the recordings. This means release confirmation was missing; it is not proof that VT continued commanding the drone.

## Orientation telemetry

Aircraft and main-gimbal pitch/roll/yaw and gimbal-relative yaw are present. All orientation samples during AIMING report fresh gimbal attitude. The 09:36 recording had REQUEST_HANDLER_NOT_FOUND before active aiming; readings recovered. Relative gimbal yaw reached -6.2 to +5.2 degrees across active runs, so camera and aircraft orientation are not always identical. This does not by itself prove accidental camera panning or explain earlier footage.

## Ride detections (not visually verified waves)

| Fast detection | Confirmation | End / interruption |
|---|---|---|
| 09:15:38.834 | Not confirmed | Fast-only ended 09:17:41.776; no-ride return had already started |
| 09:43:35.938 | 09:43:39.423 | Ride ended 09:46:12.991; return followed |
| 10:05:02.014 | 10:05:05.096 | RTH at 10:06:14.937 before a logged ride end |

## All navigation attempts

### cam3_full_2026-09-27_090337_716_c8d79b97.jsonl

| Phase | Start | First translation command | End | Planned travel m | Outcome | Live separation at end m | Return central miss m |
|---|---|---|---|---:|---|---:|---:|
| APPROACHING | 09:05:20.934 | 09:05:31.271 | 09:06:50.850 | 61.9 | filming_distance_reached | 30.5 | — |
| RETURNING | 09:12:35.279 | 09:12:51.391 | 09:14:06.340 | 57.7 | return_travel_completed | 90.9 | 3.9 |
| APPROACHING | 09:14:06.441 | 09:14:09.928 | 09:15:32.984 | 60.9 | filming_distance_reached | 35.0 | 3.9 |
| RETURNING | 09:16:33.044 | 09:16:43.700 | 09:18:00.675 | 60.8 | return_travel_completed | 102.5 | 3.0 |
| APPROACHING | 09:18:01.796 | 09:18:11.929 | 09:19:50.870 | 72.6 | filming_distance_reached | 39.1 | 3.0 |
| RETURNING | 09:20:50.888 | 09:21:02.034 | 09:21:50.919 | 71.6 | recording ended or stopped | — | — |

### cam3_full_2026-09-27_092207_843_c16da0f5.jsonl

| Phase | Start | First translation command | End | Planned travel m | Outcome | Live separation at end m | Return central miss m |
|---|---|---|---|---:|---|---:|---:|
| APPROACHING | 09:23:58.055 | 09:24:05.661 | 09:25:50.861 | 64.9 | filming_distance_reached | 61.5 | — |
| APPROACHING | 09:26:05.877 | 09:26:12.393 | 09:27:17.146 | 46.9 | filming_distance_reached | 29.2 | — |
| APPROACHING | 09:28:14.970 | 09:28:14.970 | 09:28:55.712 | 23.1 | filming_distance_reached | 31.2 | — |

### cam3_full_2026-09-27_093020_150_4097e942.jsonl

| Phase | Start | First translation command | End | Planned travel m | Outcome | Live separation at end m | Return central miss m |
|---|---|---|---|---:|---|---:|---:|
| APPROACHING | 09:31:20.717 | 09:31:29.585 | 09:32:40.628 | 55.5 | filming_distance_reached | 27.9 | — |
| APPROACHING | 09:32:54.923 | 09:33:04.797 | 09:33:51.568 | 17.2 | filming_distance_reached | 43.5 | — |

### cam3_full_2026-09-27_093607_134_5c09d2e3.jsonl

| Phase | Start | First translation command | End | Planned travel m | Outcome | Live separation at end m | Return central miss m |
|---|---|---|---|---:|---|---:|---:|
| APPROACHING | 09:38:08.076 | 09:38:12.924 | 09:39:35.155 | 60.5 | filming_distance_reached | 7.5 | — |
| APPROACHING | 09:40:04.913 | 09:40:11.563 | 09:40:36.724 | 11.1 | filming_distance_reached | 50.9 | — |
| APPROACHING | 09:40:36.826 | 09:40:44.924 | 09:41:16.208 | 20.9 | filming_distance_reached | 33.8 | — |
| APPROACHING | 09:41:22.972 | 09:41:28.520 | 09:41:51.083 | 10.5 | filming_distance_reached | 46.1 | — |
| APPROACHING | 09:41:51.185 | 09:42:02.874 | 09:42:30.044 | 16.1 | filming_distance_reached | 33.2 | — |
| RETURNING | 09:46:12.991 | 09:46:27.739 | 09:46:42.623 | 2.9 | return_travel_completed | 86.1 | 3.0 |
| APPROACHING | 09:46:42.725 | 09:46:54.911 | 09:46:57.174 | 56.1 | recording ended or stopped | — | — |

### cam3_full_2026-09-27_094712_391_1048e214.jsonl

| Phase | Start | First translation command | End | Planned travel m | Outcome | Live separation at end m | Return central miss m |
|---|---|---|---|---:|---|---:|---:|
| APPROACHING | 09:48:43.317 | 09:48:52.374 | 09:50:09.555 | 58.2 | filming_distance_reached | 65.7 | — |
| APPROACHING | 09:50:17.043 | 09:50:37.283 | 09:51:35.288 | 42.1 | filming_distance_reached | 38.9 | — |
| APPROACHING | 09:51:44.944 | 09:51:51.105 | 09:52:10.668 | 10.6 | filming_distance_reached | 45.3 | — |
| APPROACHING | 09:52:10.770 | 09:52:21.444 | 09:52:49.651 | 15.3 | filming_distance_reached | 39.9 | — |
| APPROACHING | 09:52:49.958 | 09:52:58.886 | 09:53:19.414 | 10.7 | filming_distance_reached | 46.1 | — |
| APPROACHING | 09:53:19.517 | 09:53:30.183 | 09:54:06.100 | 16.1 | filming_distance_reached | 76.8 | — |
| APPROACHING | 09:54:21.068 | 09:54:27.127 | 09:55:50.211 | 58.7 | filming_distance_reached | 30.4 | — |

### cam3_full_2026-09-27_095731_541_87dc7310.jsonl

| Phase | Start | First translation command | End | Planned travel m | Outcome | Live separation at end m | Return central miss m |
|---|---|---|---|---:|---|---:|---:|
| APPROACHING | 09:59:23.402 | 09:59:36.061 | 10:00:54.714 | 58.7 | filming_distance_reached | 19.1 | — |
| APPROACHING | 10:03:13.072 | 10:03:18.808 | 10:03:50.649 | 11.9 | filming_distance_reached | 54.4 | — |
| APPROACHING | 10:03:50.751 | 10:03:59.367 | 10:04:39.382 | 24.4 | filming_distance_reached | 35.1 | — |
