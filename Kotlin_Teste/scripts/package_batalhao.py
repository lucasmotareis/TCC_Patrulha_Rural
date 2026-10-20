"""Build the reproducible 8 BPM vector package from a previously validated graph.

Visual features are clipped to the union of the eleven IBGE municipalities.
Routing retains the existing 15 km operational buffer and is never rebuilt here.
Dependencies: osmium, shapely and pyproj (requirements-batalhao.txt).
"""
import argparse
from collections import Counter
from contextlib import closing
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import struct
import tarfile

import osmium
from pyproj import Transformer
import shapely
from shapely.geometry import shape
from shapely.ops import unary_union

import prepare_palmas as visual

REPOSITORY = Path(__file__).resolve().parents[2]
DEFAULT_REGION = REPOSITORY / "dados_qgis/vale_araguaia_11_municipios"
DEFAULT_SETTLEMENTS = REPOSITORY / "dados_qgis/assentamentos_lista_compartilhada/2026-10-19"
DEFAULT_AREAS = REPOSITORY / "dados_qgis/assentamentos_cigma/2026-10-20"
DEFAULT_ACCESS = REPOSITORY / "dados_qgis/acessos_assentamentos/revisao_2026-10-20"
POLYGONS = ("Polygon", "MultiPolygon")
LINES = ("LineString", "MultiLineString")
EXTRA_LAYERS = ("region", "municipalities", "settlements", "settlement_areas", "access_roads")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def file_record(path):
    try:
        source = path.resolve().relative_to(REPOSITORY).as_posix()
    except ValueError:
        source = path.name
    return {"path": source, "bytes": path.stat().st_size, "sha256": sha256(path)}


def gpkg_geometry(blob):
    """Decode the standard GeoPackage header before the WKB payload."""
    if blob[:2] != b"GP" or blob[2] != 0:
        raise ValueError("Unsupported GeoPackage geometry header")
    flags = blob[3]
    envelope = (flags >> 1) & 7
    if envelope > 4 or flags & 0x20:
        raise ValueError("Unsupported extended GeoPackage geometry")
    header_size = 8 + (0, 32, 48, 48, 64)[envelope]
    srs_id = struct.unpack("<i" if flags & 1 else ">i", blob[4:8])[0]
    return shapely.from_wkb(blob[header_size:]), srs_id


def read_municipalities(path, layer):
    """Read polygons without needing a QGIS process or a GDAL installation."""
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        metadata = db.execute(
            "SELECT column_name, srs_id FROM gpkg_geometry_columns WHERE table_name=?", (layer,)
        ).fetchone()
        if metadata is None:
            raise ValueError(f"Municipality layer not found: {layer}")
        geometry_column, srs_id = metadata
        projection = db.execute(
            "SELECT organization, organization_coordsys_id, definition "
            "FROM gpkg_spatial_ref_sys WHERE srs_id=?", (srs_id,)
        ).fetchone()
        source_crs = f"EPSG:{projection[1]}" if projection[0].upper() == "EPSG" else projection[2]
        transformer = Transformer.from_crs(source_crs, "EPSG:4326", always_xy=True)
        rows = db.execute('SELECT * FROM "' + layer.replace('"', '""') + '"').fetchall()
    features = []
    for row in rows:
        geometry, geometry_srs = gpkg_geometry(row[geometry_column])
        if geometry_srs != srs_id:
            raise ValueError("Geometry SRS differs from the GeoPackage layer")
        geometry = shapely.make_valid(shapely.transform(geometry, transformer.transform, interleaved=False))
        geometry = geometry_parts(geometry, POLYGONS)
        if geometry.is_empty:
            raise ValueError("Empty municipality polygon")
        properties = {key: row[key] for key in row.keys() if key != geometry_column}
        properties.update(id=properties["CD_MUN"], nome=properties["NM_MUN"])
        features.append(visual.feature(geometry, properties, properties["CD_MUN"]))
    features.sort(key=lambda feature: feature["properties"]["CD_MUN"])
    return features, source_crs


def geometry_parts(geometry, allowed):
    """Keep only the intended dimension after validity repair/intersection."""
    if geometry.geom_type in allowed:
        return geometry
    if hasattr(geometry, "geoms"):
        parts = [geometry_parts(part, allowed) for part in geometry.geoms]
        parts = [part for part in parts if not part.is_empty]
        if parts:
            return unary_union(parts)
    return shapely.GeometryCollection()


