package vn.hiep.motonav

import android.content.Context
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
    private val ctx: Context,
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
    // Mốc thời gian bắt đầu "gần như đứng yên, gần đích" — dự phòng tự kết thúc chuyến khi đường
    // không vào sát được tới điểm đến thật (cổng/bãi xe riêng...). 0 = chưa ở trạng thái đó.
    private var nearDestSince = 0L

    fun start(lat: Double, lon: Double, name: String?) {
        prefs.saveTrip(lat, lon, name)
        Live.ownNav = true
        Live.destLat = lat
        Live.destLon = lon
        Live.destName = name
        Live.route = null
        Live.routeError = null
        seg = 0
        nearDestSince = 0L
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
        // Google Directions (thử nghiệm) thay VietMap khi bật + đã nhập mã — lỗi thì tự quay lại
        // VietMap (không có dữ liệu kẹt xe khi dùng Google).
        fun findRoutes(alt: Boolean, via: List<Pair<Double, Double>> = emptyList()): List<Route> =
            VietMap.routes(key, lat, lon, heading, dLat, dLon, prefs.traffic, alt, via, prefs.avoidHighway)
        executor.execute {
            try {
                // Luôn lấy vài tuyến để tự chọn tuyến tốt nhất (tránh kẹt xe / ngắn nhất)
                var list = findRoutes(true)
                // Né các đoạn người dùng tự báo "đường bị chặn" (dữ liệu bản đồ cũ): bỏ bớt tuyến
                // đi ngang điểm đó nếu còn tuyến khác không dính
                val clean = list.filter { !BlockedRoads.blocksPath(ctx, it.lats, it.lons) }
                if (clean.isNotEmpty() && clean.size < list.size) list = clean
                list = RoutePick.rank(list, heading, forwardFirst = heading != null)
                // Quẹo nhầm / lệch tuyến: không bắt quay đầu – ép đi tiếp qua một điểm phía trước
                if (heading != null && (reroute || Live.speedKmh > 8) && RoutePick.isBackward(list.first(), heading)) {
                    try {
                        val ap = RoutePick.aheadPoint(Live.osm, lat, lon, heading)
                        val vr = findRoutes(false, listOf(ap)).first()
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
        var hi = min(n - 2, seg + 80)
        // Giới hạn thêm theo khoảng cách thực (không chỉ số điểm) — tránh khớp nhảy xa hàng trăm mét
        // chỉ vì 1 lần định vị nhiễu trùng hợp gần một điểm xa trên tuyến (ví dụ gần cuối tuyến)
        while (hi > seg && r.cum[hi] - r.cum[seg] > 400.0) hi--
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

        // Đến nơi — chốt thêm bằng khoảng cách thẳng tới điểm đích thực tế, tránh báo "đến nơi"
        // sớm khi khớp tuyến bị nhảy nhầm tới gần cuối tuyến (ví dụ GPS nhiễu/tuyến vòng gần đích)
        val straightDist = Geo.distance(lat, lon, Live.destLat, Live.destLon)
        if (remaining < 30 && straightDist < 40) {
            // Ghi log rõ lý do kết thúc tuyến — trước đây finish()/stop() không ghi gì cả, nên khi
            // tuyến tự biến mất giữa chừng (ví dụ GPS nhảy lố tới gần đích) không có cách nào phân
            // biệt được với các nguyên nhân khác (bấm nút Kết thúc, bấm nút Dừng trên màn xe...).
            TripLog.ev("arrived", "remaining" to remaining, "straight" to straightDist)
            alert("Đã đến nơi${Live.destName?.let { ": $it" } ?: ""}", "Đã đến nơi", false)
            finish()
            return
        }
        // Dự phòng: nhiều điểm đến (cổng công ty, bãi xe riêng, hẻm nhỏ...) đường trên bản đồ
        // không vào sát được tới mốc chính xác, nên "remaining<30 & straight<40" ở trên không bao
        // giờ đúng — xe cứ loanh quanh gần đó (đi bộ dắt xe/tìm chỗ đậu) mà app không tự kết thúc,
        // nhìn như "dẫn đường chưa thông minh, tới nơi rồi không kết thúc mà bắt vòng ngược lại"
        // (log thực tế: đứng/đi rất chậm ~2 phút cách đích 48-85m, không bao giờ vào được ngưỡng cũ).
        // Nới ra: gần như đứng yên một lúc (~20s) trong bán kính rộng hơn (120m) thì coi như đã tới.
        val now2 = SystemClock.elapsedRealtime()
        if (straightDist < 120 && speedMps < 1.0) {
            if (nearDestSince == 0L) nearDestSince = now2
            if (now2 - nearDestSince > 20_000) {
                TripLog.ev("arrived", "remaining" to remaining, "straight" to straightDist, "why" to "stationaryNearDest")
                alert("Đã đến nơi${Live.destName?.let { ": $it" } ?: ""}", "Đã đến nơi", false)
                finish()
                return
            }
        } else {
            nearDestSince = 0L
        }

        // Đi lệch tuyến (~10 m) → tìm tuyến mới (2 lần định vị liên tiếp ≈ 2 giây).
        // Chỉ khi xe THẬT SỰ đang chạy: tốc độ > 7 km/h và đã đi được ≥ 25 m kể từ lần tìm trước
        // (GPS trong nhà/đứng yên trôi vài chục mét không được tính là lệch tuyến).
        // Gần đích (trong 150m) thì nới ngưỡng: lúc đó thường đang đi rất chậm (dắt xe, tìm cổng/
        // chỗ đậu trong khu dân cư/công ty), tốc độ > 7km/h gần như không bao giờ đạt nên trước đây
        // không bao giờ tìm lại tuyến được nữa — xe cứ loanh quanh gần đích mà tuyến cũ (có khi vẽ
        // vòng xa) không được cập nhật, nhìn như "bắt vòng ngược lại" (phản hồi thực tế).
        val nearDest = straightDist < 150
        val moving = (if (nearDest) speedMps > 0.5 else speedMps > 2.0) &&
            Geo.distance(lat, lon, rrLat, rrLon) >= (if (nearDest) 12.0 else 25.0)
        if (off && moving) offCount++ else offCount = 0
        val now = SystemClock.elapsedRealtime()
        // Tìm lại liên tục nhiều lần (≥ 3 lần trong 2 phút) → giãn ra 60 giây cho đỡ tốn lượt VietMap.
        // Nhưng nếu đã đi lệch QUÁ XA (≥ 60 m) hoặc đang ở gần đích thì luôn tìm lại nhanh (5 giây) —
        // ưu tiên bắt theo hướng xe đang chạy tới ngay, không để xe chạy xa cả trăm mét mà app vẫn
        // chỉ tuyến cũ phía sau, và đoạn cuối gần đích cần bám sát hơn bình thường.
        rerouteTimes.removeAll { now - it > 120_000 }
        val urgent = bestD > 60 || nearDest
        val gap = if (urgent) 5_000 else if (rerouteTimes.size >= 3) 60_000 else 8_000
        if (moving && (offCount >= 2 || bestD > 40 || urgent) && !routing && now - lastReroute > gap) {
            rerouteTimes.add(now)
            TripLog.ev("reroute", "d" to bestD, "n" to offCount, "spd" to speedMps, "hdg" to heading)
            rrLat = lat; rrLon = lon
            alert("Đi lệch tuyến, đang tìm đường mới", "Đang tìm đường mới", false)
            requestRoute(lat, lon, heading)
        }

        // Đang lệch tuyến: không hiện mũi tên/chỉ dẫn rẽ của tuyến CŨ nữa — tuyến đó có thể chỉ
        // ngược về phía sau xe (chỗ vừa đi lệch khỏi), nhìn vào dễ hiểu lầm là app chỉ sai hướng.
        // Chờ tìm lại tuyến mới xong rồi hiện lại. LƯU Ý: không tắt Live.navActive ở đây — cờ đó
        // nghĩa là "đang có chuyến đi" dùng ở nhiều nơi khác (ô nổi, màn chính…), tắt nhầm lúc chỉ
        // lệch tuyến thoáng qua (ví dụ GPS nhiễu lúc đứng yên) làm toàn bộ các nơi đó tắt theo.
        // Màn TFT tự xét thêm Live.offRoute để không gửi mũi tên cũ lúc đang lệch.
        if (off) {
            Live.maneuverLabel = null
            Live.maneuverStreet = null
            Live.nextLeg = null
            Live.thenText = null
            Live.thenDistM = 0.0
            return
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
        var phrase = Maneuver.phrase(next)
        if (next.sign == 4) {
            // "Đến nơi" báo từ xa (còn 200-300m, tuyến xanh vẫn đang dẫn) nghe như đã tới rồi —
            // gây hiểu lầm (phản hồi thực tế). Thay bằng kiểu Google Maps: định vị điểm đến THẬT
            // (Live.destLat/Lon — chính xác hơn điểm cuối tuyến đã snap vào đường) nằm bên nào so
            // với hướng xe đang chạy tới, rồi báo đếm ngược khoảng cách như chỉ dẫn rẽ bình thường.
            // Câu "Đã đến nơi" chỉ nói đúng lúc tới thật, ở nhánh remaining<30 phía trên.
            val pIdx = max(0, mIdx - 1)
            val roadBrg = if (r.lats[pIdx] != r.lats[mIdx] || r.lons[pIdx] != r.lons[mIdx])
                Geo.bearing(r.lats[pIdx], r.lons[pIdx], r.lats[mIdx], r.lons[mIdx]) else null
            phrase = if (roadBrg != null && (Live.destLat != 0.0 || Live.destLon != 0.0)) {
                val toDestBrg = Geo.bearing(r.lats[mIdx], r.lons[mIdx], Live.destLat, Live.destLon)
                when (val side = Geo.signedAngle(roadBrg, toDestBrg)) {
                    in 25.0..180.0 -> "Điểm đến bên phải"
                    in -180.0..-25.0 -> "Điểm đến bên trái"
                    else -> "Điểm đến phía trước"
                }
            } else "Điểm đến"
        }
        Live.maneuverLat = r.lats[mIdx]
        Live.maneuverLon = r.lons[mIdx]
        Live.maneuverLabel = if (next.sign == 4) phrase else if (next.street.isNotBlank()) next.street else Maneuver.verb(next.sign)
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
            Live.thenDistM = gap
        } else {
            Live.thenText = null
            Live.thenDistM = 0.0
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
            // Báo theo THỜI GIAN tới chỗ rẽ (như Google Maps): ~15 giây trước và ~5 giây trước,
            // nên chạy nhanh thì báo sớm hơn, chạy chậm trong phố thì không báo quá xa.
            val v = maxOf(speedMps, 4.0)
            val far = (v * 15).coerceIn(200.0, 800.0)
            val near = (v * 5).coerceIn(40.0, 130.0)
            if (toTurn <= far && toTurn > near + 60 && spoken.add("$key/far")) {
                val dd = (toTurn / 50).roundToInt() * 50
                alert("$dd m nữa: $phrase", "$dd mét nữa, $phrase", false)
            }
            if (toTurn <= near && spoken.add("$key/now")) {
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
