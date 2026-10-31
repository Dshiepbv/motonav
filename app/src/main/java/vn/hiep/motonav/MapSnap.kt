package vn.hiep.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Vẽ bản đồ Goong ra ẢNH ngoài nền (MapSnapshotter của MapLibre) cho màn TFT — không cần MapView hiện
 * trên màn hình nên tắt màn vẫn chạy. Dùng MỘT snapshotter cho cả chuyến (chỉ đổi camera giữa các khung)
 * để không tính thêm lượt "tải bản đồ". Lỗi/quá hạn → trả null, TftLink tự dùng lại bản vẽ vector.
 */
object MapSnap {
    private val main = Handler(Looper.getMainLooper())
    private var snapshotter: MapSnapshotter? = null
    private var curStyle: String? = null
    private var curW = 0
    private var curH = 0
    @Volatile private var inFlight = false
    private var failStreak = 0
    private var pauseUntil = 0L
    private var okCount = 0
    private var failCount = 0
    private var totalMs = 0L
    @Volatile private var disabledUntil = 0L
    private var crashHooked = false
    private var flagSet = false

    /** Tắt chế độ ảnh bản đồ một lúc (lỗi nặng) — TftLink dùng lại vector. */
    fun disable(reason: String, forMs: Long = 600_000L) {
        disabledUntil = SystemClock.elapsedRealtime() + forMs
        try { TripLog.ev("mapSnap", "disabled" to reason) } catch (_: Throwable) {}
    }

    fun render(ctx: Context, styleUrl: String, dark: Boolean, lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double,
               w: Int, h: Int, timeoutMs: Long = 1500): MapSnapshot? {
        return try { renderInner(ctx, styleUrl, dark, lat, lon, bearing, zoom, tilt, w, h, timeoutMs) } catch (t: Throwable) {
            disable(t.javaClass.simpleName + ": " + (t.message ?: ""))
            null
        }
    }

