package vn.hiep.motonav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class Way(
    val id: Long,
    val tags: Map<String, String>,
    val lats: DoubleArray,
    val lons: DoubleArray,
) {
    val highway: String = tags["highway"] ?: ""
    val name: String? = tags["name"]

    /** 0 = hai chiều, 1 = một chiều theo hướng vẽ, -1 = một chiều ngược hướng vẽ. */
    val oneway: Int = run {
        when (tags["oneway"]?.lowercase()) {
            "yes", "true", "1" -> 1
            "-1", "reverse" -> -1
            "no", "false", "0" -> 0
            else -> {
                val j = tags["junction"]
                if (highway == "motorway" || j == "roundabout" || j == "circular") 1 else 0
            }
        }
    }

    val lanes: Int? = tags["lanes"]?.trim()?.toIntOrNull()

    val minLat = lats.minOrNull() ?: 0.0
    val maxLat = lats.maxOrNull() ?: 0.0
    val minLon = lons.minOrNull() ?: 0.0
    val maxLon = lons.maxOrNull() ?: 0.0
}

object CamKind {
    const val SPEED = 0
    const val RED_LIGHT = 1
    const val TRAFFIC = 2
    const val FINE = 3

    fun label(kind: Int): String = when (kind) {
        SPEED -> "camera tốc độ"
        RED_LIGHT -> "camera đèn đỏ"
        FINE -> "camera phạt nguội"
        else -> "camera giao thông"
    }
}

class Camera(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: Int,
    /** Hướng xe chạy mà camera áp dụng; -1 = mọi hướng. */
    val heading: Double,
    val fromUser: Boolean,
)

class OsmSnapshot(
    val centerLat: Double,
    val centerLon: Double,
    val ways: List<Way>,
    val cameras: List<Camera>,
    /** true = phường/thị trấn, false = xã, null = không rõ. */
    val urbanArea: Boolean?,
    val areaName: String?,
)

/** Tải đường và camera quanh vị trí từ OpenStreetMap (Overpass API). */
object Overpass {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    private const val HIGHWAYS =
        "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    fun fetch(lat: Double, lon: Double, wayRadius: Int, camRadius: Int): OsmSnapshot {
        val p = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val q = buildString {
            append("[out:json][timeout:25];\n")
            append("way(around:$wayRadius,$p)[\"highway\"~\"^($HIGHWAYS)\$\"];\n")
            append("out tags geom;\n")
            append("(\n")
            append("node(around:$camRadius,$p)[\"highway\"=\"speed_camera\"];\n")
            append("node(around:$camRadius,$p)[\"enforcement\"];\n")
            append("node(around:$camRadius,$p)[\"man_made\"=\"surveillance\"][\"surveillance:zone\"=\"traffic\"];\n")
            append("node(around:$camRadius,$p)[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"ALPR\"];\n")
            append(");\n")
            append("out;\n")
            append("is_in($p)->.a;\n")
            append("area.a[\"boundary\"=\"administrative\"];\n")
            append("out tags;\n")
        }

        var lastError: Exception? = null
        for (ep in endpoints) {
            try {
                val body = post(ep, q)
                return parse(body, lat, lon)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("Không tải được dữ liệu")
    }

    private fun post(endpoint: String, query: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "MotoNav/1.0 (personal Android app)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val data = "data=" + URLEncoder.encode(query, "UTF-8")
            conn.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, lat: Double, lon: Double): OsmSnapshot {
        val root = JSONObject(body)
        val els = root.optJSONArray("elements") ?: throw RuntimeException("Dữ liệu rỗng")
        val ways = ArrayList<Way>()
        val cams = ArrayList<Camera>()
        var bestAreaLevel = -1
        var urban: Boolean? = null
        var areaName: String? = null

        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tagsObj = e.optJSONObject("tags")
            val tags = HashMap<String, String>()
            if (tagsObj != null) {
                val it = tagsObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    tags[k] = tagsObj.optString(k)
                }
            }
            when (e.optString("type")) {
                "way" -> {
                    val svc = tags["service"]
                    if (svc == "parking_aisle" || svc == "driveway" || svc == "drive-through") continue
                    if (tags["access"] == "private" || tags["access"] == "no") continue
                    val g = e.optJSONArray("geometry") ?: continue
                    if (g.length() < 2) continue
                    val la = DoubleArray(g.length())
                    val lo = DoubleArray(g.length())
                    for (j in 0 until g.length()) {
                        val pt = g.getJSONObject(j)
                        la[j] = pt.optDouble("lat")
                        lo[j] = pt.optDouble("lon")
                    }
                    ways.add(Way(e.optLong("id"), tags, la, lo))
                }
                "node" -> {
                    val kind = when {
                        tags["highway"] == "speed_camera" -> CamKind.SPEED
                        tags["enforcement"] == "maxspeed" -> CamKind.SPEED
                        tags["enforcement"] == "traffic_signals" -> CamKind.RED_LIGHT
                        else -> CamKind.TRAFFIC
                    }
                    cams.add(
                        Camera(
                            "osm" + e.optLong("id"),
                            e.optDouble("lat"), e.optDouble("lon"),
                            kind, -1.0, false,
                        ),
                    )
                }
                "area" -> {
                    val nm = tags["name"] ?: continue
                    val lvl = tags["admin_level"]?.toIntOrNull() ?: 0
                    val u = when {
                        nm.startsWith("Phường") || nm.startsWith("Thị trấn") -> true
                        nm.startsWith("Xã") || nm.startsWith("Đặc khu") -> false
                        else -> null
                    }
                    if (u != null && lvl > bestAreaLevel) {
                        bestAreaLevel = lvl
                        urban = u
                        areaName = nm
                    }
                }
            }
        }
        return OsmSnapshot(lat, lon, ways, cams, urban, areaName)
    }
}
