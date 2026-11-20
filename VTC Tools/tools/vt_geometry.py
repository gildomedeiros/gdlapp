"""Geographic helpers reused verbatim from VT Tools telemetry_around.py."""
import math

def travel_bearing(lat1, lon1, lat2, lon2):
    """Initial bearing from previous recorded coordinate to current coordinate."""
    a, b = math.radians(lat1), math.radians(lat2)
    delta = math.radians(lon2 - lon1)
    return math.degrees(math.atan2(math.sin(delta) * math.cos(b),
                                  math.cos(a) * math.sin(b) - math.sin(a) * math.cos(b) * math.cos(delta))) % 360


def offset_point(lat, lon, bearing, metres):
    a, o, h = map(math.radians, (lat, lon, bearing))
    d = metres / 6371000.0
    b = math.asin(math.sin(a) * math.cos(d) + math.cos(a) * math.sin(d) * math.cos(h))
    q = o + math.atan2(math.sin(h) * math.sin(d) * math.cos(a), math.cos(d) - math.sin(a) * math.sin(b))
    return math.degrees(b), (math.degrees(q) + 180) % 360 - 180


