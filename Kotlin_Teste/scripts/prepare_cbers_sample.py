"""Prepare a bounded, attributed CBERS RGB sample for the offline Android map.

Run with QGIS's Python (GDAL/OGR included). Only HTTP range reads of the COG
are used; the full multi-gigabyte scene must never be downloaded.
"""
import argparse
import hashlib
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from osgeo import gdal, ogr, osr


ROOT = Path(__file__).resolve().parents[2]
COLLECTION = "CB4A-WPM-PCA-FUSED-1"
BASE = "https://data.inpe.br/bdc/stac/v1"
DEFAULT_SCENE = "CBERS4A_WPM_PCA_RGB321_20260804_210_125"
POLYGON = ROOT / "dados_qgis/vale_araguaia_11_municipios/regiao_batalhao_11_municipios_ibge2024.gpkg"
OUT = ROOT / "dados_qgis/cbers_8bpm_teste"
ASSETS = ROOT / "Kotlin_Teste/app/src/main/batalhaoAssets/batalhao/satellite"


def read_json(url):
    with urllib.request.urlopen(url, timeout=45) as response:
        return json.load(response)


def spatial_ref(code):
    reference = osr.SpatialReference()
    reference.ImportFromEPSG(code)
    reference.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return reference


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inspect", action="store_true")
    parser.add_argument("--scene", default=DEFAULT_SCENE)
    parser.add_argument("--longitude", type=float, default=-49.9269444)
    parser.add_argument("--latitude", type=float, default=-9.5666667)
    parser.add_argument("--side-meters", type=int, default=6144)
    parser.add_argument("--pixel-meters", type=float, default=2)
    args = parser.parse_args()
    gdal.UseExceptions()
    ogr.UseExceptions()
    source = ogr.Open(str(POLYGON))
    layers = []
    for i in range(source.GetLayerCount()):
        layer = source.GetLayerByIndex(i)
        layers.append({
            "name": layer.GetName(),
            "count": layer.GetFeatureCount(),
            "geometry": ogr.GeometryTypeToName(layer.GetGeomType()),
            "extent": layer.GetExtent() if layer.GetSpatialRef() else None,
            "srs": layer.GetSpatialRef().ExportToWkt() if layer.GetSpatialRef() else None,
        })
    if args.inspect:
        print(json.dumps({"gdal": gdal.VersionInfo(), "polygonLayers": layers}, ensure_ascii=False, indent=2))
        return

    pixels = round(args.side_meters / args.pixel_meters)
    if pixels < 1 or pixels > 4096:
        raise ValueError("Sample texture must be at most 4096 pixels on either side.")
    OUT.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)

    # Pick the dissolved region if present, otherwise union all municipalities.
    candidate = next((item for item in layers if item["count"] == 1 and "Polygon" in item["geometry"]), None)
    if candidate is None:
        candidate = next((item for item in layers if "Polygon" in item["geometry"]), None)
    if candidate is None:
        raise RuntimeError("No polygon coverage layer found.")
    layer = source.GetLayerByName(candidate["name"])
    source_srs = layer.GetSpatialRef().Clone()
    source_srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    transform = osr.CoordinateTransformation(source_srs, spatial_ref(4326))
    region = None
    for feature in layer:
        geometry = feature.GetGeometryRef().Clone()
        geometry.Transform(transform)
        geometry = geometry.MakeValid()
        region = geometry if region is None else region.Union(geometry)
    point = ogr.Geometry(ogr.wkbPoint)
    point.AddPoint(args.longitude, args.latitude)
    if not region.Contains(point):
        raise RuntimeError("Sample center is outside the strict 11-municipality region.")
    cutline = OUT / "limite_8bpm_sem_margem.geojson"
    save_json(cutline, {
        "type": "FeatureCollection",
        "features": [{"type": "Feature", "properties": {"source": POLYGON.name, "layer": candidate["name"]},
                      "geometry": json.loads(region.ExportToJson())}],
    })

    item_url = f"{BASE}/collections/{COLLECTION}/items/{args.scene}"
    item = read_json(item_url)
    collection = read_json(f"{BASE}/collections/{COLLECTION}")
    if collection.get("license") != "Creative-Commons-Attribution-4.0-International":
        raise RuntimeError("Unexpected collection licence; review before packaging.")
    asset = item["assets"].get("rgb") or item["assets"].get("tci")
    url = asset["href"]
    save_json(OUT / "cena_stac.json", item)
    save_json(OUT / "colecao_stac.json", collection)

    # Refuse a server which would return the whole scene instead of a range.
    request = urllib.request.Request(url, headers={"Range": "bytes=0-1023"})
    with urllib.request.urlopen(request, timeout=45) as response:
        if response.status != 206:
            raise RuntimeError(f"Server did not honor range request: HTTP {response.status}")
        range_header = response.headers.get("Content-Range")
        first_bytes = response.read(1024)
    if len(first_bytes) != 1024:
        raise RuntimeError("Unexpected range response length.")

    # Configure range reads and prevent probing for remote sidecar files.
    for key, value in {
        "GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR",
        "CPL_VSIL_CURL_ALLOWED_EXTENSIONS": ".tif",
        "GDAL_HTTP_TIMEOUT": "45",
        "GDAL_HTTP_CONNECTTIMEOUT": "15",
        "GDAL_HTTP_MAX_RETRY": "2",
        "GDAL_HTTP_RETRY_DELAY": "1",
        "GDAL_HTTP_MULTIRANGE": "YES",
        "CPL_VSIL_CURL_CACHE_SIZE": "33554432",
    }.items():
        gdal.SetConfigOption(key, value)

    forward = osr.CoordinateTransformation(spatial_ref(4326), spatial_ref(3857))
    inverse = osr.CoordinateTransformation(spatial_ref(3857), spatial_ref(4326))
    center_x, center_y, _ = forward.TransformPoint(args.longitude, args.latitude)
    half = args.side_meters / 2
    projected_bounds = [center_x - half, center_y - half, center_x + half, center_y + half]
    west, south, _ = inverse.TransformPoint(projected_bounds[0], projected_bounds[1])
    east, north, _ = inverse.TransformPoint(projected_bounds[2], projected_bounds[3])

    print(json.dumps({"stage": "range_verified", "scene": args.scene, "contentRange": range_header,
                      "fullSceneBytesNotDownloaded": asset.get("bdc:size"),
                      "outputPixels": [pixels, pixels], "bounds": [west, south, east, north]}, ensure_ascii=False), flush=True)
    remote = gdal.OpenEx("/vsicurl/" + url, gdal.OF_RASTER | gdal.OF_READONLY)
    geotiff = OUT / "onalicio_cbers_rgb_2m_3857.tif"
    warped = gdal.Warp(
        str(geotiff), remote,
        options=gdal.WarpOptions(
            format="GTiff", dstSRS="EPSG:3857", outputBounds=projected_bounds,
            width=pixels, height=pixels, resampleAlg="bilinear",
            cutlineDSName=str(cutline), cropToCutline=False,
            dstAlpha=True, dstNodata=0,
            creationOptions=["COMPRESS=DEFLATE", "PREDICTOR=2", "TILED=YES"],
            multithread=False, warpMemoryLimit=64,
        ),
    )
    if warped is None:
        raise RuntimeError("GDAL failed to prepare the sample.")
    warped.FlushCache()
    remote = None

    png = ASSETS / "sample.png"
    image = gdal.Translate(str(png), warped, format="PNG",
                           creationOptions=["ZLEVEL=6"], outputType=gdal.GDT_Byte)
    image.FlushCache()
    image = None
    warped = None
    if png.stat().st_size > 40 * 1024 * 1024:
        raise RuntimeError("Sample PNG exceeds the 40 MiB package budget.")
    metadata = {
        "title": "Satélite CBERS · Onalício Barros",
        "acquiredAt": item["properties"]["datetime"][:10],
        "bounds": [west, south, east, north],
        "image": "sample.png",
        "attribution": "CBERS-4A / INPE · CC BY 4.0",
        "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
        "sourceUrl": item_url,
        "imageSha256": sha256(png),
        "imageBytes": png.stat().st_size,
        "width": pixels,
        "height": pixels,
        "pixelMeters3857": args.pixel_meters,
        "projection": "EPSG:3857",
        "processing": "RGB fusionado por PCA pelo INPE; recorte, reprojeção EPSG:3857 e máscara dos 11 municípios.",
        "nativeResolution": {"panchromaticMeters": 2, "multispectralMeters": 8},
        "strictMunicipalityMask": True,
    }
    save_json(ASSETS / "sample.json", metadata)
    (ASSETS / "NOTICE.md").write_text(
        "# Imagem de satélite — amostra Onalício Barros\n\n"
        f"CBERS-4A / INPE. Cena `{args.scene}`, adquirida em {metadata['acquiredAt']}.\n\n"
        "Licença Creative Commons Atribuição 4.0 Internacional (CC BY 4.0): "
        "https://creativecommons.org/licenses/by/4.0/\n\n"
        f"Fonte e metadados: {item_url}\n\n"
        "Produto RGB fusionado por PCA pelo INPE, com pixel de 2 m. Resoluções nativas: "
        "pancromática de 2 m e multiespectral de 8 m. A preparação local recortou e "
        "reprojetou o produto para EPSG:3857, com interpolação bilinear, e aplicou a máscara "
        "dos limites municipais IBGE 2024 dos 11 municípios do 8º BPM, sem margem externa.\n\n"
        "Esta é uma amostra de visualização; não comprova condições atuais de passagem "
        "e não altera o grafo de roteamento. Licença e crédito devem acompanhar redistribuições.\n",
        encoding="utf-8",
    )
    save_json(OUT / "proveniencia.json", {
        **metadata, "scene": args.scene, "cogSourceUrl": url,
        "fullSceneBytes": asset.get("bdc:size"), "rangeProbe": range_header,
        "coverageSource": str(POLYGON), "coverageLayer": candidate["name"],
        "geotiff": str(geotiff), "geotiffSha256": sha256(geotiff),
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "cloudCoverMetadata": item["properties"].get("eo:cloud_cover"),
        "cloudQuality": "Requer inspeção visual da amostra; metadado de nuvens ausente.",
    })
    # The app needs only PNG and JSON; sidecars remain available with the GeoTIFF.
    sidecar = Path(str(png) + ".aux.xml")
    if sidecar.exists():
        sidecar.replace(OUT / "sample.png.aux.xml")
    print(json.dumps({"stage": "complete", "png": str(png), "metadata": str(ASSETS / "sample.json"),
                      "imageBytes": png.stat().st_size, "geoTiffBytes": geotiff.stat().st_size,
                      "scene": args.scene, "bounds": metadata["bounds"]}, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
