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

    /** Chiều cao thừa phía dưới ảnh: dòng ghi nguồn bản đồ của MapLibre nằm ở đây, rơi ngoài khung màn xe. */
    const val EXTRA_H = 36

    /** Hệ số phóng bề rộng đường so với mét thật (xe máy cần thấy rõ cả hẻm nhỏ trên màn bé). */
    const val WIDTH_SCALE = 3.0

    fun render(ctx: Context, styleUrl: String, dark: Boolean, lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double,
               w: Int, h: Int, timeoutMs: Long = 1500, plain: Boolean = false): MapSnapshot? {
        return try { renderInner(ctx, styleUrl, dark, lat, lon, bearing, zoom, tilt, w, h + EXTRA_H, timeoutMs, plain) } catch (t: Throwable) {
            disable(t.javaClass.simpleName + ": " + (t.message ?: ""))
            null
        }
    }

    private fun renderInner(ctx: Context, styleUrl: String, dark: Boolean, lat: Double, lon: Double, bearing: Double, zoom: Double, tilt: Double,
                            w: Int, h: Int, timeoutMs: Long, plain: Boolean): MapSnapshot? {
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
                if (appCtxForCache == null) {
                    appCtxForCache = ctx.applicationContext
                    // build-365: giữ nhiều mảnh bản đồ đã tải (300 MB) để đường quen vẫn có nền khi mạng/DNS lỗi.
                    try {
                        org.maplibre.android.offline.OfflineManager.getInstance(ctx.applicationContext)
                            .setMaximumAmbientCacheSize(300L * 1024 * 1024, object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                                override fun onSuccess() {}
                                override fun onError(message: String) { try { TripLog.ev("mapSnap", "cacheSizeErr" to message) } catch (_: Throwable) {} }
                            })
                    } catch (_: Throwable) {}
                }
                val cam = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(bearing).tilt(tilt).build()
                var s = snapshotter
                val tk = tKey(dark, plain)
                val styleKey = styleUrl + (if (tweakedMap[tk] != null) "#t" else "") + (if (dark) "d" else "l") + (if (plain) "p" else "")
                if (s == null || curStyle != styleKey || curW != w || curH != h) {
                    try { s?.cancel() } catch (_: Throwable) {}
                    val sj = tweakedStyle(styleUrl, dark, plain)
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
    private val tweakedMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    // build-377: nền trơn = CÙNG style bản đồ (đúng tỉ lệ, bề rộng, zoom như nền map) nhưng bỏ mảng nước/cây/khu đất.
    private fun tKey(dark: Boolean, plain: Boolean) = (if (dark) "d" else "l") + (if (plain) "p" else "m")
    @Volatile private var tweakTryAt = 0L
    @Volatile private var tweaking = false
    private val styleHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    // build-365: lưu sẵn file style (Sếp: "lưu sẵn file để không phụ thuộc mạng"). Log 6/11 17:42–20:17: điện thoại
    // không tra được tên miền tiles.openfreemap.org (DNS) → không có style → TFT tụt về bản vẽ vector (mất zoom/đường x3).
    // Giờ: có file thì dựng style ngay từ file; tải mạng chỉ để làm mới file (thành công 1 lần/lần chạy app là đủ).
    @Volatile private var styleFromNet = false
    @Volatile var appCtxForCache: Context? = null
    private fun styleFile(): java.io.File? = appCtxForCache?.let { java.io.File(it.filesDir, "mapsnap_style_positron.json") }

    private fun tweakedStyle(url: String, dark: Boolean, plain: Boolean): String? {
        if (!url.contains("openfreemap")) return null
        if (tweakedMap[tKey(dark, plain)] == null) {
            try {
                val f = styleFile()
                if (f != null && f.exists() && f.length() > 1000) {
                    buildTweaked(f.readText())
                    TripLog.ev("mapSnap", "styleFromFile" to f.length())
                }
            } catch (t: Throwable) { try { TripLog.ev("mapSnap", "styleFileErr" to (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {} }
        }
        val nowT = SystemClock.elapsedRealtime()
        if (!styleFromNet && !tweaking && nowT - tweakTryAt > 60_000L) {
            tweaking = true; tweakTryAt = nowT
            Thread({
                try {
                    val body = styleHttp.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "MotoNav").build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.string() else null
                    }
                    if (body != null) {
                        buildTweaked(body)
                        styleFromNet = true
                        try { styleFile()?.let { f -> val tmp = java.io.File(f.path + ".tmp"); tmp.writeText(body); tmp.renameTo(f) } } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    try { TripLog.ev("mapSnap", "styleNetErr" to (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {}
                } finally { tweaking = false }
            }, "mapsnap-style").apply { isDaemon = true; start() }
        }
        return tweakedMap[tKey(dark, plain)]
    }

    private fun buildTweaked(body: String) {
                    run {
                        // build-278: dựng sẵn CẢ HAI nền sáng/tối từ một lần tải — trước đây bật sang nền kia phải chờ ≥60 s,
                        // trong lúc đó dùng style gốc (đường trắng trên nền sáng → không thấy đường).
                        for (dkv in booleanArrayOf(false, true)) for (plain in booleanArrayOf(false, true)) {
                        val dark = dkv
                        val j = org.json.JSONObject(body)
                        val allLayers = j.getJSONArray("layers")
                        // Bỏ toàn bộ lớp chữ/biểu tượng của nền: tên đường do app tự vẽ lên khung đặc (dễ đọc hơn trên TFT).
                        val kept = org.json.JSONArray()
                        for (li in 0 until allLayers.length()) { val ly = allLayers.getJSONObject(li); val lid = ly.optString("id").lowercase(); val lt = ly.optString("type")
                            // build-387 (Sếp): giữ toà nhà (tô cam) + tên sông/hồ; tên đường vẫn do app tự vẽ.
                            val keepSym = !plain && (lid.startsWith("water_name") || lid.startsWith("waterway_line_label"))
                            if (lt == "symbol" && !keepSym) continue
                            // build-392 (Sếp): đường vẽ lại y như tối 8/11 (bản 386, đủ mọi cấp đường); chỉ thêm sông + cây, bỏ toà nhà.
                            if (lid.contains("building")) continue
                            if (lt == "line" && (lid.contains("rail") || lid.contains("boundary") || lid.contains("aeroway"))) continue
                            if ((lt == "fill" || lt == "fill-extrusion") && !(lid.contains("water") || lid.contains("park") || lid.contains("grass") ||
                                    lid.contains("wood") || lid.contains("forest") || lid.contains("green") || lid.contains("garden") ||
                                    lid.contains("pitch") || lid.contains("cemetery") || lid.contains("landcover") || lid.contains("meadow"))) continue
                            if (lt == "raster" || lt == "hillshade") continue
                            if (plain && (lt == "fill" || lt == "fill-extrusion" || lt == "raster" || lt == "hillshade" ||
                                    (lt == "line" && (lid.contains("waterway") || lid.contains("river") || lid.contains("canal") ||
                                        lid.contains("rail") || lid.contains("boundary") || lid.contains("aeroway"))))) continue
                            kept.put(ly) } // build-302: bỏ lớp toà nhà (Sếp: không cần)
                        // build-398 (Sếp: "không thấy tí màu nào"): positron chỉ tô rừng (wood) + vườn quốc gia, còn công viên/cỏ
                        // trong phố (OpenMapTiles landcover class grass, subclass park…) không có lớp nào → tự thêm lớp tô xanh.
                        if (!plain) try {
                            val greens = org.json.JSONArray()
                            greens.put(org.json.JSONObject("""{"id":"motonav_green_landcover","type":"fill","source":"openmaptiles","source-layer":"landcover",
                                "filter":["match",["get","class"],["grass","wood","wetland","farmland"],true,false],
                                "paint":{"fill-color":"#8FD19B","fill-opacity":1}}"""))
                            greens.put(org.json.JSONObject("""{"id":"motonav_green_landuse","type":"fill","source":"openmaptiles","source-layer":"landuse",
                                "filter":["match",["get","class"],["park","cemetery","pitch","playground","garden","stadium","zoo","theme_park"],true,false],
                                "paint":{"fill-color":"#8FD19B","fill-opacity":1}}"""))
                            val withG = org.json.JSONArray()
                            var inserted = false
                            for (li in 0 until kept.length()) {
                                val ly = kept.getJSONObject(li)
                                // chèn ngay trước lớp nước đầu tiên (nước vẽ đè lên cỏ), sau nền
                                if (!inserted && ly.optString("type") != "background") { for (g in 0 until greens.length()) withG.put(greens.get(g)); inserted = true }
                                withG.put(ly)
                            }
                            if (!inserted) for (g in 0 until greens.length()) withG.put(greens.get(g))
                            j.put("layers", withG)
                        } catch (t: Throwable) { TripLog.ev("mapSnap", "greenErr" to (t.message ?: t.javaClass.simpleName)); j.put("layers", kept) }
                        else j.put("layers", kept)
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
                                    id.contains("building") -> { paint.put("fill-color", if (dark) "#B9733A" else "#F5B57A"); paint.put("fill-outline-color", if (dark) "#8A5228" else "#D98E4A"); paint.put("fill-opacity", 1); changed++ }
                                }
                                "line" -> when {
                                    id.contains("waterway") || id.contains("river") || id.contains("canal") -> { paint.put("line-color", "#5BAEF0"); changed++ }
                                    id.contains("rail") || id.contains("boundary") || id.contains("aeroway") -> {}
                                    id.contains("casing") -> { paint.put("line-color", if (dark) "#3A3F49" else "#6E747C"); paint.put("line-width", roadWidthExpr(id, true)); changed++ }
                                    id.contains("highway") || id.contains("road") || id.contains("bridge") || id.contains("tunnel") ||
                                        id.contains("path") || id.contains("street") -> { paint.put("line-color", if (dark) "#A4ABB6" else "#A3A9B1"); paint.put("line-width", roadWidthExpr(id, false)); changed++ }
                                }
                                "symbol" -> {
                                    paint.put("text-color", if (dark) "#000000" else "#FFFFFF"); paint.put("text-halo-color", if (dark) "#FFFFFF" else "#000000"); paint.put("text-halo-width", 2.2); paint.put("text-halo-blur", 0)
                                    (l.optJSONObject("layout") ?: org.json.JSONObject().also { l.put("layout", it) }).put("text-size", 14)
                                    changed++
                                }
                            }
                        }
                        // build-387 (Sếp): tên công viên, hồ, toà nhà/điểm lớn (Vincom…) — chỉ chữ, không icon.
                        if (!plain) try {
                            fun nameExpr() = org.json.JSONArray("[\"coalesce\",[\"get\",\"name:vi\"],[\"get\",\"name\"]]")
                            fun lbl(id: String, srcLayer: String, minZ: Double, filter: String, color: String, size: Int, font: String) =
                                org.json.JSONObject().apply {
                                    put("id", id); put("type", "symbol"); put("source", "openmaptiles"); put("source-layer", srcLayer)
                                    put("minzoom", minZ); put("filter", org.json.JSONArray(filter))
                                    put("layout", org.json.JSONObject().apply {
                                        put("text-field", nameExpr()); put("text-font", org.json.JSONArray().put(font))
                                        put("text-size", size); put("text-max-width", 8); put("text-padding", 4)
                                    })
                                    put("paint", org.json.JSONObject().apply {
                                        put("text-color", color); put("text-halo-color", if (dark) "#000000" else "#FFFFFF"); put("text-halo-width", 2.0); put("text-halo-blur", 0)
                                    })
                                }
                            val pt = "[\"match\",[\"geometry-type\"],[\"MultiPoint\",\"Point\"],true,false]"
                            layers.put(lbl("motonav_park_label", "park", 13.0, pt, if (dark) "#9BE3A8" else "#1E6B2E", 13, "Noto Sans Bold"))
                            layers.put(lbl("motonav_poi_major", "poi", 13.5, "[\"all\",$pt,[\"<\",[\"get\",\"rank\"],7]]", if (dark) "#F7C08A" else "#8A3F00", 13, "Noto Sans Bold"))
                            layers.put(lbl("motonav_poi_mid", "poi", 15.0, "[\"all\",$pt,[\">=\",[\"get\",\"rank\"],7],[\"<\",[\"get\",\"rank\"],20]]", if (dark) "#E8E8E8" else "#333333", 12, "Noto Sans Regular"))
                            changed += 3
                        } catch (t: Throwable) { TripLog.ev("mapSnap", "labelErr" to (t.message ?: t.javaClass.simpleName)) }
                        TripLog.ev("mapSnap", "styleTweaked" to changed, "layers" to layers.length())
                        try {
                            val ids = StringBuilder()
                            for (li in 0 until layers.length()) { val ly = layers.getJSONObject(li); if (ly.optString("type") == "line") ids.append(ly.optString("id")).append(',') }
                            TripLog.ev("mapSnap", "lineLayers" to ids.toString().take(900))
                        } catch (_: Throwable) {}
                        tweakedMap[tKey(dark, plain)] = j.toString()
                        }
                    }
    }


    /**
     * Độ rộng đường = rộng THẬT (mét) theo cấp đường, đổi sang pixel theo mức zoom bằng nội suy mũ cơ số 2
     * (pixel = mét × 2^zoom / (156543 × cos vĩ độ)) — đại lộ vẽ đúng bề rộng, không còn nét mảnh. Vĩ độ TP.HCM ≈ 10.9°.
     */
    private fun roadWidthExpr(id: String, casing: Boolean): org.json.JSONArray {
        val meters = when {
            id.contains("motorway") -> 12.0
            id.contains("trunk") -> 11.0
            id.contains("primary") -> 10.0
            id.contains("secondary") -> 8.0
            id.contains("major") -> 9.0
            id.contains("tertiary") -> 6.5
            id.contains("minor") -> 5.5
            id.contains("service") || id.contains("track") -> 3.5
            id.contains("path") || id.contains("pedestrian") || id.contains("footway") -> 2.0
            else -> 6.0
        }
        val k = 156543.03 * Math.cos(Math.toRadians(10.9))
        fun px(z: Int) = Math.max(if (casing) 5.0 else 3.0, WIDTH_SCALE * meters * Math.pow(2.0, z.toDouble()) / k + (if (casing) 2.0 else 0.0))
        // ["zoom"] chỉ được dùng ở biểu thức "interpolate" cấp cao nhất (không bọc trong "max") → đặt mức tối thiểu ngay trong từng mốc.
        val interp = org.json.JSONArray().put("interpolate").put(org.json.JSONArray().put("linear")).put(org.json.JSONArray().put("zoom"))
        for (z in 12..20) interp.put(z).put(px(z))
        return interp
    }

    fun release() {
        main.post { try { snapshotter?.cancel() } catch (_: Throwable) {}; snapshotter = null; curStyle = null }
    }
}
