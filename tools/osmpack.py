#!/usr/bin/env python3
"""
Đóng gói dữ liệu OSM (đường + camera + biển báo) thành file zip chia ô cho MotoNav dùng ngoại tuyến.

  python3 osmpack.py input.osm.pbf output.zip

Mỗi ô 0,02° (~2,2 km):
  w/<ix>_<iy>.json  – các đường chạm vào ô (định dạng giống Overpass, hình học nén "g": [lat,lon,lat,lon,...])
  n/<ix>_<iy>.json  – camera, biển báo, đèn, gờ giảm tốc… trong ô
  a/<ix>_<iy>.json  – mảng màu nền TFT: nước, công viên/cỏ/rừng, sông/kênh (đa giác/đường rút gọn)
  meta.json         – vùng phủ, ngày dữ liệu, cỡ ô
"""
import json, sys, zipfile, datetime, math
from collections import defaultdict
import osmium

TILE = 0.02
HIGHWAYS = set("motorway trunk primary secondary tertiary unclassified residential living_street service road "
               "motorway_link trunk_link primary_link secondary_link tertiary_link".split())
WAY_KEYS = {"highway", "name", "ref", "oneway", "junction", "motorcycle", "motor_vehicle", "access", "service",
            "lanes", "dual_carriageway", "bridge", "tunnel", "zone:maxspeed"}
WAY_PREFIX = ("maxspeed", "parking")
NODE_KEYS = {"highway", "enforcement", "man_made", "surveillance:zone", "surveillance:type", "hazard", "railway",
             "traffic_sign", "traffic_sign:direction", "direction", "traffic_calming", "maxspeed", "crossing"}
NODE_HW = {"speed_camera", "traffic_signals", "crossing", "stop", "give_way", "speed_bump", "speed_hump"}


WATER_NAT = {"water", "wetland"}
WATER_LU = {"basin", "reservoir"}
GREEN_LEI = {"park", "garden", "pitch", "playground", "golf_course", "recreation_ground"}
GREEN_LU = {"grass", "forest", "meadow", "recreation_ground", "cemetery", "village_green"}
GREEN_NAT = {"wood", "scrub", "grassland"}
URBAN_LU = {"residential", "commercial", "retail", "industrial", "construction", "railway"}
URBAN_AM = {"school", "university", "college", "hospital", "marketplace"}
MAX_PTS = 90


def area_kind(t, closed):
    """0 = nước, 1 = xanh, 2 = khu dân cư/công nghiệp/trường/bệnh viện (xám nhạt), 3 = sông/kênh (đường); None = không dùng. Trùng Areas.kt trong app."""
    ww = t.get("waterway")
    if ww in ("river", "canal"): return 3
    if not closed: return None
    if t.get("natural") in WATER_NAT or ww == "riverbank" or t.get("landuse") in WATER_LU: return 0
    if t.get("leisure") in GREEN_LEI or t.get("landuse") in GREEN_LU or t.get("natural") in GREEN_NAT: return 1
    if t.get("landuse") in URBAN_LU or t.get("amenity") in URBAN_AM: return 2
    return None


def tile(lat, lon):
    return int(math.floor(lat / TILE)), int(math.floor(lon / TILE))


def keep_node(t):
    if t.get("highway") in NODE_HW: return True
    if "enforcement" in t or "hazard" in t or "traffic_sign" in t or "traffic_calming" in t: return True
    if t.get("railway") in ("level_crossing", "crossing"): return True
    if t.get("man_made") == "surveillance" and (t.get("surveillance:zone") == "traffic" or t.get("surveillance:type") == "ALPR"): return True
    return False


