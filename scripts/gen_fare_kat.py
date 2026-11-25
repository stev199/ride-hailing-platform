#!/usr/bin/env python3
"""Generate fare KAT vectors (P4-1/P4-3 parity).

Independent implementation of FareMath (backend Kotlin) in Python:
IEEE-754 doubles are identical across JVM/ART/CPython, so totals must match
bit-for-bit on every platform (rounding boundaries deliberately avoided; the
generator refuses vectors within 1e-9 of a .005 rounding boundary).

Output: backend/src/test/resources/fare_kat.json (android copy lands with P4-3;
both test suites pin the SHA-256 of this file).
"""
import hashlib
import json
import math
import pathlib

R = 6371008.8


def round_money(x: float) -> float:
    return math.floor(x * 100.0 + 0.5) / 100.0


def haversine(lat1, lon1, lat2, lon2):
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def surge_for_hour(p, hour):
    h = hour % 24

    def covers(start, end):
        if start == end:
            return False
        return start <= h < end if start < end else (h >= start or h < end)

    if p["nightMultiplier"] > 1.0 and covers(p["nightStartHourUtc"], p["nightEndHourUtc"]):
        return p["nightMultiplier"], "night"
    if p["peakMultiplier"] > 1.0 and covers(p["peakStartHourUtc"], p["peakEndHourUtc"]):
        return p["peakMultiplier"], "peak"
    return 1.0, None


def calculate(p, dist_m, dur_s, surge):
    distance_cost = (dist_m / 1000.0) * p["perKm"]
    time_cost = (dur_s / 60.0) * p["perMinute"]
    subtotal = p["base"] + (distance_cost + time_cost) * surge
    minimum_applied = subtotal < p["minimumFare"]
    after_minimum = p["minimumFare"] if minimum_applied else subtotal
    total = round_money(after_minimum + p["bookingFee"])
    return {
        "base": round_money(p["base"]),
        "distanceCost": round_money(distance_cost),
        "timeCost": round_money(time_cost),
        "surgeMultiplier": surge,
        "minimumApplied": minimum_applied,
        "bookingFee": round_money(p["bookingFee"]),
        "total": total,
    }


def assert_safe(*values):
    for v in values:
        frac = abs(v * 100.0 - math.floor(v * 100.0) - 0.5)
        assert frac > 1e-9, f"vector value {v} sits on a rounding boundary - pick another input"


def estimate_range(p, dist_m, dur_s, max_surge):
    low = calculate(p, dist_m, dur_s, 1.0)
    high = calculate(p, dist_m, dur_s, max_surge) if max_surge > 1.0 else low
    return low, high


def make_vector(name, fares, dist_m, dur_s, hour, note):
    p = fares
    surge, reason = surge_for_hour(p, hour)
    max_surge = max(surge, p["nightMultiplier"], p["peakMultiplier"])
    low, high = estimate_range(p, dist_m, dur_s, max_surge)
    assert_safe(low["total"], high["total"], dist_m, dur_s)
    return {
        "name": name,
        "note": note,
        "fares": p,
        "route": {"distanceMeters": dist_m, "durationSeconds": dur_s},
        "hourUtc": hour,
        "expect": {
            "surgeMultiplier": surge,
            "surgeReason": reason,
            "maxSurge": max_surge,
            "low": low,
            "high": high,
        },
    }


BASE = {
    "base": 2.5, "perKm": 1.8, "perMinute": 0.45, "minimumFare": 8.0,
    "bookingFee": 1.5, "cancelFee": 3.0, "waitFeePerMinute": 0.5,
    "nightMultiplier": 1.0, "nightStartHourUtc": 0, "nightEndHourUtc": 0,
    "peakMultiplier": 1.0, "peakStartHourUtc": 0, "peakEndHourUtc": 0,
}
NIGHT = {**BASE, "nightMultiplier": 1.25, "nightStartHourUtc": 22, "nightEndHourUtc": 6}
PEAK = {**BASE, "peakMultiplier": 1.4, "peakStartHourUtc": 8, "peakEndHourUtc": 10}
BOTH = {**BASE, "nightMultiplier": 1.25, "nightStartHourUtc": 22, "nightEndHourUtc": 6,
        "peakMultiplier": 1.4, "peakStartHourUtc": 23, "peakEndHourUtc": 5}
CHEAP = {**BASE, "base": 1.0, "perKm": 0.4, "perMinute": 0.1, "minimumFare": 9.75, "bookingFee": 0.35}

vectors = [
    make_vector("standard_no_surge", BASE, 5234.5, 720.0, 12,
                "plain urban trip, no surge windows configured"),
    make_vector("minimum_fare_applied", CHEAP, 800.0, 180.0, 12,
                "subtotal below minimum -> minimum wins before booking fee"),
    make_vector("night_window_active", NIGHT, 9000.0, 1500.0, 23,
                "night multiplier 1.25 inside 22..6"),
    make_vector("night_window_wraps_midnight", NIGHT, 6000.0, 900.0, 3,
                "hour 3 inside 22..6 wrap-around"),
    make_vector("peak_window_active", PEAK, 4200.0, 660.0, 9,
                "peak multiplier 1.4 inside 8..10"),
    make_vector("peak_excludes_boundary", PEAK, 4200.0, 660.0, 10,
                "window is [8,10) - hour 10 is OFF-peak"),
    make_vector("night_beats_peak_on_overlap", BOTH, 7000.0, 1100.0, 2,
                "both windows cover hour 2 -> night reason wins"),
    make_vector("same_point_zero_distance", BASE, 0.0, 0.0, 12,
                "identical coords -> base only, minimum applies"),
    make_vector("rounding_half_up", {**BASE, "bookingFee": 0.37}, 3511.0, 470.0, 12,
                "fractional booking fee exercises floor(x*100+0.5)"),
    make_vector("surge_causes_minimum_release", CHEAP, 12000.0, 1800.0, 3,
                "no-surge subtotal below minimum, surged subtotal above it"),
]

# Straight-line fallback vector: haversine(Monaco) x detour, duration from speed.
detour = 1.3
speed = 22.0
hav = haversine(43.7384, 7.4246, 43.7543, 7.4312)
meters = hav * detour
seconds = meters / 1000.0 / speed * 3600.0
vectors.append(make_vector("straight_line_fallback", BASE, meters, seconds, 12,
                           "offline fallback: haversine x detourFactor, duration from assumed speed"))

doc = {"generator": "scripts/gen_fare_kat.py (independent Python implementation of FareMath)",
       "rounding": "floor(x*100+0.5)/100", "haversineRadiusMeters": R, "vectors": vectors}

out = pathlib.Path(__file__).resolve().parents[1] / "backend/src/test/resources/fare_kat.json"
out.write_text(json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
digest = hashlib.sha256(out.read_bytes()).hexdigest()
print(f"wrote {out} ({len(vectors)} vectors) sha256={digest}")