def clip_features(features, extent, allowed):
    result, outside, repaired = [], [], []
    for index, feature in enumerate(features):
        geometry = shape(feature["geometry"])
        identifier = feature.get("id", feature.get("properties", {}).get("id", index + 1))
        if not geometry.is_valid:
            repaired.append(identifier)
            geometry = shapely.make_valid(geometry)
        clipped = geometry_parts(geometry.intersection(extent), allowed)
        if clipped.is_empty:
            outside.append(identifier)
            continue
        # Discard stale source bbox values: intersection may change the bounds.
        result.append(visual.feature(clipped, copy.deepcopy(feature.get("properties", {})), identifier))
    return result, {"input": len(features), "included": len(result),
                    "outsideRegionIds": outside, "repairedGeometryIds": repaired}


def settlement_features(path, extent, excluded_ids):
    features = read_json(path)["features"]
    selected, excluded = [], []
    for feature in features:
        properties = feature["properties"]
        identifier = int(properties["id"])
        if identifier in excluded_ids or properties.get("status_revisao") == "incorreto":
            excluded.append({"id": identifier, "nome": properties.get("nome"),
                             "reason": "registro_incorreto" if properties.get("status_revisao") == "incorreto"
                             else "exclusao_explicita"})
        else:
            selected.append(feature)
    result, report = clip_features(selected, extent, ("Point",))
    report.update(input=len(features), explicitlyExcluded=excluded,
                  statusCounts=dict(Counter(f["properties"]["status_revisao"] for f in result)))
    return result, report


def area_features(path, extent):
    features = read_json(path)["features"]
    for index, feature in enumerate(features, 1):
        feature["id"] = f"cigma-{index}"
        feature["properties"].update(
            id=feature["id"], nome=feature["properties"].get("Nome do Assentamento", ""),
            fonte_perimetro="CIGMA / INCRA", validacao_campo="Nao realizada"
        )
    return clip_features(features, extent, POLYGONS)


def access_features(paths, extent):
    features, excluded = [], []
    for path in paths:
        for feature in read_json(path)["features"]:
            if feature["properties"].get("status") != "revisado":
                excluded.append({"id": feature.get("id"), "reason": "acesso_nao_revisado"})
                continue
            features.append(feature)
    result, report = clip_features(features, extent, LINES)
    report["unreviewedExcluded"] = excluded
    return result, report


