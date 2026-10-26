package vn.hiep.motonav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dịch vụ chạy nền: đọc GPS mỗi giây, tìm đoạn đường đang đi trên OpenStreetMap,
 * xác định tốc độ giới hạn, cảnh báo vượt tốc và camera.
 */
class DriveService : Service(), LocationListener {

    companion object {
        private const val CHANNEL = "drive"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "vn.hiep.motonav.STOP"
        const val ACTION_NAV_START = "vn.hiep.motonav.NAV_START"
        const val ACTION_NAV_STOP = "vn.hiep.motonav.NAV_STOP"
        const val ACTION_NAV_ALTS = "vn.hiep.motonav.NAV_ALTS"
        const val ACTION_NAV_CHOOSE = "vn.hiep.motonav.NAV_CHOOSE"
        const val ACTION_NAV_CHOICES_DONE = "vn.hiep.motonav.NAV_CHOICES_DONE"

        fun send(ctx: Context, action: String, idx: Int = -1) {
            ctx.startService(Intent(ctx, DriveService::class.java).setAction(action).putExtra("idx", idx))
        }

        fun startNav(ctx: Context, lat: Double, lon: Double, name: String?) {
            ctx.startForegroundService(
                Intent(ctx, DriveService::class.java).setAction(ACTION_NAV_START)
                    .putExtra("lat", lat).putExtra("lon", lon).putExtra("name", name),
            )
        }

        fun stopNav(ctx: Context) {
            ctx.startService(Intent(ctx, DriveService::class.java).setAction(ACTION_NAV_STOP))
        }

        private const val WAY_RADIUS = 1800      // tải đường trong bán kính 1,8 km (khớp mốc neo maxAhead mặc định trên TFT, để nhánh đường nối dài ra đủ xa)
        private const val CAM_RADIUS = 4000      // tải camera trong bán kính 4 km
        private const val REFETCH_AT = 600.0     // đi xa tâm vùng đã tải 600 m thì tải lại
        private const val MATCH_MAX = 45.0       // điểm khớp đường tối đa (mét + phạt hướng)
        private const val CAM_ALERT_START = 500.0 // bắt đầu báo camera từ 500 m
        private val CAM_COUNTDOWN = intArrayOf(300, 100)

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DriveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DriveService::class.java))
        }
    }

    private lateinit var lm: LocationManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var prefs: Prefs
    private lateinit var voice: Voice
    private lateinit var overlay: Overlay
    private lateinit var nav: NavEngine
    private val overlayListener: () -> Unit = { overlay.update() }
    private val executor = Executors.newSingleThreadExecutor()
    private val navExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var snapshot: OsmSnapshot? = null
    @Volatile private var fetching = false
    @Volatile private var lastFetchFail = 0L

    private var lastFixTime = 0L
    private var prevLoc: Location? = null
    private var heading = -1.0

    private var currentWayId: Long? = null
    private var currentWay: Way? = null
    private var currentWayForward: Boolean = true
    private var warnedMotorway: Long? = null
    private var warnedNoMoto: Long? = null
    private var noMotoCount = 0
    private val matcher = MapMatcher()
    private var noMatchCount = 0
    private var logMatchKey = ""
    private var matchMax = MATCH_MAX
    private var pendingKey: String? = null
    private var pendingCount = 0
    private var shownLimit: LimitResult? = null
    private var announcedLimit: Int? = null

    private var overSince = 0L
    private var lastOverAlert = 0L
    private val alertedCams = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        try { TripLog.start(this) } catch (_: Exception) {}
        voice = Voice(this)
        overlay = Overlay(this, prefs)
        nav = NavEngine(this, prefs, navExecutor) { text, speech, urgent -> alert(text, speech, urgent = urgent) }
        // Bấm nút "Dừng dẫn đường" trên màn TFT xe (cmd 49) → dừng dẫn đường thật trong app.
        TftLink.onStopRequested = { handler.post { nav.finish() } }
        Live.addListener(overlayListener)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        thread = HandlerThread("drive").also { it.start() }
        handler = Handler(thread.looper)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Theo dõi khi chạy xe", NotificationManager.IMPORTANCE_LOW),
        )
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        Live.serviceRunning = true
        Live.status = "Đang chờ GPS…"
        Live.changed()

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.looper)
            // Dự phòng khi GPS chập chờn (Pixel…): vị trí "fused" chỉ dùng khi GPS im lặng > 2,5 giây
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1000L, 0f, this, thread.looper)
                    }
                } catch (_: Exception) {}
            }
        } catch (e: SecurityException) {
            Live.status = "Chưa có quyền vị trí"
            Live.changed()
        } catch (e: Exception) {
            Live.status = "Không bật được GPS: ${e.message}"
            Live.changed()
        }
        handler.postDelayed(watchdog, 3000)
        handler.postDelayed(fgWatch, 1500)
        handler.postDelayed(tftWatch, 1000)
        // Camera theo thông báo CSGT: tìm toạ độ ngã tư trên OSM (chạy nền, chỉ khi còn điểm chưa có)
        if (OfficialCameras.needsResolve(this)) {
            Thread {
                try {
                    OfficialCameras.resolveAll(this) { i, n ->
                        Live.officialProgress = "Đang định vị camera CSGT $i/$n…"
                    }
                } catch (_: Exception) {
                }
                Live.officialProgress = null
                lastIcons = 0L
                Live.changed()
            }.apply { isDaemon = true; start() }
        }
        // App bị tắt giữa chuyến → dẫn tiếp tới điểm cũ
        prefs.pendingTrip()?.let { (la, lo, nm) ->
            if (!Live.ownNav) handler.post { nav.start(la, lo, nm) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                nav.finish()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NAV_START -> {
                val lat = intent.getDoubleExtra("lat", Double.NaN)
                val lon = intent.getDoubleExtra("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    handler.post { nav.start(lat, lon, intent.getStringExtra("name")) }
                }
            }
            ACTION_NAV_STOP -> handler.post { TripLog.ev("navStopBtn"); nav.finish() }
            ACTION_NAV_ALTS -> handler.post { nav.requestAlternatives() }
            ACTION_NAV_CHOOSE -> {
                val i = intent.getIntExtra("idx", 0)
                handler.post { nav.choose(i) }
            }
            ACTION_NAV_CHOICES_DONE -> handler.post { nav.closeChoices() }
        }
        return START_STICKY
    }

    /** Vuốt tắt app khỏi danh sách đa nhiệm → dừng luôn dịch vụ và thông báo. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Live.userStopped = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        executor.shutdownNow()
        navExecutor.shutdownNow()
        nav.stop()
        voice.shutdown()
        TftLink.stop()
        Live.removeListener(overlayListener)
        overlay.hide()
        Live.serviceRunning = false
        Live.gpsOk = false
        Live.speedKmh = -1
        Live.status = "Đã dừng"
        Live.changed()
        TripLog.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("NMAX Navi đang chạy")
            .setContentText("Theo dõi tốc độ giới hạn và camera")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dừng", stop,
                ).build(),
            )
            .build()
    }

    /** Theo dõi Google Maps có đang ở màn hình không để tự bật/tắt ô nổi. */
    private val fgWatch = object : Runnable {
        override fun run() {
            val v: Boolean? = if (prefs.overlay && ForegroundApp.hasPermission(this@DriveService)) {
                val pkg = ForegroundApp.current(this@DriveService)
                if (pkg == null) null else pkg == ForegroundApp.MAPS
            } else null
            if (v != Live.mapsForeground) {
                Live.mapsForeground = v
                Live.changed()
            }
            handler.postDelayed(this, if (prefs.batterySaver) 3000 else 1200)
        }
    }

    /** Đẩy dẫn đường (kênh chữ nhẹ) lên màn TFT của xe qua Bluetooth, nếu đã bật trong Cài đặt. */
    private var tftLinkWasOn = false
    private val tftWatch = object : Runnable {
        override fun run() {
            val on = prefs.tftLink
            if (on != tftLinkWasOn) {
                tftLinkWasOn = on
                if (on) TftLink.start(this@DriveService, prefs.tftDevice) else TftLink.stop()
            }
            if (on) {
                val navOn = Live.ownNav && Live.navActive && !Live.offRoute
                TftLink.update(
                    active = navOn,
                    sign = Live.maneuverSign,
                    roundabout = Live.maneuverSign == 6,
                    distanceM = if (Live.navMeters >= 0) Live.navMeters else 0,
                    road = Live.navInstruction ?: Live.maneuverLabel ?: "",
                    etaMinutes = (Live.etaMs / 60000L).toInt(),
                )
                TftLink.updateSketch(
                    active = navOn,
                    route = Live.route,
                    lat = Live.lat,
                    lon = Live.lon,
                    headingDeg = Live.heading,
                    maneuverLat = Live.maneuverLat,
                    maneuverLon = Live.maneuverLon,
                    sign = Live.maneuverSign,
                    roundabout = Live.maneuverSign == 6,
                    ways = Live.osm?.ways,
                    speedKmh = Live.speedKmh,
                    limitKmh = Live.limit,
                    distanceM = if (Live.navMeters >= 0) Live.navMeters else -1,
                    nextStreet = Live.maneuverLabel,
                    remainingM = Live.remainingM,
                    etaMs = Live.etaMs,
                    currentStreet = Live.street,
                    wxCat = Live.wxDestCat,
                    wxLabel = Live.wxDestLabel,
                    wxNight = Live.wxDestNight,
                )
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val ok = SystemClock.elapsedRealtime() - lastFixTime < 5000
            if (ok != Live.gpsOk) {
                Live.gpsOk = ok
                if (!ok) {
                    Live.speedKmh = -1
                    Live.status = "Mất tín hiệu GPS…"
                }
                Live.changed()
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ---------- Xử lý mỗi lần có vị trí mới ----------

    private var lastGpsFix = 0L
    private var lastGoodFix = 0L
    private val startedAt = SystemClock.elapsedRealtime()

    override fun onLocationChanged(loc: Location) {
        TripLog.fix(loc)
        val now = SystemClock.elapsedRealtime()
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsFix = now
        else if (now - lastGpsFix < 2500) return
        // Bỏ điểm định vị quá kém (mới bật GPS, fused đoán theo wifi/trạm) nếu vừa có điểm tốt
        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        if (acc > 40f && now - lastGoodFix < 15000) return
        if (acc <= 40f) lastGoodFix = now
        // Mới mở: chờ GPS ổn định (≤ 40 m) tối đa 6 giây trước khi nhận điểm thô
        if (acc > 40f && lastGoodFix == 0L && now - startedAt < 6000) return
        try {
            process(loc)
        } catch (e: Exception) {
            Live.status = "Lỗi: ${e.message}"
            Live.changed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        Live.status = "GPS đang tắt, hãy bật Vị trí"
        Live.changed()
    }

    private fun process(loc: Location) {
        lastFixTime = SystemClock.elapsedRealtime()
        val lat = loc.latitude
        val lon = loc.longitude

        // Tốc độ
        val prev = prevLoc
        var speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else -1.0
        if (speedMps < 0 && prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0.2) speedMps = Geo.distance(prev.latitude, prev.longitude, lat, lon) / dt
        }
        if (speedMps < 0) speedMps = 0.0
        val speedKmh = (speedMps * 3.6).roundToInt()

        // Hướng đi
        // Ngưỡng dịch chuyển tối thiểu để tin là đang đi thật (không phải GPS trôi khi đứng yên):
        // theo độ chính xác GPS báo về, thay vì mốc cố định 6m — mốc cố định đó ở tần suất đọc vị
        // trí ~1 lần/giây nghĩa là phải chạy ~20km/h mới đủ xa để bắt hướng xoay bản đồ (phản hồi
        // thực tế: vừa nhấc xe đi đã cần xoay đúng hướng ngay, không phải chờ tới 20km/h).
        val accM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 10.0
        val moveThresh = accM.coerceIn(2.5, 6.0)
        if (loc.hasBearing() && speedMps > 1.0) {
            heading = loc.bearing.toDouble()
        } else if (prev != null && Geo.distance(prev.latitude, prev.longitude, lat, lon) > moveThresh) {
            heading = Geo.bearing(prev.latitude, prev.longitude, lat, lon)
        }
        if (prev == null || Geo.distance(prev.latitude, prev.longitude, lat, lon) > moveThresh || speedMps > 1.0) {
            prevLoc = loc
        }
        val movingHeading: Double? = if (speedMps > 2.0 && heading >= 0) heading else null

        Live.lat = lat
        Live.lon = lon
        Live.heading = if (speedMps > 1.0) heading else -1.0
        Live.speedKmh = speedKmh
        Live.gpsOk = true

        ensureData(lat, lon)
        val snap = snapshot

        // Tốc độ giới hạn
        if (snap != null) {
            matchMax = if (loc.hasAccuracy()) (loc.accuracy * 1.4).coerceIn(MATCH_MAX, 70.0) else MATCH_MAX
            val old = match(snap, lat, lon, movingHeading)
            // Khớp đường kiểu mới (HMM): luôn chạy song song để ghi nhật ký so sánh;
            // bật trong Cài đặt thì dùng kết quả HMM thay cho cách cũ.
            val hm = try {
                matcher.update(snap, lat, lon, movingHeading, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 15.0)
            } catch (_: Exception) { null }
            if (hm?.way?.id != old?.way?.id) {
                TripLog.ev("hmm", "way" to hm?.way?.id, "name" to hm?.way?.name, "old" to old?.way?.id, "oldName" to old?.way?.name,
                    "dist" to hm?.dist, "used" to prefs.hmmMatch)
            }
            val m = if (prefs.hmmMatch && hm != null) Match(hm.way, hm.forward, hm.dist, hm.bearing) else if (prefs.hmmMatch) null else old
            if (m != null) {
                noMatchCount = 0
                currentWayId = m.way.id
                currentWay = m.way
                currentWayForward = m.forward
                Live.street = m.way.name ?: roadTypeName(m.way.highway)
                // Không dẫn đường: xoay bản đồ theo hướng đoạn đường đang đi
                if (!Live.ownNav && m.bearing >= 0) Live.roadBearing = m.bearing
                checkUrban(m.way, snap.urbanArea)
                if (m.way.highway == "motorway" && m.way.id != warnedMotorway && speedMps > 3) {
                    warnedMotorway = m.way.id
                    alert("CẢNH BÁO: đây là đường cao tốc, xe máy không được đi!", "Cảnh báo, xe máy không được đi đường cao tốc", urgent = true, showMs = 10000)
                }
                // Hai làn song song cách nhau dải phân cách (ví dụ làn ô tô/xe máy) có thể bị nhầm lẫn
                // qua lại do GPS nhiễu — chỉ báo khi khớp CHẮC CHẮN (sát đường, nhiều lần liên tiếp)
                val noMotoHere = m.way.tags["motorcycle"] == "no" || m.way.tags["motor_vehicle"] == "no"
                if (noMotoHere && m.score < 10.0) noMotoCount++ else noMotoCount = 0
                if (noMotoCount >= 4 && m.way.id != warnedNoMoto && speedMps > 3) {
                    warnedNoMoto = m.way.id
                    alert("CẢNH BÁO: đang đi vào làn/đường cấm xe máy!", "Cảnh báo, đang đi vào đường cấm xe máy", urgent = true, showMs = 10000)
                }
                val res = SpeedRules.resolve(m.way, m.forward, prefs, snap.urbanArea)
                val key = res?.let { "${it.kmh}/${it.estimated}" } ?: "none"
                if (key == pendingKey) pendingCount++ else {
                    pendingKey = key
                    pendingCount = 1
                }
                // Đoạn đường không xác định được tốc độ: giữ biển cũ, chỉ xoá nếu kéo dài ~6 giây
                if (pendingCount >= (if (res == null) 6 else 2)) applyLimit(res)
                val lk = "${m.way.id}/$key"
                if (lk != logMatchKey) {
                    logMatchKey = lk
                    TripLog.ev("match", "way" to m.way.id, "name" to m.way.name, "hw" to m.way.highway,
                        "lim" to res?.kmh, "est" to res?.estimated, "shown" to shownLimit?.kmh, "score" to m.score, "mm" to matchMax.toDouble())
                }
            } else {
                noMatchCount++
                if (noMatchCount == 1 || noMatchCount == 12) TripLog.ev("nomatch", "n" to noMatchCount, "mm" to matchMax.toDouble())
                // Mất khớp đường: giữ biển tốc độ cũ ~12 giây (GPS chập chờn) rồi mới xoá
                if (noMatchCount >= 12) {
                    currentWayId = null
                    currentWay = null
                    Live.street = null
                    applyLimit(null)
                }
            }
            if (!fetching) {
                Live.status = snap.areaName ?: ""
            }
        }

        checkOverspeed(speedKmh)
        nav.onLocation(lat, lon, movingHeading, speedMps)
        updateJunctions(lat, lon)
        updateLimitAhead()
        updateAlertIcons(lat, lon)
        updateSigns(lat, lon, movingHeading)
        updateWeather()
        if (!Live.ownNav) checkTurn()
        checkCameras(snap, lat, lon, speedMps, movingHeading)
        Live.changed()
    }

    /**
     * Mọi cảnh báo đi qua đây: luôn hiện chữ trên màn hình (ô nổi + màn hình chính),
     * luôn có tiếng bíp, và đọc thành tiếng nếu bật Giọng nói.
     */
    private fun alert(text: String, speech: String = text, beep: Boolean = true, urgent: Boolean = false, showMs: Long = 6000) {
        TripLog.ev("alert", "text" to text, "urgent" to urgent)
        Live.alertText = text
        Live.alertUntil = SystemClock.elapsedRealtime() + showMs
        Live.alertUrgent = urgent
        if (beep) voice.beep()
        if (prefs.voice) voice.say(speech, urgent)
        Live.changed()
    }

    private var lastTurnKey: String? = null
    private var lastTurnNear: String? = null

    /** Tiếng báo + chữ khi tới gần chỗ rẽ (Google Maps vẫn đọc chỉ dẫn của nó). */
    private fun checkTurn() {
        val m = Live.navMeters
        val instr = Live.navInstruction
        if (!Live.navActive || instr.isNullOrBlank() || m < 0) return
        if (m <= 300 && lastTurnKey != instr) {
            lastTurnKey = instr
            Live.alertText = "Sắp rẽ: $instr"
            Live.alertUntil = SystemClock.elapsedRealtime() + 5000
            Live.alertUrgent = false
            voice.beep()
        }
        if (m <= 60 && lastTurnNear != instr) {
            lastTurnNear = instr
            Live.alertText = "RẼ NGAY: $instr"
            Live.alertUntil = SystemClock.elapsedRealtime() + 5000
            Live.alertUrgent = true
            voice.beep()
        }
    }

    // ---------- Tốc độ giới hạn sắp đổi phía trước ----------

    private var lastAhead = 0L
    private var announcedAhead: String? = null

    private fun updateLimitAhead() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAhead < 2000) return
        lastAhead = now
        val r = Live.route
        val snap = snapshot
        val cur = shownLimit?.kmh
        if (!Live.ownNav || r == null || snap == null || cur == null) {
            clearAhead()
            return
        }
        val n = r.lats.size
        val start = Live.routeProgress
        var prevId: Long? = currentWayId
        var cand: Int? = null
        var candEst = false
        var candDist = -1.0
        var candLat = 0.0
        var candLon = 0.0
        var confirm = 0
        var d = 20.0
        var i = Live.routeSeg
        while (d <= 320) {
            val target = start + d
            while (i < n - 2 && r.cum[i + 1] < target) i++
            if (r.cum[i + 1] < target) break
            val segLen = r.cum[i + 1] - r.cum[i]
            val t = if (segLen < 0.01) 0.0 else ((target - r.cum[i]) / segLen).coerceIn(0.0, 1.0)
            val la = r.lats[i] + t * (r.lats[i + 1] - r.lats[i])
            val lo = r.lons[i] + t * (r.lons[i + 1] - r.lons[i])
            val brg = Geo.bearing(r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1])
            val m = match(snap, la, lo, brg, prevId)
            if (m != null) {
                prevId = m.way.id
                val res = SpeedRules.resolve(m.way, m.forward, prefs, snap.urbanArea)
                val v = res?.kmh
                if (v != null && v != cur) {
                    if (cand == v) {
                        confirm++
                    } else {
                        // Neo biển vào đúng đầu mút đoạn đường (điểm cố định) thay vì điểm nội suy
                        // theo quãng đường đã đi — tránh biển bị "kéo/nhoè" khi tiến độ tuyến thay đổi
                        val dEnd = Geo.distance(la, lo, m.way.lats.last(), m.way.lons.last())
                        val dStart = Geo.distance(la, lo, m.way.lats.first(), m.way.lons.first())
                        val anchored = when {
                            dEnd <= dStart && dEnd < 60 -> m.way.lats.last() to m.way.lons.last()
                            dStart < 60 -> m.way.lats.first() to m.way.lons.first()
                            else -> la to lo
                        }
                        cand = v; candEst = res.estimated; candDist = d; candLat = anchored.first; candLon = anchored.second; confirm = 1
                    }
                    if (confirm >= 2) break
                } else if (cand != null) {
                    cand = null; confirm = 0
                }
            }
            d += 20.0
        }
        if (cand == null || confirm < 2) {
            clearAhead()
            return
        }
        Live.nextLimit = cand
        Live.nextLimitDist = candDist.roundToInt()
        Live.nextLimitLat = candLat
        Live.nextLimitLon = candLon
        Live.nextLimitEst = candEst
        // Báo một lần khi còn ≤ 200 m
        val key = "$cand@${(candLat * 2000).roundToInt()},${(candLon * 2000).roundToInt()}"
        if (candDist <= 200 && announcedAhead != key) {
            announcedAhead = key
            val r50 = max(20, (candDist / 10).roundToInt() * 10)
            alert("Sắp đổi giới hạn: $cand km/h · còn $r50 m", "Còn $r50 mét, giới hạn $cand", beep = false, showMs = 5000)
        }
    }

    private fun clearAhead() {
        if (Live.nextLimit != null) {
            Live.nextLimit = null
            Live.nextLimitDist = -1
        }
    }

    // ---------- Biểu tượng cảnh báo trên bản đồ ----------

    private var lastIcons = 0L
    private var iconsSnap: OsmSnapshot? = null
    private var iconsCount = -1

    private fun updateAlertIcons(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        val snap = snapshot
        val user = CameraStore.all(this) + OfficialCameras.cameras(this)
        if (snap === iconsSnap && user.size == iconsCount && now - lastIcons < 30000) return
        lastIcons = now
        iconsSnap = snap
        iconsCount = user.size
        val all = ArrayList<Camera>()
        if (snap != null) all.addAll(snap.cameras)
        all.addAll(user)
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (c in all) {
            if (Geo.distance(lat, lon, c.lat, c.lon) > 4000) continue
            if (CamKind.isPlace(c.kind)) continue // đã bỏ cảnh báo trường học, chợ
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","properties":{"k":""").append(c.kind)
                .append("""},"geometry":{"type":"Point","coordinates":[""")
                .append(c.lon).append(',').append(c.lat).append("]}}")
        }
        sb.append("]}")
        Live.alertsJson = sb.toString()
        Live.alertsVersion++
    }

    // ---------- Thời tiết dọc tuyến (tiết kiệm pin: 1 lần gọi/tuyến, làm mới 20 phút) ----------

    @Volatile private var wxBusy = false
    private var wxRouteVer = -1
    private var wxDest = ""
    private var lastWx = 0L
    private var wxAnnounced: String? = null

    private fun updateWeather() {
        val r = Live.route
        if (!prefs.weather || prefs.batterySaver || !Live.ownNav || r == null) {
            if (Live.wxJson != null) {
                Live.wxJson = null
                Live.wxSummary = null
                Live.wxDestCat = null
                Live.wxDestLabel = null
                Live.wxVersion++
            }
            wxRouteVer = -1
            return
        }
        if (wxBusy) return
        val now = SystemClock.elapsedRealtime()
        val dest = "${Live.destLat},${Live.destLon}"
        val newTrip = dest != wxDest
        val routeChanged = Live.routeVersion != wxRouteVer
        val due = newTrip || (routeChanged && now - lastWx > 120_000) || now - lastWx > 20 * 60_000
        if (!due) return
        wxBusy = true
        wxRouteVer = Live.routeVersion
        wxDest = dest
        lastWx = now
        val progress = Live.routeProgress
        Thread {
            try {
                val (marks, summary) = RouteWeather.fetch(r, progress)
                Live.wxJson = RouteWeather.toGeoJson(marks)
                Live.wxSummary = summary
                // Mốc cuối cùng = gần điểm đến nhất — dùng riêng cho icon thời tiết trên màn TFT.
                val destMark = marks.lastOrNull()
                Live.wxDestCat = destMark?.cat
                Live.wxDestLabel = destMark?.label
                Live.wxDestNight = destMark?.let { RouteWeather.isNightAt(it.minutes) } ?: false
                Live.wxVersion++
                // Báo một lần khi phía trước có mưa
                if (summary != null && summary.startsWith("Phía trước") && summary != wxAnnounced) {
                    wxAnnounced = summary
                    handler.post { alert("🌧 $summary", summary.substringBefore(" (").replace("km", "ki lô mét"), beep = false, showMs = 7000) }
                }
                Live.changed()
            } catch (_: Exception) {
                lastWx = SystemClock.elapsedRealtime() - 18 * 60_000 // lỗi mạng: thử lại sau ~2 phút
            } finally {
                wxBusy = false
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------- Biển báo phía trước ----------

    private val announcedSigns = HashSet<String>()
    private val activeSigns = HashSet<String>()

    private var signHdg = -1.0

    private fun updateSigns(lat: Double, lon: Double, hdgIn: Double?) {
        val snap = snapshot
        if (hdgIn != null) signHdg = hdgIn
        // Dừng xe vẫn giữ biển theo hướng vừa đi
        val hdg: Double? = hdgIn ?: (if (signHdg >= 0) signHdg else null)
        val out = ArrayList<Triple<Int, Int, Int>>()
        val way = currentWay
        // Trước đây có thêm biển cấm dừng đỗ/một chiều "áp dụng cho cả đoạn đường đang đi" (hiện
        // chữ "Đoạn này" thay vì số mét) — bỏ: không có khoảng cách cụ thể nên không rõ còn áp dụng
        // tới đâu, gây rối hơn là giúp ích.
        if (snap != null && hdg != null) {
            val found = ArrayList<Pair<RoadSign, Double>>()
            // Chỉ xét đường đang đi + các đoạn nối tiếp phía trước đúng chiều (không lấy nhánh/đường cắt ngang)
            val corridor = if (way != null) corridorWays(snap, way, currentWayForward, hdg) else emptyList()
            val corridorIds = corridor.map { it.id }.toHashSet()
            for (sg in snap.signs) {
                if (sg.type == SignType.SPEED) continue // giới hạn tốc độ đã có ô riêng
                val d = Geo.distance(lat, lon, sg.lat, sg.lon)
                if (d > 300) {
                    if (d > 500) { announcedSigns.remove(sg.id); activeSigns.remove(sg.id) }
                    continue
                }
                if (sg.heading >= 0 && Geo.angleDiff(sg.heading, hdg) > 60) continue
                if (corridor.isEmpty()) { activeSigns.remove(sg.id); continue }
                // Đường THỰC SỰ gần biển nhất phải là đường đang đi (phân biệt 2 đường song song,
                // ví dụ làn xe máy/ô tô tách riêng) — không chỉ "đủ gần" tuyến đang đi
                var nearestWay: Way? = null
                var nearestD = Double.MAX_VALUE
                for (w in snap.ways) {
                    val dd = distToWay(w, sg.lat, sg.lon)
                    if (dd < nearestD) { nearestD = dd; nearestWay = w }
                }
                val onCorridorWay = nearestWay != null && nearestWay.id in corridorIds
                // Độ trễ (hysteresis): biển đã hiện thì giữ thêm biên độ để không nhấp nháy khi ở ranh giới
                val thresh = if (sg.id in activeSigns) 32.0 else 20.0
                val onCorridor = onCorridorWay && nearestD < thresh
                // Đi qua biển rồi (phía sau xe hơn 10 m theo hướng đang chạy) → tắt biển, không tính nữa
                val brgToSign = Geo.bearing(lat, lon, sg.lat, sg.lon)
                val ahead = d * kotlin.math.cos(Math.toRadians(Geo.angleDiff(brgToSign, hdg)))
                if (onCorridor && ahead >= -10.0) {
                    found.add(Pair(sg, max(0.0, ahead)))
                    activeSigns.add(sg.id)
                } else {
                    activeSigns.remove(sg.id)
                    if (ahead < -10.0) announcedSigns.remove(sg.id)
                }
            }
            // Đường phía trước: vòng xuyến, cấm xe máy (từ thẻ của đường trên OSM) — chỉ trong tuyến đang đi
            for (w in corridor) {
                val ra = w.tags["junction"] == "roundabout" || w.tags["junction"] == "circular"
                val noMoto = w.tags["motorcycle"] == "no" || w.tags["motor_vehicle"] == "no"
                if (!ra && !noMoto) continue
                if (w === way) continue
                var bi = -1
                var bd = Double.MAX_VALUE
                for (i in w.lats.indices) {
                    val d = Geo.distance(lat, lon, w.lats[i], w.lons[i])
                    if (d < bd) { bd = d; bi = i }
                }
                if (bi < 0 || bd > 250) continue
                val t = if (noMoto) SignType.NO_MOTO else SignType.ROUNDABOUT
                found.add(Pair(RoadSign("w${w.id}", w.lats[bi], w.lons[bi], t, 0, -1.0), bd))
            }
            found.sortBy { it.second }
            // Vạch người đi bộ ngay cạnh đèn tín hiệu: chỉ giữ biển đèn
            val sig = found.filter { it.first.type == SignType.SIGNALS }
            found.removeAll { f ->
                f.first.type == SignType.PEDESTRIAN && sig.any { Geo.distance(it.first.lat, it.first.lon, f.first.lat, f.first.lon) < 40 }
            }
            // Gộp biển cùng loại đứng sát nhau (< 30 m)
            val uniq = ArrayList<Pair<RoadSign, Double>>()
            for (f in found) {
                if (uniq.none { it.first.type == f.first.type && Geo.distance(it.first.lat, it.first.lon, f.first.lat, f.first.lon) < 30 }) uniq.add(f)
            }
            // Cắm lên bản đồ: tối đa 6 biển gần nhất (không kèm số mét để biểu tượng không nháy)
            val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            uniq.take(6).forEachIndexed { k, (sg, _) ->
                if (k > 0) sb.append(',')
                sb.append("""{"type":"Feature","properties":{"img":"sign-${sg.type}-${sg.value}","t":${sg.type},"v":${sg.value}},"geometry":{"type":"Point","coordinates":[${sg.lon},${sg.lat}]}}""")
            }
            sb.append("]}")
            val js = sb.toString()
            if (js != Live.signsJson) {
                Live.signsJson = js
                Live.signsVersion++
            }
            for ((sg, d) in uniq) {
                if (out.size >= 4) break
                out.add(Triple(sg.type, sg.value, d.roundToInt()))
                if (SignType.important(sg.type) && d <= 120 && announcedSigns.add(sg.id)) {
                    val txt = SignType.label(sg.type, sg.value)
                    alert("$txt · ${d.roundToInt()} m", "Phía trước, $txt", showMs = 5000)
                }
            }
        }
        Live.signsAhead = out
    }

    // ---------- Khu đông dân cư ----------

    private var urbanPending: Boolean? = null
    private var urbanCount = 0

    private fun checkUrban(way: Way, urbanArea: Boolean?) {
        val (u, est) = SpeedRules.urbanOf(way, prefs, urbanArea)
        if (u == urbanPending) urbanCount++ else {
            urbanPending = u
            urbanCount = 1
        }
        if (urbanCount < 4) return
        val prev = Live.urban
        Live.urbanEst = est
        if (prev == u) return
        Live.urban = u
        if (prev != null) {
            val lim = if (prefs.moped) "40" else if (u) "50–60" else "60–70"
            if (u) alert("Vào khu đông dân cư · xe máy tối đa $lim km/h", "Vào khu đông dân cư", beep = false, showMs = 5000)
            else alert("Ra khỏi khu đông dân cư · xe máy tối đa $lim km/h", "Ra khỏi khu đông dân cư", beep = false, showMs = 5000)
        }
    }

    // ---------- Tuyến đường có camera phạt nguội (danh sách công khai) ----------

    private var lastCamStreetAlert: String? = null

    private fun checkCameraStreet(way: Way, lat: Double, lon: Double) {
        val s = CameraStreets.match(way.name, way.tags["ref"], lat, lon)
        Live.camStreet = s
        if (s != null && s != lastCamStreetAlert) {
            lastCamStreetAlert = s
            alert("Tuyến $s có camera phạt nguội – chú ý", "Chú ý, tuyến đường có camera phạt nguội", showMs = 7000)
        }
        if (s == null && way.name != null) lastCamStreetAlert = null
    }

    private var lastJunctions = 0L

    /** Nhãn tên các đường cắt ngang tuyến phía trước (cập nhật mỗi 2,5 giây). */
    private fun updateJunctions(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        val r = Live.route
        val snap = snapshot
        if (!Live.ownNav || r == null || snap == null) return
        if (now - lastJunctions < (if (prefs.batterySaver) 5000 else 2500)) return
        lastJunctions = now
        try {
            val turn = Live.maneuverStreet
            val list = Junctions.compute(r, Live.routeSeg, Live.routeProgress, snap, lat, lon, Live.street, null)
            // So khớp tên đường rẽ của VietMap với tên trên OSM (bỏ chữ "Đường", "Đ.", dấu chấm…)
            val tn = turn?.let { norm(it) }
            val marked = list.map {
                val hit = tn != null && tn.isNotEmpty() && (norm(it.name) == tn || norm(it.name).contains(tn) || tn.contains(norm(it.name)))
                if (hit) it.withTurn(true) else it
            }
            Live.junctionHasTurn = marked.any { it.isTurn }
            // Hiệu ứng chữ khối 3D dựng tại ngã rẽ đã bỏ (gây nháy, không giải quyết được) — không còn tạo mn-text3d nữa.
            // Nhãn chữ phẳng (trắng viền) không dùng nữa — hay nhoè khi bản đồ nghiêng/xoay
            Live.junctionJson = null
            Live.junctionVersion++
        } catch (_: Exception) {
        }
    }

    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("""^(đường|đ\.|duong|phố|pho|hẻm|hem|quốc lộ|ql\.?)\s*"""), "")
        .replace(Regex("""[.\s]+"""), " ").trim()

    private fun applyLimit(res: LimitResult?) {
        shownLimit = res
        Live.limit = res?.kmh
        Live.limitEstimated = res?.estimated ?: false
        if (res != null && res.kmh != announcedLimit) {
            announcedLimit = res.kmh
            if (prefs.announceLimit) {
                val note = if (res.estimated) " (ước tính)" else ""
                alert("Giới hạn ${res.kmh} km/h$note", "Giới hạn ${res.kmh}", showMs = 4000)
            }
        }
        if (res == null) announcedLimit = null
    }

    private fun checkOverspeed(speedKmh: Int) {
        val lim = shownLimit?.kmh
        val now = SystemClock.elapsedRealtime()
        if (lim != null && speedKmh > lim + prefs.tolerance) {
            if (overSince == 0L) overSince = now
            Live.overspeed = true
            if (now - overSince > 1500 && now - lastOverAlert > 12000) {
                lastOverAlert = now
                alert("QUÁ TỐC ĐỘ! Giới hạn $lim km/h", "Quá tốc độ, giới hạn $lim", urgent = true)
            }
        } else {
            overSince = 0L
            Live.overspeed = false
        }
    }

    private class CamAlert(val firstDist: Int) {
        val spoken = HashSet<Int>()
    }

    private val camAlerts = HashMap<String, CamAlert>()
    private var lastCamOverAlert = 0L

    /** Khoảng cách (m) từ một điểm tới đoạn đường w. */
    private fun distToWay(w: Way, lat: Double, lon: Double): Double {
        val proj = LocalProjection(lat, lon)
        var best = Double.MAX_VALUE
        for (i in 0 until w.lats.size - 1) {
            val ax = proj.x(w.lons[i]); val ay = proj.y(w.lats[i])
            val bx = proj.x(w.lons[i + 1]); val by = proj.y(w.lats[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 0.01) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = min(best, sqrt(px * px + py * py))
        }
        return best
    }

    private fun distToWay(w: Way, c: Camera): Double = distToWay(w, c.lat, c.lon)

    /**
     * Danh sách đường nối tiếp phía trước xe (theo đúng chiều di chuyển, kể cả một chiều),
     * dùng để chỉ lấy biển báo nằm trên tuyến đang đi, không lấy biển ở nhánh/đường cắt ngang.
     */
    private fun corridorWays(snap: OsmSnapshot, way: Way, forward: Boolean, hdg: Double, maxWays: Int = 3): List<Way> {
        val out = ArrayList<Way>()
        out.add(way)
        var cur = way
        var curForward = forward
        var guard = 0
        while (out.size < maxWays && guard < 6) {
            guard++
            val ex = if (curForward) cur.lats.last() else cur.lats.first()
            val ey = if (curForward) cur.lons.last() else cur.lons.first()
            var best: Way? = null
            var bestForward = true
            var bestDiff = 40.0
            for (w in snap.ways) {
                if (w === cur || out.contains(w)) continue
                val dStart = Geo.distance(ex, ey, w.lats.first(), w.lons.first())
                val dEnd = Geo.distance(ex, ey, w.lats.last(), w.lons.last())
                val connIdx = if (dStart < 15 && dStart <= dEnd) 0 else if (dEnd < 15) 1 else -1
                if (connIdx < 0) continue
                val nf = connIdx == 0 // nối vào đầu way kế tiếp => đi theo chiều vẽ của nó
                if (w.oneway == 1 && !nf) continue
                if (w.oneway == -1 && nf) continue
                val bIdx = if (nf) min(1, w.lats.size - 1) else max(0, w.lats.size - 2)
                val segBrg = Geo.bearing(ex, ey, w.lats[bIdx], w.lons[bIdx])
                val diff = Geo.angleDiff(hdg, segBrg)
                if (diff < bestDiff) { bestDiff = diff; best = w; bestForward = nf }
            }
            if (best == null) break
            out.add(best)
            cur = best
            curForward = bestForward
        }
        return out
    }

    private fun checkCameras(snap: OsmSnapshot?, lat: Double, lon: Double, speedMps: Double, hdg: Double?) {
        val cams = ArrayList<Camera>()
        if (snap != null) cams.addAll(snap.cameras)
        cams.addAll(CameraStore.all(this))
        cams.addAll(OfficialCameras.cameras(this))
        val way = currentWay

        var nearest: Camera? = null
        var nearestDist = Double.MAX_VALUE

        for (c in cams) {
            val d = Geo.distance(lat, lon, c.lat, c.lon)
            if (d > 800) {
                camAlerts.remove(c.id)
                continue
            }
            val place = CamKind.isPlace(c.kind)
            if (place) continue // đã bỏ cảnh báo trường học, chợ
            val startAt = if (place || c.kind == CamKind.RAIL) 250.0 else CAM_ALERT_START
            if (hdg == null || d > startAt) continue
            if (c.heading >= 0 && Geo.angleDiff(c.heading, hdg) >= 60) continue
            val diff = Geo.angleDiff(hdg, Geo.bearing(lat, lon, c.lat, c.lon))
            // Trường học/chợ là khu đất: chấp nhận cách đường tới 90 m
            val onRoad = way != null && distToWay(way, c) < (if (place) 90.0 else 40.0)
            if (place && !onRoad) continue
            val ahead = when {
                d < 30 -> true
                onRoad -> diff < 70
                d > 200 -> diff < 12
                else -> diff < 25
            }
            if (ahead && d < nearestDist) {
                nearest = c
                nearestDist = d
            }
        }

        if (nearest == null) {
            Live.camText = null
            Live.camKind = -1
            return
        }
        val dInt = nearestDist.roundToInt()
        Live.camText = CamKind.title(nearest.kind)
        Live.camKind = nearest.kind
        Live.camDistance = dInt

        val st = camAlerts[nearest.id]
        if (st == null) {
            camAlerts[nearest.id] = CamAlert(dInt)
            val r = max(50, (dInt / 50.0).roundToInt() * 50)
            val lim = shownLimit?.kmh
            val limTxt = if (nearest.kind == CamKind.SPEED && lim != null) ", giới hạn $lim" else ""
            alert(
                "Phía trước $r m: ${CamKind.label(nearest.kind)}$limTxt",
                "Phía trước $r mét có ${CamKind.label(nearest.kind)}$limTxt",
                showMs = 8000,
            )
        } else {
            for (t in CAM_COUNTDOWN) {
                if (dInt <= t && st.firstDist > t + 40 && st.spoken.add(t)) {
                    alert("Còn $t m: ${CamKind.label(nearest.kind)}", "Còn $t mét", beep = false, showMs = 8000)
                    break
                }
            }
        }

        // Đang vượt tốc mà sắp tới camera bắn tốc độ
        val now = SystemClock.elapsedRealtime()
        if (nearest.kind == CamKind.SPEED && Live.overspeed && dInt < 350 && now - lastCamOverAlert > 8000) {
            lastCamOverAlert = now
            alert("GIẢM TỐC ĐỘ! Sắp tới camera bắn tốc độ", "Giảm tốc độ", urgent = true)
        }
    }

    // ---------- Tải dữ liệu OSM ----------

    private fun ensureData(lat: Double, lon: Double) {
        val snap = snapshot
        val need = snap == null || Geo.distance(snap.centerLat, snap.centerLon, lat, lon) > REFETCH_AT
        if (!need || fetching) return
        if (SystemClock.elapsedRealtime() - lastFetchFail < 15000) return
        fetching = true
        if (snap == null) {
            Live.status = "Đang tải bản đồ tốc độ…"
            Live.changed()
        }
        executor.execute {
            try {
                // Có gói ngoại tuyến phủ vị trí này → đọc từ gói (nhanh, không cần mạng); không thì Overpass
                val usePack = try { OfflinePack.open(this) && OfflinePack.covers(lat, lon) } catch (_: Exception) { false }
                snapshot = if (usePack) {
                    try {
                        OfflinePack.snapshot(lat, lon, WAY_RADIUS, CAM_RADIUS).also {
                            TripLog.ev("osm", "src" to "pack", "ways" to it.ways.size, "date" to OfflinePack.date)
                        }
                    } catch (e: Exception) {
                        TripLog.ev("osm", "src" to "pack-fail", "err" to e.message)
                        Overpass.fetch(lat, lon, WAY_RADIUS, CAM_RADIUS)
                    }
                } else {
                    Overpass.fetch(lat, lon, WAY_RADIUS, CAM_RADIUS).also { TripLog.ev("osm", "src" to "overpass", "ways" to it.ways.size) }
                }
                Live.osm = snapshot
                lastFetchFail = 0
            } catch (e: Exception) {
                lastFetchFail = SystemClock.elapsedRealtime()
                Live.status = "Chưa tải được dữ liệu OSM, đang thử lại…"
                Live.changed()
            } finally {
                fetching = false
            }
        }
    }

    // ---------- Khớp vị trí với đoạn đường ----------

    private class Match(val way: Way, val forward: Boolean, val score: Double, val bearing: Double = -1.0)

    private fun match(snap: OsmSnapshot, lat: Double, lon: Double, hdg: Double?, prevIdIn: Long? = currentWayId): Match? {
        val proj = LocalProjection(lat, lon)
        val pad = 0.0006
        var best: Match? = null
        var keep: Match? = null
        val prevId = prevIdIn

        for (w in snap.ways) {
            if (lat < w.minLat - pad || lat > w.maxLat + pad || lon < w.minLon - pad || lon > w.maxLon + pad) continue
            var wBest: Match? = null
            for (i in 0 until w.lats.size - 1) {
                val ax = proj.x(w.lons[i])
                val ay = proj.y(w.lats[i])
                val bx = proj.x(w.lons[i + 1])
                val by = proj.y(w.lats[i + 1])
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 0.01) continue
                var t = -(ax * dx + ay * dy) / len2
                t = t.coerceIn(0.0, 1.0)
                val px = ax + t * dx
                val py = ay + t * dy
                val dist = sqrt(px * px + py * py)
                if (dist > matchMax) continue

                val segBearing = Geo.norm(Math.toDegrees(atan2(dx, dy)))
                var forward = true
                var penalty = 0.0
                if (hdg != null) {
                    val d = Geo.angleDiff(hdg, segBearing)
                    forward = d <= 90
                    penalty = when (w.oneway) {
                        1 -> if (d <= 90) d * 0.3 else 60.0
                        -1 -> if (d > 90) (180 - d) * 0.3 else 60.0
                        else -> min(d, 180 - d) * 0.3
                    }
                }
                // Ưu tiên đường lớn một chút khi khoảng cách tương đương
                val classBonus = when (w.highway) {
                    "service", "living_street" -> 4.0
                    "residential", "unclassified", "road" -> 2.0
                    else -> 0.0
                }
                val score = dist + penalty + classBonus
                if (wBest == null || score < wBest.score) {
                    // Hướng chạy dọc theo đoạn đường (theo chiều xe đang đi)
                    val travel = if (forward) segBearing else Geo.norm(segBearing + 180)
                    wBest = Match(w, forward, score, if (hdg != null) travel else -1.0)
                }
            }
            if (wBest == null) continue
            if (best == null || wBest.score < best.score) best = wBest
            if (w.id == prevId) keep = wBest
        }

        if (best == null || best.score > matchMax) return null
        if (keep != null && keep.score <= best.score + 8 && keep.score < matchMax) return keep
        return best
    }

    private fun roadTypeName(hw: String): String = when (hw) {
        "motorway" -> "Đường cao tốc"
        "trunk" -> "Quốc lộ"
        "primary" -> "Đường chính"
        "secondary", "tertiary" -> "Đường liên khu vực"
        "residential", "living_street" -> "Đường trong khu dân cư"
        "service" -> "Hẻm / đường nhánh"
        else -> "Đường chưa có tên"
    }
}
