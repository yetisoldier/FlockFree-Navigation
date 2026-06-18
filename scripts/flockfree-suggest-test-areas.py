#!/usr/bin/env python3
"""Suggest camera-dense map areas for FlockFree manual route testing."""

from __future__ import annotations

import argparse
import gzip
import json
import math
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable


DEFAULT_ASSET = Path("OsmAnd/assets/flockfree/cameras.geojson.gz")
DEFAULT_LAT = 44.9778
DEFAULT_LON = -93.2650
EARTH_RADIUS_KM = 6371.0088


@dataclass
class CameraPoint:
    lat: float
    lon: float
    properties: dict[str, Any]
    distance_km: float


@dataclass
class CameraBucket:
    row: int
    col: int
    cameras: list[CameraPoint] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.cameras)

    @property
    def center_lat(self) -> float:
        return sum(camera.lat for camera in self.cameras) / max(1, self.count)

    @property
    def center_lon(self) -> float:
        return sum(camera.lon for camera in self.cameras) / max(1, self.count)

    @property
    def nearest_distance_km(self) -> float:
        return min(camera.distance_km for camera in self.cameras)

    def brands(self) -> Counter[str]:
        values = Counter()
        for camera in self.cameras:
            brand = clean_label(camera.properties.get("brand")) or "unknown"
            values[brand] += 1
        return values

    def operators(self) -> Counter[str]:
        values = Counter()
        for camera in self.cameras:
            operator = clean_label(camera.properties.get("operator"))
            if operator:
                values[operator] += 1
        return values


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Read the bundled FlockFree camera seed and suggest dense nearby "
            "areas for manual map, alert, and route-avoidance testing."
        )
    )
    parser.add_argument("--asset", default=str(DEFAULT_ASSET), help="Path to cameras.geojson.gz.")
    parser.add_argument("--lat", type=float, default=DEFAULT_LAT, help="Search center latitude.")
    parser.add_argument("--lon", type=float, default=DEFAULT_LON, help="Search center longitude.")
    parser.add_argument("--radius-km", type=float, default=80.0, help="Search radius in kilometers.")
    parser.add_argument("--cell-km", type=float, default=1.5, help="Grid cell size in kilometers.")
    parser.add_argument("--limit", type=int, default=10, help="Maximum number of areas to print.")
    parser.add_argument(
        "--format",
        choices=("text", "csv"),
        default="text",
        help="Output format.",
    )
    return parser.parse_args()


def clean_label(value: Any) -> str | None:
    if value is None:
        return None
    label = str(value).strip()
    return label or None


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def iter_camera_points(asset: Path, center_lat: float, center_lon: float, radius_km: float) -> Iterable[CameraPoint]:
    with gzip.open(asset, "rt", encoding="utf-8") as handle:
        data = json.load(handle)
    if data.get("type") != "FeatureCollection":
        raise SystemExit(f"Camera seed is not a GeoJSON FeatureCollection: {asset}")
    for feature in data.get("features", []):
        geometry = feature.get("geometry") or {}
        coordinates = geometry.get("coordinates") or []
        if geometry.get("type") != "Point" or len(coordinates) < 2:
            continue
        lon, lat = coordinates[0], coordinates[1]
        if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            continue
        distance_km = haversine_km(center_lat, center_lon, lat, lon)
        if distance_km <= radius_km:
            yield CameraPoint(float(lat), float(lon), feature.get("properties") or {}, distance_km)


def bucket_key(camera: CameraPoint, center_lat: float, center_lon: float, cell_km: float) -> tuple[int, int]:
    km_per_lat_degree = 110.574
    km_per_lon_degree = 111.320 * max(0.1, math.cos(math.radians(center_lat)))
    row = math.floor(((camera.lat - center_lat) * km_per_lat_degree) / cell_km)
    col = math.floor(((camera.lon - center_lon) * km_per_lon_degree) / cell_km)
    return row, col


def summarize_counter(counter: Counter[str], limit: int = 3) -> str:
    if not counter:
        return "-"
    return ", ".join(f"{name} ({count})" for name, count in counter.most_common(limit))


def camera_hint(camera: CameraPoint) -> str:
    props = camera.properties
    parts = []
    for key in ("brand", "operator", "ref", "direction", "osmType", "osmId"):
        value = clean_label(props.get(key))
        if value:
            parts.append(f"{key}={value}")
    return "; ".join(parts) if parts else "no properties"


def print_text(buckets: list[CameraBucket], args: argparse.Namespace, total_points: int) -> None:
    print("FlockFree camera-dense test areas")
    print("=================================")
    print(f"Asset: {args.asset}")
    print(f"Center: {args.lat:.6f}, {args.lon:.6f}")
    print(f"Radius: {args.radius_km:g} km")
    print(f"Grid cell: {args.cell_km:g} km")
    print(f"Matching cameras: {total_points}")
    print()
    if not buckets:
        print("No cameras found in the requested search area.")
        return
    for index, bucket in enumerate(buckets, start=1):
        sample = min(bucket.cameras, key=lambda camera: camera.distance_km)
        print(f"{index}. {bucket.count} cameras near {bucket.center_lat:.6f}, {bucket.center_lon:.6f}")
        print(f"   Nearest to center: {bucket.nearest_distance_km:.1f} km")
        print(f"   Brands: {summarize_counter(bucket.brands())}")
        print(f"   Operators: {summarize_counter(bucket.operators())}")
        print(f"   Map anchor: {bucket.center_lat:.6f},{bucket.center_lon:.6f}")
        print(f"   Sample: {camera_hint(sample)}")
        print()


def print_csv(buckets: list[CameraBucket]) -> None:
    print("rank,count,lat,lon,nearest_distance_km,brands,operators")
    for index, bucket in enumerate(buckets, start=1):
        print(
            f"{index},{bucket.count},{bucket.center_lat:.6f},{bucket.center_lon:.6f},"
            f"{bucket.nearest_distance_km:.2f},"
            f"\"{summarize_counter(bucket.brands())}\","
            f"\"{summarize_counter(bucket.operators())}\""
        )


def main() -> int:
    args = parse_args()
    asset = Path(args.asset)
    if not asset.exists():
        raise SystemExit(f"Camera seed not found: {asset}")
    if args.radius_km <= 0:
        raise SystemExit("--radius-km must be positive")
    if args.cell_km <= 0:
        raise SystemExit("--cell-km must be positive")
    if args.limit <= 0:
        raise SystemExit("--limit must be positive")

    buckets: dict[tuple[int, int], CameraBucket] = {}
    total_points = 0
    for camera in iter_camera_points(asset, args.lat, args.lon, args.radius_km):
        total_points += 1
        key = bucket_key(camera, args.lat, args.lon, args.cell_km)
        bucket = buckets.setdefault(key, CameraBucket(row=key[0], col=key[1]))
        bucket.cameras.append(camera)

    ranked = sorted(
        buckets.values(),
        key=lambda bucket: (-bucket.count, bucket.nearest_distance_km, bucket.center_lat, bucket.center_lon),
    )[: args.limit]

    if args.format == "csv":
        print_csv(ranked)
    else:
        print_text(ranked, args, total_points)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