def build_style(template, version):
    style = json.loads(json.dumps(template).replace("asset://palmas/", "asset://batalhao/"))
    style["name"] = "8º BPM · Vale do Araguaia · offline"
    style["metadata"] = {"packageVersion": version, "visualClip": "union-of-11-municipalities"}
    for name in EXTRA_LAYERS:
        style["sources"][name] = {"type": "geojson", "data": f"asset://batalhao/{name}.geojson"}
    for layer in style["layers"]:
        if layer["id"] == "streams":
            layer.setdefault("layout", {})["visibility"] = "none"
        if layer["id"] == "place-labels":
            layer["minzoom"] = 7
        if layer["id"] == "road-labels":
            layer["minzoom"] = 13
    territorial_layers = [
        {"id": "municipality-boundaries", "type": "line", "source": "municipalities",
         "minzoom": 7, "paint": {"line-color": "#88938F", "line-opacity": 0.65,
                               "line-width": 1, "line-dasharray": [4, 3]}},
        {"id": "settlement-area-fill", "type": "fill", "source": "settlement_areas",
         "minzoom": 8, "paint": {"fill-color": "#F4D35E", "fill-opacity": 0.13}},
        {"id": "settlement-area-outline", "type": "line", "source": "settlement_areas",
         "minzoom": 8, "paint": {"line-color": "#C49B20", "line-opacity": 0.8,
                               "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.7, 14, 1.8]}},
        {"id": "region-boundary", "type": "line", "source": "region",
         "paint": {"line-color": "#546D78", "line-opacity": 0.9,
                   "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.3, 12, 2.3]}}
    ]
    access_layers = [
        {"id": "access-roads-casing", "type": "line", "source": "access_roads",
         "minzoom": 10, "layout": {"line-cap": "round", "line-join": "round"},
         "paint": {"line-color": "#FFFFFF", "line-width":
                   ["interpolate", ["linear"], ["zoom"], 10, 3, 16, 7]}},
        {"id": "access-roads", "type": "line", "source": "access_roads",
         "minzoom": 10, "layout": {"line-cap": "round", "line-join": "round"},
         "paint": {"line-color": "#278249", "line-width":
                   ["interpolate", ["linear"], ["zoom"], 10, 1.5, 16, 4.5]}}
    ]
    point_layers = [
        {"id": "settlement-points", "type": "circle", "source": "settlements",
         "minzoom": 7, "paint": {
             "circle-radius": ["interpolate", ["linear"], ["zoom"], 7, 3, 12, 5, 16, 7],
             "circle-color": ["match", ["get", "status_revisao"],
                              "pendente_verificacao", "#CF831F", "#4C657B"],
             "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 1.5}},
        {"id": "settlement-labels", "type": "symbol", "source": "settlements", "minzoom": 10,
         "layout": {"text-field": ["get", "nome"], "text-font": ["Open Sans Semibold"],
                    "text-size": ["interpolate", ["linear"], ["zoom"], 10, 10, 15, 13],
                    "text-anchor": "top", "text-offset": [0, 0.8], "text-max-width": 17,
                    "text-optional": True},
         "paint": {"text-color": "#33485A", "text-halo-color": "#FFFFFF", "text-halo-width": 1.8}},
        {"id": "settlement-review-labels", "type": "symbol", "source": "settlements",
         "minzoom": 14, "layout": {
             "text-field": ["match", ["get", "status_revisao"],
                            "pendente_verificacao", "Verificação pendente",
                            "nao_revisado", "Não revisado", ["get", "status_revisao"]],
             "text-font": ["Open Sans Semibold"], "text-size": 10,
             "text-anchor": "bottom", "text-offset": [0, -0.8]},
         "paint": {"text-color": "#76592A", "text-halo-color": "#FFFFFF", "text-halo-width": 1.5}}
    ]
    # Keep route and origin/destination layers above the operational overlays.
    road_index = next(i for i, layer in enumerate(style["layers"]) if layer["id"] == "road-casing")
    style["layers"][road_index:road_index] = territorial_layers
    route_index = next(i for i, layer in enumerate(style["layers"]) if layer["id"] == "route-casing")
    style["layers"][route_index:route_index] = access_layers + point_layers
    return style


def check_validated_inputs(osm, routing_tar, validation_path):
    report = read_json(validation_path)
    checks = {}
    for key, path, report_key in (("osm", osm, "osm_teste"), ("routing", routing_tar, "grafo_teste")):
        expected = report["arquivos"][report_key]
        actual = file_record(path)
        if actual["sha256"] != expected["sha256"] or actual["bytes"] != expected["bytes"]:
            raise ValueError(f"{key} does not match the previously validated input: {path}")
        checks[key] = {"matchesValidatedInput": True, **actual}
    results = report["depois"]
    if not results or not all(result.get("ok") and result.get("usa_acesso_novo") for result in results.values()):
        raise ValueError("The supplied report does not confirm all Onalício routing checks")
    checks["previousRoutingTests"] = results
    checks["validationReport"] = file_record(validation_path)
    return checks


def assert_spatial_integrity(layers, extent):
    """Allow only floating-point overlay noise, never an operational margin."""
    tolerance = 1e-10  # degrees; ~0.01 mm
    safe_extent = extent.buffer(tolerance)
    shapely.prepare(safe_extent)
    counts = {}
    for name, features in layers.items():
        for feature in features:
            geometry = shape(feature["geometry"])
            if geometry.is_empty or not geometry.is_valid:
                raise ValueError(f"Empty/invalid geometry: {name}/{feature.get('id')}")
            if not safe_extent.covers(geometry):
                raise ValueError(f"Feature outside the eleven municipalities: {name}/{feature.get('id')}")
        counts[name] = len(features)
    ids = [f["properties"]["id"] for f in layers["settlements"]]
    if len(ids) != len(set(ids)) or 40 in ids:
        raise ValueError("Settlement IDs are duplicated or the known incorrect point 40 is present")
    return {"passed": True, "checkedFeatures": counts, "coordinateToleranceDegrees": tolerance}


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=REPOSITORY / "dados_batalhao")
    parser.add_argument("--coverage", type=Path, default=DEFAULT_REGION / "cobertura_com_margem.geojson",
                        help="Existing routing coverage with 15 km buffer; never used as visual mask")
    parser.add_argument("--region", type=Path,
                        default=DEFAULT_REGION / "regiao_batalhao_11_municipios_ibge2024.gpkg")
    parser.add_argument("--municipalities-layer", default="municipios")
    parser.add_argument("--settlements", type=Path, default=DEFAULT_SETTLEMENTS / "assentamentos_8bpm.geojson")
    parser.add_argument("--settlement-areas", type=Path,
                        default=DEFAULT_AREAS / "assentamentos_tocantins_cigma.geojson")
    parser.add_argument("--access-roads", type=Path, nargs="+",
                        default=[DEFAULT_ACCESS / "acesso_onalicio_revisado.geojson"])
    parser.add_argument("--osm", type=Path, help="Default: DATA/batalhao_onalicio_v1.osm.pbf")
    parser.add_argument("--routing-tar", type=Path, help="Default: DATA/onalicio_graph_v1/valhalla_tiles.tar")
    parser.add_argument("--routing-validation", type=Path,
                        help="Default: DATA/onalicio_teste/validacao_onalicio.json")
    parser.add_argument("--exclude-settlement-id", type=int, action="append", default=[40])
    parser.add_argument("--version", default="8bpm-vector-2026-10-20-v1")
    parser.add_argument("--assets", type=Path, required=True)
    return parser.parse_args()


def main():
    args = arguments()
    osm_path = args.osm or args.data / "batalhao_onalicio_v1.osm.pbf"
    routing_path = args.routing_tar or args.data / "onalicio_graph_v1/valhalla_tiles.tar"
    validation_path = args.routing_validation or args.data / "onalicio_teste/validacao_onalicio.json"
    validated_inputs = check_validated_inputs(osm_path, routing_path, validation_path)
    municipalities, source_crs = read_municipalities(args.region, args.municipalities_layer)
    if len(municipalities) != 11:
        raise ValueError(f"Expected exactly 11 municipalities, found {len(municipalities)}")
    extent = unary_union([shape(feature["geometry"]) for feature in municipalities])
    if not extent.is_valid or extent.is_empty or extent.geom_type not in POLYGONS:
        raise ValueError("Invalid municipality union")
    routing_extent = unary_union([shape(f["geometry"]) for f in read_json(args.coverage)["features"]])
    if not routing_extent.covers(extent):
        raise ValueError("Visual territory is not covered by the routing extraction region")
    visual.EXTENT = extent
    visual.WEST, visual.SOUTH, visual.EAST, visual.NORTH = extent.bounds
    shapely.prepare(extent)
    handler = visual.VisualMap()
    print("Reading validated OSM data and clipping the visual layers to 11 municipalities...", flush=True)
    # libosmium on Windows does not accept the accented absolute workspace path.
    # A relative path preserves access to the same input without lossy encoding.
    processor = (osmium.FileProcessor(os.path.relpath(osm_path)).with_locations("sparse_mem_array")
                 .with_areas().with_filter(osmium.filter.KeyFilter(
                     "highway", "waterway", "place", "natural", "landuse", "leisure", "building")))
    for obj in processor:
        if obj.is_node():
            handler.node(obj)
        elif obj.is_way():
            handler.way(obj)
        elif obj.is_area():
            handler.area(obj)
    # VisualMap simplifies after clipping and selects places by bounding box.
    # Reclip every output, including points, to enforce the exact territory.
    layers, reports = {}, {}
    for name, allowed in (("roads", LINES), ("areas", POLYGONS),
                          ("waterways", LINES), ("places", ("Point",))):
        layers[name], reports[name] = clip_features(getattr(handler, name), extent, allowed)
    layers["municipalities"] = municipalities
    layers["region"] = [visual.feature(extent, {
        "id": "8bpm", "nome": "8º BPM · Vale do Araguaia", "municipios": 11,
        "fonte": "IBGE — malha municipal 2024", "visual_buffer_km": 0
    }, "8bpm")]
    layers["settlements"], reports["settlements"] = settlement_features(
        args.settlements, extent, set(args.exclude_settlement_id))
    layers["settlement_areas"], reports["settlement_areas"] = area_features(args.settlement_areas, extent)
    layers["access_roads"], reports["access_roads"] = access_features(args.access_roads, extent)
    if not layers["roads"] or not layers["settlements"] or not layers["access_roads"]:
        raise ValueError("Required roads, settlement points or reviewed accesses are missing")
    spatial_validation = assert_spatial_integrity(layers, extent)
    out = args.assets / "batalhao"
    out.mkdir(parents=True, exist_ok=True)
    for name, features in layers.items():
        visual.write_geojson(out / f"{name}.geojson", features)
    old = Path(__file__).resolve().parents[1] / "app/src/main/assets/palmas"
    style = build_style(read_json(old / "style.json"), args.version)
    write_json(out / "style.json", style)
    shutil.copytree(old / "fonts", out / "fonts", dirs_exist_ok=True)
    tar = args.assets / "batalhao_tiles_v1.tar"
    shutil.copy2(routing_path, tar)
    with tarfile.open(tar) as archive:
        members = archive.getmembers()
        counts = {str(z): sum(1 for member in members
                             if member.name.startswith(f"{z}/") and member.name.endswith(".gph"))
                  for z in range(3)}
    routing_hash = sha256(tar)
    if routing_hash != validated_inputs["routing"]["sha256"] or not all(counts.values()):
        raise ValueError("Packaged routing graph failed integrity/hierarchy verification")
    sources = {"osm": file_record(osm_path), "municipalities": file_record(args.region),
               "routingCoverage": file_record(args.coverage), "settlements": file_record(args.settlements),
               "settlementAreas": file_record(args.settlement_areas),
               "accessRoads": [file_record(path) for path in args.access_roads]}
    content_hashes = {path.relative_to(args.assets).as_posix(): file_record(path)
                      for path in sorted(out.rglob("*")) if path.is_file()
                      and path.name not in ("metadata.json", "validation_report.json", "NOTICE.md")}
    metadata = {
        "packageVersion": args.version, "crs": "EPSG:4326", "municipalitySourceCrs": source_crs,
        "bounds": list(routing_extent.bounds), "routingBounds": list(routing_extent.bounds),
        "visualBounds": list(extent.bounds), "visualBufferKm": 0, "routingBufferKm": 15,
        "description": "Mapa visual recortado estritamente à união dos 11 municípios do 8º BPM. "
                       "Grafo offline preserva a margem operacional de 15 km.",
        "source": "OSM/Geofabrik Norte 2026-10-05; IBGE 2024; lista local 2026-10-19; "
                  "CIGMA/INCRA e acesso Onalício revisado 2026-10-20",
        "license": "ODbL-1.0 (base OSM); fontes complementares identificadas em NOTICE.md",
        "featureCounts": {name: len(features) for name, features in layers.items()},
        "routingTilesByLevel": counts, "routingBytes": tar.stat().st_size, "routingSha256": routing_hash,
        "invalidGeometriesSkipped": handler.invalid_geometries,
        "settlementReviewCounts": reports["settlements"]["statusCounts"],
        "exclusionsAndClipping": reports, "sources": sources, "contentFiles": content_hashes,
        "spatialValidation": spatial_validation,
        "routingValidation": validated_inputs,
        "limitations": [
            "Marcadores mantêm o status original; revisão geométrica de acesso não valida o marcador em campo.",
            "Perímetros CIGMA/INCRA são referência temática e foram recortados na divisa regional.",
            "Condições de piso, porteiras, pontes e atoleiros do acesso Onalício não foram informadas.",
            "O recorte visual é estrito; rotas podem usar trechos externos necessários dentro da margem operacional."
        ]
    }
    write_json(out / "metadata.json", metadata)
    write_json(out / "validation_report.json", {
        "packageVersion": args.version, "spatialIntegrity": spatial_validation,
        "routingArchiveMatchesValidatedSource": True, "routingSha256": routing_hash,
        "inputValidation": validated_inputs, "featureCounts": metadata["featureCounts"],
        "clippingAndExclusions": reports, "sourceFiles": sources, "contentFiles": content_hashes
    })
    (out / "NOTICE.md").write_text(
        "Dados de base © colaboradores do OpenStreetMap, ODbL 1.0: "
        "https://www.openstreetmap.org/copyright\n"
        "Extrato Geofabrik Norte de 2026-10-05.\n"
        "Limites municipais: IBGE, malha municipal 2024, onze municípios do Vale do Araguaia.\n"
        "Marcadores: lista compartilhada de uso do 8º BPM, coletada em 2026-10-19. "
        "Nomes, procedência e status originais preservados. Registro incorreto 40 excluído.\n"
        "Perímetros: CIGMA, fonte declarada INCRA, coletados em 2026-10-20. "
        "Geometrias recortadas ao território; a área original em hectares permanece como atributo de origem.\n"
        "Acessos locais: desenhos do usuário no QGIS, uso autorizado ao batalhão; não publicados no OSM. "
        "Grafo inclui Chácara 2 Meninos e Onalício Barros; a camada operacional destaca o acesso "
        "revisado a Onalício. Revisão geométrica e teste de rota não constituem validação em campo.\n"
        "Fontes Open Sans: licença e arquivos preservados do pacote original.\n", encoding="utf-8")
    print(json.dumps({
        "packageVersion": args.version, "featureCounts": metadata["featureCounts"],
        "settlementReviewCounts": metadata["settlementReviewCounts"],
        "settlementsExcluded": reports["settlements"],
        "visualBounds": metadata["visualBounds"], "routingSha256": routing_hash,
        "routingTilesByLevel": counts, "spatialValidation": spatial_validation,
        "totalAssetBytes": sum(path.stat().st_size for path in args.assets.rglob("*") if path.is_file())
    }, ensure_ascii=True, indent=2), flush=True)


if __name__ == "__main__":
    main()
