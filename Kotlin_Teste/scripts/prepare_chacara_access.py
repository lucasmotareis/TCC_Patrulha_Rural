"""Prepare the missing terminal access from this user's drawing, preserving it.

This is a case-specific reconciliation, not an automatic general road conflator.
OSM way 913318451 was identified by inspecting the overlap of the supplied drawing.
"""
import json
from pathlib import Path
from shapely.geometry import shape, mapping, Point, LineString
from shapely.ops import substring
from shapely import transform
from pyproj import Transformer

root=Path(__file__).resolve().parents[2]/'dados_batalhao'
to_m=Transformer.from_crs(4326,31982,always_xy=True).transform
to_geo=Transformer.from_crs(31982,4326,always_xy=True).transform
source=json.loads((root/'acesso_desenhado_rascunho.geojson').read_text(encoding='utf-8'))['features'][0]
line=transform(shape(source['geometry']),to_m,interleaved=False)
roads=json.loads((root/'estradas_osm_referencia.geojson').read_text(encoding='utf-8'))['features']
reference=next(r for r in roads if r['properties']['osm_way_id']=='913318451')
existing=transform(shape(reference['geometry']),to_m,interleaved=False)
# The drawing follows this same OSM track with 2-17 m positional differences.
# Its final bend leaves that track. Keep only the tail after the last supplied
# vertex within 20 m, then align that junction with the existing road geometry.
coords=list(line.coords)
near=[i for i,p in enumerate(coords) if existing.distance(Point(p))<=20]
if not near or near[-1]==len(coords)-1:
    raise ValueError('Drawing changed; inspect the missing terminal access again.')
cut_index=near[-1]
endpoint=Point(coords[cut_index])
junction=existing.interpolate(existing.project(endpoint))
adjustment=endpoint.distance(junction)
new=LineString([(junction.x,junction.y),*coords[cut_index+1:]])
if not 100<new.length<1000:
    raise ValueError('Unexpected new-access length; inspect instead of trimming automatically.')
props={'nome':'Acesso à Chácara 2 Meninos','highway':'service','surface':None,'access':'yes','oneway':'no','status':'revisado','observacao':'Permissão de uso pelo batalhão informada pelo usuário; piso não informado. Base local, sem publicação no OSM. Desenho original preservado; trecho coincidente substituído pela estrada OSM existente.'}
feature={'type':'Feature','properties':props,'geometry':mapping(transform(new,to_geo,interleaved=False))}
(root/'acesso_chacara_novo.geojson').write_text(json.dumps({'type':'FeatureCollection','features':[feature]},ensure_ascii=False,indent=2),encoding='utf-8')
report={'source_drawing_m':line.length,'new_access_m':new.length,'junction_alignment_m':adjustment,'reused_osm_way':913318451,'junction_lonlat':list(feature['geometry']['coordinates'][0]),'destination_lonlat':list(feature['geometry']['coordinates'][-1]),'note':'Uses the destination in the latest QGIS drawing, not the older approximate point. Junction aligned to existing OSM geometry; confirm positional accuracy in field.'}
(root/'acesso_chacara_manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
