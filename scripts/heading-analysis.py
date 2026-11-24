#!/usr/bin/env python3
"""Estimate the north offset from walking GPS bearings and head yaw in a field log."""

import argparse
import csv
import io
import math
import sys
from collections import deque
from dataclasses import dataclass
from pathlib import Path


REQUIRED = {
    "elapsed_s", "state", "ar_tracking", "ar_yaw_deg",
    "gps_bearing_deg", "gps_bearing_acc_deg", "gps_speed_mps",
}
YAW_TO_CLOCKWISE = -1
MIN_SAMPLES = 20
RECENT_SAMPLES = 120


def signed(angle):
    return (angle + 180) % 360 - 180


def circular_stats(angles):
    x = sum(math.cos(math.radians(angle)) for angle in angles)
    y = sum(math.sin(math.radians(angle)) for angle in angles)
    length = min(1.0, max(1e-15, math.hypot(x, y) / len(angles)))
    mean = math.degrees(math.atan2(y, x)) % 360
    return mean, math.degrees(math.sqrt(-2 * math.log(length)))


def number(row, key):
    value = row.get(key)
    if not value:
        return None
    try:
        return float(value)
    except ValueError:
        raise ValueError(f"{key} must be numeric") from None


def finite(value):
    return value is not None and math.isfinite(value)


@dataclass(frozen=True)
class Row:
    time: float
    walking: bool
    tracking: bool
    yaw: float | None
    bearing: float | None
    bearing_accuracy: float | None
    speed: float | None

    @property
    def has_fix(self):
        return any(value is not None for value in (self.bearing, self.bearing_accuracy, self.speed))


def read_rows(source):
    reader = csv.DictReader(source)
    if missing := REQUIRED.difference(reader.fieldnames or ()):
        raise ValueError(f"missing field log columns: {', '.join(sorted(missing))}")
    previous = -math.inf
    for line, row in enumerate(reader, 2):
        try:
            time = number(row, "elapsed_s")
            if not finite(time) or time <= previous:
                raise ValueError("elapsed_s must be finite and strictly increasing")
            yield Row(time, row["state"] == "WALKING", row["ar_tracking"] == "TRACKING",
                      number(row, "ar_yaw_deg"), number(row, "gps_bearing_deg"),
                      number(row, "gps_bearing_acc_deg"), number(row, "gps_speed_mps"))
            previous = time
        except ValueError as error:
            raise ValueError(f"row {line}: {error}") from error


def tracked_segments(rows):
    segment = []
    for row in rows:
        # CSV rows are sampled once a second, unlike the app's head pose stream.
        if segment and (not row.tracking or row.time - segment[-1].time > 1.5):
            yield segment
            segment = []
        if row.tracking:
            segment.append(row)
    if segment:
        yield segment


def collect_samples(rows):
    bearings = deque()
    offsets = deque(maxlen=RECENT_SAMPLES)
    for row in rows:
        if not row.has_fix:
            continue
        accuracy = row.bearing_accuracy
        if (not row.walking or not finite(row.bearing) or not finite(row.speed) or row.speed < 1
                or (accuracy is not None and not 0 <= accuracy <= 30)):
            bearings.clear()
            continue
        bearings.append((row.time, row.bearing))
        while row.time - bearings[0][0] > 3:
            bearings.popleft()
        if accuracy is None and (len(bearings) < 3 or circular_stats([b for _, b in bearings])[1] > 15):
            continue
        if not finite(row.yaw) or (offsets and row.time - offsets[-1][0] < 1):
            continue
        offsets.append((row.time, (row.bearing - YAW_TO_CLOCKWISE * row.yaw) % 360))
    return offsets


@dataclass(frozen=True)
class Estimate:
    offset: float
    uncertainty: float
    sample_count: int
    retained_count: int


def estimate_north(samples, now):
    if len(samples) < MIN_SAMPLES:
        return None
    mean, _ = circular_stats([angle for _, angle in samples])
    retained = [angle for _, angle in samples if abs(signed(angle - mean)) <= 30]
    if len(retained) < MIN_SAMPLES:
        return None
    offset, deviation = circular_stats(retained)
    age_minutes = max(0, now - samples[-1][0]) / 60
    uncertainty = max(8, deviation / math.sqrt(len(retained) / 5)) + age_minutes
    return Estimate(offset, uncertainty, len(samples), len(retained))


def report(rows):
    estimated = False
    for segment in tracked_segments(rows):
        samples = collect_samples(segment)
        end = segment[-1].time
        if estimate := estimate_north(samples, end):
            print(f"end_s={end:g} samples={estimate.sample_count} retained={estimate.retained_count} "
                  f"north_offset_deg={estimate.offset:.3f} north_uncertainty_deg={estimate.uncertainty:.3f}")
            estimated = True
    if not estimated:
        print("Insufficient samples for a north estimate.")
    return estimated


def self_test():
    source = io.StringIO()
    writer = csv.DictWriter(source, fieldnames=sorted(REQUIRED))
    writer.writeheader()
    for step in range(130):
        bearing = (10, 110, 240)[step // 45]
        head_turn = 60 if step % 10 == 0 else (10 if step % 2 == 0 else -10)
        writer.writerow(dict(elapsed_s=step, state="WALKING", ar_tracking="TRACKING",
                             ar_yaw_deg=154 - bearing - head_turn, gps_bearing_deg=bearing,
                             gps_bearing_acc_deg=25, gps_speed_mps=1.4))
    writer.writerow(dict(elapsed_s=130, state="STOPPED", ar_tracking="PAUSED"))
    writer.writerow(dict(elapsed_s=131, state="WALKING", ar_tracking="TRACKING",
                         ar_yaw_deg=0, gps_bearing_deg=65, gps_bearing_acc_deg=25, gps_speed_mps=1.4))
    source.seek(0)
    walk, recovery = tracked_segments(read_rows(source))
    estimate = estimate_north(collect_samples(walk), walk[-1].time)
    assert estimate is not None
    assert estimate.sample_count == 120 and estimate.retained_count == 108
    assert abs(signed(estimate.offset - 154)) <= 5
    assert estimate.uncertainty == 8
    assert estimate_north(collect_samples(recovery), recovery[-1].time) is None
    print(f"PASS self-test: offset={estimate.offset:.3f} deg, uncertainty={estimate.uncertainty:.3f} deg, "
          f"retained={estimate.retained_count}/{estimate.sample_count}, tracking reset")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.csv_path is None:
        parser.error("a CSV path or --self-test is required")
    try:
        with args.csv_path.open(encoding="utf-8-sig", newline="") as source:
            return 0 if report(read_rows(source)) else 1
    except (OSError, ValueError, csv.Error) as error:
        print(f"heading analysis: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
