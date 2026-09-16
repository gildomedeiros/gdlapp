#!/usr/bin/env python3
"""Convert lora_tester JSONL logs to Google Earth KML (Python 3.9+, no packages).

Usage: python lora_to_kml.py session.jsonl [-o session.kml]
Only derived_reading records are plotted; usb_chunk records are raw backups,
not additional GPS samples. Every valid GPS reading, including repeats, is kept.
"""

import argparse
import html
import json
import math
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

NS = "http://www.opengis.net/kml/2.2"
ET.register_namespace("", NS)


def element(parent, tag, text=None, **attributes):
    node = ET.SubElement(parent, f"{{{NS}}}{tag}", attributes)
    if text is not None:
        node.text = str(text)
    return node


def coordinates(point):
    return f"{point['longitude']:.8f},{point['latitude']:.8f},0"


def read_points(path):
    points, warnings = [], []
    boundary = True
    with path.open(encoding="utf-8-sig") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError("JSON record is not an object")
            except (ValueError, TypeError) as error:
                warnings.append(f"Line {number}: {error}")
                boundary = True
                continue
            if row.get("kind") in ("usb_connected", "usb_disconnected", "usb_error", "session_start"):
                boundary = True
            if row.get("kind") != "derived_reading":
                continue
            if row.get("classification") == "receiver_status":
                boundary = True
            if row.get("classification") != "gps":
                continue
            try:
                point = {key: float(row[key]) for key in ("latitude", "longitude", "rssi", "snr", "hdop")}
                if not all(math.isfinite(value) for value in point.values()):
                    raise ValueError("Non-finite GPS or radio value")
                if not -90 <= point["latitude"] <= 90 or not -180 <= point["longitude"] <= 180:
                    raise ValueError("Coordinates out of range")
                for key in ("sequence", "sender_ms", "satellites", "receiver_missed", "phone_epoch_ms"):
                    point[key] = int(row[key])
                point["time"] = datetime.fromtimestamp(point["phone_epoch_ms"] / 1000, timezone.utc)
                point["elapsed_ns"] = int(row["elapsed_realtime_ns"]) if "elapsed_realtime_ns" in row else None
                point["sequence_state"] = str(row.get("sequence_state", ""))
                point["boundary"] = boundary
                points.append(point)
                boundary = False
            except (KeyError, ValueError, TypeError, OverflowError, OSError) as error:
                warnings.append(f"Line {number}: skipping invalid GPS record: {error}")
                boundary = True
    return points, warnings


def interval(before, after):
    if before["elapsed_ns"] is not None and after["elapsed_ns"] is not None:
        return (after["elapsed_ns"] - before["elapsed_ns"]) / 1_000_000_000
    return (after["phone_epoch_ms"] - before["phone_epoch_ms"]) / 1000


def split_tracks(points, threshold):
    segments, breaks, current = [], [], []
    for point in points:
        if current:
            previous = current[-1]
            seconds = interval(previous, point)
            step = point["sequence"] - previous["sequence"]
            reason = None
            if point["boundary"]:
                reason = "USB/session boundary or invalid log record; radio loss cannot be inferred"
            elif step <= 0 or point["sender_ms"] < previous["sender_ms"]:
                reason = "Repeated/older sequence, possible restart or counter wrap"
            elif seconds < 0:
                reason = "Reception clock moved backwards"
            elif step > 1:
                reason = f"{step - 1} missing sequence number(s)"
            elif seconds > threshold:
                reason = "Long reception interval, with consecutive sequence numbers"
            if reason:
                segments.append(current)
                breaks.append((previous, point, seconds, reason))
                current = []
        current.append(point)
    if current:
        segments.append(current)
    return segments, breaks


def description(parent, fields):
    # ElementTree escapes the HTML for XML; Google Earth renders the description.
    element(parent, "description", "<br/>".join(
        f"<b>{html.escape(str(key))}:</b> {html.escape(str(value))}"
        for key, value in fields
    ))


def make_point(parent, point):
    node = element(parent, "Point")
    element(node, "altitudeMode", "clampToGround")
    element(node, "coordinates", coordinates(point))


