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

    fun render(ctx: Context, styleUrl: String, lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double,
               w: Int, h: Int, timeoutMs: Long = 1500): MapSnapshot? {
        val now = SystemClock.elapsedRealtime()
        if (now < pauseUntil || inFlight) return null
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
                if (s == null || curStyle != styleUrl || curW != w || curH != h) {
                    try { s?.cancel() } catch (_: Throwable) {}
                    val opts = MapSnapshotter.Options(w, h)
                        .withStyleBuilder(Style.Builder().fromUri(styleUrl))
                        .withCameraPosition(cam)
                        .withPixelRatio(1.0f)
                        .withLogo(false)
                    s = MapSnapshotter(ctx.applicationContext, opts)
                    snapshotter = s; curStyle = styleUrl; curW = w; curH = h
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
            if (okCount % 40 == 1) TripLog.ev("mapSnap", "ok" to okCount, "fail" to failCount, "avgMs" to (totalMs / okCount), "lastMs" to ms)
        } else {
            failCount++; failStreak++
            if (failCount <= 3 || failCount % 20 == 0) TripLog.ev("mapSnap", "fail" to failCount, "err" to (errMsg ?: if (done) "null" else "timeout"), "ms" to ms)
            // Liên tục lỗi → nghỉ 45 s dùng vector, khỏi làm chậm khung
            if (failStreak >= 4) { pauseUntil = SystemClock.elapsedRealtime() + 45_000L; failStreak = 0 }
        }
        return res
    }

    fun release() {
        main.post { try { snapshotter?.cancel() } catch (_: Throwable) {}; snapshotter = null; curStyle = null }
    }
}
