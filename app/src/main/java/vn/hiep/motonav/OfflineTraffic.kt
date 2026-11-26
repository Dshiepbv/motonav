package vn.hiep.motonav

import android.content.Context
import android.location.Location
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class TrafficSpeedPoint(
    val id: String, val lat: Double, val lon: Double, val speed: Int,
    val bearing: Int, val isHcmArterial: Boolean,
)

internal data class TrafficCameraPoint(
    val id: String, val lat: Double, val lon: Double, val speedFlag: Int, val type: Int,
)

internal data class TrafficZonePoint(
    val id: String, val lat: Double, val lon: Double, val code: Int, val bearing: Int,
)

internal data class TrafficSignPoint(
    val id: String, val lat: Double, val lon: Double, val code: Int, val bearing: Int,
    val extraField: Int? = null,
)

internal data class TrafficTurnPoint(
    val id: String, val lat: Double, val lon: Double, val code: Int, val bearing: Int,
)

internal data class TrafficAlert(
    val id: String, val label: String, val distanceM: Int, val priority: Int,
    val category: Int, val lat: Double, val lon: Double, val color: Int,
)

internal data class TrafficUpdate(
    val stableBearing: Double,
    val effectiveMotoLimit: Int?,
    val isInUrbanZone: Boolean,
    val alert: TrafficAlert?,
    val overspeed: Boolean,
    val speech: String?,
    val currentSpeedKmh: Int,
    val loaded: Boolean,
)

