#!/usr/bin/env python3
"""
Đóng gói dữ liệu OSM (đường + camera + biển báo) thành file zip chia ô cho MotoNav dùng ngoại tuyến.

  python3 osmpack.py input.osm.pbf output.zip

Mỗi ô 0,02° (~2,2 km):
  w/<ix>_<iy>.json  – các đường chạm vào ô (định dạng giống Overpass, hình học nén "g": [lat,lon,lat,lon,...])
  n/<ix>_<iy>.json  – camera, biển báo, đèn, gờ giảm tốc… trong ô
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

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in HIGHWAYS: return
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
        meta = {"tile": TILE, "bbox": h.bbox, "ways": h.nw, "nodes": h.nn,
                "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"), "source": "© OpenStreetMap contributors (ODbL)"}
        z.writestr("meta.json", json.dumps(meta))
    print(json.dumps({"ways": h.nw, "nodes": h.nn, "wayTiles": len(h.wt), "nodeTiles": len(h.nt), "bbox": h.bbox}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
