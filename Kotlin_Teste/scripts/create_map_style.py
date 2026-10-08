"""Local MapLibre style. All sources and glyphs are bundled Android assets."""
import json
from pathlib import Path

assets = Path(__file__).resolve().parents[1] / "app/src/main/assets/palmas"
empty = {"type": "FeatureCollection", "features": []}
sources = {name: {"type": "geojson", "data": f"asset://palmas/{name}.geojson"}
           for name in ("roads", "areas", "waterways", "places")}
sources.update({name: {"type": "geojson", "data": empty} for name in ("route", "origin", "destination")})
scale = ["match", ["get", "kind"], ["motorway", "trunk", "primary"], 1.6,
         ["secondary", "tertiary"], 1.2, ["footway", "path", "cycleway", "steps"], 0.5, 1]
def road_width(extra=0):
    # Zoom must be the input of a top-level interpolate, per the MapLibre style spec.
    result = ["interpolate", ["linear"], ["zoom"]]
    for zoom, width in ((10, 0.7), (14, 3), (17, 8), (20, 16)):
        result += [zoom, ["+", ["*", width, scale], extra]]
    return result
layers = [{"id": "background", "type": "background", "paint": {"background-color": "#F1F2ED"}}]
for kind, color in (("urban", "#F8F7F3"), ("green", "#D6E7CB"), ("water", "#B5D8E8"), ("building", "#DFDFD6")):
    layers.append({"id": kind, "type": "fill", "source": "areas", "filter": ["==", "kind", kind],
                   "minzoom": 15 if kind == "building" else 0, "paint": {"fill-color": color}})
layers += [
    {"id": "streams", "type": "line", "source": "waterways", "paint": {"line-color": "#91C4DB", "line-width": 1.5}},
    {"id": "road-casing", "type": "line", "source": "roads", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": "#D1D3CA", "line-width": road_width(1.5)}},
    {"id": "roads", "type": "line", "source": "roads", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": ["match", ["get", "kind"], ["motorway", "trunk", "primary"], "#F8D99B", "#FFFFFF"], "line-width": road_width()}},
    {"id": "route-casing", "type": "line", "source": "route", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": "#FFFFFF", "line-width": 9}},
    {"id": "route-line", "type": "line", "source": "route", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": "#006B5E", "line-width": 5}},
    {"id": "road-labels", "type": "symbol", "source": "roads", "minzoom": 14,
     "filter": ["!=", "name", ""],
     "layout": {"symbol-placement": "line", "text-field": "{name}", "text-font": ["Open Sans Semibold"], "text-size": 11},
     "paint": {"text-color": "#555D54", "text-halo-color": "#FFFFFF", "text-halo-width": 1.5}},
    {"id": "place-labels", "type": "symbol", "source": "places", "minzoom": 10,
     "layout": {"text-field": "{name}", "text-font": ["Open Sans Semibold"], "text-size": 15},
     "paint": {"text-color": "#34463D", "text-halo-color": "#FFFFFF", "text-halo-width": 2}},
]
for source, color in (("origin", "#006B5E"), ("destination", "#B84920")):
    layers += [
        {"id": f"{source}-circle", "type": "circle", "source": source,
         "paint": {"circle-radius": 13, "circle-color": color, "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3}},
        {"id": f"{source}-label", "type": "symbol", "source": source,
         "layout": {"text-field": "{name}", "text-font": ["Open Sans Semibold"], "text-size": 13,
                    "text-allow-overlap": True, "text-ignore-placement": True}, "paint": {"text-color": "#FFFFFF"}},
    ]
style = {"version": 8, "name": "Palmas offline", "glyphs": "asset://palmas/fonts/{fontstack}/{range}.pbf",
         "sources": sources, "layers": layers}
(assets / "style.json").write_text(json.dumps(style, ensure_ascii=False, indent=2), encoding="utf-8")
print("Created local style:", assets / "style.json")
