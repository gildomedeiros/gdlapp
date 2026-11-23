"""FM Tools desktop launcher and HS replay log reader."""
import json
import math
import subprocess
import tempfile
from datetime import datetime
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Timer
import tkinter as tk
from tkinter import filedialog
import webbrowser

ROOT = Path(__file__).resolve().parent
SETTINGS = ROOT / "settings.json"
TOLERANCE = ROOT / "gap-tolerance.json"
DEFAULT_FOLDER = ROOT.parent / "FM" / "phone-logs"
PHONE_FOLDER = "Galaxy A54 5G > Internal storage > Download > FM > logs"
DASH = "—"


def read_events(path):
    with path.open(encoding="utf-8-sig", errors="replace") as file:
        for line in file:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if isinstance(event, dict):
                yield event


def latest_report(folder, aim=False):
    button = "Aim at Surfer HS" if aim else "Replay viewpoints (HS)"
    prefix = "aim_" if aim else "hs_"
    logs = sorted(
        (path for path in folder.glob("FM-*.jsonl") if path.is_file()),
        key=lambda path: (path.stat().st_mtime_ns, path.name), reverse=True,
    )
    for path in logs:
        events = read_events(path)
        try:
            for event in events:
                kind = event.get("event", "")
                if event.get("button") == button or str(kind).startswith(prefix):
                    events_in_log = [event, *events]
                    return build_aim_report(path, events_in_log) if aim else build_report(path, events_in_log)
                if kind == "run_start" and event.get("button"):
                    break
        finally:
            events.close()
    if logs:
        raise ValueError(f"No {button} log found.")
    raise ValueError("No FM JSONL logs found.")


