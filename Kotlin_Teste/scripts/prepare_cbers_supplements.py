"""Fetch sparse supplementary allocations without reading their empty bounding boxes.

May run alongside prepare_cbers_region.py warp: separate temporary files and
first-publisher semantics keep completed sources valid for the main pipeline.
"""
import argparse
import json
import math
import os
import time

import numpy as np
from osgeo import gdal, ogr
import prepare_cbers_region as base


def prepare(entry):
    scene = entry['item']['id']
    output = base.WORK / 'warped' / (scene + '.tif')
    marker = output.with_suffix('.complete.json')
    if marker.exists():
        base.log(stage='supplement_cached', scene=scene)
        return
    started = time.monotonic()
    partial = output.with_suffix(f'.windowed-{os.getpid()}.part.tif')
    resolution = base.world_resolution(base.MAX_ZOOM)
    left, bottom, right, top = entry['bounds3857']
    width, height = round((right-left)/resolution), round((top-bottom)/resolution)
    chunk = 1024
    columns, rows = math.ceil(width/chunk), math.ceil(height/chunk)
    projection = base.reference(3857).ExportToWkt()
    coarse = gdal.GetDriverByName('MEM').Create('', columns, rows, 1, gdal.GDT_Byte)
    coarse.SetGeoTransform((left,resolution*chunk,0,top,0,-resolution*chunk))
    coarse.SetProjection(projection)
    cutline = ogr.Open(entry['cutline'])
    gdal.RasterizeLayer(coarse,[1],cutline.GetLayer(),burn_values=[1],options=['ALL_TOUCHED=TRUE'])
    cells = np.argwhere(coarse.ReadAsArray() > 0)
    destination = gdal.GetDriverByName('GTiff').Create(str(partial),width,height,4,gdal.GDT_Byte,
        options=['COMPRESS=DEFLATE','PREDICTOR=2','TILED=YES','BLOCKXSIZE=512','BLOCKYSIZE=512',
                 'BIGTIFF=IF_SAFER','SPARSE_OK=TRUE','NUM_THREADS=2'])
    destination.SetGeoTransform((left,resolution,0,top,0,-resolution))
    destination.SetProjection(projection)
    for index, interpretation in enumerate((gdal.GCI_RedBand,gdal.GCI_GreenBand,gdal.GCI_BlueBand,gdal.GCI_AlphaBand),1):
        destination.GetRasterBand(index).SetColorInterpretation(interpretation)
        destination.GetRasterBand(index).SetNoDataValue(0)
    asset = entry['item']['assets'].get('tci') or entry['item']['assets']['rgb']
    source = gdal.Open('/vsicurl/' + asset['href'])
    base.log(stage='supplement_start',scene=scene,windows=len(cells),allWindows=rows*columns)
    for count, (row,column) in enumerate(cells,1):
        # If the ordinary worker finished this scene, its complete TIFF wins.
        if marker.exists():
            destination = None
            partial.unlink(missing_ok=True)
            base.log(stage='supplement_completed_by_main',scene=scene)
            return
        x,y = int(column)*chunk,int(row)*chunk
        w,h = min(chunk,width-x),min(chunk,height-y)
        result = gdal.Warp('',source,options=gdal.WarpOptions(format='MEM',dstSRS='EPSG:3857',
            outputBounds=[left+x*resolution,top-(y+h)*resolution,left+(x+w)*resolution,top-y*resolution],
            width=w,height=h,cutlineDSName=entry['cutline'],dstAlpha=True,dstNodata=0,
            resampleAlg='bilinear',warpMemoryLimit=64))
        destination.WriteArray(result.ReadAsArray(),xoff=x,yoff=y)
        result = None
        if count%20 == 0 or count == len(cells):
            base.log(stage='supplement_progress',scene=scene,windowsDone=count,windows=len(cells),seconds=round(time.monotonic()-started))
    destination.FlushCache()
    destination = None
    # Windows rename refuses an existing output; never replace a published source.
    try:
        partial.rename(output)
    except FileExistsError:
        partial.unlink(missing_ok=True)
        base.log(stage='supplement_completed_by_main',scene=scene)
        return
    base.save(marker,{'scene':scene,'bytes':output.stat().st_size,'seconds':round(time.monotonic()-started),'method':'sparse_windows'})
    base.log(stage='supplement_done',scene=scene,bytes=output.stat().st_size,seconds=round(time.monotonic()-started))


if __name__ == '__main__':
    gdal.UseExceptions()
    ogr.UseExceptions()
    base.configure_gdal()
    parser = argparse.ArgumentParser()
    parser.add_argument('--start-index',type=int,default=7)
    parser.add_argument('--reverse',action='store_true')
    args = parser.parse_args()
    plan = json.loads((base.WORK/'download-plan.json').read_text(encoding='utf-8'))
    (base.WORK/'warped').mkdir(exist_ok=True)
    # Ordinary operation keeps the first seven larger scenes on the main worker.
    selected = plan[args.start_index:]
    if args.reverse:
        selected.reverse()
    for entry in selected:
        prepare(entry)
    base.log(stage='supplements_complete')