class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.wt = defaultdict(list)
        self.nt = defaultdict(list)
        self.at = defaultdict(list)
        self.na = 0
        self.nw = 0
        self.nn = 0
        self.bbox = [90, 180, -90, -180]

    def node(self, n):
        t = {k: v for k, v in n.tags}
        if not t or not keep_node(t): return
        tg = {k: v for k, v in t.items() if k in NODE_KEYS}
        lat, lon = round(n.location.lat, 7), round(n.location.lon, 7)
        self.nt[tile(lat, lon)].append({"type": "node", "id": n.id, "lat": lat, "lon": lon, "tags": tg})
        self.nn += 1

    def area_way(self, w):
        t = {k: v for k, v in w.tags}
        try:
            closed = w.is_closed()
        except Exception:
            closed = False
        kind = area_kind(t, closed)
        if kind is None: return
        pts = []
        try:
            for nd in w.nodes:
                if nd.location.valid(): pts.append((nd.location.lat, nd.location.lon))
        except osmium.InvalidLocationError:
            return
        if len(pts) < (2 if kind == 3 else 4): return
        la = [p[0] for p in pts]; lo = [p[1] for p in pts]
        if kind != 3 and (max(la) - min(la)) < 0.00015 and (max(lo) - min(lo)) < 0.00015: return  # < ~15 m
        if len(pts) > MAX_PTS:
            step = len(pts) / float(MAX_PTS)
            idx = sorted(set(int(i * step) for i in range(MAX_PTS)) | {len(pts) - 1})
            pts = [pts[i] for i in idx]
        g = []
        for a, b in pts: g += [round(a, 5), round(b, 5)]
        el = {"i": w.id, "k": kind, "g": g}
        tiles = set()
        if kind == 3:
            for i in range(0, len(g) - 2, 2):
                a, b = tile(g[i], g[i + 1]), tile(g[i + 2], g[i + 3])
                for x in range(min(a[0], b[0]), max(a[0], b[0]) + 1):
                    for y in range(min(a[1], b[1]), max(a[1], b[1]) + 1):
                        tiles.add((x, y))
        else:
            a, b = tile(min(la), min(lo)), tile(max(la), max(lo))
            if (b[0] - a[0] + 1) * (b[1] - a[1] + 1) > 400: return  # quá lớn (vùng biển/rừng cả tỉnh)
            for x in range(a[0], b[0] + 1):
                for y in range(a[1], b[1] + 1):
                    tiles.add((x, y))
        for t2 in tiles: self.at[t2].append(el)
        self.na += 1

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in HIGHWAYS:
            self.area_way(w)
            return
        tg = {k: v for k, v in w.tags if k in WAY_KEYS or k.startswith(WAY_PREFIX)}
        g = []
        tiles = set()
        try:
            for nd in w.nodes:
                if not nd.location.valid(): continue
                lat, lon = round(nd.location.lat, 6), round(nd.location.lon, 6)
                g += [lat, lon]
                tiles.add(tile(lat, lon))
                b = self.bbox
                b[0] = min(b[0], lat); b[1] = min(b[1], lon); b[2] = max(b[2], lat); b[3] = max(b[3], lon)
        except osmium.InvalidLocationError:
            return
        if len(g) < 4: return
        # Ô bị đường đi xuyên qua (giữa 2 đỉnh xa nhau) cũng phải có đường
        for i in range(0, len(g) - 2, 2):
            a, b = tile(g[i], g[i + 1]), tile(g[i + 2], g[i + 3])
            if a != b:
                for x in range(min(a[0], b[0]), max(a[0], b[0]) + 1):
                    for y in range(min(a[1], b[1]), max(a[1], b[1]) + 1):
                        tiles.add((x, y))
        el = {"type": "way", "id": w.id, "tags": tg, "g": g}
        for t in tiles: self.wt[t].append(el)
        self.nw += 1


def main(src, dst):
    h = H()
    h.apply_file(src, locations=True)
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for (x, y), els in h.wt.items():
            z.writestr(f"w/{x}_{y}.json", json.dumps({"elements": els}, ensure_ascii=False, separators=(",", ":")))
        for (x, y), els in h.nt.items():
            z.writestr(f"n/{x}_{y}.json", json.dumps({"elements": els}, ensure_ascii=False, separators=(",", ":")))
        for (x, y), els in h.at.items():
            z.writestr(f"a/{x}_{y}.json", json.dumps({"elements": els}, separators=(",", ":")))
        meta = {"tile": TILE, "bbox": h.bbox, "ways": h.nw, "nodes": h.nn, "areas": h.na,
                "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"), "source": "© OpenStreetMap contributors (ODbL)"}
        z.writestr("meta.json", json.dumps(meta))
    print(json.dumps({"ways": h.nw, "nodes": h.nn, "areas": h.na, "wayTiles": len(h.wt), "nodeTiles": len(h.nt), "areaTiles": len(h.at), "bbox": h.bbox}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
