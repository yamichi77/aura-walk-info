#!/usr/bin/env python3
"""Estimate the GPS-to-ARCore heading offset from a T15 field log."""

import argparse
import csv
import io
import itertools
import math
import sys
from dataclasses import dataclass
from pathlib import Path


REQUIRED = {
    "elapsed_s", "ar_tracking", "ar_x_m", "ar_z_m", "ar_yaw_deg",
    "gps_bearing_deg", "gps_bearing_acc_deg", "gps_speed_mps", "accuracy_m",
}


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
        parsed = float(value)
    except ValueError:
        raise ValueError(f"{key} must be numeric") from None
    return parsed if math.isfinite(parsed) else None


@dataclass(frozen=True)
class Row:
    time: float
    position: tuple | None
    bearing: float | None
    bearing_accuracy: float | None
    speed: float | None
    accuracy: float | None

    @property
    def has_fix(self):
        return any(value is not None for value in (self.bearing, self.bearing_accuracy, self.speed, self.accuracy))


def read_rows(source):
    reader = csv.DictReader(source)
    if missing := REQUIRED.difference(reader.fieldnames or ()):
        raise ValueError(f"missing T15 columns: {', '.join(sorted(missing))}")
    previous = -math.inf
    for line, row in enumerate(reader, 2):
        try:
            time = number(row, "elapsed_s")
            if time is None or time <= previous:
                raise ValueError("elapsed_s must be finite and strictly increasing")
            x, z = number(row, "ar_x_m"), number(row, "ar_z_m")
            position = (x, z) if row["ar_tracking"] == "TRACKING" and x is not None and z is not None else None
            accuracy = number(row, "gps_bearing_acc_deg")
            if row["gps_bearing_acc_deg"] and accuracy is None:
                accuracy = math.inf
            yield Row(time, position, number(row, "gps_bearing_deg"), accuracy,
                      number(row, "gps_speed_mps"), number(row, "accuracy_m"))
            previous = time
        except ValueError as error:
            raise ValueError(f"row {line}: {error}") from error


def tracked_segments(rows):
    segment = []
    for row in rows:
        if row.position is None or (segment and row.time - segment[-1].time > 1.5):
            if segment:
                yield segment
                segment = []
        if row.position is not None:
            segment.append(row)
    if segment:
        yield segment


def window_sample(window):
    start, end = window[0], window[-1]
    fixes = [row for row in window if row.has_fix]
    if len(fixes) < 3 or fixes[0].time - start.time > 1.5 or end.time - fixes[-1].time > 1.5:
        return None
    if any(b.time - a.time > 2 for a, b in itertools.pairwise(fixes)):
        return None
    if any(row.bearing is None or row.speed is None or row.speed < 0.8
           or row.accuracy is None or not 0 <= row.accuracy <= 30
           or (row.bearing_accuracy is not None and not 0 <= row.bearing_accuracy <= 20) for row in fixes):
        return None
    gps_mean, gps_sd = circular_stats([row.bearing for row in fixes])
    if gps_sd > 10:
        return None
    dx = end.position[0] - start.position[0]
    dz = end.position[1] - start.position[1]
    displacement = math.hypot(dx, dz)
    path = sum(math.dist(a.position, b.position) for a, b in itertools.pairwise(window))
    if displacement < 3 or displacement / path < 0.9:
        return None
    return end.time, gps_mean, math.degrees(math.atan2(dx, -dz))


def collect_samples(rows):
    for segment in tracked_segments(rows):
        windows = []
        start = next((i for i, row in enumerate(segment) if row.has_fix), len(segment))
        for end in range(start + 1, len(segment)):
            if segment[end].time - segment[start].time < 5:
                continue
            if sample := window_sample(segment[start:end + 1]):
                windows.append(sample)
            start = end
        if windows:
            yield windows


def drift_per_minute(segments):
    covariance = variance = 0.0
    for segment in segments:
        times, angles = zip(*segment)
        unwrapped = [angles[0]]
        for before, after in itertools.pairwise(angles):
            unwrapped.append(unwrapped[-1] + signed(after - before))
        mean_t = sum(times) / len(times)
        mean_angle = sum(unwrapped) / len(unwrapped)
        covariance += sum((t - mean_t) * (a - mean_angle) for t, a in zip(times, unwrapped))
        variance += sum((t - mean_t) ** 2 for t in times)
    return 60 * covariance / variance if variance else None