/** Chỉ mục không gian 0,02°; với các bán kính cảnh báo <=350 m, mỗi lần tra cứu đọc tối đa 9 ô. */
internal class TrafficSpatialGrid<T>(
    private val latOf: (T) -> Double,
    private val lonOf: (T) -> Double,
) {
    private val cells = HashMap<Long, MutableList<T>>()
    private fun cell(lat: Double, lon: Double): Long {
        val x = floor(lat / 0.02).toInt()
        val y = floor(lon / 0.02).toInt()
        return (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }
    fun add(item: T) { cells.getOrPut(cell(latOf(item), lonOf(item))) { ArrayList() }.add(item) }
    fun remove(item: T) { cells[cell(latOf(item), lonOf(item))]?.remove(item) }
    fun near(lat: Double, lon: Double): List<T> {
        val cx = floor(lat / 0.02).toInt()
        val cy = floor(lon / 0.02).toInt()
        val out = ArrayList<T>()
        for (dx in -1..1) for (dy in -1..1) {
            val key = (cx.toLong() + dx).shl(32) xor ((cy.toLong() + dy) and 0xffffffffL)
            cells[key]?.let(out::addAll)
        }
        return out
    }
}

internal class OfflineTrafficDataset private constructor(
    val speedPoints: List<TrafficSpeedPoint>,
    val cameras: List<TrafficCameraPoint>,
    val zones: List<TrafficZonePoint>,
    val signs: List<TrafficSignPoint>,
    val turns: List<TrafficTurnPoint>,
    val loadedFiles: Set<String>,
    val errors: List<String>,
) {
    val speedGrid = TrafficSpatialGrid<TrafficSpeedPoint>({ it.lat }, { it.lon }).also { g -> speedPoints.forEach(g::add) }
    val cameraGrid = TrafficSpatialGrid<TrafficCameraPoint>({ it.lat }, { it.lon }).also { g -> cameras.forEach(g::add) }
    val zoneGrid = TrafficSpatialGrid<TrafficZonePoint>({ it.lat }, { it.lon }).also { g -> zones.forEach(g::add) }
    val signGrid = TrafficSpatialGrid<TrafficSignPoint>({ it.lat }, { it.lon }).also { g -> signs.forEach(g::add) }
    val turnGrid = TrafficSpatialGrid<TrafficTurnPoint>({ it.lat }, { it.lon }).also { g -> turns.forEach(g::add) }

    companion object {
        suspend fun load(context: Context): OfflineTrafficDataset = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val loaded = linkedSetOf<String>()
            val errors = ArrayList<String>()
            fun read(name: String): String? = try {
                app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }.also { loaded += name }
            } catch (e: Exception) {
                errors += "$name: ${e.javaClass.simpleName}"
                null
            }
            fun readArray(name: String): JSONArray? {
                val body = read(name) ?: return null
                return try { JSONArray(body) } catch (_: Exception) {
                    errors += "$name: JSON không hợp lệ"
                    null
                }
            }
            fun readObject(name: String): JSONObject? {
                val body = read(name) ?: return null
                return try { JSONObject(body) } catch (_: Exception) {
                    errors += "$name: JSON không hợp lệ"
                    null
                }
            }
            fun row(a: JSONArray, minSize: Int = 4): DoubleArray? {
                if (a.length() < minSize) return null
                val lat = a.optDouble(0, Double.NaN)
                val lon = a.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                return doubleArrayOf(lat, lon, a.optInt(2, -1).toDouble(), a.optInt(3, -1).toDouble(),
                    if (a.length() > 4) a.optInt(4, -1).toDouble() else -1.0)
            }
            fun rows(root: JSONArray?, minSize: Int = 4): List<DoubleArray> {
                if (root == null) return emptyList()
                val out = ArrayList<DoubleArray>(root.length())
                for (i in 0 until root.length()) root.optJSONArray(i)?.let { row(it, minSize) }?.let(out::add)
                return out
            }

            val plainSigns = rows(readArray(StringVault.trafficSignsFile()))
            val hcmSigns = rows(readArray(StringVault.hcmSignsFile()))
            val cameraRoot = readObject(StringVault.camerasFile())
            val cameraRows = rows(cameraRoot?.optJSONArray("cams"))
            val limitRows = rows(cameraRoot?.optJSONArray("limits"))
            val zoneRows = rows(cameraRoot?.optJSONArray("zones"))
            val signRows = rows(cameraRoot?.optJSONArray("signs"), 4)
            val turnRows = rows(cameraRoot?.optJSONArray("turns"))

            val speeds = ArrayList<TrafficSpeedPoint>()
            val speedIndex = TrafficSpatialGrid<TrafficSpeedPoint>({ it.lat }, { it.lon })
            fun addSpeed(r: DoubleArray, source: String, hcm: Boolean) {
                val speed = r[2].toInt()
                val bearing = r[3].toInt().let { if (it < 0) 0 else it % 360 }
                if (speed <= 0) return
                val duplicate = speedIndex.near(r[0], r[1]).firstOrNull {
                    it.speed == speed && Geo.angleDiff(it.bearing.toDouble(), bearing.toDouble()) <= 20.0 &&
                        Geo.distance(it.lat, it.lon, r[0], r[1]) < 15.0
                }
                if (duplicate != null) {
                    if (hcm && !duplicate.isHcmArterial) {
                        speeds.remove(duplicate)
                        speedIndex.remove(duplicate)
                        val upgraded = duplicate.copy(isHcmArterial = true)
                        speeds += upgraded
                        speedIndex.add(upgraded)
                    }
                    return
                }
                val p = TrafficSpeedPoint("$source:${speeds.size}", r[0], r[1], speed, bearing, hcm)
                speeds += p
                speedIndex.add(p)
            }
            plainSigns.forEach { addSpeed(it, "vn", false) }
            hcmSigns.forEach { addSpeed(it, "hcm", true) }
            limitRows.forEach { addSpeed(it, "limits", false) }

            val cameras = cameraRows.mapIndexedNotNull { i, r ->
                val type = r[3].toInt()
                if (type !in 0..4) null else TrafficCameraPoint("cam:$i", r[0], r[1], r[2].toInt(), type)
            }
            val zones = zoneRows.mapIndexedNotNull { i, r ->
                val code = r[2].toInt()
                if (code != 10 && code != 11) null else TrafficZonePoint("zone:$i", r[0], r[1], code, r[3].toInt())
            }
            // signs.json/cameras_vn.json có thể chứa thêm trường thứ năm. Trường này được giữ lại
            // như metadata nhưng không được tự diễn giải vì cấu trúc nguồn chưa mô tả ý nghĩa.
            val signs = signRows.mapIndexedNotNull { i, r ->
                val code = r[2].toInt()
                if (code !in setOf(8, 9, 12, 13)) null
                else TrafficSignPoint("sign:$i", r[0], r[1], code, r[3].toInt(), r[4].toInt().takeIf { it >= 0 })
            }
            val turns = turnRows.mapIndexed { i, r ->
                TrafficTurnPoint("turn:$i", r[0], r[1], r[2].toInt(), r[3].toInt())
            }
            OfflineTrafficDataset(speeds, cameras, zones, signs, turns, loaded, errors)
        }
    }
}

