"""Connect reviewed QGIS access roads to an OSM extract without changing OSM IDs.

Only endpoints connect automatically, within an explicit metre tolerance. Interior
crossings are reported for review, never turned into junctions automatically.
Requires osmium, shapely and pyproj. Input GeoJSON must be EPSG:4326.
"""
import argparse
import json
from pathlib import Path
import osmium
from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely import transform as transform_geometry, union_all
from shapely.strtree import STRtree

TO_METRIC = Transformer.from_crs(4326, 31982, always_xy=True).transform
TO_WGS84 = Transformer.from_crs(31982, 4326, always_xy=True).transform
HIGHWAYS = {'service','track','unclassified','residential','tertiary','secondary','primary'}
ACCESS = {'yes','private','permissive','destination','no'}
EXCLUDED = {'footway','cycleway','steps','path','construction','proposed','pedestrian'}

def transform(function, geometry):
    return transform_geometry(geometry, function, interleaved=False)


def merge(base, additions, output, report_path, tolerance=5.0):
    if output.exists():
        raise ValueError('Output already exists; generate a new version instead of overwriting it.')
    collection = json.loads(additions.read_text(encoding='utf-8'))
    features = collection.get('features', [])
    if not features:
        raise ValueError('No saved access-road geometry was supplied.')
    roads, max_node, max_way = [], 0, 0
    for obj in osmium.FileProcessor(str(base)).with_locations('sparse_mem_array'):
        if obj.is_node():
            max_node = max(max_node,obj.id)
        elif obj.is_way():
            max_way = max(max_way,obj.id)
            if obj.tags.get('highway') and obj.tags.get('highway') not in EXCLUDED and len(obj.nodes)>=2:
                coords=[(n.lon,n.lat) for n in obj.nodes]
                line=transform(TO_METRIC,LineString(coords))
                roads.append({'id':obj.id,'refs':[n.ref for n in obj.nodes],'line':line})
    if not roads:
        raise ValueError('Base extract contains no road to connect to.')
    tree=STRtree([r['line'] for r in roads])
    nodes, ways, inserts, connections, crossings = {}, [], {}, [], []
    reused={}

    def new_node(metric):
        nonlocal max_node
        # Reuse explicitly coincident new vertices; do not conflate nearby roads.
        key=(round(metric[0],4),round(metric[1],4))
        if key not in reused:
            max_node+=1
            reused[key]=max_node
            nodes[max_node]=TO_WGS84(*metric)
        return reused[key]

    for feature in features:
        props=feature.get('properties') or {}
        name=props.get('nome') or props.get('name')
        if not name or props.get('highway') not in HIGHWAYS:
            raise ValueError('Each road needs nome and a supported highway classification.')
        if props.get('status')!='revisado':
            raise ValueError(f'{name}: set status=revisado after checking the geometry and attributes.')
        if props.get('access') not in ACCESS or props.get('oneway') not in ('no','yes','-1'):
            raise ValueError(f'{name}: access and oneway must be specified explicitly.')
        geom=shape(feature['geometry'])
        if geom.geom_type!='LineString' or not geom.is_valid or not geom.is_simple:
            raise ValueError(f'{name}: expected a valid, non-self-intersecting LineString.')
        if any(not(-180<=c[0]<=180 and -90<=c[1]<=90) for c in geom.coords):
            raise ValueError('GeoJSON coordinates must be longitude/latitude, EPSG:4326.')
        line=transform(TO_METRIC,geom)
        if line.length<1:
            raise ValueError(f'{name}: road shorter than one metre.')
        nearby=[roads[int(i)]['line'].buffer(5) for i in tree.query(line.buffer(5))]
        if nearby and line.intersection(union_all(nearby)).length/line.length > 0.8:
            raise ValueError(f'{name}: mostly overlaps an existing road; review and add only the missing access.')
        coords=[tuple(c[:2]) for c in line.coords]
        endpoint_refs={}
        for endpoint in (0,len(coords)-1):
            point=Point(coords[endpoint])
            near=sorted((point.distance(roads[int(i)]['line']),int(i)) for i in tree.query(point.buffer(tolerance)))
            near=[v for v in near if v[0]<=tolerance]
            if not near:
                continue
            distance,index=near[0]
            road=roads[index]
            measure=road['line'].project(point)
            snapped=road['line'].interpolate(measure)
            road_coords=list(road['line'].coords)
            nearest_vertex=min(range(len(road_coords)),key=lambda i:Point(road_coords[i]).distance(snapped))
            # Only reuse an existing vertex when the projected point truly reaches it.
            if Point(road_coords[nearest_vertex]).distance(snapped)<=0.05:
                node_id=road['refs'][nearest_vertex]
                snapped=Point(road_coords[nearest_vertex])
                if len(near)>1 and near[1][0]-distance<0.5:
                    other=roads[near[1][1]]
                    if node_id not in other['refs']:
                        raise ValueError(f'{name}: ambiguous endpoint near distinct roads; draw an explicit connection.')
            else:
                if len(near)>1 and near[1][0]-distance<0.5:
                    raise ValueError(f'{name}: ambiguous endpoint; snap explicitly to the intended road.')
                node_id=new_node((snapped.x,snapped.y))
                # Track the source segment, not a whole-line projection: closed ways
                # and repeated vertices must keep their original node order.
                segment=min(range(len(road_coords)-1),key=lambda i:LineString(road_coords[i:i+2]).distance(snapped))
                fraction=LineString(road_coords[segment:segment+2]).project(snapped)
                entry=(segment,fraction,node_id)
                if entry not in inserts.setdefault(road['id'],[]):
                    inserts[road['id']].append(entry)
            endpoint_refs[endpoint]=node_id
            coords[endpoint]=(snapped.x,snapped.y)
            connections.append({'road':name,'endpoint':endpoint,'osm_way_id':road['id'],'shared_node_id':node_id,'snap_distance_m':round(distance,3)})
        if not endpoint_refs:
            raise ValueError(f'{name}: neither endpoint reaches the base network within {tolerance} metres.')
        refs=[endpoint_refs[i] if i in endpoint_refs else new_node(c) for i,c in enumerate(coords)]
        if any(a==b for a,b in zip(refs,refs[1:])):
            raise ValueError(f'{name}: consecutive duplicate vertices; clean the drawing first.')
        max_way+=1
        tags={k:str(props[k]) for k in ('highway','surface','access','oneway') if props.get(k)}
        tags.update(name=str(name),source='Batalhao - cadastro local')
        ways.append(osmium.osm.mutable.Way(id=max_way,nodes=refs,tags=tags))
        line=LineString(coords)
        for idx in tree.query(line):
            other=roads[int(idx)]
            if line.crosses(other['line']):
                crossings.append({'road':name,'osm_way_id':other['id'],'action':'No automatic interior connection; review junction/bridge.'})
    if crossings:
        report_path.write_text(json.dumps({'status':'needs_review','interior_crossings':crossings},indent=2),encoding='utf-8')
        raise ValueError('Interior crossings need review: split the new road at actual junctions, keeping bridge crossings separate.')

    temporary=output.with_name(output.stem+'.part.osm.pbf')
    if temporary.exists():
        raise ValueError(f'Previous temporary output exists: {temporary}')
    new_nodes_written=new_ways_written=False
    with osmium.SimpleWriter(str(temporary)) as writer:
        def flush_nodes():
            nonlocal new_nodes_written
            if not new_nodes_written:
                for node_id,loc in sorted(nodes.items()):
                    writer.add_node(osmium.osm.mutable.Node(id=node_id,location=loc))
                new_nodes_written=True
        def flush_ways():
            nonlocal new_ways_written
            if not new_ways_written:
                for way in ways:
                    writer.add_way(way)
                new_ways_written=True
        for obj in osmium.FileProcessor(str(base)):
            if obj.is_node():
                writer.add_node(obj)
            elif obj.is_way():
                flush_nodes()
                if obj.id in inserts:
                    refs=[]
                    for i,node in enumerate(obj.nodes):
                        refs.append(node.ref)
                        refs.extend(n for seg,_,n in sorted(inserts[obj.id]) if seg==i)
                    writer.add_way(obj.replace(nodes=refs))
                else:
                    writer.add_way(obj)
            else:
                flush_nodes(); flush_ways(); writer.add_relation(obj)
        flush_nodes(); flush_ways()
    temporary.replace(output)
    report={'status':'connected','new_roads':len(ways),'new_way_ids':[way.id for way in ways],'new_nodes':len(nodes),'modified_existing_ways':len(inserts),'connections':connections,'interior_crossings':crossings,'tolerance_m':tolerance,'output':str(output)}
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--roads',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--tolerance-m',type=float,default=5.0)
    args=parser.parse_args()
    if not 0 < args.tolerance_m <= 20:
        parser.error('Tolerance must be positive and at most 20 metres.')
    print(json.dumps(merge(args.base,args.roads,args.output,args.report,args.tolerance_m),ensure_ascii=True,indent=2))
