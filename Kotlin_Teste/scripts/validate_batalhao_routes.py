"""Validate actual Valhalla graphs served locally, including before/after access."""
import json
from pathlib import Path
import urllib.request
import urllib.error

root=Path(__file__).resolve().parents[2]/'dados_batalhao'
manifest=json.loads((root/'base_manifest.json').read_text(encoding='utf-8'))
access=json.loads((root/'acesso_chacara_manifest.json').read_text(encoding='utf-8'))
municipal=json.loads((root.parent/'dados_qgis/vale_araguaia_11_municipios/dimensionamento.json').read_text(encoding='utf-8'))
names={m['municipio'] for m in municipal['municipios']}
places={p['name']:p for p in manifest['places'] if p['name'] in names}
assert len(places)==11

def route(port,start,end):
    req={'locations':[{'lon':start[0],'lat':start[1],'search_cutoff':100},{'lon':end[0],'lat':end[1],'search_cutoff':100}],'costing':'auto','directions_options':{'units':'kilometers','language':'pt-BR'}}
    data=json.dumps(req).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}/route',data=data,headers={'Content-Type':'application/json'}),timeout=40) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        return json.loads(exc.read())

chapada=places['Chapada de Areia']
start=[chapada['lon'],chapada['lat']]
end=access['destination_lonlat']
before=route(18003,start,end)
after=route(18002,start,end)
(root/'rota_chacara_antes.json').write_text(json.dumps(before,ensure_ascii=False,indent=2),encoding='utf-8')
(root/'rota_chacara_depois.json').write_text(json.dumps(after,ensure_ascii=False,indent=2),encoding='utf-8')
assert 'trip' not in before, 'Original graph unexpectedly reaches the new access within 100 m.'
assert 'trip' in after, after
maneuvers=[m for leg in after['trip']['legs'] for m in leg['maneuvers']]
assert any('Acesso à Chácara 2 Meninos' in m.get('street_names',[]) for m in maneuvers), maneuvers
source=places['Paraíso do Tocantins']
regional=[]
for name,p in places.items():
    if name==source['name']:
        continue
    response=route(18002,[source['lon'],source['lat']],[p['lon'],p['lat']])
    regional.append({'from':source['name'],'to':name,'ok':'trip' in response,'summary':response.get('trip',{}).get('summary'),'error':response.get('error')})
report={'before_error':before,'after_summary':after['trip']['summary'],'new_road_in_instructions':True,'regional_routes':regional}
(root/'validacao_rotas.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=True,indent=2))
assert all(r['ok'] for r in regional)