def build_kml(points, source_name, threshold, offset):
    segments, breaks = split_tracks(points, threshold)
    local_zone = timezone(timedelta(hours=offset))

    def local_time(point):
        return point["time"].astimezone(local_zone).isoformat(timespec="milliseconds")

    low = min(p["rssi"] for p in points)
    high = max(p["rssi"] for p in points)
    root = ET.Element(f"{{{NS}}}kml")
    doc = element(root, "Document")
    element(doc, "name", source_name)
    description(doc, [
        ("GPS readings", len(points)),
        ("Colours", f"Relative RSSI: red {low:g} dBm to green {high:g} dBm; not pass/fail thresholds. Equal values use one colour."),
        ("Track breaks", f"Sequence gaps, repeats, resets, USB boundaries or intervals over {threshold:g} seconds."),
        ("Time", "Phone reception time, not GNSS fix time. KML timestamps are UTC."),
        ("Position", "Received GPS coordinates only. Altitude is unavailable; points are clamped to ground."),
    ])
    style = element(doc, "Style", id="track")
    line_style = element(style, "LineStyle")
    element(line_style, "color", "ffebc34a")
    element(line_style, "width", "3")

    readings = element(doc, "Folder")
    element(readings, "name", "GPS readings — coloured by RSSI")
    for point in points:
        placemark = element(readings, "Placemark")
        element(placemark, "name", f"#{point['sequence']} | {point['rssi']:g} dBm | SNR {point['snr']:g} dB")
        description(placemark, [
            ("Reception time", local_time(point)),
            ("Sequence", point["sequence"]),
            ("Latitude", point["latitude"]), ("Longitude", point["longitude"]),
            ("RSSI", f"{point['rssi']:g} dBm"), ("SNR", f"{point['snr']:g} dB"),
            ("Satellites", point["satellites"]), ("HDOP", point["hdop"]),
            ("Sender milliseconds", point["sender_ms"]),
            ("Receiver missing count", point["receiver_missed"]),
        ])
        stamp = element(placemark, "TimeStamp")
        element(stamp, "when", point["time"].isoformat(timespec="milliseconds").replace("+00:00", "Z"))
        fraction = (point["rssi"] - low) / (high - low) if high > low else 0.5
        red, green = round(235 - 205 * fraction), round(65 + 145 * fraction)
        style = element(placemark, "Style")
        icon = element(style, "IconStyle")
        element(icon, "color", f"ff41{green:02x}{red:02x}")  # KML: alpha, blue, green, red
        element(icon, "scale", "0.7")
        element(element(icon, "Icon"), "href", "https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png")
        element(element(style, "LabelStyle"), "scale", "0")
        data = element(placemark, "ExtendedData")
        for key in ("sequence", "sender_ms", "latitude", "longitude", "rssi", "snr", "satellites", "hdop", "receiver_missed"):
            element(element(data, "Data", name=key), "value", point[key])
        make_point(placemark, point)

    tracks = element(doc, "Folder")
    element(tracks, "name", "Received track segments — gaps left open")
    for index, segment in enumerate(segments, 1):
        if len(segment) < 2:
            continue  # Singleton readings remain visible in the points folder.
        placemark = element(tracks, "Placemark")
        element(placemark, "name", f"Segment {index}: #{segment[0]['sequence']} to #{segment[-1]['sequence']}")
        element(placemark, "styleUrl", "#track")
        line = element(placemark, "LineString")
        element(line, "tessellate", "1")
        element(line, "altitudeMode", "clampToGround")
        element(line, "coordinates", " ".join(coordinates(p) for p in segment))

    boundaries = element(doc, "Folder")
    element(boundaries, "name", "Track break endpoints")
    for before, after, seconds, reason in breaks:
        for label, point in (("Before", before), ("After", after)):
            placemark = element(boundaries, "Placemark")
            element(placemark, "name", f"{label} {seconds:.3f}s break")
            description(placemark, [
                ("Reason", reason), ("Last position", local_time(before)),
                ("Next position", local_time(after)), ("Interval", f"{seconds:.3f} seconds"),
                ("Note", "These are known endpoints. No route is inferred through the gap."),
            ])
            make_point(placemark, point)
    ET.indent(root, space="  ")
    return ET.ElementTree(root), len(breaks)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", type=Path, help="lora_tester .jsonl log")
    parser.add_argument("-o", "--output", type=Path, help="Output KML; default: beside input with .kml extension")
    parser.add_argument("--gap-seconds", type=float, default=1.5, help="Break track above this reception interval (default 1.5)")
    parser.add_argument("--utc-offset", type=float, default=10, help="Description time zone offset (default +10, Brisbane)")
    parser.add_argument("--overwrite", action="store_true", help="Replace an existing output file")
    args = parser.parse_args()
    output = args.output or args.input.with_suffix(".kml")
    if output.resolve() == args.input.resolve():
        parser.error("Output must differ from the source log")
    if not math.isfinite(args.gap_seconds) or args.gap_seconds <= 0:
        parser.error("--gap-seconds must be positive and finite")
    if not math.isfinite(args.utc_offset) or not -24 < args.utc_offset < 24:
        parser.error("--utc-offset must be between -24 and +24")
    try:
        points, warnings = read_points(args.input)
        for warning in warnings:
            print(f"Warning: {warning}", file=sys.stderr)
        if not points:
            raise ValueError("No valid GPS readings found; NOFIX/RAW packets cannot be mapped")
        tree, breaks = build_kml(points, args.input.stem, args.gap_seconds, args.utc_offset)
        # Exclusive creation prevents accidental replacement unless explicitly requested.
        with output.open("wb" if args.overwrite else "xb") as target:
            tree.write(target, encoding="utf-8", xml_declaration=True)
        print(f"Saved: {output.resolve()}")
        print(f"GPS points: {len(points)} | Track breaks: {breaks} | Invalid records skipped: {len(warnings)}")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
