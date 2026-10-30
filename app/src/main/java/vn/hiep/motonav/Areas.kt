package vn.hiep.motonav

import org.json.JSONObject

/**
 * Mảng màu nền cho khung TFT (kiểu bản đồ Waze nền tối): nước, công viên/cỏ/rừng, khu dân cư/công
 * nghiệp. Lấy từ OSM (Overpass), chỉ để VẼ — không dính tới khớp đường hay dẫn đường.
 */
class Area(val kind: Int, val lats: DoubleArray, val lons: DoubleArray, val line: Boolean)

object Areas {
    const val WATER = 0
    const val GREEN = 1
    const val URBAN = 2
    const val RIVER = 3 // đường nước (sông/kênh) vẽ nét dày

    fun fetch(lat: Double, lon: Double, radius: Int): List<Area> {
        val p = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
        val q = "[out:json][timeout:25];\n(\n" +
            "way(around:$radius,$p)[\"natural\"~\"^(water|wetland)\$\"];\n" +
            "way(around:$radius,$p)[\"waterway\"=\"riverbank\"];\n" +
            "way(around:$radius,$p)[\"landuse\"~\"^(basin|reservoir)\$\"];\n" +
            "way(around:$radius,$p)[\"leisure\"~\"^(park|garden|pitch|playground|golf_course|recreation_ground)\$\"];\n" +
            "way(around:$radius,$p)[\"landuse\"~\"^(grass|forest|meadow|recreation_ground|cemetery|village_green)\$\"];\n" +
            "way(around:$radius,$p)[\"natural\"~\"^(wood|scrub|grassland)\$\"];\n" +
            "way(around:$radius,$p)[\"waterway\"~\"^(river|canal|stream)\$\"];\n" +
            ");\nout tags geom;\n"
        val root: JSONObject = Overpass.raw(q)
        val els = root.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Area>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val g = e.optJSONArray("geometry") ?: continue
            if (g.length() < 2) continue
            val la = DoubleArray(g.length()) { g.getJSONObject(it).optDouble("lat") }
            val lo = DoubleArray(g.length()) { g.getJSONObject(it).optDouble("lon") }
            val ww = tags.optString("waterway")
            val nat = tags.optString("natural")
            val lu = tags.optString("landuse")
            val le = tags.optString("leisure")
            val closed = la.size >= 4 && la[0] == la[la.size - 1] && lo[0] == lo[lo.size - 1]
            when {
                ww == "river" || ww == "canal" || ww == "stream" -> out.add(Area(RIVER, la, lo, true))
                !closed -> continue
                nat == "water" || nat == "wetland" || ww == "riverbank" || lu == "basin" || lu == "reservoir" -> out.add(Area(WATER, la, lo, false))
                le.isNotEmpty() || lu in setOf("grass", "forest", "meadow", "recreation_ground", "cemetery", "village_green") ||
                    nat in setOf("wood", "scrub", "grassland") -> out.add(Area(GREEN, la, lo, false))
                else -> continue
            }
        }
        return out
    }
}