internal class OfflineTrafficEngine {
    @Volatile private var data: OfflineTrafficDataset? = null
    private var stableBearing = -1.0
    private var pendingBearing: Double? = null
    private var lastBearingLat = 0.0
    private var lastBearingLon = 0.0
    private var lastSpeedAnchorLat = 0.0
    private var lastSpeedAnchorLon = 0.0
    private var lastSpeedAnchorBearing = -1.0
    private var currentRawSpeedLimit: Int? = null
    private var currentSpeedIsHcm = false
    private var effectiveMotoLimit: Int? = null
    private var isInUrbanZone = false
    private var zoneKnown = false
    private var lastZonePointId: String? = null
    private val announced = LinkedHashMap<String, Pair<Double, Double>>()
    private var lastZoneSpeechLat = Double.NaN
    private var lastZoneSpeechLon = Double.NaN
    private var overspeedSince = 0L
    private var overspeedSpoken = false
    private var lastEvent: TrafficAlert? = null
    private var lastEventUntil = 0L

    fun install(dataset: OfflineTrafficDataset) { data = dataset }

    fun stabilizeBearing(
        lat: Double, lon: Double, speedMps: Double, gpsBearing: Double,
        bearingAccuracyDegrees: Float? = null,
    ): Double {
        if (gpsBearing < 0.0 || !gpsBearing.isFinite()) return stableBearing
        if (speedMps < 1.4 || (bearingAccuracyDegrees != null && bearingAccuracyDegrees > 35f)) return stableBearing
        val next = Geo.norm(gpsBearing)
        if (stableBearing < 0.0) {
            stableBearing = next
            lastBearingLat = lat
            lastBearingLon = lon
            return stableBearing
        }
        if (Geo.angleDiff(next, stableBearing) > 60.0) {
            val pending = pendingBearing
            if (pending != null && Geo.angleDiff(next, pending) <= 30.0) {
                stableBearing = next
                pendingBearing = null
                lastBearingLat = lat
                lastBearingLon = lon
            } else pendingBearing = next
        } else {
            stableBearing = next
            pendingBearing = null
            lastBearingLat = lat
            lastBearingLon = lon
        }
        return stableBearing
    }

