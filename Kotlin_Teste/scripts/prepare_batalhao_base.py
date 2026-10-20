"""Extract reference-complete OSM data and editable reference roads for the battalion.

Requires pyosmium and shapely. The coverage GeoJSON includes the operational buffer.
Source ways retain their original OSM node IDs and turn-restriction relations.
"""
import argparse
import json
from pathlib import Path
import osmium
import shapely
from shapely.geometry import shape, mapping, Point


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--osm', type=Path, required=True)
    parser.add_argument('--coverage', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    region = shape(json.loads(args.coverage.read_text(encoding='utf-8'))['features'][0]['geometry'])
    shapely.prepare(region)
    pbf = args.output / 'batalhao_base.osm.pbf'
    if pbf.exists():
        raise RuntimeError(f'Output already exists: {pbf}')
    factory = osmium.geom.WKBFactory()
    selected_ways = set()
    roads, places = [], []
    invalid = 0
    header = osmium.io.Reader(str(args.osm)).header()
    timestamp = header.get('osmosis_replication_timestamp')
    with osmium.BackReferenceWriter(str(pbf), str(args.osm), remove_tags=False) as writer:
        for obj in osmium.FileProcessor(str(args.osm)).with_locations('sparse_mem_array'):
            if obj.is_node():
                if obj.tags and obj.location.valid() and shapely.intersects(region, Point(obj.location.lon, obj.location.lat)):
                    writer.add_node(obj)
                    if obj.tags.get('place') in ('city', 'town', 'village', 'hamlet'):
                        places.append({'id':obj.id, 'name':obj.tags.get('name'), 'lon':obj.location.lon,'lat':obj.location.lat})
            elif obj.is_way():
                if len(obj.nodes) < 2:
                    continue
                try:
                    line = shapely.from_wkb(factory.create_linestring(obj))
                except (osmium.InvalidLocationError, RuntimeError, ValueError):
                    invalid += 1
                    continue
                if shapely.intersects(region, line):
                    writer.add_way(obj)
                    selected_ways.add(obj.id)
                    if obj.tags.get('highway'):
                        tags = dict(obj.tags)
                        properties = {k:tags.get(k,'') for k in ('name','highway','surface','access','motor_vehicle','motorcar','oneway','bridge','tunnel','layer')}
                        properties['osm_way_id'] = str(obj.id)
                        roads.append({'type':'Feature','id':obj.id,'properties':properties,'geometry':mapping(line)})
            elif obj.is_relation():
                if obj.tags.get('type') in ('restriction','multipolygon') and any(m.type=='w' and m.ref in selected_ways for m in obj.members):
                    writer.add_relation(obj)
    (args.output/'estradas_osm_referencia.geojson').write_text(json.dumps({'type':'FeatureCollection','features':roads},ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    report = {'bounds':list(region.bounds),'source':str(args.osm),'source_timestamp':timestamp,'selected_ways':len(selected_ways),'roads':len(roads),'places':places,'invalid_ways_skipped':invalid,'pbf_bytes':pbf.stat().st_size}
    (args.output/'base_manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='places'},ensure_ascii=True,indent=2),flush=True)


if __name__ == '__main__':
    main()