@dataclass(frozen=True)
class Candidate:
    forward: str
    travel_sign: int
    yaw_sign: int
    median: float
    deviation: float
    drift: float | None


def rank_candidates(samples):
    candidates = []
    for forward, phase in (("-Z", 0), ("+Z", 180)):
        for travel_sign, yaw_sign in itertools.product((1, -1), repeat=2):
            segments = [[(t, (gps - phase - travel_sign * travel) % 360)
                         for t, gps, travel in segment] for segment in samples]
            offsets = [angle for segment in segments for _, angle in segment]
            median = min(offsets, key=lambda a: sum(abs(signed(a - b)) for b in offsets))
            _, deviation = circular_stats(offsets)
            candidates.append(Candidate(forward, travel_sign, yaw_sign, median, deviation,
                                        drift_per_minute(segments)))
    return candidates


def winners(candidates, forward=None, yaw_sign=None):
    eligible = [c for c in candidates if forward in (None, c.forward) and yaw_sign in (None, c.yaw_sign)]
    best = min(c.deviation for c in eligible)
    return [c for c in eligible if math.isclose(c.deviation, best, abs_tol=1e-6)]


def report(rows, forward=None, yaw_sign=None):
    samples = list(collect_samples(rows))
    count = sum(map(len, samples))
    print(f"samples={count} tracked_segments_with_samples={len(samples)}")
    if not count:
        return False
    candidates = rank_candidates(samples)
    best = winners(candidates, forward, yaw_sign) if count > 1 else []
    for c in candidates:
        drift = "NA" if c.drift is None else f"{c.drift:+.3f}"
        marker = " best" if c in best else ""
        print(f"forward={c.forward} travel_sign={c.travel_sign:+d} yaw_to_cw={c.yaw_sign:+d} "
              f"median_deg={c.median:.3f} circular_sd_deg={c.deviation:.3f} drift_deg_per_min={drift}{marker}")
    if not best:
        print("Insufficient samples to rank candidates.")
    elif len(best) > 1:
        print(f"Best deviation is tied ({len(best)} candidates).")
        if forward is None or yaw_sign is None:
            print("A 180-degree shift and head yaw sign cannot be distinguished from GPS and position scatter alone.")
        else:
            print("Walk straight in different directions to distinguish travel signs.")
    return True


def self_test():
    source = io.StringIO()
    fields = sorted(REQUIRED)
    writer = csv.DictWriter(source, fieldnames=fields)
    writer.writeheader()
    x = z = 0.0
    # Straight windows in different directions, separated by observed tracking loss.
    for index, travel in enumerate((-35, 70, 145)):
        start = 8 * index
        for step in range(6):
            bearing = (37 + 180 - travel) % 360
            writer.writerow(dict(elapsed_s=start + step, ar_tracking="TRACKING", ar_x_m=x, ar_z_m=z,
                                 ar_yaw_deg=(-1) ** step * 40, gps_bearing_deg=bearing,
                                 gps_bearing_acc_deg=8, gps_speed_mps=1, accuracy_m=4))
            x += math.sin(math.radians(travel))
            z -= math.cos(math.radians(travel))
        writer.writerow(dict(elapsed_s=start + 6, ar_tracking="PAUSED"))
    source.seek(0)
    samples = list(collect_samples(read_rows(source)))
    assert sum(map(len, samples)) == 3
    candidates = rank_candidates(samples)
    assert len(winners(candidates)) == 4
    selected, = winners(candidates, forward="+Z", yaw_sign=-1)
    assert selected.travel_sign == -1
    assert abs(signed(selected.median - 37)) < 2
    assert selected.deviation < 2
    assert abs(drift_per_minute([[(0, 359), (60, 1)]]) - 2) < 1e-6
    print("PASS self-test: 3 samples, 37-degree offset, candidate ties, observed axes, and drift")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--forward", choices=("-Z", "+Z"), help="observed forward axis (e.g. --forward=-Z)")
    parser.add_argument("--yaw-sign", choices=(-1, 1), type=int, help="observed multiplier for clockwise yaw")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.csv_path is None:
        parser.error("a CSV path or --self-test is required")
    try:
        with args.csv_path.open(encoding="utf-8-sig", newline="") as source:
            return 0 if report(read_rows(source), args.forward, args.yaw_sign) else 1
    except (OSError, ValueError, csv.Error) as error:
        print(f"heading analysis: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