    fun evaluate(
        lat: Double, lon: Double, speedMps: Double, gpsSpeedKmh: Int,
        bearing: Double, nowElapsed: Long, bikeSpeedKmh: Int = -1,
    ): TrafficUpdate {
        val dataset = data
        if (lastZoneSpeechLat.isFinite() &&
            Geo.distance(lat, lon, lastZoneSpeechLat, lastZoneSpeechLon) > 60.0) lastZonePointId = null
        val currentSpeed = if (bikeSpeedKmh >= 0) bikeSpeedKmh else gpsSpeedKmh
        var speech: String? = null
        var speechPriority = Int.MAX_VALUE
        fun offerSpeech(priority: Int, text: String) { if (priority < speechPriority) { speechPriority = priority; speech = text } }
        if (dataset == null) return snapshot(currentSpeed, null, false, speech, false)
        val hdg = if (bearing >= 0.0) bearing else stableBearing
        if (hdg < 0.0) return snapshot(currentSpeed, null, false, speech, false)

        if (lastSpeedAnchorBearing >= 0 && Geo.angleDiff(hdg, lastSpeedAnchorBearing) > 60.0 &&
            Geo.distance(lat, lon, lastSpeedAnchorLat, lastSpeedAnchorLon) > 500.0) {
            currentRawSpeedLimit = null
            currentSpeedIsHcm = false
            effectiveMotoLimit = null
            lastSpeedAnchorBearing = hdg
            lastSpeedAnchorLat = lat
            lastSpeedAnchorLon = lon
        }

        val lookahead = (speedMps * 1.1).coerceAtMost(35.0)
        val rad = Math.toRadians(hdg)
        val predictedLat = lat + cos(rad) * lookahead / 111320.0
        val predictedLon = lon + sin(rad) * lookahead / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))

        // Nhận diện khu đông dân cư theo đúng hướng tiếp cận biển.
        val zone = dataset.zoneGrid.near(lat, lon).asSequence()
            .map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { (p, d) -> d <= 150.0 && Geo.angleDiff(p.bearing.toDouble(), hdg) <= 35.0 &&
                (Geo.angleDiff(hdg, Geo.bearing(lat, lon, p.lat, p.lon)) <= 45.0 || d <= 25.0) }
            .minByOrNull { it.second }?.first
        if (zone != null && zone.id != lastZonePointId) {
            lastZonePointId = zone.id
            val newUrban = zone.code == 10
            if (!zoneKnown || newUrban != isInUrbanZone) {
                val firstZoneObservation = !zoneKnown
                isInUrbanZone = newUrban
                zoneKnown = true
                effectiveMotoLimit = currentRawSpeedLimit?.let { if (newUrban || currentSpeedIsHcm) minOf(it, 60) else it }
                if (!firstZoneObservation || newUrban) {
                    offerSpeech(6, if (newUrban) "Vào khu đông dân cư, tối đa 60" else "Hết khu đông dân cư")
                    lastZoneSpeechLat = lat
                    lastZoneSpeechLon = lon
                }
            }
        }

        // Biển tốc độ phía trước: khoảng cách xa hơn khi tốc độ hiện tại >=60 km/h.
        val speedRadius = if (currentSpeed >= 60) 350.0 else 200.0
        val speedPoint = dataset.speedGrid.near(lat, lon).asSequence()
            .map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { (p, d) -> d <= speedRadius && Geo.angleDiff(p.bearing.toDouble(), hdg) <= 35.0 &&
                (Geo.angleDiff(hdg, Geo.bearing(lat, lon, p.lat, p.lon)) <= 45.0 || d <= 40.0) }
            .minByOrNull { it.second }?.first
        if (speedPoint != null && (currentRawSpeedLimit != speedPoint.speed || currentSpeedIsHcm != speedPoint.isHcmArterial)) {
            currentRawSpeedLimit = speedPoint.speed
            currentSpeedIsHcm = speedPoint.isHcmArterial
            effectiveMotoLimit = if (speedPoint.isHcmArterial || isInUrbanZone) minOf(speedPoint.speed, 60) else speedPoint.speed
            lastSpeedAnchorLat = speedPoint.lat
            lastSpeedAnchorLon = speedPoint.lon
            lastSpeedAnchorBearing = hdg
        } else if (effectiveMotoLimit == null && currentRawSpeedLimit != null) {
            effectiveMotoLimit = if (currentSpeedIsHcm || isInUrbanZone) minOf(currentRawSpeedLimit!!, 60) else currentRawSpeedLimit
        }

        val candidates = ArrayList<TrafficAlert>()
        fun aheadDistance(pLat: Double, pLon: Double): Int? {
            val d = Geo.distance(predictedLat, predictedLon, pLat, pLon)
            if (d > 350.0) return null
            val relative = Geo.angleDiff(hdg, Geo.bearing(predictedLat, predictedLon, pLat, pLon))
            if (relative > 30.0 && d > 40.0) return null
            return d.roundToInt()
        }
        for (cam in dataset.cameraGrid.near(predictedLat, predictedLon)) {
            val d = aheadDistance(cam.lat, cam.lon) ?: continue
            val (label, priority, color) = when (cam.type) {
                0 -> Triple("TỐC ĐỘ", 2, 0xFFD32F2F.toInt())
                4 -> Triple("PHẠT NGUỘI", 2, 0xFFD32F2F.toInt())
                3 -> Triple("CAM AI", 4, 0xFF4527A0.toInt())
                2 -> Triple("ĐÈN ĐỎ", 5, 0xFFE65100.toInt())
                else -> Triple("GIÁM SÁT", 7, 0xFFF57F17.toInt())
            }
            candidates += TrafficAlert(cam.id, label, d, priority, cam.type, cam.lat, cam.lon, color)
            if (d <= 300 && cam.type in setOf(0, 3, 4) && !wasAnnounced(cam.lat, cam.lon, lat, lon)) {
                offerSpeech(priority, when (cam.type) {
                    0 -> "Phía trước 300 mét có camera bắn tốc độ"
                    4 -> "Phía trước 300 mét có camera phạt nguội"
                    else -> "Phía trước 300 mét có camera AI"
                })
                rememberAnnounced(cam.id, cam.lat, cam.lon)
            }
        }
        for (sign in dataset.signGrid.near(predictedLat, predictedLon)) {
            val d = aheadDistance(sign.lat, sign.lon) ?: continue
            if (d > 250) continue
            val (label, priority, color, speechText) = when (sign.code) {
                8 -> Quad("CẤM XE MÁY", 1, 0xFFB71C1C.toInt(), "Chú ý phía trước có biển cấm xe máy")
                9 -> Quad("ĐƯỜNG CẤM", 1, 0xFFB71C1C.toInt(), "Chú ý phía trước là đường cấm")
                12 -> Quad("CẤM VƯỢT", 6, 0xFF0277BD.toInt(), null)
                else -> Quad("HẾT CẤM VƯỢT", 6, 0xFF0277BD.toInt(), null)
            }
            if (Geo.angleDiff(sign.bearing.toDouble(), hdg) <= 35.0) {
                candidates += TrafficAlert(sign.id, label, d, priority, 100 + sign.code, sign.lat, sign.lon, color)
                if (speechText != null && d <= 250 && !wasAnnounced(sign.lat, sign.lon, lat, lon)) {
                    offerSpeech(priority, speechText)
                    rememberAnnounced(sign.id, sign.lat, sign.lon)
                }
            }
        }
        for (turn in dataset.turnGrid.near(predictedLat, predictedLon)) {
            val d = aheadDistance(turn.lat, turn.lon) ?: continue
            if (d > 250) continue
            if (Geo.angleDiff(turn.bearing.toDouble(), hdg) <= 35.0) {
                candidates += TrafficAlert(turn.id, "CẤM RẼ", d, 3, 200 + turn.code, turn.lat, turn.lon, 0xFFC62828.toInt())
            }
        }
        for (z in dataset.zoneGrid.near(predictedLat, predictedLon)) {
            val d = aheadDistance(z.lat, z.lon) ?: continue
            if (d > 250) continue
            if (Geo.angleDiff(z.bearing.toDouble(), hdg) <= 35.0) {
                candidates += TrafficAlert(z.id, if (z.code == 10) "VÀO ĐÔ THỊ" else "HẾT ĐÔ THỊ",
                    d, 6, 300 + z.code, z.lat, z.lon, 0xFF0277BD.toInt())
            }
        }

        val best = candidates.minWithOrNull(compareBy<TrafficAlert> { it.priority }.thenBy { it.distanceM })
        if (best != null) {
            lastEvent = best
            if (best.distanceM <= 60) lastEventUntil = nowElapsed + 3000L
        }
        val visibleAlert = best ?: lastEvent?.takeIf { nowElapsed <= lastEventUntil }

        val limit = effectiveMotoLimit
        val over = limit != null && currentSpeed >= limit + 4
        if (over) {
            if (overspeedSince == 0L) overspeedSince = nowElapsed
            if (!overspeedSpoken && nowElapsed - overspeedSince >= 2000L) {
                offerSpeech(0, "Chạy quá tốc độ giới hạn")
                overspeedSpoken = true
            }
        } else {
            overspeedSince = 0L
            overspeedSpoken = false
        }
        return TrafficUpdate(hdg, limit, isInUrbanZone, visibleAlert, over && overspeedSince > 0L &&
            nowElapsed - overspeedSince >= 2000L, speech, currentSpeed, true)
    }

    private data class Quad(val label: String, val priority: Int, val color: Int, val speech: String?)
    private fun wasAnnounced(
        pointLat: Double, pointLon: Double, currentLat: Double, currentLon: Double,
    ): Boolean {
        val sameCluster = announced.values.any { Geo.distance(it.first, it.second, pointLat, pointLon) < 60.0 }
        if (!sameCluster) return false
        val distanceToPoint = Geo.distance(currentLat, currentLon, pointLat, pointLon)
        val stillAhead = stableBearing >= 0.0 &&
            Geo.angleDiff(stableBearing, Geo.bearing(currentLat, currentLon, pointLat, pointLon)) <= 90.0
        return distanceToPoint <= 60.0 || (stillAhead && distanceToPoint <= 500.0)
    }
    private fun rememberAnnounced(
        id: String, pointLat: Double, pointLon: Double,
    ) {
        announced[id] = pointLat to pointLon
        while (announced.size > 256) announced.remove(announced.keys.first())
    }
    private fun snapshot(speed: Int, alert: TrafficAlert?, over: Boolean, speech: String?, loaded: Boolean): TrafficUpdate =
        TrafficUpdate(stableBearing, effectiveMotoLimit, isInUrbanZone, alert, over, speech, speed, loaded)
}
