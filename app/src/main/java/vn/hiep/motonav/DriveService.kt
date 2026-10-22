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

        private const val WAY_RADIUS = 1200      // tải đường trong bán kính 1,2 km
        private const val CAM_RADIUS = 4000      // tải camera trong bán kính 4 km
        private const val REFETCH_AT = 600.0     // đi xa tâm vùng đã tải 600 m thì tải lại
        private const val MATCH_MAX = 45.0       // điểm khớp đường tối đa (mét + phạt hướng)

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
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var snapshot: OsmSnapshot? = null
    @Volatile private var fetching = false
    @Volatile private var lastFetchFail = 0L

    private var lastFixTime = 0L
    private var prevLoc: Location? = null
    private var heading = -1.0

    private var currentWayId: Long? = null
    private var noMatchCount = 0
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
        voice = Voice(this)
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
        } catch (e: SecurityException) {
            Live.status = "Chưa có quyền vị trí"
            Live.changed()
        } catch (e: Exception) {
            Live.status = "Không bật được GPS: ${e.message}"
            Live.changed()
        }
        handler.postDelayed(watchdog, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        executor.shutdownNow()
        voice.shutdown()
        Live.serviceRunning = false
        Live.gpsOk = false
        Live.speedKmh = -1
        Live.status = "Đã dừng"
        Live.changed()
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
            .setContentTitle("MotoNav đang chạy")
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

    override fun onLocationChanged(loc: Location) {
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
        if (loc.hasBearing() && speedMps > 1.5) {
            heading = loc.bearing.toDouble()
        } else if (prev != null && Geo.distance(prev.latitude, prev.longitude, lat, lon) > 6) {
            heading = Geo.bearing(prev.latitude, prev.longitude, lat, lon)
        }
        if (prev == null || Geo.distance(prev.latitude, prev.longitude, lat, lon) > 3 || speedMps > 1.5) {
            prevLoc = loc
        }
        val movingHeading: Double? = if (speedMps > 2.0 && heading >= 0) heading else null

        Live.lat = lat
        Live.lon = lon
        Live.heading = if (speedMps > 1.5) heading else -1.0
        Live.speedKmh = speedKmh
        Live.gpsOk = true

        ensureData(lat, lon)
        val snap = snapshot

        // Tốc độ giới hạn
        if (snap != null) {
            val m = match(snap, lat, lon, movingHeading)
            if (m != null) {
                noMatchCount = 0
                currentWayId = m.way.id
                Live.street = m.way.name ?: roadTypeName(m.way.highway)
                val res = SpeedRules.resolve(m.way, m.forward, prefs, snap.urbanArea)
                val key = res?.let { "${it.kmh}/${it.estimated}" } ?: "none"
                if (key == pendingKey) pendingCount++ else {
                    pendingKey = key
                    pendingCount = 1
                }
                if (pendingCount >= 2) applyLimit(res)
            } else {
                noMatchCount++
                if (noMatchCount >= 4) {
                    currentWayId = null
                    Live.street = null
                    applyLimit(null)
                }
            }
            if (!fetching) {
                Live.status = snap.areaName ?: ""
            }
        }

        checkOverspeed(speedKmh)
        checkCameras(snap, lat, lon, speedMps, movingHeading)
        Live.changed()
    }

    private fun applyLimit(res: LimitResult?) {
        shownLimit = res
        Live.limit = res?.kmh
        Live.limitEstimated = res?.estimated ?: false
        if (res != null && res.kmh != announcedLimit) {
            announcedLimit = res.kmh
            if (prefs.voice && prefs.announceLimit) {
                voice.say("Giới hạn ${res.kmh}")
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
                if (prefs.voice) {
                    voice.beep()
                    voice.say("Quá tốc độ, giới hạn $lim", urgent = true)
                }
            }
        } else {
            overSince = 0L
            Live.overspeed = false
        }
    }

    private fun checkCameras(snap: OsmSnapshot?, lat: Double, lon: Double, speedMps: Double, hdg: Double?) {
        val cams = ArrayList<Camera>()
        if (snap != null) cams.addAll(snap.cameras)
        cams.addAll(CameraStore.all(this))

        var nearest: Camera? = null
        var nearestDist = Double.MAX_VALUE
        val alertDist = min(600.0, max(300.0, speedMps * 25))

        for (c in cams) {
            val d = Geo.distance(lat, lon, c.lat, c.lon)
            if (d > 800) {
                alertedCams.remove(c.id)
                continue
            }
            if (hdg == null || d > alertDist) continue
            val brg = Geo.bearing(lat, lon, c.lat, c.lon)
            val cone = if (d > 150) 25.0 else 45.0
            val ahead = d < 35 || Geo.angleDiff(hdg, brg) < cone
            val dirOk = c.heading < 0 || Geo.angleDiff(c.heading, hdg) < 60
            if (ahead && dirOk && d < nearestDist) {
                nearest = c
                nearestDist = d
            }
        }

        if (nearest == null) {
            Live.camText = null
            return
        }
        val label = CamKind.label(nearest.kind)
        Live.camText = label.replaceFirstChar { it.uppercase() }
        Live.camDistance = nearestDist.roundToInt()
        if (alertedCams.add(nearest.id) && prefs.voice) {
            val rounded = max(50, ((nearestDist / 50).roundToInt()) * 50)
            voice.beep()
            voice.say("Phía trước $rounded mét có $label")
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
                snapshot = Overpass.fetch(lat, lon, WAY_RADIUS, CAM_RADIUS)
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

    private class Match(val way: Way, val forward: Boolean, val score: Double)

    private fun match(snap: OsmSnapshot, lat: Double, lon: Double, hdg: Double?): Match? {
        val proj = LocalProjection(lat, lon)
        val pad = 0.0006
        var best: Match? = null
        var keep: Match? = null
        val prevId = currentWayId

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
                if (dist > MATCH_MAX) continue

                val segBearing = Geo.norm(Math.toDegrees(atan2(dx, dy)))
                var forward = true
                var penalty = 0.0
                if (hdg != null) {
                    val d = Geo.angleDiff(hdg, segBearing)
                    forward = d <= 90
                    penalty = when (w.oneway) {
                        1 -> if (d <= 90) d * 0.4 else 60.0
                        -1 -> if (d > 90) (180 - d) * 0.4 else 60.0
                        else -> min(d, 180 - d) * 0.4
                    }
                }
                // Ưu tiên đường lớn một chút khi khoảng cách tương đương
                val classBonus = when (w.highway) {
                    "service", "living_street" -> 4.0
                    "residential", "unclassified", "road" -> 2.0
                    else -> 0.0
                }
                val score = dist + penalty + classBonus
                if (wBest == null || score < wBest.score) wBest = Match(w, forward, score)
            }
            if (wBest == null) continue
            if (best == null || wBest.score < best.score) best = wBest
            if (w.id == prevId) keep = wBest
        }

        if (best == null || best.score > MATCH_MAX) return null
        if (keep != null && keep.score <= best.score + 8 && keep.score < MATCH_MAX) return keep
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
