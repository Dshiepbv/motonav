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

    /** Cấm dừng/đỗ dọc đoạn đường (bên phải theo chiều xe chạy ở VN, hoặc cả hai bên). */
    val parkingRule: Int = run {
        var rule = 0
        for (side in listOf("both", "right")) {
            val vals = listOfNotNull(
                tags["parking:$side"], tags["parking:lane:$side"], tags["parking:$side:restriction"],
                tags["parking:condition:$side"], tags["parking:$side:condition"],
            ).joinToString(";").lowercase()
            if (vals.contains("no_stopping")) return@run SignType.NO_STOP
            if (vals.contains("no_parking") || vals.split(';').any { it == "no" }) rule = SignType.NO_PARK
        }
        rule
    }

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
    const val HAZARD = 4
    const val POLICE = 5
    const val RAIL = 6
    const val SCHOOL = 7
    const val MARKET = 8

    /** Điểm cố định (không phải camera): báo gần hơn và theo khoảng cách tới đường. */
    fun isPlace(kind: Int) = kind == SCHOOL || kind == MARKET

    /** Biểu tượng hiện trên bản đồ. */
    fun emoji(kind: Int): String = when (kind) {
        SPEED -> "🚨"
        RED_LIGHT -> "🚦"
        FINE -> "📷"
        HAZARD -> "⚠️"
        POLICE -> "👮"
        RAIL -> "🚂"
        SCHOOL -> "🏫"
        MARKET -> "🛒"
        else -> "📹"
    }

    fun color(kind: Int): String = when (kind) {
        SPEED, POLICE -> "#E53935"
        FINE, RED_LIGHT, TRAFFIC -> "#E65100"
        HAZARD, RAIL -> "#F9A825"
        else -> "#1E88E5"
    }

    fun label(kind: Int): String = when (kind) {
        SPEED -> "camera bắn tốc độ"
        RED_LIGHT -> "camera đèn đỏ"
        FINE -> "camera phạt nguội"
        HAZARD -> "ổ gà, đường xấu"
        POLICE -> "trạm cảnh sát giao thông"
        RAIL -> "đường sắt cắt ngang"
        SCHOOL -> "khu vực trường học"
        MARKET -> "khu chợ, đông người"
        else -> "camera giám sát"
    }

    /** Chữ ngắn in hoa cho ô nổi. */
    fun short(kind: Int): String = when (kind) {
        SPEED -> "BẮN TỐC ĐỘ"
        FINE -> "PHẠT NGUỘI"
        RED_LIGHT -> "ĐÈN ĐỎ"
        HAZARD -> "Ổ GÀ"
        POLICE -> "CSGT"
        RAIL -> "ĐƯỜNG SẮT"
        SCHOOL -> "TRƯỜNG HỌC"
        MARKET -> "KHU CHỢ"
        else -> "CAMERA"
    }

    fun title(kind: Int): String = label(kind).replaceFirstChar { it.uppercase() }

    /** Đoán loại camera từ chữ (tên điểm trong file KML/CSV). */
    fun fromText(t: String?): Int {
        val s = (t ?: "").lowercase()
        return when {
            s.contains("ổ gà") || s.contains("o ga") || s.contains("pothole") || s.contains("nguy hiểm") || s.contains("đường xấu") -> HAZARD
            s.contains("csgt") || s.contains("công an") || s.contains("cảnh sát") || s.contains("police") -> POLICE
            s.contains("tốc") || s.contains("toc do") || s.contains("speed") || s.contains("bắn") -> SPEED
            s.contains("đèn") || s.contains("den do") || s.contains("red") -> RED_LIGHT
            s.contains("giám sát") || s.contains("giam sat") || s.contains("surveillance") -> TRAFFIC
            else -> FINE
        }
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
    val signs: List<RoadSign> = emptyList(),
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
            append("node(around:$camRadius,$p)[\"hazard\"];\n")
            append("node(around:$camRadius,$p)[\"railway\"~\"^(level_crossing|crossing)\$\"];\n")
            append(");\n")
            append("out;\n")
            // Biển báo (cấm rẽ, cấm dừng đỗ, nhường đường, nguy hiểm…)
            append("(\n")
            append("node(around:$wayRadius,$p)[\"traffic_sign\"];\n")
            append("node(around:$wayRadius,$p)[\"highway\"~\"^(stop|give_way|traffic_signals|crossing|speed_bump|speed_hump)\$\"];\n")
            append("node(around:$wayRadius,$p)[\"traffic_calming\"];\n")
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

    /** Gửi một truy vấn Overpass bất kỳ, thử lần lượt các máy chủ. */
    fun raw(query: String): JSONObject {
        var lastError: Exception? = null
        for (ep in endpoints) {
            try {
                return JSONObject(post(ep, query))
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
        val signs = ArrayList<RoadSign>()
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
                "way", "relation" -> {
                    // Trường học / chợ (có "center", không có hình đường)
                    val ctr = e.optJSONObject("center")
                    val amen = tags["amenity"]
                    if (ctr != null && (amen == "school" || amen == "marketplace")) {
                        cams.add(
                            Camera(
                                "osm" + e.optString("type") + e.optLong("id"),
                                ctr.optDouble("lat"), ctr.optDouble("lon"),
                                if (amen == "school") CamKind.SCHOOL else CamKind.MARKET, -1.0, false,
                            ),
                        )
                        continue
                    }
                    if (e.optString("type") != "way") continue
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
                    val isCam = tags["highway"] == "speed_camera" || tags.containsKey("enforcement") ||
                        tags["man_made"] == "surveillance" || tags.containsKey("hazard") ||
                        tags["railway"] == "level_crossing" || tags["railway"] == "crossing" ||
                        tags["amenity"] == "school" || tags["amenity"] == "marketplace"
                    if (!isCam) {
                        val sp = SignType.parse(tags)
                        if (sp != null) {
                            signs.add(
                                RoadSign("s" + e.optLong("id"), e.optDouble("lat"), e.optDouble("lon"), sp.first, sp.second, SignType.headingOf(tags)),
                            )
                        }
                        continue
                    }
                    val kind = when {
                        tags["amenity"] == "school" -> CamKind.SCHOOL
                        tags["amenity"] == "marketplace" -> CamKind.MARKET
                        tags["railway"] == "level_crossing" || tags["railway"] == "crossing" -> CamKind.RAIL
                        tags["hazard"] != null -> CamKind.HAZARD
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
        return OsmSnapshot(lat, lon, ways, cams, urban, areaName, signs)
    }
}
