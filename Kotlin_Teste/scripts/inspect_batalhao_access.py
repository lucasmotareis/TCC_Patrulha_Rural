import json
from pathlib import Path
from shapely.geometry import shape, Point, mapping
from shapely.ops import transform
from shapely import union_all
from shapely.strtree import STRtree
from pyproj import Transformer

root=Path(__file__).resolve().parents[2]/'dados_batalhao'
to_m=Transformer.from_crs(4326,31982,always_xy=True).transform
f=json.loads((root/'acesso_desenhado_rascunho.geojson').read_text(encoding='utf-8'))['features'][0]
line=transform(to_m,shape(f['geometry']))
roads=json.loads((root/'estradas_osm_referencia.geojson').read_text(encoding='utf-8'))['features']
geoms=[transform(to_m,shape(r['geometry'])) for r in roads]
tree=STRtree(geoms)
ends=[]
for coordinate in [line.coords[0],line.coords[-1]]:
    p=Point(coordinate)
    idx=int(tree.nearest(p))
    ends.append({'distance_m':p.distance(geoms[idx]),'road':roads[idx]['properties']})
crossings=[{'road':roads[int(i)]['properties'],'intersection':line.intersection(geoms[int(i)]).wkt} for i in tree.query(line) if line.crosses(geoms[int(i)])]
near=[geoms[int(i)].buffer(10) for i in tree.query(line.buffer(10))]
report={'length_m':line.length,'endpoints':ends,'crossings':crossings,'within_10m_existing_roads_m':line.intersection(union_all(near)).length}
(root/'inspecao_acesso.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=True,indent=2))
