package vn.hiep.motonav

import org.json.JSONArray
import java.util.Locale

/**
 * Nhập / xuất danh sách camera.
 *
 * Nhập được:
 *  - KML (Google My Maps → Xuất KML): mỗi điểm là 1 camera, loại đoán theo tên điểm/thư mục.
 *  - CSV / TXT: mỗi dòng có vĩ độ, kinh độ, (loại), (hướng). Dấu phân cách , ; hoặc tab.
 *  - JSON: [{"lat":..,"lon":..,"type":"phạt nguội","heading":..}]
 */
object CameraIO {

    class Item(val lat: Double, val lon: Double, val kind: Int, val heading: Double)

    private fun isLat(v: Double) = v in 7.0..24.5
    private fun isLon(v: Double) = v in 101.0..118.0

    fun parse(text: String): List<Item> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            t.contains("<kml", ignoreCase = true) || t.contains("<Placemark", ignoreCase = true) -> parseKml(t)
            t.startsWith("[") -> parseJson(t)
            else -> parseCsv(t)
        }
    }

    private val placemarkRe = Regex("""<Placemark\b.*?</Placemark>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val nameRe = Regex("""<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val descRe = Regex("""<description>(.*?)</description>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val pointRe = Regex("""<Point\b.*?<coordinates>\s*([-\d.]+)\s*,\s*([-\d.]+)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val folderRe = Regex("""<Folder\b.*?</Folder>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun parseKml(t: String): List<Item> {
        val out = ArrayList<Item>()
        // Tên thư mục (lớp) trên My Maps cũng dùng để đoán loại camera
        val folders = folderRe.findAll(t).toList()
        val scopes = if (folders.isEmpty()) listOf(t) else folders.map { it.value }
        for (scope in scopes) {
            val folderName = if (folders.isEmpty()) "" else nameRe.find(scope.substringBefore("<Placemark"))?.groupValues?.get(1) ?: ""
            for (pm in placemarkRe.findAll(scope)) {
                val block = pm.value
                val p = pointRe.find(block) ?: continue
                val lon = p.groupValues[1].toDoubleOrNull() ?: continue
                val lat = p.groupValues[2].toDoubleOrNull() ?: continue
                if (!isLat(lat) || !isLon(lon)) continue
                val name = nameRe.find(block)?.groupValues?.get(1)
                val desc = descRe.find(block)?.groupValues?.get(1)
                val kindText = listOfNotNull(name, desc, folderName).joinToString(" ")
                out.add(Item(lat, lon, CamKind.fromText(kindText), -1.0))
            }
        }
        return out
    }

    private fun parseJson(t: String): List<Item> {
        val out = ArrayList<Item>()
        val arr = JSONArray(t)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", o.optDouble("latitude", Double.NaN))
            val lon = o.optDouble("lon", o.optDouble("lng", o.optDouble("longitude", Double.NaN)))
            if (lat.isNaN() || lon.isNaN() || !isLat(lat) || !isLon(lon)) continue
            val kindText = o.optString("type", o.optString("kind", o.optString("name", "")))
            out.add(Item(lat, lon, CamKind.fromText(kindText), o.optDouble("heading", -1.0)))
        }
        return out
    }

    private fun parseCsv(t: String): List<Item> {
        val out = ArrayList<Item>()
        for (rawLine in t.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = when {
                line.contains('\t') -> '\t'
                line.contains(';') -> ';'
                else -> ','
            }
            val parts = line.split(sep).map { it.trim().trim('"') }
            val nums = parts.map { it.replace(',', '.').toDoubleOrNull() }
            // Tìm cặp vĩ độ / kinh độ hợp lệ trong vùng Việt Nam
            var lat = Double.NaN
            var lon = Double.NaN
            var used = -1
            for (i in 0 until parts.size - 1) {
                val a = nums[i] ?: continue
                val b = nums[i + 1] ?: continue
                if (isLat(a) && isLon(b)) { lat = a; lon = b; used = i; break }
                if (isLon(a) && isLat(b)) { lat = b; lon = a; used = i; break }
            }
            if (used < 0) continue
            val rest = parts.filterIndexed { i, _ -> i != used && i != used + 1 }
            val heading = rest.mapNotNull { it.toDoubleOrNull() }.firstOrNull { it in 0.0..360.0 } ?: -1.0
            val kindText = rest.filter { it.toDoubleOrNull() == null }.joinToString(" ")
            out.add(Item(lat, lon, CamKind.fromText(kindText), heading))
        }
        return out
    }

    fun toCsv(cams: List<Camera>): String {
        val sb = StringBuilder("lat,lon,loai,huong\n")
        for (c in cams) {
            sb.append(String.format(Locale.US, "%.6f,%.6f,%s,%.0f\n", c.lat, c.lon, CamKind.label(c.kind), c.heading))
        }
        return sb.toString()
    }
}
