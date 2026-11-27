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
    val bearing: Int, val isHcmArterial: Boolean, val laneCode: Int = 0,
)

internal data class TrafficCameraPoint(
    val id: String, val lat: Double, val lon: Double, val speedFlag: Int, val type: Int,
) {
    // Mã loại trong cameras_vn.json: 0 tốc độ, 1 giám sát, 2 đèn đỏ, 3 AI, 4 phạt nguội.
    // Ánh xạ theo hằng số CamKind nội bộ: SPEED=0, RED_LIGHT=1, TRAFFIC=2,
    // FINE=3, HAZARD=4, AI=9. Không dùng trực tiếp mã JSON làm CamKind.
    val kind: Int get() = when (type) {
        0 -> CamKind.SPEED
        1 -> CamKind.TRAFFIC
        2 -> CamKind.RED_LIGHT
        3 -> CamKind.AI
        4 -> CamKind.FINE
        else -> CamKind.TRAFFIC
    }
}

internal data class TrafficZonePoint(
    val id: String, val lat: Double, val lon: Double, val code: Int, val bearing: Int,
)

internal data class TrafficSignPoint(
    val id: String, val lat: Double, val lon: Double, val code: Int, val bearing: Int,
    val extraField: Int? = null,
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
    val nearestCamera: TrafficAlert? = null,
    val cameraPins: List<TrafficCameraPoint> = emptyList(),
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
    val loadedFiles: Set<String>,
    val errors: List<String>,
) {
    val speedGrid = TrafficSpatialGrid<TrafficSpeedPoint>({ it.lat }, { it.lon }).also { g -> speedPoints.forEach(g::add) }
    val cameraGrid = TrafficSpatialGrid<TrafficCameraPoint>({ it.lat }, { it.lon }).also { g -> cameras.forEach(g::add) }
    val zoneGrid = TrafficSpatialGrid<TrafficZonePoint>({ it.lat }, { it.lon }).also { g -> zones.forEach(g::add) }
    val signGrid = TrafficSpatialGrid<TrafficSignPoint>({ it.lat }, { it.lon }).also { g -> signs.forEach(g::add) }

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

            val speeds = ArrayList<TrafficSpeedPoint>()
            val speedIndex = TrafficSpatialGrid<TrafficSpeedPoint>({ it.lat }, { it.lon })

            data class SpeedCandidate(val row: DoubleArray, val source: String, val hcm: Boolean) {
                val speed get() = row[2].toInt()
                val bearing get() = row[3].toInt().let { if (it < 0) -1 else it % 360 }
                val laneCode get() = row.getOrNull(4)?.toInt()?.takeIf { it >= 0 } ?: 0
            }
            val laneCandidates = buildList {
                plainSigns.forEach { add(SpeedCandidate(it, "vn", false)) }
                hcmSigns.forEach { add(SpeedCandidate(it, "hcm", true)) }
            }.filter { it.speed > 0 }

            // Gom các biển cùng trục (<25 m, cùng hướng). Trên trục đại lộ,
            // bỏ biển làn xe tải/xe khách tốc độ <=40; ưu tiên làn xe máy 50–60,
            // sau đó mới đến biển làn chung laneCode=0.
            val laneGroups = ArrayList<MutableList<SpeedCandidate>>()
            for (candidate in laneCandidates) {
                val group = laneGroups.firstOrNull { g ->
                    g.any { other ->
                        Geo.distance(other.row[0], other.row[1], candidate.row[0], candidate.row[1]) < 25.0 &&
                            other.bearing >= 0 && candidate.bearing >= 0 &&
                            Geo.angleDiff(other.bearing.toDouble(), candidate.bearing.toDouble()) <= 20.0
                    }
                }
                if (group == null) laneGroups += mutableListOf(candidate) else group += candidate
            }
            for (group in laneGroups) {
                val arterial = group.any { it.hcm } || group.size > 1
                // laneCode khác 0 và tốc độ <=40 là làn xe tải/container/xe khách: loại bỏ
                // kể cả khi biển đứng riêng, không phụ thuộc việc cụm có được nhận diện là đại lộ.
                val eligible = group.filterNot { it.speed <= 40 && it.laneCode != 0 }
                if (eligible.isEmpty()) continue
                // Giữ toàn bộ biển hợp lệ trong cụm. Việc chọn biển phải/biển làn xe máy phải được
                // quyết định theo vị trí tương đối với xe đang chạy, không chọn sẵn theo thứ tự JSON.
                for (selected in eligible) {
                    val r = selected.row
                    val point = TrafficSpeedPoint(
                        "${selected.source}:${speeds.size}", r[0], r[1], selected.speed,
                        selected.bearing, arterial, selected.laneCode,
                    )
                    speeds += point
                    speedIndex.add(point)
                }
            }
            for (r in limitRows) {
                val speed = r[2].toInt()
                val bearing = r[3].toInt().let { if (it < 0) -1 else it % 360 }
                if (speed <= 0) continue
                val duplicate = speedIndex.near(r[0], r[1]).firstOrNull {
                    it.bearing >= 0 && bearing >= 0 &&
                        Geo.angleDiff(it.bearing.toDouble(), bearing.toDouble()) <= 20.0 &&
                        Geo.distance(it.lat, it.lon, r[0], r[1]) < 15.0
                }
                if (duplicate == null) {
                    val p = TrafficSpeedPoint("limits:${speeds.size}", r[0], r[1], speed, bearing, false, 0)
                    speeds += p
                    speedIndex.add(p)
                }
            }

            val cameras = cameraRows.mapIndexedNotNull { i, r ->
                val type = r[3].toInt()
                if (type !in 0..4) null else TrafficCameraPoint("cam:$i", r[0], r[1], r[2].toInt(), type)
            }
            val zones = zoneRows.mapIndexedNotNull { i, r ->
                val code = r[2].toInt()
                if (code != 10 && code != 11) null else TrafficZonePoint("zone:$i", r[0], r[1], code, r[3].toInt())
            }
            // Mảng signs trong cameras_vn.json: [lat,lng,signCode,bearing,extraAttr].
            // extraAttr chỉ là metadata của nguồn này, không phải laneCode.
            val signs = signRows.mapIndexedNotNull { i, r ->
                val code = r[2].toInt()
                if (code !in setOf(5, 6, 7, 8, 9, 12, 13)) null
                else TrafficSignPoint("sign:$i", r[0], r[1], code, r[3].toInt(), r[4].toInt().takeIf { it >= 0 })
            }
            OfflineTrafficDataset(speeds, cameras, zones, signs, loaded, errors)
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
    private var lastRoadKey: String? = null
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
        bearing: Double, nowElapsed: Long, bikeSpeedKmh: Int = -1, roadKey: String? = null,
        authoritativeLimitKmh: Int? = null,
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

        // Khi OSM xác nhận đã sang tuyến khác, không mang giới hạn của tuyến cũ sang.
        // Chỉ dùng tên/ref ổn định; không dùng ID đoạn OSM vì mỗi đoạn cùng đường có ID riêng.
        if (!roadKey.isNullOrBlank()) {
            val previousRoad = lastRoadKey
            if (previousRoad != null && previousRoad != roadKey) {
                // Sang đường mới: không mang biển của tuyến cũ sang. Đánh dấu cần định vị lại
                // biển tốc độ và mốc trong/ngoài khu dân cư trên chính tuyến mới ngay ở lần fix này.
                currentRawSpeedLimit = null
                currentSpeedIsHcm = false
                effectiveMotoLimit = null
                isInUrbanZone = false
                zoneKnown = false
                lastZonePointId = null
                lastSpeedAnchorBearing = -1.0
                overspeedSince = 0L
                overspeedSpoken = false
            }
            lastRoadKey = roadKey
        }

        if (lastSpeedAnchorBearing >= 0 && Geo.angleDiff(hdg, lastSpeedAnchorBearing) > 60.0 &&
            Geo.distance(lat, lon, lastSpeedAnchorLat, lastSpeedAnchorLon) > 40.0) {
            // Rẽ sang hướng đường mới: không kéo giới hạn của nhánh cũ qua giao lộ.
            // Lần evaluate này sẽ chiếu lại biển và mốc khu dân cư gần nhất theo hướng mới.
            currentRawSpeedLimit = null
            currentSpeedIsHcm = false
            effectiveMotoLimit = null
            isInUrbanZone = false
            zoneKnown = false
            lastZonePointId = null
            lastSpeedAnchorBearing = -1.0
            lastSpeedAnchorLat = lat
            lastSpeedAnchorLon = lon
        }

        val lookahead = (speedMps * 1.1).coerceAtMost(35.0)
        val rad = Math.toRadians(hdg)
        val predictedLat = lat + cos(rad) * lookahead / 111320.0
        val predictedLon = lon + sin(rad) * lookahead / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))

        // Nhận diện khu đông dân cư theo đúng hướng tiếp cận biển.
        val zone = dataset.zoneGrid.near(lat, lon).asSequence()
            .map { p ->
                val d = Geo.distance(lat, lon, p.lat, p.lon)
                val side = Geo.signedAngle(hdg, Geo.bearing(lat, lon, p.lat, p.lon))
                Triple(p, d, side)
            }
            .filter { (p, d, side) ->
                val along = d * cos(Math.toRadians(side))
                val crossTrack = kotlin.math.abs(d * sin(Math.toRadians(side)))
                d <= 350.0 && along >= -10.0 && crossTrack <= 100.0 &&
                    (p.bearing < 0 || Geo.angleDiff(p.bearing.toDouble(), hdg) <= 40.0)
            }
            // Chiếu mốc khu dân cư lên hướng đường đang chạy: chọn mốc phía trước gần nhất,
            // không chọn điểm ở nhánh ngang hoặc phía sau giao lộ.
            .minByOrNull { (_, d, side) ->
                d * cos(Math.toRadians(side)) + kotlin.math.abs(d * sin(Math.toRadians(side))) * 0.35
            }?.first
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
        val speedCandidates = dataset.speedGrid.near(lat, lon).mapNotNull { p ->
            val d = Geo.distance(lat, lon, p.lat, p.lon)
            if (d > speedRadius) return@mapNotNull null
            if (p.bearing >= 0 && Geo.angleDiff(p.bearing.toDouble(), hdg) > 35.0) return@mapNotNull null
            val bearingToSign = Geo.bearing(lat, lon, p.lat, p.lon)
            val sideAngle = Geo.signedAngle(hdg, bearingToSign) // dương = bên phải, âm = bên trái
            val along = d * cos(Math.toRadians(sideAngle))
            if (along < -10.0) return@mapNotNull null
            // Biển càng xa càng phải nằm sát trục chạy; càng gần có thể chấp nhận góc lệch lớn hơn.
            val maxRightAngle = when {
                d <= 30.0 -> 30.0
                d <= 80.0 -> 20.0
                d <= 150.0 -> 12.0
                else -> 5.0
            }
            // Quy ước laneCode đã thống nhất: làn riêng tốc độ 50–60 thường là làn xe máy;
            // laneCode khác 0 với tốc độ <=40 là làn xe tải/container/xe khách, tuyệt đối không nhận.
            val explicitMoto = p.laneCode != 0 && p.speed in 50..60
            val rightSide = sideAngle > 0.5 && sideAngle <= maxRightAngle
            val centered = sideAngle >= -0.5 && sideAngle <= 2.0
            // Không chọn biển bên trái (thường dành cho làn ô tô), trừ khi metadata của bảng
            // xác nhận là biển dành riêng cho xe máy. Biển gần chính diện vẫn là phương án dự phòng.
            if (!rightSide && !centered && !explicitMoto) return@mapNotNull null
            Triple(p, d, sideAngle)
        }
        // Nếu có cụm biển chung, ưu tiên biển bên phải nhất theo góc chiếu; sau đó mới xét khoảng
        // cách. Biển có metadata laneCode được giữ như dấu hiệu làn/biển riêng; không lấy biển trái
        // chỉ vì nó gần GPS hơn.
        val nearestSpeedCandidate = speedCandidates.minByOrNull {
            it.second * cos(Math.toRadians(it.third))
        }
        val speedCluster = nearestSpeedCandidate?.let { seed ->
            speedCandidates.filter { candidate ->
                Geo.distance(seed.first.lat, seed.first.lon, candidate.first.lat, candidate.first.lon) <= 25.0 &&
                    (seed.first.bearing < 0 || candidate.first.bearing < 0 ||
                        Geo.angleDiff(seed.first.bearing.toDouble(), candidate.first.bearing.toDouble()) <= 20.0)
            } ?: emptyList()
        } ?: emptyList()
        val speedPoint = speedCluster.sortedWith(
            compareBy<Triple<TrafficSpeedPoint, Double, Double>> {
                when {
                    it.third > 0.5 && it.first.laneCode != 0 && it.first.speed in 50..60 -> 0
                    it.third > 0.5 -> 1       // ưu tiên góc dương: biển bên phải theo chiều kim đồng hồ
                    it.first.laneCode != 0 && it.first.speed in 50..60 -> 2 // làn xe máy theo quy ước laneCode
                    else -> 3                 // biển gần chính diện, chỉ dùng khi không có biển phải
                }
            }.thenByDescending { it.third }.thenBy { it.second * cos(Math.toRadians(it.third)) }
        ).firstOrNull()?.first
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
        val cameraPins = dataset.cameraGrid.near(lat, lon).filter {
            Geo.distance(lat, lon, it.lat, it.lon) <= 1500.0
        }
        var nearestCamera: TrafficAlert? = null
        fun aheadDistance(pLat: Double, pLon: Double): Int? {
            val d = Geo.distance(predictedLat, predictedLon, pLat, pLon)
            if (d > 350.0) return null
            val relative = Geo.angleDiff(hdg, Geo.bearing(predictedLat, predictedLon, pLat, pLon))
            if (relative > 30.0 && d > 40.0) return null
            return d.roundToInt()
        }
        for (cam in cameraPins) {
            val actualDistance = Geo.distance(lat, lon, cam.lat, cam.lon)
            val cameraBearing = Geo.bearing(lat, lon, cam.lat, cam.lon)
            val cameraAngle = Geo.angleDiff(hdg, cameraBearing)
            // Camera có thể nằm hai bên đường, khác với biển giới hạn tốc độ; chỉ loại điểm phía
            // sau khi đã cách đủ xa để xác định hướng. Trong 40 m giữ lại để không nhấp nháy.
            if (actualDistance <= 900.0 && (cameraAngle <= 40.0 || actualDistance <= 40.0)) {
                val (cameraLabel, cameraPriority, cameraColor) = when (cam.kind) {
                    CamKind.SPEED -> Triple("TỐC ĐỘ", 2, 0xFFD32F2F.toInt())
                    CamKind.RED_LIGHT -> Triple("ĐÈN ĐỎ", 5, 0xFFE65100.toInt())
                    CamKind.TRAFFIC -> Triple("GIÁM SÁT", 7, 0xFFF57F17.toInt())
                    CamKind.AI -> Triple("CAM AI", 4, 0xFF4527A0.toInt())
                    CamKind.FINE -> Triple("PHẠT NGUỘI", 2, 0xFFD32F2F.toInt())
                    CamKind.HAZARD -> Triple("NGUY HIỂM", 4, 0xFFF57F17.toInt())
                    else -> Triple("GIÁM SÁT", 7, 0xFFF57F17.toInt())
                }
                val cameraEvent = TrafficAlert(cam.id, cameraLabel, actualDistance.roundToInt(),
                    cameraPriority, cam.kind, cam.lat, cam.lon, cameraColor)
                if (nearestCamera == null || cameraEvent.distanceM < nearestCamera!!.distanceM) {
                    nearestCamera = cameraEvent
                }
            }
            val d = aheadDistance(cam.lat, cam.lon) ?: continue
            val (label, priority, color) = when (cam.kind) {
                CamKind.SPEED -> Triple("TỐC ĐỘ", 2, 0xFFD32F2F.toInt())
                CamKind.RED_LIGHT -> Triple("ĐÈN ĐỎ", 5, 0xFFE65100.toInt())
                CamKind.TRAFFIC -> Triple("GIÁM SÁT", 7, 0xFFF57F17.toInt())
                CamKind.AI -> Triple("CAM AI", 4, 0xFF4527A0.toInt())
                CamKind.FINE -> Triple("PHẠT NGUỘI", 2, 0xFFD32F2F.toInt())
                CamKind.HAZARD -> Triple("NGUY HIỂM", 4, 0xFFF57F17.toInt())
                else -> Triple("GIÁM SÁT", 7, 0xFFF57F17.toInt())
            }
            candidates += TrafficAlert(cam.id, label, d, priority, cam.kind, cam.lat, cam.lon, color)
            if (d <= 300 && cam.kind in setOf(CamKind.SPEED, CamKind.FINE, CamKind.AI) &&
                !wasAnnounced(cam.lat, cam.lon, lat, lon)) {
                offerSpeech(priority, when (cam.kind) {
                    CamKind.SPEED -> "Phía trước 300 mét có camera bắn tốc độ"
                    CamKind.FINE -> "Phía trước 300 mét có camera phạt nguội"
                    CamKind.AI -> "Phía trước 300 mét có camera AI"
                    else -> "Phía trước 300 mét có camera phạt nguội"
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
                13 -> Quad("HẾT CẤM VƯỢT", 6, 0xFF0277BD.toInt(), null)
                else -> Quad("CẤM RẼ / QUAY ĐẦU", 3, 0xFFC62828.toInt(), null)
            }
            if (Geo.angleDiff(sign.bearing.toDouble(), hdg) <= 35.0) {
                candidates += TrafficAlert(sign.id, label, d, priority, 100 + sign.code, sign.lat, sign.lon, color)
                if (speechText != null && d <= 250 && !wasAnnounced(sign.lat, sign.lon, lat, lon)) {
                    offerSpeech(priority, speechText)
                    rememberAnnounced(sign.id, sign.lat, sign.lon)
                }
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

        val limit = authoritativeLimitKmh ?: effectiveMotoLimit
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
            nowElapsed - overspeedSince >= 2000L, speech, currentSpeed, true, nearestCamera, cameraPins)
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
