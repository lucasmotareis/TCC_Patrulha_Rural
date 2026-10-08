"""Generate an offline visual map and a regional routing archive from existing OSM data.

Dependencies: osmium, shapely. Does not fetch map tiles from public tile servers.
The visual extent is a rectangle around Palmas, Taquaralto and Taquarucu, not a
municipal administrative polygon. Routing keeps a buffer and the coarser hierarchy.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import tarfile
import unicodedata

import osmium
from shapely import from_wkb, make_valid
from shapely.geometry import box, mapping

WEST, SOUTH, EAST, NORTH = -48.45, -10.42, -48.05, -10.08
EXTENT = box(WEST, SOUTH, EAST, NORTH)


def feature(geometry, properties, identifier):
    return {"type": "Feature", "id": identifier,
            "properties": properties, "geometry": mapping(geometry)}


def label(value):
    return unicodedata.normalize("NFC", value or "")


class VisualMap(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.factory = osmium.geom.WKBFactory()
        self.roads, self.areas, self.waterways, self.places = [], [], [], []
        self.invalid_geometries = 0

    def node(self, node):
        if not node.location.valid():
            return
        if node.tags.get("place") not in ("city", "town", "village", "suburb", "neighbourhood"):
            return
        tags = dict(node.tags)
        if tags.get("place") not in ("city", "town", "village", "suburb", "neighbourhood"):
            return
        x, y = node.location.lon, node.location.lat
        if WEST <= x <= EAST and SOUTH <= y <= NORTH and tags.get("name"):
            self.places.append({"type": "Feature", "id": node.id,
                "geometry": {"type": "Point", "coordinates": [x, y]},
                "properties": {"name": label(tags["name"]), "kind": tags["place"]}})

    def way(self, way):
        tags = dict(way.tags)
        highway, waterway = tags.get("highway"), tags.get("waterway")
        if not highway and not waterway:
            return
        try:
            if len(way.nodes) < 2:
                return
            # Build geometry in native code instead of wrapping millions of node refs in Python.
            line = from_wkb(self.factory.create_linestring(way))
            if not line.intersects(EXTENT):
                return
            geometry = line.intersection(EXTENT)
            if geometry.is_empty or geometry.geom_type not in ("LineString", "MultiLineString"):
                return
            geometry = geometry.simplify(0.000004, preserve_topology=True)
            properties = {"name": label(tags.get("name")), "kind": highway or waterway}
            (self.roads if highway else self.waterways).append(feature(geometry, properties, way.id))
        except (osmium.InvalidLocationError, ValueError):
            self.invalid_geometries += 1

    def area(self, area):
        tags = dict(area.tags)
        natural, landuse, leisure = tags.get("natural"), tags.get("landuse"), tags.get("leisure")
        if natural in ("water", "wetland") or landuse == "reservoir":
            kind = "water"
        elif natural in ("wood", "scrub", "grassland") or landuse in ("forest", "grass", "recreation_ground") or leisure in ("park", "garden", "nature_reserve"):
            kind = "green"
        elif tags.get("building"):
            kind = "building"
        elif landuse in ("residential", "commercial", "industrial", "retail"):
            kind = "urban"
        else:
            return
        try:
            geometry = from_wkb(self.factory.create_multipolygon(area))
            if not geometry.intersects(EXTENT):
                return
            geometry = make_valid(geometry).intersection(EXTENT)
            if geometry.is_empty or geometry.geom_type not in ("Polygon", "MultiPolygon"):
                return
            geometry = geometry.simplify(0.00001, preserve_topology=True)
            self.areas.append(feature(geometry, {"kind": kind, "name": label(tags.get("name"))}, area.id))
        except (osmium.InvalidLocationError, RuntimeError, ValueError):
            self.invalid_geometries += 1


def write_geojson(path, features):
    path.write_text(json.dumps({"type": "FeatureCollection", "features": features},
                              ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def regional_tiles(source, destination):
    selected = {}
    # A local-tile buffer keeps boundary connections; upper-level tiles are indivisible.
    buffer = 0.25
    for level, size in enumerate((4.0, 1.0, 0.25)):
        columns = int(360 / size)
        ids = set()
        for row in range(math.floor((SOUTH - buffer + 90) / size), math.floor((NORTH + buffer + 90) / size) + 1):
            for col in range(math.floor((WEST - buffer + 180) / size), math.floor((EAST + buffer + 180) / size) + 1):
                ids.add(row * columns + col)
        selected[level] = ids
    temporary = destination.with_suffix(".tmp")
    counts = {0: 0, 1: 0, 2: 0}
    with tarfile.open(source) as src, tarfile.open(temporary, "w", format=tarfile.USTAR_FORMAT) as dst:
        for member in src:
            match = re.fullmatch(r"([012])/([0-9/]+)\.gph", member.name)
            if not match:
                continue  # Never reuse index.bin: its offsets refer to the original TAR.
            level = int(match[1])
            tile_id = int(match[2].replace("/", ""))
            if tile_id in selected[level]:
                dst.addfile(member, src.extractfile(member))
                counts[level] += 1
    if not counts[2]:
        temporary.unlink()
        raise RuntimeError("No local tiles found for Palmas")
    temporary.replace(destination)
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--osm", type=Path, required=True)
    parser.add_argument("--routing-tar", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    visual = args.assets / "palmas"
    visual.mkdir(parents=True, exist_ok=True)
    handler = VisualMap()
    print("Reading OSM roads, polygons and places...", flush=True)
    # Location caching and area assembly run before this native tag filter. No OSM references
    # are lost, while untagged nodes never cross into Python.
    processor = (osmium.FileProcessor(str(args.osm)).with_locations("sparse_mem_array")
        .with_areas().with_filter(osmium.filter.KeyFilter(
            "highway", "waterway", "place", "natural", "landuse", "leisure", "building")))
    for obj in processor:
        if obj.is_node():
            handler.node(obj)
        elif obj.is_way():
            handler.way(obj)
        elif obj.is_area():
            handler.area(obj)
    layers = {"roads": handler.roads, "areas": handler.areas,
              "waterways": handler.waterways, "places": handler.places}
    if not handler.roads:
        raise RuntimeError("No roads found inside Palmas extent")
    for name, features in layers.items():
        write_geojson(visual / f"{name}.geojson", features)
    tar = args.assets / "palmas_tiles_v1.tar"
    counts = regional_tiles(args.routing_tar, tar)
    with tar.open("rb") as routing_file:
        routing_hash = hashlib.file_digest(routing_file, "sha256").hexdigest()
    metadata = {"bounds": [WEST, SOUTH, EAST, NORTH],
        "description": "Recorte de Palmas, Taquaralto e Taquaruçu; não é o limite municipal.",
        "source": "OpenStreetMap / Geofabrik Norte", "license": "ODbL-1.0",
        "featureCounts": {name: len(features) for name, features in layers.items()},
        "routingTilesByLevel": counts, "routingBytes": tar.stat().st_size,
        "routingSha256": routing_hash,
        "invalidGeometriesSkipped": handler.invalid_geometries}
    (visual / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
