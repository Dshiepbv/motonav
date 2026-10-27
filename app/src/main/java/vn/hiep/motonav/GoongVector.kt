package vn.hiep.motonav

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Nét đường trắng trên màn TFT lấy từ mảnh bản đồ vector Goong (MVT) — cùng nguồn dữ liệu với nét
 * đỏ (tuyến Goong) để hai nét khớp nhau (Hiệp: "sao không lấy đường của Goong mà lấy của OSM").
 * Chỉ dùng để VẼ; khớp vị trí xe vẫn dùng OSM.
 *
 * CHƯA thử được với tài khoản thật (máy dựng không gọi được tiles.goong.io): cấu trúc lớp/thuộc tính
 * của mảnh Goong được dò lúc chạy (đọc style → mẫu URL mảnh, chọn lớp đường theo tên) và ghi log
 * "goongTiles" (tên lớp, khoá thuộc tính, số đường) để chỉnh lại nếu đoán sai. Lỗi/thiếu dữ liệu →
 * trả null, TFT tự dùng lại đường OSM như trước.
 */
object GoongVector {
    private const val STYLE_BASE = "https://tiles.goong.io/assets"
    private val LAYER_OK = Regex("road|transport|highway|street|bridge|tunnel|motorway|way", RegexOption.IGNORE_CASE)
    private val LAYER_NO = Regex("label|rail|ferry|aerial|boundary|admin|water|building|poi|landuse|housenumber|name|shield|oneway|arrow|path|pedestrian|footway", RegexOption.IGNORE_CASE)
    private val CLASS_KEYS = listOf("class", "highway", "type", "kind", "road_class", "fclass", "subclass")

