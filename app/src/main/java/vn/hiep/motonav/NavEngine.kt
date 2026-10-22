package vn.hiep.motonav

import android.os.SystemClock
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dẫn đường của riêng MotoNav: bám theo tuyến VietMap, tính khoảng cách tới chỗ rẽ,
 * đọc + hiện chỉ dẫn, tự tìm lại tuyến khi đi lệch.
 */
class NavEngine(
    private val prefs: Prefs,
    private val executor: ExecutorService,
    private val alert: (text: String, speech: String, urgent: Boolean) -> Unit,
) {
    private var seg = 0
    private var offCount = 0
    private var rrLat = 0.0
    private var rrLon = 0.0
    private val rerouteTimes = ArrayList<Long>()
    @Volatile private var routing = false
    private var lastReroute = 0L
    private val spoken = HashSet<String>()

    fun start(lat: Double, lon: Double, name: String?) {
        prefs.saveTrip(lat, lon, name)
        Live.ownNav = true
        Live.destLat = lat
        Live.destLon = lon
        Live.destName = name
        Live.route = null
        Live.routeError = null
        seg = 0
        spoken.clear()
        if (Live.gpsOk) requestRoute(Live.lat, Live.lon, if (Live.heading >= 0) Live.heading else null, offerChoice = true)
        else Live.routeStatus = "Đang chờ GPS để tìm đường…"
        Live.changed()
    }

    /** Kết thúc chuyến (người dùng bấm Kết thúc hoặc đã đến nơi): xoá chuyến đã lưu. */
    fun finish() {
        prefs.clearTrip()
        stop()
    }

    fun stop() {
        Live.ownNav = false
        Live.route = null
        Live.routeStatus = null
        Live.routeError = null
        Live.maneuverLabel = null
        Live.nextLeg = null
        Live.junctionJson = null
        Live.junctionVersion++
        Live.text3dJson = null
        Live.text3dVersion++
        Live.clearNav()
    }

    /** Tìm tuyến khác từ vị trí hiện tại để người dùng chọn. */
    fun requestAlternatives() {
        if (!Live.ownNav || !Live.gpsOk) return
        requestRoute(Live.lat, Live.lon, if (Live.heading >= 0) Live.heading else null, offerChoice = true, force = true)
    }

    fun choose(i: Int) {
        val list = Live.routeChoices
        if (i !in list.indices) return
        Live.choiceSelected = i
        Live.route = list[i]
        Live.routeVersion++
        Live.choicesVersion++
        seg = 0
        offCount = 0
        spoken.clear()
        Live.changed()
    }

    fun closeChoices() {
        Live.routeChoices = emptyList()
        Live.choicesVersion++
        Live.changed()
    }

    private fun requestRoute(lat: Double, lon: Double, heading: Double?, offerChoice: Boolean = false, force: Boolean = false) {
        val key = prefs.vietmapKey
        if (key.isBlank()) {
            Live.routeError = "Chưa nhập mã API VietMap (Cài đặt)"
            Live.changed()
            return
        }
        if (routing && !force) return
        routing = true
        lastReroute = SystemClock.elapsedRealtime()
        rrLat = lat; rrLon = lon
        Live.routeStatus = when {
            offerChoice && Live.route != null -> "Đang tìm tuyến khác…"
            Live.route == null -> "Đang tìm đường xe máy…"
            else -> "Đang tìm lại tuyến…"
        }
        Live.changed()
        val dLat = Live.destLat
        val dLon = Live.destLon
        val reroute = Live.route != null && !offerChoice
        executor.execute {
            try {
                // Luôn lấy vài tuyến để tự chọn tuyến tốt nhất (tránh kẹt xe / ngắn nhất)
                var list = VietMap.routes(key, lat, lon, heading, dLat, dLon, prefs.traffic, true)
                list = RoutePick.rank(list, heading, forwardFirst = heading != null)
                // Quẹo nhầm / lệch tuyến: không bắt quay đầu – ép đi tiếp qua một điểm phía trước
                if (heading != null && (reroute || Live.speedKmh > 8) && RoutePick.isBackward(list.first(), heading)) {
                    try {
                        val ap = RoutePick.aheadPoint(Live.osm, lat, lon, heading)
                        val vr = VietMap.routes(key, lat, lon, heading, dLat, dLon, prefs.traffic, false, listOf(ap)).first()
                        if (!RoutePick.isBackward(vr, heading)) list = listOf(vr) + list
                    } catch (_: Exception) {
                    }
                }
                if (!offerChoice && list.size > 1) list = list.take(1)
                val r = list.first()
                if (Live.ownNav) {
                    if (offerChoice && list.size > 1) {
                        Live.routeChoices = list
                        Live.choiceSelected = 0
                    } else {
                        if (offerChoice && Live.route != null) Live.routeError = "VietMap không có tuyến khác cho đoạn này"
                        Live.routeChoices = emptyList()
                    }
                    Live.choicesVersion++
                    Live.route = r
                    Live.routeVersion++
                    TripLog.route(r, if (reroute) "reroute" else if (offerChoice) "start" else "retry")
                    if (!(offerChoice && list.size == 1 && Live.routeError != null)) Live.routeError = null
                    seg = 0
                    offCount = 0
                    spoken.clear()
                }
            } catch (e: Exception) {
                Live.routeError = e.message ?: "Lỗi tìm đường"
            } finally {
                routing = false
                Live.routeStatus = null
                Live.changed()
            }
        }
    }

    fun onLocation(lat: Double, lon: Double, heading: Double?, speedMps: Double) {
        if (!Live.ownNav) return
        val r = Live.route
        if (r == null) {
            if (!routing && Live.routeError == null) requestRoute(lat, lon, heading)
            else if (!routing && SystemClock.elapsedRealtime() - lastReroute > 60000) requestRoute(lat, lon, heading)
            return
        }

        // Chiếu vị trí lên tuyến (tìm quanh đoạn hiện tại)
        val proj = LocalProjection(lat, lon)
        val n = r.lats.size
        val lo = max(0, seg - 5)
        val hi = min(n - 2, seg + 80)
        var bestD = Double.MAX_VALUE
        var bestI = seg
        var bestT = 0.0
        for (i in lo..hi) {
            val ax = proj.x(r.lons[i]); val ay = proj.y(r.lats[i])
            val bx = proj.x(r.lons[i + 1]); val by = proj.y(r.lats[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 0.01) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) { bestD = d; bestI = i; bestT = t }
        }
        seg = bestI
        val segLen = r.cum[bestI + 1] - r.cum[bestI]
        val progress = r.cum[bestI] + bestT * segLen
        Live.routeSeg = bestI
        Live.routeProgress = progress
        val remaining = max(0.0, r.total - progress)
        Live.remainingM = remaining
        Live.etaMs = if (r.total > 0) (r.timeMs * (remaining / r.total)).toLong() else 0L
        // Lệch tuyến: cách tuyến > 12 m mà hướng đi lệch hướng tuyến > 35°, hoặc cách > 22 m
        val segBrg = Geo.bearing(r.lats[bestI], r.lons[bestI], r.lats[bestI + 1], r.lons[bestI + 1])
        val turnedAway = heading != null && speedMps > 1.5 && Geo.angleDiff(heading, segBrg) > 35
        val off = (bestD > 12 && turnedAway) || bestD > 22
        if (off != Live.offRoute) TripLog.ev("off", "on" to off, "d" to bestD, "hdg" to heading, "seg" to segBrg)
        Live.offRoute = off
        TripLog.ev("nav", "d" to bestD, "p" to progress, "i" to bestI)
        // Hướng tuyến phía trước (nhìn trước ~30 m) để bản đồ luôn để đường thẳng dọc màn hình.
        // Đã lệch tuyến thì bản đồ theo hướng xe thật, không kéo ngược về tuyến cũ.
        if (off) {
            Live.roadBearing = heading ?: -1.0
        } else run {
            var j = bestI + 1
            while (j < n - 1 && r.cum[j] - progress < 30) j++
            val sLat = r.lats[bestI] + bestT * (r.lats[bestI + 1] - r.lats[bestI])
            val sLon = r.lons[bestI] + bestT * (r.lons[bestI + 1] - r.lons[bestI])
            if (Geo.distance(sLat, sLon, r.lats[j], r.lons[j]) > 3) {
                Live.roadBearing = Geo.bearing(sLat, sLon, r.lats[j], r.lons[j])
            }
        }
        Live.snapLat = r.lats[bestI] + bestT * (r.lats[bestI + 1] - r.lats[bestI])
        Live.snapLon = r.lons[bestI] + bestT * (r.lons[bestI + 1] - r.lons[bestI])

        // Đến nơi
        if (remaining < 30) {
            alert("Đã đến nơi${Live.destName?.let { ": $it" } ?: ""}", "Đã đến nơi", false)
            finish()
            return
        }

        // Đi lệch tuyến (~10 m) → tìm tuyến mới (2 lần định vị liên tiếp ≈ 2 giây).
        // Chỉ khi xe THẬT SỰ đang chạy: tốc độ > 7 km/h và đã đi được ≥ 25 m kể từ lần tìm trước
        // (GPS trong nhà/đứng yên trôi vài chục mét không được tính là lệch tuyến).
        val moving = speedMps > 2.0 && Geo.distance(lat, lon, rrLat, rrLon) >= 25
        if (off && moving) offCount++ else offCount = 0
        val now = SystemClock.elapsedRealtime()
        // Tìm lại liên tục nhiều lần (≥ 3 lần trong 2 phút) → giãn ra 60 giây cho đỡ tốn lượt VietMap
        rerouteTimes.removeAll { now - it > 120_000 }
        val gap = if (rerouteTimes.size >= 3) 60_000 else 8_000
        if (moving && (offCount >= 2 || bestD > 40) && !routing && now - lastReroute > gap) {
            rerouteTimes.add(now)
            TripLog.ev("reroute", "d" to bestD, "n" to offCount, "spd" to speedMps, "hdg" to heading)
            rrLat = lat; rrLon = lon
            alert("Đi lệch tuyến, đang tìm đường mới", "Đang tìm đường mới", false)
            requestRoute(lat, lon, heading)
        }

        // Chỗ rẽ tiếp theo: bước đầu tiên có điểm bắt đầu nằm phía trước
        var next: Step? = null
        var nextIdx = -1
        for ((k, s) in r.steps.withIndex()) {
            if (k == 0) continue
            if (s.from > bestI) { next = s; nextIdx = k; break }
        }
        if (next == null) {
            // còn lại chỉ là về đích
            next = r.steps.lastOrNull()
            nextIdx = r.steps.size - 1
        }
        if (next == null) return
        val mIdx = min(next.from, n - 1)
        val toTurn = max(0.0, r.cum[mIdx] - progress)
        val phrase = Maneuver.phrase(next)
        Live.maneuverLat = r.lats[mIdx]
        Live.maneuverLon = r.lons[mIdx]
        Live.maneuverLabel = if (next.street.isNotBlank()) next.street else Maneuver.verb(next.sign)
        Live.maneuverSign = next.sign
        Live.maneuverStreet = next.street.ifBlank { null }

        // Đoạn đường sắp rẽ vào: từ chỗ rẽ đi tiếp ~250 m (tô sáng trên bản đồ)
        if (next.sign != 0 && next.sign != 4 && next.sign != 5) {
            val sb = StringBuilder("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
            var j = mIdx
            var first = true
            while (j < n && r.cum[j] - r.cum[mIdx] <= 250) {
                if (!first) sb.append(',')
                sb.append('[').append(r.lons[j]).append(',').append(r.lats[j]).append(']')
                first = false
                j++
            }
            if (j < n && !first) sb.append(",[").append(r.lons[j]).append(',').append(r.lats[j]).append(']')
            sb.append("]}}")
            Live.nextLeg = if (j - mIdx >= 1) sb.toString() else null
        } else {
            Live.nextLeg = null
        }

        // Bước sau nữa (hiện dòng "Sau đó")
        val after = r.steps.getOrNull(nextIdx + 1)
        // Luôn hiện lần rẽ kế tiếp: "Sau đó · 380 m: Rẽ trái vào Lê Lợi"
        if (after != null && after.sign != 0) {
            val gap = r.cum[min(after.from, n - 1)] - r.cum[mIdx]
            Live.thenText = "Sau đó · ${fmtDist(gap)}: " + Maneuver.phrase(after)
            Live.thenSign = after.sign
        } else {
            Live.thenText = null
        }

        // Đưa vào các trường dùng chung (ô nổi, thẻ rẽ, tiếng bíp khi sắp rẽ)
        Live.navActive = true
        Live.navArrow = ArrowIcon.get(next.sign, 192)
        Live.navMeters = toTurn.roundToInt()
        Live.navDistance = fmtDist(toTurn)
        Live.navInstruction = phrase
        Live.navExtra = null

        // Đọc chỉ dẫn (đang lệch tuyến thì không đọc chỉ dẫn của tuyến cũ, tránh bảo quay lại)
        if (next.sign != 0 && !off) {
            val key = "$nextIdx@" + next.from
            val spd = speedMps * 3.6
            val far = if (spd > 45) 600 else 300
            if (toTurn <= far && toTurn > 120 && spoken.add("$key/far")) {
                val dd = (toTurn / 50).roundToInt() * 50
                alert("$dd m nữa: $phrase", "$dd mét nữa, $phrase", false)
            }
            if (toTurn <= 70 && spoken.add("$key/now")) {
                alert(phrase.uppercase(), phrase, true)
            }
        }
    }

    companion object {
        fun fmtDist(m: Double): String = when {
            m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000).replace('.', ',')
            m >= 100 -> "${(m / 10).roundToInt() * 10} m"
            else -> "${max(0, (m / 5).roundToInt() * 5)} m"
        }
    }
}