    private fun renderInner(ctx: Context, styleUrl: String, dark: Boolean, lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double,
                            w: Int, h: Int, timeoutMs: Long): MapSnapshot? {
        val now = SystemClock.elapsedRealtime()
        if (now < disabledUntil || now < pauseUntil || inFlight) return null
        if (!crashHooked) {
            crashHooked = true
            // Ghi lại lỗi làm sập app vào nhật ký (flush ngay) để biết nguyên nhân nếu có
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, ex ->
                try { TripLog.ev("note", "crash" to (th.name + ": " + android.util.Log.getStackTraceString(ex).take(1500))) } catch (_: Throwable) {}
                prev?.uncaughtException(th, ex)
            }
            TripLog.ev("mapSnap", "start" to 1)
        }
        // Cầu chì: đặt cờ "đang thử" (ghi đồng bộ) trước khi chạm vào MapLibre ngoài nền; xoá khi có ảnh đầu tiên.
        // Nếu app chết ngay (lỗi hệ thống không bắt được), lần khởi động sau DriveService thấy cờ còn đó → tự tắt chế độ này.
        if (okCount == 0 && !flagSet) {
            flagSet = true
            try { ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit().putBoolean("tftMapSnapTrying", true).commit() } catch (_: Throwable) {}
            TripLog.ev("note", "mapSnapStep" to "first-render")
        }
        inFlight = true
        val t0 = now
        val latch = CountDownLatch(1)
        var out: MapSnapshot? = null
        var errMsg: String? = null
        main.post {
            try {
                MapLibre.getInstance(ctx.applicationContext)
                val cam = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(bearing).tilt(tilt).build()
                var s = snapshotter
                val styleKey = styleUrl + (if (tweakedMap[dark] != null) "#t" else "") + (if (dark) "d" else "l")
                if (s == null || curStyle != styleKey || curW != w || curH != h) {
                    try { s?.cancel() } catch (_: Throwable) {}
                    val sj = tweakedStyle(styleUrl, dark)
                    val opts = MapSnapshotter.Options(w, h)
                        .withStyleBuilder(if (sj != null) Style.Builder().fromJson(sj) else Style.Builder().fromUri(styleUrl))
                        .withCameraPosition(cam)
                        .withPixelRatio(1.0f)
                        .withLogo(false)
                    s = MapSnapshotter(ctx.applicationContext, opts)
                    snapshotter = s; curStyle = styleKey; curW = w; curH = h
                } else {
                    s.setCameraPosition(cam)
                }
                s.start({ snap -> out = snap; latch.countDown() }, { err -> errMsg = err; latch.countDown() })
            } catch (e: Throwable) {
                errMsg = e.message ?: e.javaClass.simpleName
                latch.countDown()
            }
        }
        val done = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        val res = if (done) out else null
        if (!done) {
            // Quá hạn: để lần gọi sau không chồng lên bản đang chạy dở, nhưng không chặn lâu
            main.postDelayed({ inFlight = false }, 1500)
        } else {
            inFlight = false
        }
        val ms = SystemClock.elapsedRealtime() - t0
        if (res != null) {
            failStreak = 0; okCount++; totalMs += ms
            if (okCount == 1) try { ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit().putBoolean("tftMapSnapTrying", false).apply() } catch (_: Throwable) {}
            if (okCount % 40 == 1) TripLog.ev("mapSnap", "ok" to okCount, "fail" to failCount, "avgMs" to (totalMs / okCount), "lastMs" to ms)
        } else {
            failCount++; failStreak++
            if (failCount <= 3 || failCount % 20 == 0) TripLog.ev("mapSnap", "fail" to failCount, "err" to (errMsg ?: if (done) "null" else "timeout"), "ms" to ms)
            // Liên tục lỗi → nghỉ 45 s dùng vector, khỏi làm chậm khung
            if (failStreak >= 4) { pauseUntil = SystemClock.elapsedRealtime() + 45_000L; failStreak = 0 }
        }
        return res
    }


    // ---- Chỉnh màu style OpenFreeMap cho màn TFT: đường xám, mảng xanh lá/xanh dương rõ, chữ đen viền trắng ----
    private val tweakedMap = java.util.concurrent.ConcurrentHashMap<Boolean, String>()
    @Volatile private var tweakTryAt = 0L
    @Volatile private var tweaking = false
    private val styleHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    private fun tweakedStyle(url: String, dark: Boolean): String? {
        if (!url.contains("openfreemap")) return null
        tweakedMap[dark]?.let { return it }
        val nowT = SystemClock.elapsedRealtime()
        if (!tweaking && nowT - tweakTryAt > 60_000L) {
            tweaking = true; tweakTryAt = nowT
            Thread({
                try {
                    val body = styleHttp.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "MotoNav").build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.string() else null
                    }
                    if (body != null) {
                        val j = org.json.JSONObject(body)
                        val layers = j.getJSONArray("layers")
                        var changed = 0
                        for (i in 0 until layers.length()) {
                            val l = layers.getJSONObject(i)
                            val id = l.optString("id").lowercase()
                            val type = l.optString("type")
                            val paint = l.optJSONObject("paint") ?: org.json.JSONObject().also { l.put("paint", it) }
                            when (type) {
                                "background" -> { paint.put("background-color", if (dark) "#171A21" else "#F3F3F1"); changed++ }
                                "fill" -> when {
                                    id.contains("water") -> { paint.put("fill-color", "#5BAEF0"); paint.put("fill-opacity", 1); changed++ }
                                    id.contains("park") || id.contains("grass") || id.contains("wood") || id.contains("forest") ||
                                        id.contains("green") || id.contains("garden") || id.contains("pitch") || id.contains("cemetery") ||
                                        id.contains("landcover") || id.contains("meadow") -> { paint.put("fill-color", "#8FD19B"); paint.put("fill-opacity", 1); changed++ }
                                    id.contains("building") -> { paint.put("fill-color", if (dark) "#2C313B" else "#CFCBC4"); paint.put("fill-opacity", 1); changed++ }
                                }
                                "line" -> when {
                                    id.contains("waterway") || id.contains("river") || id.contains("canal") -> { paint.put("line-color", "#5BAEF0"); changed++ }
                                    id.contains("rail") || id.contains("boundary") || id.contains("aeroway") -> {}
                                    id.contains("casing") -> { paint.put("line-color", if (dark) "#262A32" else "#6E747C"); changed++ }
                                    id.contains("highway") || id.contains("road") || id.contains("bridge") || id.contains("tunnel") ||
                                        id.contains("path") || id.contains("street") -> { paint.put("line-color", if (dark) "#6A717C" else "#A3A9B1"); changed++ }
                                }
                                "symbol" -> {
                                    paint.put("text-color", "#FFFFFF"); paint.put("text-halo-color", "#000000"); paint.put("text-halo-width", 2.2); paint.put("text-halo-blur", 0)
                                    (l.optJSONObject("layout") ?: org.json.JSONObject().also { l.put("layout", it) }).put("text-size", 14)
                                    changed++
                                }
                            }
                        }
                        TripLog.ev("mapSnap", "styleTweaked" to changed, "layers" to layers.length())
                        tweakedMap[dark] = j.toString()
                    }
                } catch (_: Throwable) {
                } finally { tweaking = false }
            }, "mapsnap-style").apply { isDaemon = true; start() }
        }
        return null
    }

    fun release() {
        main.post { try { snapshotter?.cancel() } catch (_: Throwable) {}; snapshotter = null; curStyle = null }
    }
}