def phone_report(aim=False):
    with tempfile.TemporaryDirectory(prefix="fm-tools-phone-") as directory:
        try:
            process = subprocess.run(
                ["powershell.exe", "-NoProfile", "-STA", "-File",
                 str(ROOT / "read_phone_logs.ps1"), directory, "Aim" if aim else "Replay"],
                capture_output=True, text=True, errors="replace", timeout=180,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
        except subprocess.TimeoutExpired as error:
            raise ValueError("Reading phone logs timed out. Check the USB connection and try again.") from error
        if process.returncode:
            message = process.stderr.strip().splitlines()
            raise ValueError(next(
                (line.strip() for line in message if line.startswith((
                    "Unlock the Galaxy", "Cannot find", "No FM JSONL", "No HS replay", "No Aim at Surfer HS", "The phone did not"
                ))),
                message[0] if message else "Could not read FM logs from the phone.",
            ))
        report = latest_report(Path(directory), aim)
        report["source"] = PHONE_FOLDER + " > " + Path(report["source"]).name
        return report


def unit(value, suffix):
    return DASH if value is None else f"{value:.2f} {suffix}"


def elapsed(value):
    return DASH if value is None else f"{value} ms"


def limit_label(value):
    return None if value is None else format(Decimal(str(value)).normalize(), "f") + " m"


def build_report(path, events):
    plans = {event["viewpoint"]: event for event in events if event.get("event") == "hs_viewpoint_plan"}
    by_type = {event.get("event"): event for event in events}
    wall_start = by_type.get("hs_measured_start", {}).get("wallTimeMs")
    summary = by_type.get("run_result", {}).get("display")
    limit = by_type.get("hs_replay_file", {}).get("distanceGapLimitM")
    rows = []
    for event in events:
        if event.get("event") != "hs_viewpoint_result":
            continue
        index, row, outcome = (event[key] for key in ("viewpoint", "row", "outcome"))
        plan = plans.get(index, {})
        interval = event.get("sincePreviousCheckpointMs")
        error = event.get("timingErrorMs")
        lead = DASH if error is None else "On time"
        if error:
            lead = elapsed(abs(error)) + (" early" if error < 0 else " late")
        observed_ms = event.get("observedMs")
        observed = DASH
        if observed_ms is not None:
            clock = DASH
            if wall_start is not None:
                clock = datetime.fromtimestamp((wall_start + observed_ms) / 1000).strftime("%H:%M:%S.%f")[:-3]
            observed = f"{clock}\n+{elapsed(observed_ms)}"
        limit = event.get("distanceGapLimitM", limit)
        over = event.get("distanceGapOverLimit") is True
        gap = unit(event.get("distanceGapM"), "m")
        mount_range = unit(plan.get("rangeM"), "m")
        target = unit(event.get("targetPaceKmh"), "km/h")
        matched = unit(event.get("matchedPaceKmh"), "km/h")
        expected = elapsed(event.get("expectedMs"))
        lat, lon, elev = (plan.get(key) for key in ("latitude", "longitude", "elevation"))
        location = DASH if None in (lat, lon, elev) else f"{lat:.8f}, {lon:.8f} · Elevation {elev:.2f} m"
        warning = f" · Over {limit_label(limit)}" if over and limit is not None else ""
        shared = " · Shared sample" if interval == 0 else ""
        required = unit(plan.get("requiredCountsPerSecond"), "counts/s")
        commanded = unit(event.get("commandedCountsPerSecond"), "counts/s")
        count = event.get("observedCount")
        details = "\n".join([
            f"Viewpoint {index} · CSV row {row} · {outcome}", location,
            f"Gap to target at due time · {gap}{warning}", f"Mount range · {mount_range}",
            f"Target pace · {target}", f"Matched pace · {matched}",
            f"CSV delay · {elapsed(event.get('delayMs'))}", f"Expected · {expected}",
            f"Observed · {observed}", f"Gap · {elapsed(interval)}{shared}", f"Lead / lag · {lead}",
            f"Required {required} · Commanded {commanded}",
            f"Target count {plan.get('targetCount', DASH)} · Observed {DASH if count is None else count}",
        ])
        rows.append({"index": index, "observed": observed_ms is not None, "overLimit": over, "gapM": event.get("distanceGapM"),
                     "cells": [f"{index} / {row}", gap, mount_range, target, matched, expected,
                               observed, elapsed(interval), lead, outcome], "details": details})
    if not summary:
        summary = "FM has not written the final report to this log."
        failure = by_type.get("failure", {}).get("reason")
        if failure:
            summary += "\n" + failure
    return {"source": str(path), "summary": summary, "limit": limit_label(limit), "rows": rows}


def build_aim_report(path, events):
    records = {event.get("event"): event for event in events}
    start = records.get("run_start", {})
    result = records.get("aim_result", {})
    publication = records.get("aim_publication", {})
    summary = result.get("display") or "FM has not written the final aim result to this log."
    plan = records.get("aim_pan_plan", {})
    target = records.get("aim_target", {})
    baseline = plan.get("baseline")
    final = result.get("finalCount")
    revolution = plan.get("countsPerRevolution") or target.get("panCpr")
    range_m = target.get("rangeM")
    displacement = None
    if None not in (baseline, final, range_m) and revolution and revolution > 0:
        delta = (final - baseline + 0x800000) % 0x1000000 - 0x800000
        displacement = 2 * range_m * abs(math.sin(math.pi * delta / revolution))
    first, separator, timing = summary.partition("\n\n")
    summary = f"{first}\nMount displacement at target range · {unit(displacement, 'm')}{separator}{timing}"
    press = start.get("pressMs")
    decision = result.get("stopDecisionMs")
    decision_offset = decision - press if decision is not None and press is not None else None
    decision_line = f"Button to Stop decision · {elapsed(decision_offset)}"
    summary = summary.replace("Decision to Stop send ·", decision_line + "\nDecision to Stop send ·", 1)
    wall_time = start.get("wallTimeMs")
    logged_at = start.get("elapsedMs")
    stages = []
    for event in events:
        if event.get("event") != "aim_stage":
            continue
        started = event.get("startedMs")
        offset = started - press if started is not None and press is not None else None
        timestamp = DASH
        if None not in (started, wall_time, logged_at):
            seconds, millis = divmod(wall_time + started - logged_at, 1000)
            timestamp = f"{datetime.fromtimestamp(seconds):%Y-%m-%d %H:%M:%S}.{millis:03d}"
        stages.append([
            event["stage"].replace("_", " ").capitalize(), timestamp,
            elapsed(offset), elapsed(event.get("durationMs")), event.get("failure") or DASH,
        ])
    return {"source": str(path), "summary": summary, "stages": stages,
            "publication": publication.get("buttonToResultMs")}


def configured_folder():
    try:
        folder = json.loads(SETTINGS.read_text(encoding="utf-8"))["folder"]
        if isinstance(folder, str):
            return folder
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return str(DEFAULT_FOLDER)


def configured_tolerance():
    try:
        return json.loads(TOLERANCE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def choose_folder(initial):
    root = tk.Tk()
    root.withdraw()
    try:
        root.attributes("-topmost", True)
        return filedialog.askdirectory(parent=root, initialdir=initial, mustexist=True,
                                       title="Choose the FM log folder")
    finally:
        root.destroy()


class Handler(BaseHTTPRequestHandler):
    def send_body(self, status, content, body):
        self.send_response(status)
        self.send_header("Content-Type", content)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, data, status=200):
        self.send_body(status, "application/json; charset=utf-8", json.dumps(data).encode("utf-8"))

    def do_GET(self):
        if self.headers.get("Host") != self.server.local_host:
            self.send_error(403)
        elif self.path == "/":
            self.send_body(200, "text/html; charset=utf-8", (ROOT / "report.html").read_bytes())
        elif self.path == "/settings":
            self.send_json({"folder": configured_folder(), "gapTolerance": configured_tolerance()})
        else:
            self.send_error(404)

    def do_POST(self):
        if (self.headers.get("Host") != self.server.local_host
                or self.headers.get("Origin") != "http://" + self.server.local_host
                or self.headers.get_content_type() != "application/json"):
            self.send_error(403)
            return
        try:
            size = int(self.headers.get("Content-Length", 0))
            if not 0 < size <= 65536:
                raise ValueError("Invalid request size.")
            payload = json.loads(self.rfile.read(size))
            if self.path == "/tolerance":
                value = payload["gapTolerance"]
                if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or value < 0):
                    raise ValueError("Enter a non-negative tolerance in metres.")
                temporary = TOLERANCE.with_suffix(".tmp")
                temporary.write_text(json.dumps(value), encoding="utf-8")
                temporary.replace(TOLERANCE)
                self.send_json({"gapTolerance": value})
                return
            value = payload["folder"]
            if not isinstance(value, str):
                raise ValueError("Enter a folder path.")
            folder = Path(value.strip()).expanduser()
            if self.path == "/browse":
                self.send_json({"folder": choose_folder(str(folder) if folder.is_dir() else str(ROOT))})
            elif self.path == "/report":
                phone = value == PHONE_FOLDER
                if not phone and (not value.strip() or not folder.is_absolute() or not folder.is_dir()):
                    raise ValueError("Choose an existing folder for the FM logs.")
                selection = PHONE_FOLDER if phone else str(folder)
                if selection != configured_folder():
                    temporary = SETTINGS.with_suffix(".tmp")
                    temporary.write_text(json.dumps({"folder": selection}, indent=2), encoding="utf-8")
                    temporary.replace(SETTINGS)
                aim = payload.get("report") == "aim"
                self.send_json(phone_report(aim) if phone else latest_report(folder, aim))
            else:
                self.send_error(404)
        except (OSError, ValueError, TypeError, KeyError, tk.TclError) as error:
            self.send_json({"error": str(error)}, 400)

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    with HTTPServer(("127.0.0.1", 0), Handler) as server:
        server.local_host = f"127.0.0.1:{server.server_port}"
        url = "http://" + server.local_host
        print(f"FM Tools is running at {url}\nClose this window to exit.", flush=True)
        Timer(0.15, webbrowser.open, args=(url,)).start()
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
