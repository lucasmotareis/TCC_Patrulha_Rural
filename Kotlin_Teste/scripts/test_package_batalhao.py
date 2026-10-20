"""Regression checks for territorial clipping, review states and package integrity."""
import json
from pathlib import Path
import tempfile
import unittest

from shapely.geometry import LineString, Point, Polygon, box, mapping, shape
from shapely.ops import unary_union

import package_batalhao as package


def feature(geometry, identifier, **properties):
    return {"type": "Feature", "id": identifier, "geometry": mapping(geometry),
            "properties": {"id": identifier, **properties}}


class ClippingTest(unittest.TestCase):
    def test_point_in_bbox_but_outside_polygon_is_excluded(self):
        extent = Polygon([(0, 0), (4, 0), (0, 4), (0, 0)])
        points = [feature(Point(1, 1), 1), feature(Point(3, 3), 2), feature(Point(0, 0), 3)]
        included, report = package.clip_features(points, extent, ("Point",))
        self.assertEqual([1, 3], [f["id"] for f in included])
        self.assertEqual([2], report["outsideRegionIds"])

    def test_crossing_lines_are_trimmed_and_polygon_holes_preserved(self):
        extent = Polygon([(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)],
                         holes=[[(1, 1), (3, 1), (3, 3), (1, 3), (1, 1)]])
        lines, _ = package.clip_features([feature(LineString([(-1, 2), (5, 2)]), 1)],
                                         extent, package.LINES)
        self.assertEqual("MultiLineString", lines[0]["geometry"]["type"])
        self.assertEqual(2, shape(lines[0]["geometry"]).length)
        polygons, _ = package.clip_features([feature(box(-1, -1, 5, 5), 2)],
                                            extent, package.POLYGONS)
        self.assertTrue(shape(polygons[0]["geometry"]).equals(extent))

    def test_repair_retains_polygon_and_removes_touch_only_feature(self):
        bowtie = Polygon([(0, 0), (2, 2), (2, 0), (0, 2), (0, 0)])
        included, report = package.clip_features(
            [feature(bowtie, 1), feature(box(2, 0, 3, 1), 2)], box(0, 0, 2, 2), package.POLYGONS)
        self.assertEqual(1, len(included))
        self.assertTrue(shape(included[0]["geometry"]).is_valid)
        self.assertEqual([1], report["repairedGeometryIds"])
        self.assertEqual([2], report["outsideRegionIds"])

    def test_review_states_are_preserved_and_incorrect_points_removed(self):
        original = [
            feature(Point(1, 1), 4, nome="Pendente", status_revisao="pendente_verificacao"),
            feature(Point(1, 1), 44, nome="Onalício", status_revisao="nao_revisado"),
            feature(Point(1, 1), 40, nome="Incorreto", status_revisao="incorreto"),
            feature(Point(5, 5), 60, nome="Externo", status_revisao="nao_revisado")]
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "points.geojson"
            source.write_text(json.dumps({"type": "FeatureCollection", "features": original}), encoding="utf-8")
            included, report = package.settlement_features(source, box(0, 0, 2, 2), {40})
        self.assertEqual([4, 44], [f["id"] for f in included])
        self.assertEqual(original[0]["properties"], included[0]["properties"])
        self.assertEqual(original[1]["properties"], included[1]["properties"])
        self.assertEqual([60], report["outsideRegionIds"])
        self.assertEqual(40, report["explicitlyExcluded"][0]["id"])

    def test_validation_rejects_geometries_outside_actual_region(self):
        extent = Polygon([(0, 0), (4, 0), (0, 4), (0, 0)])
        with self.assertRaisesRegex(ValueError, "outside"):
            package.assert_spatial_integrity(
                {"settlements": [feature(Point(3, 3), 1)]}, extent)


class PackagedDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assets = package.REPOSITORY / "Kotlin_Teste/app/src/main/batalhaoAssets"
        cls.out = cls.assets / "batalhao"
        cls.metadata = package.read_json(cls.out / "metadata.json")
        cls.layers = {name: package.read_json(cls.out / f"{name}.geojson")["features"]
                      for name in cls.metadata["featureCounts"]}

    def test_all_layers_match_territory_and_metadata(self):
        municipalities, _ = package.read_municipalities(
            package.DEFAULT_REGION / "regiao_batalhao_11_municipios_ibge2024.gpkg", "municipios")
        extent = unary_union([shape(f["geometry"]) for f in municipalities])
        self.assertTrue(shape(self.layers["region"][0]["geometry"]).equals(extent))
        self.assertEqual(11, len(self.layers["municipalities"]))
        self.assertEqual(self.metadata["featureCounts"],
                         {name: len(features) for name, features in self.layers.items()})
        self.assertTrue(package.assert_spatial_integrity(self.layers, extent)["passed"])
        self.assertEqual(list(extent.bounds), self.metadata["visualBounds"])
        self.assertEqual(0, self.metadata["visualBufferKm"])
        self.assertEqual(15, self.metadata["routingBufferKm"])

    def test_settlements_keep_original_attributes(self):
        original = {f["properties"]["id"]: f["properties"] for f in
                    package.read_json(package.DEFAULT_SETTLEMENTS / "assentamentos_8bpm.geojson")["features"]}
        exported = {f["properties"]["id"]: f["properties"] for f in self.layers["settlements"]}
        self.assertNotIn(40, exported)
        self.assertIn(44, exported)
        self.assertEqual("nao_revisado", exported[44]["status_revisao"])
        for identifier, properties in exported.items():
            self.assertEqual(original[identifier], properties)
        for identifier in (4, 41):
            self.assertEqual("pendente_verificacao", exported[identifier]["status_revisao"])

    def test_packaged_graph_matches_validated_graph(self):
        graph = self.assets / "batalhao_tiles_v1.tar"
        original = package.REPOSITORY / "dados_batalhao/onalicio_graph_v1/valhalla_tiles.tar"
        self.assertEqual(package.sha256(original), package.sha256(graph))
        self.assertEqual(self.metadata["routingSha256"], package.sha256(graph))
        self.assertEqual(graph.stat().st_size, self.metadata["routingBytes"])
        checks = package.check_validated_inputs(
            package.REPOSITORY / "dados_batalhao/batalhao_onalicio_v1.osm.pbf", original,
            package.REPOSITORY / "dados_batalhao/onalicio_teste/validacao_onalicio.json")
        self.assertTrue(checks["routing"]["matchesValidatedInput"])
        self.assertEqual(4, len(checks["previousRoutingTests"]))

    def test_content_hashes_and_local_style_sources(self):
        for relative, expected in self.metadata["contentFiles"].items():
            path = self.assets / relative
            self.assertEqual(expected["bytes"], path.stat().st_size, relative)
            self.assertEqual(expected["sha256"], package.sha256(path), relative)
        style = package.read_json(self.out / "style.json")
        self.assertEqual("asset://batalhao/fonts/{fontstack}/{range}.pbf", style["glyphs"])
        for name in self.layers:
            self.assertEqual(f"asset://batalhao/{name}.geojson", style["sources"][name]["data"])
        identifiers = [layer["id"] for layer in style["layers"]]
        self.assertEqual(len(identifiers), len(set(identifiers)))
        for layer_id in ("settlement-points", "settlement-labels", "settlement-area-fill",
                         "settlement-area-outline", "region-boundary", "municipality-boundaries",
                         "access-roads", "route-line", "origin-circle", "destination-circle"):
            self.assertIn(layer_id, identifiers)


if __name__ == "__main__":
    unittest.main()