    private var tileUrls: List<String>? = null
    private var tileZoom = 14
    private val tileCache = object : LinkedHashMap<String, List<Way>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Way>>?) = size > 40
    }
    private var syntheticId = -1_000_000L
    private var logged = false

    private fun httpGet(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (Android)")
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            var b = c.inputStream.use { it.readBytes() }
            if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
                b = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
            }
            return b
        } finally {
            c.disconnect()
        }
    }

    private fun withKey(url: String, key: String): String =
        if (url.contains("api_key=")) url else url + (if (url.contains('?')) "&" else "?") + "api_key=" + URLEncoder.encode(key, "UTF-8")

    /** Đọc style → mẫu URL mảnh vector (và zoom tối đa của nguồn). */
    private fun discover(key: String): List<String> {
        tileUrls?.let { return it }
        val style = JSONObject(String(httpGet(GoongMap.tileStyleUrl(key, true))))
        val sources = style.optJSONObject("sources") ?: throw RuntimeException("style không có sources")
        val urls = ArrayList<String>()
        var maxZ = 14
        for (name in sources.keys()) {
            val s = sources.getJSONObject(name)
            if (s.optString("type") != "vector") continue
            var tiles = s.optJSONArray("tiles")
            var maxzoom = s.optInt("maxzoom", -1)
            if (tiles == null && s.has("url")) {
                val tj = JSONObject(String(httpGet(withKey(s.getString("url"), key))))
                tiles = tj.optJSONArray("tiles")
                if (maxzoom < 0) maxzoom = tj.optInt("maxzoom", -1)
            }
            if (tiles != null) for (i in 0 until tiles.length()) urls.add(tiles.getString(i))
            if (maxzoom in 1..22) maxZ = maxzoom
        }
        if (urls.isEmpty()) throw RuntimeException("style không có nguồn vector")
        tileZoom = maxZ.coerceIn(13, 15)
        tileUrls = urls
        return urls
    }

    /** Đường (dạng Way) quanh vị trí từ 3x3 mảnh. null = không lấy được. */
    fun ways(key: String, lat: Double, lon: Double): List<Way>? {
        val urls = discover(key)
        val z = tileZoom
        val n = 1 shl z
        val tx = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat)
        val ty = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        val out = ArrayList<Way>()
        var failed = 0
        for (dy in -1..1) for (dx in -1..1) {
            val x = tx + dx; val y = ty + dy
            if (y < 0 || y >= n) continue
            val ck = "$z/${Math.floorMod(x, n)}/$y"
            val cached = synchronized(tileCache) { tileCache[ck] }
            if (cached != null) { out.addAll(cached); continue }
            try {
                val tpl = urls[0].replace("{z}", z.toString()).replace("{x}", Math.floorMod(x, n).toString()).replace("{y}", y.toString())
                val parsed = decode(httpGet(withKey(tpl, key)), z, Math.floorMod(x, n), y)
                synchronized(tileCache) { tileCache[ck] = parsed }
                out.addAll(parsed)
            } catch (e: Exception) {
                failed++
                TripLog.ev("goongTiles", "fail" to ck, "err" to e.message)
            }
        }
        if (failed == 9 || out.isEmpty()) return null
        return out
    }

    // ---------- Giải mã Mapbox Vector Tile (protobuf tối giản) ----------

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val v = b[p++].toInt() and 0xFF
                r = r or ((v and 0x7F).toLong() shl s)
                if (v < 0x80) return r
                s += 7
            }
        }
        fun bytes(): Reader { val len = varint().toInt(); val r = Reader(b, p, p + len); p += len; return r }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> { val len = varint().toInt(); p += len }
                5 -> p += 4
                else -> throw RuntimeException("wire $wire")
            }
        }
    }

    private fun decode(data: ByteArray, z: Int, tx: Int, ty: Int): List<Way> {
        val out = ArrayList<Way>()
        val tile = Reader(data)
        val layerNames = ArrayList<String>()
        val keyDump = HashSet<String>()
        var classSample = HashSet<String>()
        while (tile.more()) {
            val tag = tile.varint(); val f = (tag shr 3).toInt(); val w = (tag and 7).toInt()
            if (f != 3 || w != 2) { tile.skip(w); continue }
            val lr = tile.bytes()
            // Lớp: name=1, features=2, keys=3, values=4, extent=5
            var name = ""
            var extent = 4096
            val keys = ArrayList<String>()
            val values = ArrayList<String>()
            val feats = ArrayList<Reader>()
            while (lr.more()) {
                val t = lr.varint(); val lf = (t shr 3).toInt(); val lw = (t and 7).toInt()
                when {
                    lf == 1 && lw == 2 -> { val r = lr.bytes(); name = String(r.b, r.p, r.end - r.p) }
                    lf == 2 && lw == 2 -> feats.add(lr.bytes())
                    lf == 3 && lw == 2 -> { val r = lr.bytes(); keys.add(String(r.b, r.p, r.end - r.p)) }
                    lf == 4 && lw == 2 -> values.add(readValue(lr.bytes()))
                    lf == 5 && lw == 0 -> extent = lr.varint().toInt()
                    else -> lr.skip(lw)
                }
            }
            layerNames.add(name)
            if (!LAYER_OK.containsMatchIn(name) || LAYER_NO.containsMatchIn(name)) continue
            keyDump.addAll(keys)
            for (fr in feats) {
                var type = 0
                var tags: List<Int> = emptyList()
                var geom: List<Int> = emptyList()
                while (fr.more()) {
                    val t = fr.varint(); val ff = (t shr 3).toInt(); val fw = (t and 7).toInt()
                    when {
                        ff == 2 && fw == 2 -> { val r = fr.bytes(); val l = ArrayList<Int>(); while (r.more()) l.add(r.varint().toInt()); tags = l }
                        ff == 3 && fw == 0 -> type = fr.varint().toInt()
                        ff == 4 && fw == 2 -> { val r = fr.bytes(); val l = ArrayList<Int>(); while (r.more()) l.add(r.varint().toInt()); geom = l }
                        else -> fr.skip(fw)
                    }
                }
                if (type != 2) continue // chỉ đường (LineString)
                val props = HashMap<String, String>()
                var i = 0
                while (i + 1 < tags.size) {
                    val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
                    if (k != null && v != null) props[k] = v
                    i += 2
                }
                var cls = ""
                for (ck in CLASS_KEYS) { val v = props[ck]; if (!v.isNullOrBlank()) { cls = v; break } }
                if (classSample.size < 30) classSample.add("$name:$cls")
                val hw = mapClass(cls) ?: continue
                val nm = props["name"] ?: props["name:vi"] ?: props["name_vi"]
                val tg = HashMap<String, String>()
                tg["highway"] = hw
                if (!nm.isNullOrBlank()) tg["name"] = nm
                for (line in lines(geom, extent, z, tx, ty)) {
                    if (line.first.size < 2) continue
                    out.add(Way(syntheticId--, tg, line.first, line.second))
                }
            }
        }
        if (!logged) {
            logged = true
            TripLog.ev("goongTiles", "z" to z, "layers" to layerNames.joinToString(","), "keys" to keyDump.joinToString(","),
                "classes" to classSample.joinToString(","), "ways" to out.size)
        }
        return out
    }

    private fun readValue(r: Reader): String {
        var s = ""
        while (r.more()) {
            val t = r.varint(); val f = (t shr 3).toInt(); val w = (t and 7).toInt()
            when {
                f == 1 && w == 2 -> { val x = r.bytes(); s = String(x.b, x.p, x.end - x.p) }
                w == 0 -> s = r.varint().toString()
                else -> r.skip(w)
            }
        }
        return s
    }

    private fun mapClass(c: String): String? {
        val v = c.lowercase()
        return when {
            v.isEmpty() -> "service"
            v.startsWith("rail") || v in setOf("ferry", "aerialway", "subway", "transit", "construction") -> null
            v in setOf("path", "footway", "pedestrian", "steps", "cycleway", "bridleway", "corridor") -> null
            v == "motorway" || v == "trunk" || v == "primary" || v == "secondary" || v == "tertiary" -> v
            v.endsWith("_link") || v == "link" -> "tertiary_link"
            v == "minor" || v == "street" || v == "street_limited" || v == "residential" || v == "unclassified" || v == "living_street" -> "residential"
            v == "major_rail" || v == "minor_rail" -> null
            else -> "service" // service, track, driveway, alley, hẻm...
        }
    }

    private fun lines(geom: List<Int>, extent: Int, z: Int, tx: Int, ty: Int): List<Pair<DoubleArray, DoubleArray>> {
        val res = ArrayList<Pair<DoubleArray, DoubleArray>>()
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        fun flush() {
            if (la.size >= 2) res.add(la.toDoubleArray() to lo.toDoubleArray())
            la.clear(); lo.clear()
        }
        val n = (1 shl z).toDouble()
        var x = 0; var y = 0
        var i = 0
        while (i < geom.size) {
            val cmd = geom[i] and 7; val cnt = geom[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> repeat(cnt) {
                    if (i + 1 >= geom.size) return@repeat
                    val dx = unzig(geom[i]); val dy = unzig(geom[i + 1]); i += 2
                    x += dx; y += dy
                    if (cmd == 1) flush()
                    val wx = (tx + x.toDouble() / extent) / n
                    val wy = (ty + y.toDouble() / extent) / n
                    lo.add(wx * 360.0 - 180.0)
                    la.add(Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * wy)))))
                }
                else -> {}
            }
        }
        flush()
        return res
    }

    private fun unzig(v: Int): Int = (v ushr 1) xor -(v and 1)
}
