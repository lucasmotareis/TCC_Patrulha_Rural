"""Build a strictly clipped, resumable CBERS offline raster package for 8 BPM.

Run with QGIS Python for GDAL, OGR, numpy and Pillow. Official scene metadata
and source imagery are preserved separately from the Android package.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import io
import json
import math
import re
import shutil
import tarfile
from pathlib import Path
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

import numpy as np
from osgeo import gdal, ogr, osr

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "dados_qgis/cbers_8bpm_completo"
ASSETS = ROOT / "Kotlin_Teste/app/src/main/batalhaoAssets/batalhao"
COLLECTION = "CB4A-WPM-PCA-FUSED-1"
STAC = "https://data.inpe.br/bdc/stac/v1"


def log(**value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def reference(code):
    value = osr.SpatialReference()
    value.ImportFromEPSG(code)
    value.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return value


def scene_group(item):
    match = re.search(r"_(\d{8})_(\d{3})_(\d{3})(?:_L\d)?$", item["id"])
    if not match:
        raise ValueError(item["id"])
    return f"{match[2]}_{match[3]}"


def region():
    data = json.loads((ASSETS / "region.geojson").read_text(encoding="utf-8-sig"))
    geometries = [ogr.CreateGeometryFromJson(json.dumps(f["geometry"])) for f in data["features"]]
    result = geometries[0].Clone()
    for geometry in geometries[1:]:
        result = result.Union(geometry)
    return result.MakeValid()


def survey(args):
    WORK.mkdir(parents=True, exist_ok=True)
    extent = region().GetEnvelope()
    bbox = [extent[0], extent[2], extent[1], extent[3]]
    query = urllib.parse.urlencode({"collections": COLLECTION, "bbox": ",".join(map(str, bbox)),
                                    "datetime": args.dates, "limit": 500})
    url = f"{STAC}/search?{query}"
    items = []
    while url:
        page = json.loads(get(url))
        items.extend(page.get("features", []))
        url = next((link["href"] for link in page.get("links", []) if link["rel"] == "next"), None)
        log(stage="catalog_page", items=len(items), more=bool(url))
    strict = region()
    items = [item for item in items if strict.Intersects(ogr.CreateGeometryFromJson(json.dumps(item["geometry"])))]
    save(WORK / "catalog.json", {"collection": COLLECTION, "dates": args.dates, "bbox": bbox, "features": items})
    collection = json.loads(get(f"{STAC}/collections/{COLLECTION}"))
    if collection["license"] != "Creative-Commons-Attribution-4.0-International":
        raise ValueError("Collection license must be reviewed before use")
    save(WORK / "collection.json", collection)
    groups = defaultdict(list)
    for item in items:
        groups[scene_group(item)].append(item)
    summary = []
    for group, scenes in sorted(groups.items()):
        scenes.sort(key=lambda item: item["properties"]["datetime"], reverse=True)
        summary.append({"group": group, "count": len(scenes), "scenes": [item["id"] for item in scenes]})
    save(WORK / "groups.json", summary)
    log(stage="survey_complete", total=len(items), groups=summary)


def thumbnails(args):
    from PIL import Image, ImageDraw, ImageFont
    items = json.loads((WORK / "catalog.json").read_text(encoding="utf-8"))["features"]
    groups = defaultdict(list)
    for item in items:
        groups[scene_group(item)].append(item)
    candidates = []
    for group, scenes in sorted(groups.items()):
        scenes.sort(key=lambda item: abs((datetime.fromisoformat(item["properties"]["datetime"].replace("Z", "+00:00"))
                                       - datetime(2026, 8, 15, tzinfo=timezone.utc)).days))
        candidates.extend(scenes[:args.per_group])
    folder = WORK / "thumbnails"
    folder.mkdir(exist_ok=True)
    def download(item):
        path = folder / (item["id"] + ".png")
        if not path.exists():
            path.write_bytes(get(item["assets"]["thumbnail"]["href"]))
        return item, path
    paths = {}
    with ThreadPoolExecutor(max_workers=4) as executor:
        for future in as_completed([executor.submit(download, item) for item in candidates]):
            item, path = future.result()
            paths[item["id"]] = path
    width, height = 360, 400
    columns = args.per_group
    sheet = Image.new("RGB", (width * columns, height * len(groups)), "#151e25")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 15)
    for index, item in enumerate(candidates):
        with Image.open(paths[item["id"]]) as source:
            source = source.convert("RGB")
            source.thumbnail((width - 12, height - 54))
            left, top = (index % columns) * width, (index // columns) * height
            sheet.paste(source, (left + (width - source.width) // 2, top + 42))
            draw.text((left + 8, top + 4), item["id"].replace("CBERS4A_WPM_PCA_RGB321_", ""), fill="white", font=font)
            draw.text((left + 8, top + 22), "CBERS-4A/INPE | CC BY 4.0", fill="#a4bbc5", font=font)
    sheet.save(WORK / "contact-sheet.jpg", quality=92)
    save(WORK / "candidates.json", candidates)
    log(stage="contact_sheet", candidates=len(candidates), rows=len(groups), path=str(WORK / "contact-sheet.jpg"))


def configure_gdal():
    for key, value in {
        "GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR", "CPL_VSIL_CURL_ALLOWED_EXTENSIONS": ".tif",
        "GDAL_HTTP_TIMEOUT": "90", "GDAL_HTTP_CONNECTTIMEOUT": "20", "GDAL_HTTP_MAX_RETRY": "3",
        "GDAL_HTTP_RETRY_DELAY": "2", "GDAL_HTTP_MULTIRANGE": "YES",
        "CPL_VSIL_CURL_CACHE_SIZE": "67108864", "GDAL_CACHEMAX": "256",
        "GDAL_NUM_THREADS": "4",
    }.items():
        gdal.SetConfigOption(key, value)


def select(args):
    items = json.loads((WORK / "catalog.json").read_text(encoding="utf-8"))["features"]
    preferred = {
        "208_126": "20250907", "209_124": "20240726", "209_125": "20250702", "209_126": "20240726",
        "210_124": "20260804", "210_125": "20260804", "210_126": "20260804",
        "211_124": "20250823", "211_125": "20250823", "211_126": "20250823",
    }
    selected = []
    for group, date in preferred.items():
        scene = next(item for item in items if item["id"].endswith(f"_{date}_{group}"))
        selected.append(scene)
    save(WORK / "selected.json", selected)
    log(stage="selected", scenes=[item["id"] for item in selected])


def previews(args):
    configure_gdal()
    strict = region()
    save(WORK / "region.geojson", {"type": "FeatureCollection", "features": [{
        "type": "Feature", "properties": {}, "geometry": json.loads(strict.ExportToJson())}]})
    projected = strict.Clone()
    projected.Transform(osr.CoordinateTransformation(reference(4326), reference(3857)))
    left, right, bottom, top = projected.GetEnvelope()
    resolution = 200
    left, bottom = math.floor(left / resolution) * resolution, math.floor(bottom / resolution) * resolution
    right, top = math.ceil(right / resolution) * resolution, math.ceil(top / resolution) * resolution
    bounds = [left, bottom, right, top]
    items = (json.loads((WORK / "catalog.json").read_text(encoding="utf-8"))["features"] if args.all
             else json.loads((WORK / "selected.json").read_text(encoding="utf-8")))
    folder = WORK / "previews"
    folder.mkdir(exist_ok=True)
    def warp(item):
        path = folder / (item["id"] + ".tif")
        if not path.exists():
            url = (item["assets"].get("tci") or item["assets"]["rgb"])["href"]
            result = gdal.Warp(str(path), "/vsicurl/" + url, options=gdal.WarpOptions(
                dstSRS="EPSG:3857", outputBounds=bounds, xRes=resolution, yRes=resolution,
                cutlineDSName=str(WORK / "region.geojson"), dstAlpha=True, dstNodata=0,
                resampleAlg="bilinear", creationOptions=["COMPRESS=DEFLATE", "TILED=YES"]))
            result = None
        dataset = gdal.Open(str(path))
        data = dataset.ReadAsArray()
        visible = data[3] > 0
        cloudy = (data[:3].min(axis=0) > 170) & visible
        log(stage="preview", scene=item["id"], visible=int(visible.sum()), brightFraction=float(cloudy.sum()/max(1,visible.sum())))
        return item, data
    arrays = []
    with ThreadPoolExecutor(max_workers=3) as executor:
        for future in as_completed([executor.submit(warp, item) for item in items]):
            arrays.append(future.result())
    # Build a compact visual overview, preferring the clear 2026 scenes where overlapping.
    arrays.sort(key=lambda row: row[0]["properties"]["datetime"])
    result = np.zeros_like(arrays[0][1])
    for _, data in arrays:
        mask = data[3] > 0
        result[:, mask] = data[:, mask]
    from PIL import Image
    Image.fromarray(np.moveaxis(result, 0, 2)).save(WORK / "mosaic-preview.png")
    mask_ds = gdal.GetDriverByName("MEM").Create("", result.shape[2], result.shape[1], 1, gdal.GDT_Byte)
    mask_ds.SetProjection(reference(3857).ExportToWkt())
    mask_ds.SetGeoTransform((left, resolution, 0, top, 0, -resolution))
    source = ogr.Open(str(WORK / "region.geojson"))
    gdal.RasterizeLayer(mask_ds, [1], source.GetLayer(), burn_values=[1])
    expected = mask_ds.ReadAsArray() > 0
    missing = expected & (result[3] == 0)
    report = {"pixelMeters":resolution,"bounds3857":bounds,"regionPixels":int(expected.sum()),
              "missingPixels":int(missing.sum()),"coveredFraction":float(1-missing.sum()/expected.sum()),
              "scenes":[item["id"] for item in items]}
    save(WORK / "preview-coverage.json", report)
    log(stage="preview_complete", **report)

    # Audit every candidate before committing to multi-gigabyte downloads.
    if args.all:
        remaining = expected.copy()
        clean_arrays = []
        for item, data in arrays:
            visible = data[3] > 0
            bright = (data[:3].min(axis=0) > 155) & visible
            cloudy_fraction = bright.sum() / max(1, visible.sum())
            if cloudy_fraction < 0.01:
                clean_arrays.append((item, data))
        # Prefer clear 2026 scenes, then greedily cover remaining pixels.
        first = [row for row in clean_arrays if row[0]["id"] in {
            "CBERS4A_WPM_PCA_RGB321_20260804_210_124",
            "CBERS4A_WPM_PCA_RGB321_20260804_210_125",
            "CBERS4A_WPM_PCA_RGB321_20260804_210_126"}]
        pool = clean_arrays.copy()
        selected_arrays = []
        for item, data in first:
            if ((data[3] > 0) & remaining).any():
                selected_arrays.append((item, data))
                remaining[data[3] > 0] = False
        while remaining.any():
            candidates = [(int(((data[3] > 0) & remaining).sum()), item, data) for item, data in pool]
            count, item, data = max(candidates, key=lambda row: (row[0], row[1]["properties"]["datetime"]))
            if count == 0:
                break
            selected_arrays.append((item, data))
            remaining[data[3] > 0] = False
            pool = [(i, d) for i, d in pool if i["id"] != item["id"]]
        selected_items = [item for item, _ in selected_arrays]
        save(WORK / "selected-complete.json", selected_items)
        log(stage="clean_selection", missingPixels=int(remaining.sum()), scenes=[item["id"] for item in selected_items])


HALF_WORLD = 20037508.342789244
MAX_ZOOM = 16
MIN_ZOOM = 7
TILE_SIZE = 256


def world_resolution(zoom):
    return 2 * HALF_WORLD / (TILE_SIZE * 2 ** zoom)


def grid(bounds, zoom):
    left, bottom, right, top = bounds
    span = 2 * HALF_WORLD / 2 ** zoom
    xmin = math.floor((left + HALF_WORLD) / span)
    xmax = math.floor((right + HALF_WORLD) / span)
    ymin = math.floor((HALF_WORLD - top) / span)
    ymax = math.floor((HALF_WORLD - bottom) / span)
    return xmin, ymin, xmax, ymax


def tile_bounds(xmin, ymin, xmax, ymax, zoom):
    span = 2 * HALF_WORLD / 2 ** zoom
    return [-HALF_WORLD + xmin * span, HALF_WORLD - (ymax + 1) * span,
            -HALF_WORLD + (xmax + 1) * span, HALF_WORLD - ymin * span]


def plan(args):
    items = json.loads((WORK / "selected-complete.json").read_text(encoding="utf-8"))
    selected = []
    assigned = None
    strict = region()
    strict.Transform(osr.CoordinateTransformation(reference(4326), reference(3857)))
    folder = WORK / "cutlines"
    folder.mkdir(exist_ok=True)
    total = 0
    for item in items:
        preview = gdal.Open(str(WORK / "previews" / (item["id"] + ".tif")))
        alpha = preview.GetRasterBand(4).ReadAsArray() > 0
        if assigned is None:
            assigned = np.zeros_like(alpha)
        needed = alpha & ~assigned
        assigned |= alpha
        if not needed.any():
            continue
        mask = gdal.GetDriverByName("MEM").Create("", preview.RasterXSize, preview.RasterYSize, 1, gdal.GDT_Byte)
        mask.SetProjection(preview.GetProjection())
        mask.SetGeoTransform(preview.GetGeoTransform())
        mask.GetRasterBand(1).WriteArray(needed.astype(np.uint8))
        vector = ogr.GetDriverByName("Memory").CreateDataSource("")
        layer = vector.CreateLayer("allocation", reference(3857), ogr.wkbPolygon)
        layer.CreateField(ogr.FieldDefn("value", ogr.OFTInteger))
        gdal.Polygonize(mask.GetRasterBand(1), mask.GetRasterBand(1), layer, 0)
        polygons = ogr.Geometry(ogr.wkbMultiPolygon)
        for feature in layer:
            polygons.AddGeometry(feature.GetGeometryRef())
        allocation = polygons.Buffer(600).Intersection(strict).MakeValid()
        cutline = folder / (item["id"] + ".geojson")
        # Explicit CRS is necessary: allocations are measured in EPSG:3857.
        save(cutline, {"type":"FeatureCollection", "crs":{"type":"name","properties":{"name":"EPSG:3857"}},
                       "features":[{"type":"Feature","properties":{"scene":item["id"]},
                                    "geometry":json.loads(allocation.ExportToJson())}]})
        left, right, bottom, top = allocation.GetEnvelope()
        bounds = tile_bounds(*grid([left,bottom,right,top], MAX_ZOOM), MAX_ZOOM)
        record = {"item":item,"cutline":str(cutline.resolve()),"bounds3857":bounds,
                  "addedCoarsePixels":int(needed.sum()),"allocatedKm2":allocation.GetArea()/1e6}
        selected.append(record)
        total += needed.sum()
    save(WORK / "download-plan.json", selected)
    log(stage="download_plan", scenes=len(selected), approximateAreaKm2=int(total)*0.04,
        entries=[{"scene":row["item"]["id"],"allocatedKm2":round(row["allocatedKm2"],2),
                  "addedCoarsePixels":row["addedCoarsePixels"]} for row in selected])


def warp_sources(args):
    configure_gdal()
    entries = json.loads((WORK / "download-plan.json").read_text(encoding="utf-8"))
    folder = WORK / "warped"
    folder.mkdir(exist_ok=True)
    def warp(entry):
        scene = entry["item"]["id"]
        output = folder / (scene + ".tif")
        marker = output.with_suffix(".complete.json")
        if marker.exists():
            log(stage="warp_cached", scene=scene, bytes=output.stat().st_size)
            return
        partial = folder / (scene + ".part.tif")
        asset = entry["item"]["assets"].get("tci") or entry["item"]["assets"]["rgb"]
        source = "/vsicurl/" + asset["href"]
        last = [-1]
        started = time.monotonic()
        def progress(fraction, message, data):
            tick = int(fraction * 20)
            if tick != last[0]:
                last[0] = tick
                log(stage="warp_progress", scene=scene, percent=round(fraction*100,1), seconds=round(time.monotonic()-started))
            return 1
        log(stage="warp_start", scene=scene, allocatedKm2=round(entry["allocatedKm2"],2))
        result = gdal.Warp(str(partial), source, options=gdal.WarpOptions(
            dstSRS="EPSG:3857", outputBounds=entry["bounds3857"],
            xRes=world_resolution(MAX_ZOOM), yRes=world_resolution(MAX_ZOOM),
            cutlineDSName=entry["cutline"], dstAlpha=True, dstNodata=0, resampleAlg="bilinear",
            creationOptions=["COMPRESS=DEFLATE","PREDICTOR=2","TILED=YES","BLOCKXSIZE=512","BLOCKYSIZE=512","BIGTIFF=IF_SAFER","NUM_THREADS=2"],
            warpMemoryLimit=128, multithread=True, callback=progress))
        if result is None:
            raise RuntimeError(scene)
        if result.RasterCount != 4:
            raise RuntimeError(f"Expected RGBA output: {scene}")
        for index, kind in enumerate((gdal.GCI_RedBand,gdal.GCI_GreenBand,gdal.GCI_BlueBand,gdal.GCI_AlphaBand),1):
            result.GetRasterBand(index).SetColorInterpretation(kind)
        result.FlushCache()
        result = None
        partial.replace(output)
        save(marker, {"scene":scene,"bytes":output.stat().st_size,"seconds":round(time.monotonic()-started)})
        log(stage="warp_done", scene=scene, bytes=output.stat().st_size, seconds=round(time.monotonic()-started))
    # Keep requests modest on the public service while processing independent scenes.
    with ThreadPoolExecutor(max_workers=2) as executor:
        for future in as_completed([executor.submit(warp, entry) for entry in entries]):
            future.result()
    # Priority allocations overlap to prevent seams; the primary, recent scenes win.
    sources = [str(folder / (entry["item"]["id"] + ".tif")) for entry in reversed(entries)]
    build_mosaic()
    log(stage="sources_complete", files=len(sources), bytes=sum(Path(path).stat().st_size for path in sources))


def region_bounds3857():
    strict = region()
    strict.Transform(osr.CoordinateTransformation(reference(4326), reference(3857)))
    left,right,bottom,top = strict.GetEnvelope()
    return [left,bottom,right,top]


def build_mosaic():
    entries = json.loads((WORK / "download-plan.json").read_text(encoding="utf-8"))
    sources = []
    for entry in reversed(entries):
        path = WORK / "warped" / (entry["item"]["id"]+".tif")
        marker = path.with_suffix(".complete.json")
        if not marker.is_file() or path.stat().st_size != json.loads(marker.read_text(encoding="utf-8"))["bytes"]:
            raise RuntimeError(f"Source not complete: {path}")
        raster = gdal.Open(str(path))
        colors = [raster.GetRasterBand(index).GetColorInterpretation() for index in range(1,raster.RasterCount+1)]
        if colors != [gdal.GCI_RedBand,gdal.GCI_GreenBand,gdal.GCI_BlueBand,gdal.GCI_AlphaBand]:
            raise RuntimeError(f"Source must be explicitly RGBA before mosaicking: {path}")
        raster = None
        sources.append(str(path))
    vrt = gdal.BuildVRT(str(WORK / "mosaic.vrt"),sources,options=gdal.BuildVRTOptions(
        resolution="highest",srcNodata=0,VRTNodata=0,
        outputBounds=tile_bounds(*grid(region_bounds3857(),MAX_ZOOM),MAX_ZOOM)))
    vrt = None
    expected = {Path(path).name for path in sources}
    included = {Path(node.text).name for node in ET.parse(WORK/'mosaic.vrt').findall('.//SourceFilename')}
    if included != expected:
        raise RuntimeError(f"Mosaic omitted sources: {sorted(expected-included)}")
    log(stage="mosaic_validated",sources=len(included))


def levels(args):
    configure_gdal()
    build_mosaic()
    bounds = region_bounds3857()
    folder = WORK / "levels"
    folder.mkdir(exist_ok=True)
    previous = WORK / "mosaic.vrt"
    for zoom in range(MAX_ZOOM - 1, MIN_ZOOM - 1, -1):
        output = folder / f"{zoom}.tif"
        marker = output.with_suffix(".complete.json")
        if marker.exists():
            previous = output
            continue
        log(stage="overview_start", zoom=zoom)
        partial = output.with_suffix(".part.tif")
        started = time.monotonic()
        last = [-1]
        def progress(fraction, message, data):
            tick = int(fraction*10)
            if tick > last[0]:
                last[0] = tick
                log(stage="overview_progress", zoom=zoom, percent=tick*10, seconds=round(time.monotonic()-started))
            return 1
        result = gdal.Warp(str(partial), str(previous), options=gdal.WarpOptions(
            dstSRS="EPSG:3857", outputBounds=tile_bounds(*grid(bounds, zoom), zoom),
            xRes=world_resolution(zoom), yRes=world_resolution(zoom),
            # Input already carries alpha; the exact municipal mask is reapplied
            # to every delivered PNG level in tiles(). Avoid repeating expensive
            # polygon transformations for the intermediate raster pyramid.
            dstAlpha=True, dstNodata=0,
            resampleAlg="average", multithread=True, warpMemoryLimit=128,
            warpOptions=["NUM_THREADS=4"],
            creationOptions=["COMPRESS=DEFLATE","PREDICTOR=2","TILED=YES","BIGTIFF=IF_SAFER","NUM_THREADS=2"],
            callback=progress))
        result.FlushCache()
        result = None
        partial.replace(output)
        save(marker, {"zoom":zoom,"bytes":output.stat().st_size})
        log(stage="overview_done", zoom=zoom, bytes=output.stat().st_size)
        previous = output


def file_hash(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def tiles(args):
    from PIL import Image
    configure_gdal()
    package = WORK / "package"
    package.mkdir(exist_ok=True)
    masks = WORK / "masks"
    masks.mkdir(exist_ok=True)
    bounds = region_bounds3857()
    inventory = []
    reports = []
    empty_buffer = io.BytesIO()
    Image.new("RGBA",(TILE_SIZE,TILE_SIZE),(0,0,0,0)).save(empty_buffer,format="PNG")
    empty_png = empty_buffer.getvalue()
    for zoom in range(MIN_ZOOM, MAX_ZOOM + 1):
        raster = WORK / "mosaic.vrt" if zoom == MAX_ZOOM else WORK / "levels" / f"{zoom}.tif"
        xmin,ymin,xmax,ymax = grid(bounds, zoom)
        outer = tile_bounds(xmin,ymin,xmax,ymax,zoom)
        mask_file = masks / f"{zoom}.tif"
        if not mask_file.exists():
            temporary_mask = mask_file.with_suffix('.part.tif')
            masked = gdal.GetDriverByName('GTiff').Create(str(temporary_mask),
                (xmax-xmin+1)*TILE_SIZE,(ymax-ymin+1)*TILE_SIZE,1,gdal.GDT_Byte,
                options=["COMPRESS=DEFLATE","TILED=YES","BIGTIFF=IF_SAFER"])
            masked.SetProjection(reference(3857).ExportToWkt())
            masked.SetGeoTransform((outer[0],world_resolution(zoom),0,outer[3],0,-world_resolution(zoom)))
            vector = ogr.Open(str(WORK/'region.geojson'))
            # RasterizeLayer transforms the layer's WGS84 coordinates into the
            # explicitly georeferenced destination, as in the coverage survey.
            gdal.RasterizeLayer(masked,[1],vector.GetLayer(),burn_values=[255])
            masked.FlushCache()
            masked = None
            temporary_mask.replace(mask_file)
        started = time.monotonic()
        log(stage="tiles_start",zoom=zoom,rows=ymax-ymin+1,columns=xmax-xmin+1)
        def row(y):
            source = gdal.Open(str(raster))
            expected_source = gdal.Open(str(mask_file))
            transform = source.GetGeoTransform()
            offset_x = round((outer[0]-transform[0])/world_resolution(zoom))
            offset_y = round((transform[3]-outer[3])/world_resolution(zoom)) + (y-ymin)*TILE_SIZE
            data = source.ReadAsArray(offset_x, offset_y, (xmax-xmin+1)*TILE_SIZE, TILE_SIZE)
            expected = expected_source.ReadAsArray(0,(y-ymin)*TILE_SIZE,expected_source.RasterXSize,TILE_SIZE) > 0
            inside = int(expected.sum())
            missing = int((expected & (data[3] == 0)).sum())
            outside = int((~expected & (data[3] > 0)).sum())
            data[:, ~expected] = 0
            records = []
            for x in range(xmin,xmax+1):
                pixel_x = (x-xmin)*TILE_SIZE
                pixels = data[:, :, pixel_x:pixel_x+TILE_SIZE]
                name = f"tiles/{zoom}/{x}/{y}.png"
                output = package / name
                output.parent.mkdir(parents=True, exist_ok=True)
                if not output.exists():
                    temporary = output.with_suffix(".part.png")
                    if pixels[3].any():
                        # Level 3 reduces build CPU while retaining identical pixels.
                        Image.fromarray(np.moveaxis(pixels,0,2)).save(temporary,format="PNG",compress_level=3)
                    else:
                        temporary.write_bytes(empty_png)
                    temporary.replace(output)
                records.append((name,output.stat().st_size,file_hash(output)))
            return records,inside,missing,outside
        expected_pixels = missing_pixels = removed = 0
        level_records = []
        completed = 0
        with ThreadPoolExecutor(max_workers=4) as executor:
            for future in as_completed([executor.submit(row,y) for y in range(ymin,ymax+1)]):
                records,inside,missing,outside = future.result()
                level_records.extend(records)
                expected_pixels += inside
                missing_pixels += missing
                removed += outside
                completed += 1
                if completed % 20 == 0 or completed == ymax-ymin+1:
                    log(stage="tiles_progress",zoom=zoom,rowsDone=completed,rowsTotal=ymax-ymin+1,tiles=len(level_records),seconds=round(time.monotonic()-started))
        inventory.extend(level_records)
        if expected_pixels == 0:
            raise RuntimeError(f"Empty municipality mask at zoom {zoom}")
        report = {"zoom":zoom,"tiles":len(level_records),"bytes":sum(row[1] for row in level_records),
                  "regionPixels":expected_pixels,"missingPixels":missing_pixels,
                  "coveredFraction":1-missing_pixels/max(1,expected_pixels),"outsidePixelsRemoved":removed,
                  "visibleOutsideRegionAfterMask":0,"seconds":round(time.monotonic()-started)}
        reports.append(report)
        save(WORK / "tile-coverage.json", reports)
        log(stage="tiles_done",**report)
    index = package / "tile-index.tsv"
    with index.open("w",encoding="utf-8",newline="\n") as stream:
        for path,size,digest in sorted(inventory):
            stream.write(f"{path}\t{size}\t{digest}\n")
    save(WORK / "tile-summary.json",{"tileCount":len(inventory),"tileBytes":sum(row[1] for row in inventory),
                                     "indexSha256":file_hash(index),"levels":reports})
    log(stage="tiles_complete",count=len(inventory),bytes=sum(row[1] for row in inventory))


def package(args):
    from PIL import Image
    folder = WORK / "package"
    summary = json.loads((WORK / "tile-summary.json").read_text(encoding="utf-8"))
    if summary["levels"][-1]["coveredFraction"] <= 0.999:
        raise RuntimeError("High-resolution coverage is below 99.9%; review missing data before installation")
    entries = json.loads((WORK / "download-plan.json").read_text(encoding="utf-8"))
    metadata = json.loads((ASSETS / "metadata.json").read_text(encoding="utf-8"))
    municipality_data = json.loads((ASSETS / "municipalities.geojson").read_text(encoding="utf-8"))
    inverse = osr.CoordinateTransformation(reference(3857),reference(4326))
    probes = []
    for feature in municipality_data["features"]:
        geometry = ogr.CreateGeometryFromJson(json.dumps(feature["geometry"]))
        point = geometry.PointOnSurface()
        longitude,latitude = point.GetX(),point.GetY()
        mx,my,_ = osr.CoordinateTransformation(reference(4326),reference(3857)).TransformPoint(longitude,latitude)
        for zoom in (MIN_ZOOM,12,MAX_ZOOM):
            span = 2*HALF_WORLD/2**zoom
            x = math.floor((mx+HALF_WORLD)/span)
            y = math.floor((HALF_WORLD-my)/span)
            path = f"tiles/{zoom}/{x}/{y}.png"
            source = folder / path
            if not source.is_file():
                raise RuntimeError(f"No image at municipality probe: {feature['properties']} / {path}")
            with Image.open(source) as image:
                if image.size != (TILE_SIZE,TILE_SIZE) or image.getchannel("A").getextrema()[1] == 0:
                    raise RuntimeError(f"Empty/corrupt municipality tile: {path}")
                if zoom == MAX_ZOOM:
                    px=min(255,int((mx-(-HALF_WORLD+x*span))/span*TILE_SIZE))
                    py=min(255,int(((HALF_WORLD-y*span)-my)/span*TILE_SIZE))
                    if image.getpixel((px,py))[3] == 0:
                        raise RuntimeError(f"Missing satellite pixel at {feature['properties']['NM_MUN']}")
            probes.append({"municipality":feature["properties"]["NM_MUN"],"code":feature["properties"]["CD_MUN"],
                           "longitude":longitude,"latitude":latitude,"zoom":zoom,"path":path,
                           "bytes":source.stat().st_size,"sha256":file_hash(source)})
    dates = sorted(entry["item"]["properties"]["datetime"][:10] for entry in entries)
    scenes = [{"id":entry["item"]["id"],"acquiredAt":entry["item"]["properties"]["datetime"][:10],
               "sourceUrl":f"{STAC}/collections/{COLLECTION}/items/{entry['item']['id']}",
               "sourceAsset":(entry["item"]["assets"].get("tci") or entry["item"]["assets"]["rgb"])["href"],
               "allocation":Path(entry["cutline"]).relative_to(WORK.resolve()).as_posix()} for entry in entries]
    # Keep provenance paths usable when the package leaves its build directory.
    for scene,entry in zip(scenes,entries):
        target = folder / scene["allocation"]
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(entry["cutline"],target)
    shutil.copyfile(WORK/'region.geojson',folder/'region.geojson')
    save(folder / "source-scenes.json",scenes)
    (folder / "NOTICE.md").write_text(
        "# Satélite offline do 8º BPM\n\nCBERS-4A / INPE, CC BY 4.0: https://creativecommons.org/licenses/by/4.0/\n\n"
        f"Imagens de {dates[0]} a {dates[-1]}. Produto RGB fusionado por PCA pelo INPE (PAN 2 m, multiespectral 8 m).\n\n"
        "Processamento local: seleção de cenas, mosaico, recorte à união dos 11 municípios IBGE 2024, reprojeção EPSG:3857, "
        "interpolação bilinear, pirâmide com média e blocos PNG XYZ 256×256. Máscara aplicada em cada nível de zoom. "
        "O app ajusta o brilho/contraste apenas na apresentação.\n\n"
        "As cenas têm datas diferentes. Pequenos vazios sem dado permanecem transparentes. A imagem não comprova "
        "transitabilidade atual nem modifica o grafo de navegação. Créditos, licença e alterações devem acompanhar redistribuições.\n",
        encoding="utf-8")
    manifest = {"schemaVersion":1,"complete":True,"packageId":"cbers8bpm-v1","title":"Satélite CBERS · 8º BPM",
        "bounds":metadata["visualBounds"],"projection":"EPSG:3857","scheme":"xyz","format":"png",
        "tileSize":TILE_SIZE,"minZoom":MIN_ZOOM,"maxZoom":MAX_ZOOM,"tileCount":summary["tileCount"],
        "tileBytes":summary["tileBytes"],"tileCountByZoom":{str(row["zoom"]):row["tiles"] for row in summary["levels"]},
        "indexSha256":summary["indexSha256"],"sourceSceneCount":len(entries),"startDate":dates[0],"endDate":dates[-1],
        "attribution":"CBERS-4A / INPE · CC BY 4.0","licenseUrl":"https://creativecommons.org/licenses/by/4.0/",
        "sourceUrl":f"{STAC}/collections/{COLLECTION}","municipalityCount":11,"strictMunicipalityMask":True,
        "pixelMeters3857AtMaxZoom":world_resolution(MAX_ZOOM),"probes":probes,"coverage":summary["levels"][-1],
        "createdAt":datetime.now(timezone.utc).isoformat()}
    save(folder / "package.json",manifest)
    archive = WORK / "cbers8bpm-v1.tar"
    temporary = archive.with_suffix(".part.tar")
    with tarfile.open(temporary,"w",format=tarfile.USTAR_FORMAT) as output:
        for line in (folder/"tile-index.tsv").read_text(encoding="utf-8").splitlines():
            name=line.split("\t")[0]
            if not re.fullmatch(r"tiles/\d+/\d+/\d+\.png",name):
                raise ValueError(f"Invalid tile index path: {name}")
            output.add(folder/name,arcname=name)
        for name in ["tile-index.tsv","source-scenes.json","NOTICE.md","region.geojson",
                     *[scene["allocation"] for scene in scenes],"package.json"]:
            output.add(folder/name,arcname=name)
    temporary.replace(archive)
    save(WORK / "package-receipt.json",{"archive":str(archive),"bytes":archive.stat().st_size,
        "sha256":file_hash(archive),"manifestSha256":file_hash(folder/"package.json"),"tileCount":manifest["tileCount"],
        "tileBytes":manifest["tileBytes"],"coveredFraction":manifest["coverage"]["coveredFraction"]})
    log(stage="package_complete",archive=str(archive),bytes=archive.stat().st_size,tiles=manifest["tileCount"])


def fixture(args):
    from PIL import Image
    folder = ROOT / "Kotlin_Teste/app/src/androidTest/assets/raster-fixture"
    folder.mkdir(parents=True,exist_ok=True)
    sample = json.loads((ASSETS / "satellite/sample.json").read_text(encoding="utf-8"))
    transform = osr.CoordinateTransformation(reference(4326),reference(3857))
    w,s,e,n = sample["bounds"]
    left,bottom,_ = transform.TransformPoint(w,s)
    right,top,_ = transform.TransformPoint(e,n)
    zoom = 14
    xmin,ymin,xmax,ymax = grid([left,bottom,right,top],zoom)
    raster = gdal.Warp("",str(ROOT / "dados_qgis/cbers_8bpm_teste/onalicio_cbers_rgb_2m_3857.tif"),
        options=gdal.WarpOptions(format="MEM",dstSRS="EPSG:3857",outputBounds=tile_bounds(xmin,ymin,xmax,ymax,zoom),
            xRes=world_resolution(zoom),yRes=world_resolution(zoom),dstAlpha=True,resampleAlg="average"))
    data = raster.ReadAsArray()
    count = 0
    for y in range(ymin,ymax+1):
        for x in range(xmin,xmax+1):
            pixels = data[:,(y-ymin)*256:(y-ymin+1)*256,(x-xmin)*256:(x-xmin+1)*256]
            path = folder / str(zoom) / str(x) / f"{y}.png"
            path.parent.mkdir(parents=True,exist_ok=True)
            Image.fromarray(np.moveaxis(pixels,0,2)).save(path)
            count += 1
    save(folder / "metadata.json",{"bounds":sample["bounds"],"zoom":zoom,"tiles":count,
        "attribution":sample["attribution"],"sourceUrl":sample["sourceUrl"],"licenseUrl":sample["licenseUrl"]})
    log(stage="fixture_complete",tiles=count)


def overview(args):
    from PIL import Image,ImageDraw,ImageFont
    entries=json.loads((WORK / "download-plan.json").read_text(encoding="utf-8"))
    result=None
    transform=None
    for entry in reversed(entries):
        source=gdal.Open(str(WORK / "previews" / (entry["item"]["id"]+".tif")))
        data=source.ReadAsArray()
        if result is None:
            result=np.zeros_like(data)
            transform=source.GetGeoTransform()
        allocation=gdal.Rasterize("",entry["cutline"],options=gdal.RasterizeOptions(format="MEM",
            outputSRS="EPSG:3857",outputBounds=[transform[0],transform[3]+data.shape[1]*transform[5],
                transform[0]+data.shape[2]*transform[1],transform[3]],
            width=data.shape[2],height=data.shape[1],burnValues=[255]))
        valid=(data[3]>0)&(allocation.ReadAsArray()>0)
        result[:,valid]=data[:,valid]
    base=Image.fromarray(np.moveaxis(result,0,2))
    canvas=Image.new("RGB",(base.width+40,base.height+120),"#13202b")
    canvas.paste(base,(20,72),base)
    draw=ImageDraw.Draw(canvas)
    font=ImageFont.truetype("C:/Windows/Fonts/arial.ttf",15)
    title=ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf",23)
    draw.text((20,12),"8º BPM · mosaico CBERS offline",font=title,fill="white")
    draw.text((20,43),"Prévia das cenas selecionadas · 2023–2026 · pacote em preparação",font=font,fill="#bdcbd4")
    projection=osr.CoordinateTransformation(reference(4326),reference(3857))
    municipalities=json.loads((ASSETS / "municipalities.geojson").read_text(encoding="utf-8"))
    def pixel(x,y): return 20+(x-transform[0])/transform[1],72+(y-transform[3])/transform[5]
    for feature in municipalities["features"]:
        geometry=ogr.CreateGeometryFromJson(json.dumps(feature["geometry"]))
        geometry.Transform(projection)
        polygons=[geometry] if geometry.GetGeometryType() in (ogr.wkbPolygon,ogr.wkbPolygon25D) else list(geometry)
        for polygon in polygons:
            for ring in polygon:
                draw.line([pixel(point[0],point[1]) for point in ring.GetPoints()],fill="#c4d5dd",width=1)
        point=geometry.PointOnSurface()
        x,y=pixel(point.GetX(),point.GetY())
        name=feature["properties"]["NM_MUN"].replace(" do Tocantins","").replace(" de Areia", " de Areia")
        draw.text((x,y),name,font=font,fill="white",stroke_width=2,stroke_fill="#13202b",anchor="mm")
    draw.text((20,canvas.height-29),"CBERS-4A/INPE · CC BY 4.0 | limites municipais IBGE 2024",font=font,fill="#bdcbd4")
    canvas.save(WORK / "coverage-overview.png")
    log(stage="coverage_overview",path=str(WORK / "coverage-overview.png"))


def main():
    global WORK, COLLECTION
    gdal.UseExceptions()
    ogr.UseExceptions()
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["survey", "thumbnails", "select", "previews", "plan", "warp", "levels", "tiles", "package", "fixture", "overview"])
    parser.add_argument("--dates", default="2026-06-01/2026-09-30")
    parser.add_argument("--per-group", type=int, default=3)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--collection", default=COLLECTION)
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    WORK, COLLECTION = args.work, args.collection
    {"survey": survey, "thumbnails": thumbnails, "select": select, "previews": previews,
     "plan": plan, "warp": warp_sources, "levels": levels, "tiles": tiles, "package": package, "fixture":fixture, "overview":overview}[args.stage](args)


if __name__ == "__main__":
    main()
