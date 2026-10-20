"""Run inside the existing Valhalla container with /work and /reference mounts.

Build in the container's temporary filesystem, then copy only the final archive
and runtime configuration to Windows. Avoids slow random IO on a bind mount.
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--input',required=True)
parser.add_argument('--name',required=True)
args=parser.parse_args()
if not args.name.replace('_','').isalnum():
    parser.error('Use an alphanumeric output name.')
out=pathlib.Path('/work')/args.name
if out.exists():
    raise SystemExit(f'Output exists: {out}')
with tempfile.TemporaryDirectory(prefix='batalhao-') as folder:
    tmp=pathlib.Path(folder)
    source=tmp/'input.osm.pbf'
    shutil.copy2(args.input,source)
    for name in ('admins.sqlite','timezones.sqlite','default_speeds.json'):
        shutil.copy2(pathlib.Path('/reference')/name,tmp/name)
    config=json.loads(pathlib.Path('/work/base_graph/valhalla.json').read_text())
    m=config['mjolnir']
    m.update(tile_dir=str(tmp/'tiles'),tile_extract=str(tmp/'valhalla_tiles.tar'),admin=str(tmp/'admins.sqlite'),timezone=str(tmp/'timezones.sqlite'),default_speeds_config=str(tmp/'default_speeds.json'))
    conf=tmp/'valhalla.json'
    conf.write_text(json.dumps(config))
    subprocess.run(['valhalla_build_tiles','-c',str(conf),'-j','2',str(source)],check=True)
    subprocess.run(['valhalla_build_extract','-c',str(conf)],check=True)
    out.mkdir()
    shutil.copy2(tmp/'valhalla_tiles.tar',out/'valhalla_tiles.tar')
    m.update(tile_dir=str(out/'tiles'),tile_extract=str(out/'valhalla_tiles.tar'),admin='/reference/admins.sqlite',timezone='/reference/timezones.sqlite',default_speeds_config='/reference/default_speeds.json')
    (out/'valhalla.json').write_text(json.dumps(config,indent=2))
    print(json.dumps({'archive':str(out/'valhalla_tiles.tar'),'bytes':(out/'valhalla_tiles.tar').stat().st_size}),flush=True)
