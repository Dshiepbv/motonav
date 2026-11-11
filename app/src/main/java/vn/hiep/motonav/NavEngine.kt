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
    private val providerOf = java.util.WeakHashMap<Route, String>()
    @Volatile private var winnerProvider = "goong"
    private var offCount = 0
    private var loopRoute: Route? = null
    private var loopUAt = -1.0
    private var loopSince = 0L
    private var rrLat = 0.0
    private var rrLon = 0.0
    private val rerouteTimes = ArrayList<Long>()
    @Volatile private var routing = false
    private var lastTraceRoute: Route? = null
    private var lastTraceStepIndex = -1
    private var lastReroute = 0L
    private val spoken = HashSet<String>()
    // Mốc thời gian bắt đầu "gần như đứng yên, gần đích" — dự phòng tự kết thúc chuyến khi đường
    // không vào sát được tới điểm đến thật (cổng/bãi xe riêng...). 0 = chưa ở trạng thái đó.
    private var nearDestSince = 0L

    // Hiệu chỉnh giờ đến: Goong báo lâu hơn thực tế xe máy → học hệ số k = giờ thật / giờ Goong qua các chuyến.
    private var tripStartAt = 0L
    private var tripPredMs = 0L
    private fun etaK(): Double = prefs.etaK
    private fun learnEta() {
        if (tripStartAt <= 0L || tripPredMs < 5 * 60_000L) { tripStartAt = 0L; return }
        val actual = SystemClock.elapsedRealtime() - tripStartAt
        val ratio = (actual.toDouble() / tripPredMs).coerceIn(0.4, 1.3)
        val old = prefs.etaK
        val n = prefs.etaN
        val k = if (n == 0) ratio else old * 0.65 + ratio * 0.35
        prefs.etaK = k; prefs.etaN = n + 1
        TripLog.ev("etaLearn", "predMin" to tripPredMs / 60000, "actualMin" to actual / 60000, "ratio" to ratio, "k" to k, "n" to n + 1)
        tripStartAt = 0L
    }
    // Vết xe vừa đi (≈2 phút gần nhất): [lat, lon, hướng (-1 = không rõ), thời điểm] — để loại tuyến
    // kéo ngược về đoạn đường vừa đi qua.
    private val trail = ArrayDeque<DoubleArray>()
    // Lần tìm lại gần nhất không ra tuyến nào thoả "không quay đầu": giữ tuyến cũ, đi tiếp thêm một
    // đoạn rồi mới tìm lại (đếm số lần để nới dần điểm ép đi tiếp ra xa hơn).
    @Volatile private var noUturnFails = 0
    private var onRouteSince = 0L
    @Volatile private var destGen = 0
    @Volatile private var destChanged = false
    private val DEST_SNAP = false
    private val VALHALLA_ONLY = true
    private val BRIDGE_HINT = false // build-331: Sếp: "lên cầu / dưới cầu" toàn sai, tắt — tính sau
    // build-323: sau khi chốt điểm đến vào cổng/lối vào, tuyến mới dài hơn tuyến đang chạy > 300 m → bỏ, giữ điểm gốc
    @Volatile private var destCheckRemain = -1.0
    private var origDestLat = 0.0
    private var origDestLon = 0.0

    fun start(lat: Double, lon: Double, name: String?) {
        prefs.saveTrip(lat, lon, name)
        tripStartAt = 0L
        Live.ownNav = true
        Live.destLat = lat
        Live.destLon = lon
        Live.destName = name
        Live.destFlag = true
        Live.route = null
        Live.routeError = null
        seg = 0
        lastTraceRoute = null
        lastTraceStepIndex = -1
        nearDestSince = 0L
        spoken.clear()
        if (Live.gpsOk) requestRoute(Live.lat, Live.lon, if (Live.heading >= 0) Live.heading else null, offerChoice = true)
        else Live.routeStatus = "Đang chờ GPS để tìm đường…"
        Live.changed()
        // Chốt điểm đến về cổng / lối vào / bãi gửi xe (OSM) — chạy song song, không bắt chờ: tìm
        // đường ngay tới toạ độ gốc trước, có kết quả (lệch > 15 m) thì tìm lại tới điểm mới.
        val gen = ++destGen
        destChanged = false
        destCheckRemain = -1.0
        origDestLat = lat
        origDestLon = lon
        // build-324: Sếp chốt — giữ đúng điểm đến theo kết quả tìm kiếm, không tự dời vào cổng/lối vào (OSM)
        if (DEST_SNAP) Thread({
            try {
                val acc = DestAccess.resolve(lat, lon, name)
                if (acc != null && gen == destGen && Live.ownNav) {
                    val moved = Geo.distance(lat, lon, acc.lat, acc.lon)
                    TripLog.ev("destSnap", "kind" to acc.kind, "label" to acc.label, "d" to moved, "lat" to acc.lat, "lon" to acc.lon)
                    if (moved > 15.0) {
                        Live.destLat = acc.lat
                        Live.destLon = acc.lon
                        destChanged = true
                    }
                } else if (acc == null) {
                    TripLog.ev("destSnap", "kind" to "none")
                }
            } catch (e: Exception) {
                TripLog.ev("destSnap", "err" to (e.message ?: "?"))
            }
        }, "dest-access").apply { isDaemon = true; start() }
    }

    /** Kết thúc chuyến (người dùng bấm Kết thúc hoặc đã đến nơi): xoá chuyến đã lưu. */
    fun finish() {
        prefs.clearTrip()
        stop()
    }

    fun stop() {
        tripStartAt = 0L
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
        val vmKey = prefs.vietmapKey
        val goongKey = prefs.goongKey
        if (!VALHALLA_ONLY && vmKey.isBlank() && goongKey.isBlank()) {
            Live.routeError = "Chưa nhập mã API VietMap hoặc Goong (Cài đặt)"
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
        val requestStartElapsed = SystemClock.elapsedRealtime()
        var routeSucceeded = false
        TripLog.ev(
            if (reroute) "rerouteStart" else "routeRequestStart",
            "reason" to if (reroute) "off_route" else if (offerChoice) "initial_or_alternative" else "retry",
            "lat" to lat, "lng" to lon, "heading" to heading,
            "routeVersion" to Live.routeVersion,
        )
        // Theo yêu cầu "ưu tiên Goong, bỏ hẳn VietMap nếu Goong ok": có mã Goong là dùng THẲNG
        // Goong, không còn âm thầm rơi về VietMap khi Goong lỗi nữa — lỗi thật sẽ hiện rõ lên màn
        // hình (routeError) để biết ngay Goong có ổn hay chưa, thay vì VietMap "che" mất vấn đề mà
        // không ai biết Goong đang có lỗi. Chỉ dùng VietMap khi CHƯA nhập mã Goong nào cả. LƯU Ý:
        // Goong không có né cao tốc/dữ liệu kẹt xe xác nhận được (xem comment đầu GoongMap.kt) —
        // hai tuỳ chọn đó trong Cài đặt chỉ có tác dụng khi dùng VietMap.
        // Chỉ áp ràng buộc hướng khi xe đang chạy thật (đứng yên thì hướng la bàn không đáng tin).
        val moving = heading != null && heading >= 0 && Live.speedKmh > 6
        val trailNow: List<DoubleArray> = synchronized(trail) {
            // Chỉ lấy vết đã đi qua (cũ hơn 3 giây, cách xe > 30 m) — đoạn ngay quanh xe không tính.
            val now = SystemClock.elapsedRealtime().toDouble()
            trail.filter { now - it[3] > 3000 && Geo.distance(lat, lon, it[0], it[1]) > 30 }
        }
        val fails = noUturnFails
        executor.execute {
            try {
                // build-214 — ràng buộc "dự đoán hướng xe, nhất định không quay đầu" (log 27/10: Goong
                // tự gắn điểm xuất phát vào đường gần nhất — đường cũ/làn song hành — nên tuyến mới
                // bắt đi ngược 239° trong khi xe chạy 57°, đoạn ngược dài dần 25→340 m):
                //  1) Xuất phát từ điểm ~40 m phía trước xe trên ĐÚNG con đường đang đi (OSM), không
                //     dùng toạ độ GPS thô → Goong không gắn nhầm sang đường bên cạnh.
                //  2) Loại tuyến xuất phát ngược hướng, tuyến kéo ngược lại vết vừa đi, tuyến quay đầu
                //     trong 1 km đầu để chạy về lại đường cũ. Quay đầu nằm sẵn trong lộ trình đi tiếp
                //     (vòng chân cầu…) và tuyến tìm lúc bắt đầu chuyến thì giữ nguyên.
                //  3) Không còn tuyến nào hợp lệ → thử ép đi qua điểm phía trước xa dần (150/400/800 m).
                //  4) Vẫn không có → KHÔNG nhận tuyến quay đầu: giữ "đi tiếp", đi thêm 150 m rồi tìm lại.
                val on = if (moving) RoutePick.locate(Live.osm, lat, lon, heading!!) else null
                RoutePick.skipLeading.set(on != null)
                val origin = if (on != null) RoutePick.walk(on, 40.0) else (lat to lon)
                val startLat = on?.lat ?: lat
                val startLon = on?.lon ?: lon
                // build-305: chạy Goong + VietMap song song (khi có cả hai mã). Tìm tuyến lúc bắt đầu: gọi CẢ HAI, mọi tuyến
                // qua cùng bộ lọc/xếp hạng, log routeDual. Tìm lại (reroute)/điểm đi qua: chỉ gọi bên đã thắng lúc bắt đầu,
                // bên kia chỉ gọi khi bên thắng lỗi.
                // build-313: thêm Valhalla xe máy (máy chủ OSM cộng đồng, miễn phí, không cần mã). Tìm tuyến lúc
                // bắt đầu gọi MỌI nguồn; tìm lại giữa đường ưu tiên Valhalla (không tốn tiền), lỗi thì bên thắng, rồi bên còn lại.
                val provAvail = ArrayList<String>().apply {
                    // build-329: Sếp chạy thử chỉ Valhalla — tạm tắt Goong, VietMap
                    if (!VALHALLA_ONLY) { if (goongKey.isNotBlank()) add("goong"); if (vmKey.isNotBlank()) add("vm") }
                    add("vh")
                }
                val dual = provAvail.size > 1
                val rerouteOrder = (listOf("vh", winnerProvider) + provAvail).distinct().filter { it in provAvail }
                val vhHeading = if (moving) heading else null
                fun callAt(p: String, fLat: Double, fLon: Double, alt: Boolean, via: List<Pair<Double, Double>>): List<Route> = when (p) {
                    "goong" -> { countGoongCall(); GoongMap.routes(goongKey, fLat, fLon, dLat, dLon, prefs.goongVehicle, alt, via) }
                    "vm" -> { countVmCall(); VietMap.routes(vmKey, fLat, fLon, heading, dLat, dLon, prefs.traffic, alt, via, prefs.avoidHighway) }
                    else -> Valhalla.routes(fLat, fLon, vhHeading, dLat, dLon, alt, via)
                }
                fun callProv(p: String, alt: Boolean, via: List<Pair<Double, Double>>): List<Route> = callAt(p, origin.first, origin.second, alt, via)
                fun fromOrigin(alt: Boolean, via: List<Pair<Double, Double>> = emptyList()): List<Route> {
                    val both = dual && !reroute && via.isEmpty()
                    val provs = if (both) provAvail else rerouteOrder
                    val out = ArrayList<Route>()
                    var err: Exception? = null
                    for (p in provs) {
                        if (!both && out.isNotEmpty()) break
                        try {
                            val raw = callProv(p, alt, via)
                            val m = if (on != null) raw.map { RoutePick.prepend(it, startLat, startLon) } else raw
                            for (x in m) synchronized(providerOf) { providerOf[x] = p }
                            out.addAll(m)
                        } catch (e: Exception) {
                            err = e
                            if (dual) TripLog.ev("routeProvErr", "p" to p, "err" to (e.message ?: e.javaClass.simpleName))
                        }
                    }
                    if (out.isEmpty()) throw err ?: RuntimeException("Không tìm được đường")
                    return out
                }
                // Luôn lấy vài tuyến để tự chọn tuyến tốt nhất (tránh kẹt xe / ngắn nhất)
                var list = fromOrigin(true)
                // Né các đoạn người dùng tự báo "đường bị chặn" (dữ liệu bản đồ cũ): bỏ bớt tuyến
                // đi ngang điểm đó nếu còn tuyến khác không dính
                val clean = list.filter { !BlockedRoads.blocksPath(ctx, it.lats, it.lons) }
                if (clean.isNotEmpty() && clean.size < list.size) list = clean
                val mixed = synchronized(providerOf) { list.mapNotNull { providerOf[it] }.toSet().size > 1 }
                list = RoutePick.rank(list, heading, forwardFirst = heading != null, mixed = mixed)
                // Loại tuyến chạy vào làn/đường cấm xe máy (thẻ OSM) ngay từ lần tìm đầu, nếu còn tuyến khác.
                run {
                    val cm = list.map { RoutePick.carOnlyM(it, 3000.0) }
                    if (cm.any { it >= 40.0 }) {
                        TripLog.ev("carOnly", "m" to cm.joinToString(",") { it.toInt().toString() })
                        val ok = list.filterIndexed { i, _ -> cm[i] < 40.0 }
                        if (ok.isNotEmpty()) list = ok
                    }
                }
                var holdForward = false
                if (moving) {
                    val ok = list.filter { RoutePick.rejectReason(it, heading, trailNow) == null }
                    TripLog.ev("routeCheck", "cands" to list.size, "ok" to ok.size,
                        "why" to list.joinToString(",") { RoutePick.rejectReason(it, heading, trailNow) ?: "ok" },
                        "originShift" to (on != null), "fails" to fails)
                    if (ok.isNotEmpty()) {
                        list = ok
                    } else {
                        var found: Route? = null
                        val rejected = ArrayList<Route>(list)
                        // Log 31/10 (D1): mỗi lần mất tuyến, Goong đề xuất quay đầu ngay (uturnAt 30) vì đường xe đang chạy khác tuyến
                        // đã tính; thử 150 m / 250 m phía trước đều bị loại → lần 3 nhận tuyến quay đầu → xe đi thẳng → lệch lại, lặp
                        // 5–6 vòng. Nay mỗi lần thử một điểm "đi qua" XA dần (150/500/1100 m) trên đúng con đường xe đang chạy, rồi mới
                        // ép nhận ở lần thứ 4.
                        val dists = when {
                            fails >= 2 -> listOf(1100.0)
                            fails == 1 -> listOf(500.0)
                            else -> listOf(150.0)
                        }
                        val overQuota = goongCallsToday() >= 800
                        // Mọi tuyến đều chạy vào đường cấm xe máy (vd. trục chính Xa lộ Hà Nội, xe máy phải đi đường song hành):
                        // gọi Goong một lần với 3 điểm "đi qua" nằm trên chính con đường xe đang chạy (250/700/1300 m phía trước)
                        // để ép tuyến ở lại đường song hành thay vì nhảy lên trục chính.
                        var carViaTried = false
                        if (on != null && !overQuota && list.any { RoutePick.carOnlyM(it, 3000.0) >= 40.0 }) {
                            try {
                                val vp = listOf(250.0, 700.0, 1300.0).map { RoutePick.walk(on, 40.0 + it) }
                                val vr = fromOrigin(false, vp).firstOrNull()
                                if (vr != null) {
                                    val why = RoutePick.rejectReason(vr, heading, trailNow)
                                    TripLog.ev("routeCarVia", "why" to (why ?: "ok"), "carOnlyM" to RoutePick.carOnlyM(vr, 3000.0).toInt())
                                    carViaTried = true
                                    if (why == null) found = vr else rejected.add(vr)
                                }
                            } catch (_: Exception) {
                            }
                        }
                        // build-242: mỗi lần chỉ thử 1 điểm phía trước (trước 3, log 29/10 tốn ~9 lượt trong 15 s mà
                        // vẫn ra tuyến quay đầu); lần 3 liên tiếp (fails>=2) nhận luôn tuyến nhanh nhất.
                        // build-282: đã thử tuyến ép qua điểm trên đường đang đi (carVia) mà vẫn bị loại → bỏ 2 lượt Goong còn lại (shift/via cùng bị loại như log 31/10).
                        for (dAhead in if (found != null || carViaTried) emptyList() else if (fails >= 1) emptyList() else dists.take(1)) {
                            try {
                                val ap = if (on != null) RoutePick.walk(on, 40.0 + dAhead) else RoutePick.aheadPoint(Live.osm, lat, lon, heading!!, dAhead)
                                // build-237: xuất phát hẳn từ điểm phía trước (không chỉ "đi qua") — Goong hay gắn
                                // điểm xuất phát vào làn đối diện của đường có dải phân cách rồi bắt quay đầu.
                                // Đặt điểm đầu đủ xa phía trước thì không còn bị gắn nhầm; nối xe → điểm đó bằng
                                // đoạn đi tiếp trên đường đang chạy.
                                val shifted = try {
                                    var raw: List<Route> = emptyList()
                                    for (p in rerouteOrder) {
                                        try { raw = callAt(p, ap.first, ap.second, false, emptyList()); if (raw.isNotEmpty()) break } catch (_: Exception) {}
                                    }
                                    raw.firstOrNull()?.let { if (on != null) RoutePick.prependPath(it, RoutePick.walkPoints(on, 40.0 + dAhead)) else RoutePick.prepend(RoutePick.prepend(it, ap.first, ap.second), startLat, startLon) }
                                } catch (_: Exception) { null }
                                val sWhy = shifted?.let { RoutePick.rejectReason(it, heading, trailNow) }
                                if (shifted != null) TripLog.ev("routeShift", "ahead" to dAhead, "why" to (sWhy ?: "ok"))
                                if (shifted != null && sWhy == null) { found = shifted; break }
                                // Mỗi lượt Goong đều tính tiền: đã có tuyến xuất phát từ điểm trước xe thì không gọi thêm "via".
                                val vr = shifted ?: fromOrigin(false, listOf(ap)).first()
                                val why = RoutePick.rejectReason(vr, heading, trailNow)
                                TripLog.ev("routeVia", "ahead" to dAhead, "why" to (why ?: "ok"))
                                if (why == null) { found = vr; break }
                                rejected.add(vr)
                            } catch (_: Exception) {
                            }
                        }
                        if (found != null) {
                            list = listOf(found)
                        } else if (fails >= 1 && rejected.isNotEmpty()) { // build-331: lần tìm lại thứ 2 đã nhận tuyến quay lại (log 3/11 19:07 Ngã tư Hóa An, quẹo nhầm chân cầu)
                            // // build-310: về như 302 (luật 304 làm giữ tuyến cũ 80 s, log 2/11)
                            // build-229 — quay đầu chỉ bị phạt, không cấm tuyệt đối (như Google/Waze):
                            // đã thử ≥3 lần (~10 s) mà phía trước không có tuyến nào hợp lệ (đường bị
                            // chặn, dải phân cách…) → nhận tuyến NHANH NHẤT trong số đã thử, kể cả quay
                            // đầu, thay vì để nét đỏ đứng nguyên tuyến cũ cả phút.
                            val best = rejected.minWithOrNull(compareBy<Route>({ (RoutePick.carOnlyM(it, 3000.0) / 100.0).toInt() }, { it.timeMs }))!!
                            TripLog.ev("routeForced", "fails" to fails, "timeMs" to best.timeMs, "tried" to rejected.size,
                                "why" to (RoutePick.rejectReason(best, heading, trailNow) ?: "ok"))
                            list = listOf(best)
                        } else {
                            holdForward = true
                        }
                    }
                }
                if (holdForward && reroute) {
                    // Không nhận tuyến quay đầu — giữ nguyên, báo đi tiếp; NavEngine.onLocation sẽ tìm
                    // lại sau khi xe đi thêm ≥150 m.
                    noUturnFails = fails + 1
                    TripLog.ev("routeHold", "fails" to noUturnFails)
                    if (destCheckRemain >= 0) { destCheckRemain = -1.0; Live.destLat = origDestLat; Live.destLon = origDestLon }
                    return@execute
                }
                noUturnFails = 0
                if (!offerChoice && list.size > 1) list = list.take(1)
                val r = list.first()
                val chk = destCheckRemain
                if (chk >= 0) {
                    destCheckRemain = -1.0
                    if (r.total > chk + 300.0) {
                        // Điểm cổng/lối vào làm tuyến dài thêm → giữ tuyến cũ và điểm đến gốc
                        Live.destLat = origDestLat
                        Live.destLon = origDestLon
                        TripLog.ev("destSnapReject", "newM" to r.total.toInt(), "oldRemainM" to chk.toInt())
                        return@execute
                    }
                    TripLog.ev("destSnapAccept", "newM" to r.total.toInt(), "oldRemainM" to chk.toInt())
                }
                if (dual && !reroute) {
                    val pw = synchronized(providerOf) { providerOf[r] } ?: winnerProvider
                    winnerProvider = pw
                    try {
                        TripLog.ev("routeDual", "win" to pw, "cands" to list.joinToString(" | ") { c ->
                            val p = synchronized(providerOf) { providerOf[c] } ?: "?"
                            "$p ${c.total.toInt()}m ${c.timeMs / 60000}ph car${RoutePick.carOnlyM(c, 3000.0).toInt()} u${RoutePick.uturnAt(c, 3000.0).toInt()} det${RoutePick.detourM(c).toInt()} t${c.steps.count { it.sign != 0 }}"
                        })
                    } catch (_: Exception) {}
                }
                if (Live.ownNav) {
                    if (offerChoice && list.size > 1) {
                        Live.routeChoices = list
                        Live.choiceSelected = 0
                    } else {
                        if (offerChoice && Live.route != null) Live.routeError = "VietMap không có tuyến khác cho đoạn này"
                        Live.routeChoices = emptyList()
                    }
                    Live.choicesVersion++
                    if (!reroute && tripStartAt == 0L) { tripStartAt = SystemClock.elapsedRealtime(); tripPredMs = r.timeMs }
                    Live.route = r
                    routeSucceeded = true
                    Live.routeVersion++
                    TripLog.ev("routeUpdate", "reason" to if (reroute) "reroute" else if (offerChoice) "initial_or_alternative" else "retry",
                        "routeVersion" to Live.routeVersion, "distanceM" to r.distance, "timeMs" to r.timeMs,
                        "stepCount" to r.steps.size)
                    TripLog.route(r, if (reroute) "reroute" else if (offerChoice) "start" else "retry")
                    if (!(offerChoice && list.size == 1 && Live.routeError != null)) Live.routeError = null
                    seg = 0
                    offCount = 0
                    spoken.clear()
                }
            } catch (e: Exception) {
                Live.routeError = e.message ?: "Lỗi tìm đường"
                TripLog.ev("routeErr", "msg" to (e.message ?: e.javaClass.simpleName).take(120))
            } finally {
                TripLog.ev(
                    "routeUpdateComplete",
                    "reroute" to reroute,
                    "success" to routeSucceeded,
                    "durationMs" to (SystemClock.elapsedRealtime() - requestStartElapsed),
                    "routeVersion" to Live.routeVersion,
                    "error" to if (routeSucceeded) null else Live.routeError,
                )
                routing = false
                Live.routeStatus = null
                Live.changed()
            }
        }
    }

    /** Đếm lượt gọi Goong theo ngày (hạn mức 1000/ngày, 30.000/tháng) để giới hạn thử lại. */
    private fun dayKey() = "goongCalls_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    private fun goongCallsToday(): Int = ctx.applicationContext.getSharedPreferences("motonav", android.content.Context.MODE_PRIVATE).getInt(dayKey(), 0)
    private fun countVmCall() {
        try {
            val sp = ctx.applicationContext.getSharedPreferences("motonav", android.content.Context.MODE_PRIVATE)
            val k = "vm_" + dayKey()
            sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply()
        } catch (_: Exception) {}
    }
    private fun countGoongCall() {
        val sp = ctx.applicationContext.getSharedPreferences("motonav", android.content.Context.MODE_PRIVATE)
        val n = sp.getInt(dayKey(), 0) + 1
        sp.edit().putInt(dayKey(), n).apply()
        if (n % 50 == 0) TripLog.ev("goongCalls", "today" to n)
    }

    fun onLocation(lat: Double, lon: Double, heading: Double?, speedMps: Double, acc: Double = 10.0) {
        if (!Live.ownNav) return
        synchronized(trail) {
            val nowT = SystemClock.elapsedRealtime().toDouble()
            val last = trail.lastOrNull()
            if (last == null || Geo.distance(last[0], last[1], lat, lon) >= 8.0) {
                trail.addLast(doubleArrayOf(lat, lon, if (heading != null && speedMps > 1.5) heading else -1.0, nowT))
            }
            while (trail.isNotEmpty() && nowT - trail.first()[3] > 120_000) trail.removeFirst()
        }
        if (destChanged && !routing && Live.route != null) {
            destChanged = false
            val cur = Live.route!!
            destCheckRemain = (cur.total - Live.routeProgress).coerceAtLeast(0.0)
            requestRoute(lat, lon, heading)
            return
        }
        val r = Live.route
        if (r == null) {
            if (!routing && Live.routeError == null) requestRoute(lat, lon, heading)
            else if (!routing && SystemClock.elapsedRealtime() - lastReroute > 60000) requestRoute(lat, lon, heading)
            return
        }
        // Có tuyến hợp lệ đang bám theo được (tới được đây nghĩa là tuyến cũ vẫn dùng tốt) → xoá
        // thông báo lỗi cũ nếu còn sót lại. Theo phản hồi thực tế: dòng đỏ kiểu "Request failed due
        // to a permanent error: Canceled" (lỗi mạng/bị huỷ khi tìm LẠI tuyến lúc đi lệch) vẫn hiện
        // dai dẳng dù tuyến đang đi vẫn đúng và chạy bình thường — vì trước đây chỉ xoá routeError
        // lúc tìm tuyến MỚI thành công, không xoá khi tuyến CŨ vẫn đang được bám theo tốt. Giờ cứ
        // còn đang theo dõi được tuyến hiện tại (xuống tới đây) là coi như ổn, dọn luôn thông báo cũ.
        if (Live.routeError != null) Live.routeError = null

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
        Live.etaMs = if (r.total > 0) (r.timeMs * etaK() * (remaining / r.total)).toLong() else 0L
        // Lệch tuyến: cách tuyến > 12 m mà hướng đi lệch hướng tuyến > 35°, hoặc cách > 22 m.
        // ĐÃ THỬ hạ xuống 10/18m (bản 7.21) để định tuyến lại nhanh hơn, nhưng log thực tế cho thấy
        // ngưỡng đó quá sát nhiễu GPS thường (dao động 10-18m dù đang đi đúng tuyến) — gây "on/off"
        // nhấp nháy liên tục kéo theo định tuyến lại dồn dập (xem rerouteTimes/gap bên dưới) tự nó
        // trở thành nguồn "reload liên tục" mới. Trả về 12/22m gốc, vẫn ổn định mà không chậm.
        val segBrg = Geo.bearing(r.lats[bestI], r.lons[bestI], r.lats[bestI + 1], r.lons[bestI + 1])
        val turnedAway = heading != null && speedMps > 1.5 && Geo.angleDiff(heading, segBrg) > 35
        // build-240: GPS yếu (khu nhà cao tầng, sai số 20–40 m) làm khoảng cách tới tuyến nhảy 30–50 m dù xe
        // đi đúng → reroute liên tục lúc mới xuất phát (log 28/10). Ngưỡng lệch nới theo sai số GPS.
        val offTol = max(22.0, acc * 1.6)
        val offTurn = max(12.0, acc * 0.9)
        // build-276: làn song hành/làn bên cạnh — hướng xe trùng hướng tuyến (<20°), lệch ngang < 55 m thì
        // không coi là lệch tuyến (log 31/10: 8 lần reroute vô ích trên QL1K, hdg 132° = hướng đường).
        val parallelLane = heading != null && speedMps > 1.5 && Geo.angleDiff(heading, segBrg) < 20 && bestD < 90
        val off0 = !parallelLane && ((bestD > offTurn && turnedAway) || bestD > offTol)
        // build-279: xe đang đi theo vòng quay đầu mà tuyến có sẵn chỗ quay đầu quanh đó (hình học Goong không vẽ hết
        // vòng) → không coi là lệch, không reroute (log 31/10: nút giao 10,952N/106,807E, 5–6 reroute mỗi lần qua).
        // Chỉ miễn tối đa 90 s mỗi lần, và khi xe cách tuyến < 300 m.
        if (loopRoute !== r) { loopRoute = r; loopUAt = RoutePick.uturnAt(r, 6000.0); loopSince = 0L }
        val nowL = SystemClock.elapsedRealtime()
        // build-329: log 3/11 17:51 Nam Cao — nhịn 43 s tới khi cách tuyến 300 m. Chỉ nhịn khi cách tuyến < 60 m, tối đa 20 s.
        val inLoopZone = loopUAt >= 0 && progress > loopUAt - 400 && progress < loopUAt + 600 && bestD < 60 && speedMps > 1.0
        var loopHold = false
        if (off0 && inLoopZone) {
            if (loopSince == 0L) loopSince = nowL
            loopHold = nowL - loopSince < 20_000
        } else if (!off0) loopSince = 0L
        if (loopHold) TripLog.ev("loopHold", "d" to bestD, "p" to progress, "uAt" to loopUAt)
        val off = off0 && !loopHold
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
            learnEta()
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
        // build-407: log 11/11 22:00 báo tới nơi khi còn 129 m (đứng chờ gần đích) → siết lại: ≤70 m thẳng, ≤90 m theo tuyến, đứng 30 s.
        if (straightDist < 70 && remaining < 90 && speedMps < 1.0) {
            if (nearDestSince == 0L) nearDestSince = now2
            if (now2 - nearDestSince > 30_000) {
                TripLog.ev("arrived", "remaining" to remaining, "straight" to straightDist, "why" to "stationaryNearDest")
                learnEta()
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
        // Tìm lại liên tục nhiều lần (≥ 3 lần trong 2 phút) → giãn ra cho đỡ tốn lượt Goong VÀ đỡ
        // dồn khung TFT — log thực tế (chuyến 115807) cho thấy khi lệch tuyến mà định tuyến lại
        // KHÔNG giải quyết được (ví dụ ngõ cụt xe máy mới vào được, ô tô/Goong không thấy đường nối
        // lại), "urgent" (bestD>45) từng đè luôn lên ngưỡng giãn này → định tuyến lại dồn dập mỗi
        // 4-8 giây suốt 40 giây liền không dừng. Sửa: ngưỡng giãn (đã thử ≥3 lần/2 phút) LUÔN có
        // hiệu lực trước, kể cả khi urgent. "urgent" vì gần đích cũng chỉ còn tính khi THẬT SỰ đang
        // lệch tuyến (off) — trước đây cứ vào bán kính 150m quanh đích là tự coi là urgent dù đang
        // đi ĐÚNG tuyến (d chỉ 1-5m), gây định tuyến lại vô ích liên tục ngay trước khi tới nơi.
        rerouteTimes.removeAll { now - it > 120_000 }
        // build-283: làn song hành (cùng hướng, lệch < 90 m — hai chiều của đại lộ có dải phân cách) không tính là khẩn cấp
        // (log 1/11: 7 reroute vô ích ở d 46–54 m, hướng 61° trùng hướng đường).
        val urgent = (bestD > max(45.0, acc * 2.2) && !parallelLane) || (nearDest && off)
        // build-214: sau ≥3 lần tìm lại trong 2 phút, hoặc lần trước không ra tuyến "không quay đầu",
        // giãn theo QUÃNG ĐƯỜNG đã đi (≥150 m) thay vì chờ cứng 50 s — log 27/10: chờ 50 s giữ tuyến
        // quay đầu sai suốt ~350 m. Vẫn tối thiểu 6 s giữa 2 lần để không dồn lượt Goong.
        val sinceLast = Geo.distance(lat, lon, rrLat, rrLon)
        // build-229: đang giữ tuyến vì bị chặn quay đầu → hỏi lại theo THỜI GIAN (3,5 s), không chờ đi
        // thêm 150 m (log 27/10: nét đỏ đứng cũ 17–97 s mỗi lần bị chặn).
        val gapOk = if (noUturnFails > 0) {
            now - lastReroute > 3_500
        } else if (rerouteTimes.size >= 3) {
            now - lastReroute > 6_000 && sinceLast >= 150.0
        } else {
            now - lastReroute > (if (urgent) 4_500 else 6_000)
        }
        if (off && noUturnFails > 0 && !routing) Live.routeStatus = "Đi tiếp — đang tìm đường không phải quay đầu…"
        // build-331: chỉ xoá đếm "bị chặn quay đầu" khi đã đúng tuyến liên tục ≥15 s — log 19:07–19:09: off/on chập chờn
        // (cách tuyến 11–14 m, chạy ngược hướng) làm đếm về 0 mãi, tuyến quay lại không bao giờ được nhận.
        if (off) onRouteSince = 0L else if (onRouteSince == 0L) onRouteSince = now
        if (!off && noUturnFails > 0 && now - onRouteSince >= 15_000) {
            noUturnFails = 0
            if (!routing) Live.routeStatus = null
        }
        if (moving && !loopHold && (offCount >= 2 || (bestD > max(35.0, acc * 2.0) && !parallelLane) || urgent) && !routing && gapOk) {
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

        // Chỗ rẽ tiếp theo: bước đầu tiên có điểm bắt đầu (= điểm cuối đoạn đường ĐANG đi) nằm từ vị
        // trí hiện tại trở về sau. Trước dùng "s.from > bestI" (lớn hơn NGHIÊM NGẶT) — tới đúng lúc
        // xe ở sát điểm rẽ (bestI chạm đúng s.from) thì điều kiện đã sai, app nhảy luôn qua báo chỗ
        // rẽ KẾ TIẾP, khoảng cách đếm ngược của chỗ rẽ hiện tại không bao giờ chạy về 0 mà bị cắt
        // ngang sớm. Đổi thành ">=" để điểm cuối đoạn đường đang đi (= điểm bắt đầu quẹo) vẫn được
        // tính đúng tới khi còn 0 m rồi mới chuyển sang chỗ rẽ tiếp theo.
        var next: Step? = null
        var nextIdx = -1
        for ((k, s) in r.steps.withIndex()) {
            if (k == 0) continue
            if (s.from >= bestI) { next = s; nextIdx = k; break }
        }
        if (next == null) {
            // còn lại chỉ là về đích
            next = r.steps.lastOrNull()
            nextIdx = r.steps.size - 1
        }
        if (next == null) return
        val previousTraceRoute = lastTraceRoute
        val previousTraceStep = lastTraceStepIndex
        if (previousTraceRoute !== r || previousTraceStep != nextIdx) {
            val oldStep = previousTraceRoute?.steps?.getOrNull(previousTraceStep)
            TripLog.ev(
                "roadSegmentChanged",
                "old" to oldStep?.street,
                "new" to next.street,
                "oldStepIndex" to previousTraceStep,
                "newStepIndex" to nextIdx,
                "oldSign" to oldStep?.sign,
                "newSign" to next.sign,
                "routeChanged" to (previousTraceRoute !== r),
                "routeSeg" to bestI,
                "routeProgressM" to progress,
                "lat" to lat, "lng" to lon,
            )
            lastTraceRoute = r
            lastTraceStepIndex = nextIdx
        }
        var mIdx = min(next.from, n - 1)
        var toTurn = max(0.0, r.cum[mIdx] - progress)
        // build-305: chỗ tách tầng cầu/dưới cầu phía trước, gần hơn chỗ rẽ kế → báo như một bước riêng.
        var bridgeText: String? = null
        if (BRIDGE_HINT && next.sign != 4) try {
            val bh = BridgeHint.ahead(r, progress, Math.min(500.0, toTurn - 30.0))
            if (bh != null && bh.distM > 0) {
                var bi = mIdx
                for (q in bestI..mIdx) if (r.cum[q] >= progress + bh.distM) { bi = q; break }
                val street = bh.name?.trim().orEmpty()
                bridgeText = (if (bh.kind == BridgeHint.ON) "Lên cầu" else "Đi dưới cầu") + (if (street.isNotEmpty()) " $street" else "")
                next = Step(0, bridgeText, street, bh.distM, bi, bi, 0)
                mIdx = bi
                toTurn = bh.distM
                nextIdx -= 1 // "sau đó" vẫn là chỗ rẽ thật
            }
        } catch (_: Exception) {}
        var phrase = bridgeText ?: Maneuver.phrase(next)
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
        Live.maneuverLabel = bridgeText ?: (if (next.sign == 4) phrase else if (next.street.isNotBlank()) next.street else Maneuver.verb(next.sign))
        Live.maneuverSign = next.sign
        Live.maneuverExit = next.exitNumber
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
        Live.navMeters = (toTurn / 10).roundToInt() * 10 // đếm ngược chẵn mỗi 10 m
        Live.navDistance = fmtDist(toTurn)
        Live.navInstruction = phrase
        Live.navExtra = null
        TripLog.ev(
            "navigationUpdate",
            "lat" to lat, "lng" to lon, "accuracy" to acc,
            "speedMps" to speedMps, "heading" to heading,
            "routeSeg" to bestI, "routeProgressM" to progress,
            "stepIndex" to nextIdx, "nextStreet" to next.street,
            "distanceToTurnM" to toTurn, "offRoute" to off,
        )

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
            else -> "${max(0, (m / 10).roundToInt() * 10)} m"
        }
    }
}
