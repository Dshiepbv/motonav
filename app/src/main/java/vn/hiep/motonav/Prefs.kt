package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)

    var moped: Boolean
        get() = sp.getBoolean("moped", false)
        set(v) = sp.edit().putBoolean("moped", v).apply()

    var areaMode: Int
        get() = sp.getInt("area", AREA_AUTO)
        set(v) = sp.edit().putInt("area", v).apply()

    var estimate: Boolean
        get() = sp.getBoolean("estimate", true)
        set(v) = sp.edit().putBoolean("estimate", v).apply()

    var tolerance: Int
        get() = sp.getInt("tol", 3)
        set(v) = sp.edit().putInt("tol", v).apply()

    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    var announceLimit: Boolean
        get() = sp.getBoolean("announceLimit", true)
        set(v) = sp.edit().putBoolean("announceLimit", v).apply()

    var overlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var overlayScale: Float
        get() = sp.getFloat("overlayScale", 1f)
        set(v) = sp.edit().putFloat("overlayScale", v).apply()

    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    /** Client Token Mapillary (MLY|…) để tải biển tốc độ. */
    var mapillaryToken: String
        get() = sp.getString("mlyToken", "") ?: ""
        set(v) = sp.edit().putString("mlyToken", v.trim()).apply()

    var vietmapKey: String
        get() = sp.getString("vmkey", "") ?: ""
        set(v) = sp.edit().putString("vmkey", v.trim()).apply()

    /** Đang "ghi vùng tốc độ cố định" theo đoạn đường thực tế? -1 = không ghi, khác = mức km/h đang gán. */
    var recordZoneKmh: Int
        get() = sp.getInt("recordZoneKmh", -1)
        set(v) = sp.edit().putInt("recordZoneKmh", v).apply()

    /** Mã của "Tilemap consumer" trên VietMap (để hiện bản đồ); trống thì dùng mã API. */
    var vietmapTileKey: String
        get() = sp.getString("vmtile", "") ?: ""
        set(v) = sp.edit().putString("vmtile", v.trim()).apply()

    /** Mã GitHub chỉ-đọc để tải bản cập nhật từ kho riêng tư. */
    var githubToken: String
        get() = sp.getString("ghtoken", "") ?: ""
        set(v) = sp.edit().putString("ghtoken", v.trim()).apply()

    /** Tiết kiệm pin: giảm khung hình, tắt 3D, la bàn, thời tiết… chỉ giữ dẫn đường. */
    var batterySaver: Boolean
        get() = sp.getBoolean("batterySaver", false)
        set(v) = sp.edit().putBoolean("batterySaver", v).apply()

    /** Khớp đường kiểu mới theo chuỗi vị trí (HMM). Tắt = cách cũ (HMM vẫn chạy ngầm để so sánh). */
    var hmmMatch: Boolean
        get() = sp.getBoolean("hmmMatch", false)
        set(v) = sp.edit().putBoolean("hmmMatch", v).apply()

    /** Dự đoán vị trí giữa 2 lần GPS để mũi tên chạy liên tục. */
    var predict: Boolean
        get() = sp.getBoolean("predict", true)
        set(v) = sp.edit().putBoolean("predict", v).apply()

    /** Màn TFT dùng ảnh bản đồ Goong vẽ ngoài nền (như bản đồ điện thoại) thay cho nét vector; lỗi thì tự về vector. */
    /** Nguồn ảnh bản đồ trên màn xe khi tftMapSnap bật: false = Goong (tính tiền), true = OpenFreeMap (miễn phí, từ OSM). */
    var tftMapOsm: Boolean
        get() = sp.getBoolean("tftMapOsm", true)
        set(v) = sp.edit().putBoolean("tftMapOsm", v).apply()
    /** Hệ số hiệu chỉnh giờ đến (giờ thật / giờ Goong), học qua các chuyến; chưa có chuyến nào thì 0.75. */
    var etaK: Double
        get() = sp.getFloat("etaK", 0.75f).toDouble()
        set(v) = sp.edit().putFloat("etaK", v.toFloat()).apply()
    var etaN: Int
        get() = sp.getInt("etaN", 0)
        set(v) = sp.edit().putInt("etaN", v).apply()
    /** Nền màn xe: 0 sáng, 1 tối, 2 tự động theo giờ. */
    var tftTheme: Int
        get() = sp.getInt("tftTheme", 2)
        set(v) = sp.edit().putInt("tftTheme", v).apply()
    /** Thử nghiệm đứng hình: 0 tắt, 1 gửi ảnh tĩnh đơn giản thay bản đồ (so tỉ lệ đứng với ảnh bản đồ). */
    var tftDiag: Int
        get() = sp.getInt("tftDiag", 0)
        set(v) = sp.edit().putInt("tftDiag", v).apply()
    /** build-385: mốc gửi/renew ảnh TFT (ms), 100–1200, mặc định 600. */
    var tftStepMs: Int
        get() = sp.getInt("tftStepMs", 600)
        set(v) = sp.edit().putInt("tftStepMs", v).apply()
    /** build-389: cách gửi kiểu NmaxConnect (gửi ngay sau ACK, ghi cả khung một lần). */
    var tftNmaxStyle: Boolean
        get() = sp.getBoolean("tftNmaxStyle", true)
        set(v) = sp.edit().putBoolean("tftNmaxStyle", v).apply()
    /** build-394: nghe gói BLE của xe (thử nghiệm) — mặc định TẮT (bản 393 bật sẵn thì màn báo lỗi kết nối). */
    var bleWatch: Boolean
        get() = sp.getBoolean("bleWatch", false)
        set(v) = sp.edit().putBoolean("bleWatch", v).apply()
    /** build-386: lưới an toàn (giây) — chờ ACK quá mốc này mới gửi thêm 1 bản ảnh, 2–12, mặc định 9. */
    var tftSafetyS: Int
        get() = sp.getInt("tftSafetyS", 9)
        set(v) = sp.edit().putInt("tftSafetyS", v).apply()
    var tftMapSnap: Boolean
        get() = sp.getBoolean("tftMapSnap", false)
        set(v) = sp.edit().putBoolean("tftMapSnap", v).apply()

    var rotateMap: Boolean
        get() = sp.getBoolean("rotateMap", true)
        set(v) = sp.edit().putBoolean("rotateMap", v).apply()

    /** Hướng xoay bản đồ: 0 = như Google Maps (chạy: hướng đường, dừng: la bàn), 1 = luôn la bàn, 2 = luôn hướng đường */
    var mapHeading: Int
        get() = sp.getInt("mapHeading2", 0)
        set(v) = sp.edit().putInt("mapHeading2", v).apply()

    var autoZoom: Boolean
        get() = sp.getBoolean("autoZoom", true)
        set(v) = sp.edit().putBoolean("autoZoom", v).apply()

    /** Nền bản đồ: 0 = Tự động (tối từ 18h–6h), 1 = Sáng, 2 = Tối */
    var mapTheme: Int
        get() = sp.getInt("mapTheme", if (sp.getBoolean("nightMap", true)) 0 else 1)
        set(v) = sp.edit().putInt("mapTheme", v).apply()

    var text3d: Boolean
        get() = sp.getBoolean("text3d", true)
        set(v) = sp.edit().putBoolean("text3d", v).apply()

    var weather: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) = sp.edit().putBoolean("weather", v).apply()

    var traffic: Boolean
        get() = sp.getBoolean("traffic", true)
        set(v) = sp.edit().putBoolean("traffic", v).apply()

    /** Né đường cao tốc khi tìm đường (tham số "avoid=motorway" của VietMap/GraphHopper). */
    var avoidHighway: Boolean
        get() = sp.getBoolean("avoidHighway", false)
        set(v) = sp.edit().putBoolean("avoidHighway", v).apply()

    /** KHÔNG còn quyết định nguồn bản đồ nữa — giữ lại field cũ để không vỡ dữ liệu Prefs đã lưu
     * trên máy người dùng. Theo yêu cầu "ưu tiên Goong hiện map": NavActivity.loadStyle() giờ tự
     * dùng Goong ngay khi [goongTileKey] không rỗng, không cần cờ này nữa; rỗng thì dùng OpenFreeMap. */
    var mapSource: Int
        get() = sp.getInt("mapSource", 0)
        set(v) = sp.edit().putInt("mapSource", v).apply()

    /** Link kiểu bản đồ VietMap do người dùng dán (tuỳ chọn). */
    var vietmapStyleUrl: String
        get() = sp.getString("vmstyle", "") ?: ""
        set(v) = sp.edit().putString("vmstyle", v.trim()).apply()

    /** Mã API REST của Goong (Directions/Geocoding/Places) — dùng để ĐỊNH TUYẾN thay VietMap khi
     * không rỗng (xem GoongMap.kt, NavEngine.findRoutes). Theo yêu cầu "bỏ hẳn VietMap nếu Goong
     * ok": có key này là dùng THẲNG Goong, KHÔNG còn âm thầm rơi về VietMap khi Goong lỗi nữa. */
    var goongKey: String
        get() = sp.getString("goongKey", "") ?: ""
        set(v) = sp.edit().putString("goongKey", v.trim()).apply()

    /** Mã "Map Tiles Key" riêng của Goong (hiện bản đồ nền) — khác mã REST ở trên. */
    var goongTileKey: String
        get() = sp.getString("goongTileKey", "") ?: ""
        set(v) = sp.edit().putString("goongTileKey", v.trim()).apply()

    /** Loại phương tiện gửi cho Goong Directions (car/bike/taxi/truck/hd) — tài liệu Goong không nói
     * rõ "bike" là xe đạp hay cả xe máy, nhưng để "car" gần như chắc chắn sai cho app xe máy (ưu
     * tiên đường ô tô, né hẻm/đường nhỏ xe máy hay đi — đúng triệu chứng người dùng báo "không dẫn
     * vào đường nhỏ"). Đổi mặc định sang "bike" (gần với xe 2 bánh hơn "car"); chỉnh lại trong Cài
     * đặt nếu thử thực tế thấy "hd" (dành cho xe ôm công nghệ, cũng là xe máy ở VN) hợp hơn. */
    var goongVehicle: String
        get() = sp.getString("goongVehicle", "bike") ?: "bike"
        set(v) = sp.edit().putString("goongVehicle", v).apply()

    /** Đẩy dẫn đường (dạng chữ, nhẹ) lên màn TFT của xe qua Bluetooth. */
    var tftLink: Boolean
        get() = sp.getBoolean("tftLink", false)
        set(v) = sp.edit().putBoolean("tftLink", v).apply()

    /** Địa chỉ MAC Bluetooth đã ghép đôi của màn TFT xe (rỗng = chưa chọn thiết bị). */
    var tftDevice: String
        get() = sp.getString("tftDevice", "") ?: ""
        set(v) = sp.edit().putString("tftDevice", v.trim()).apply()

    /** Tên hiển thị của thiết bị trên, chỉ để hiện trong Cài đặt. */
    var tftDeviceName: String
        get() = sp.getString("tftDeviceName", "") ?: ""
        set(v) = sp.edit().putString("tftDeviceName", v.trim()).apply()

    /** Chuyến đang đi (để dẫn tiếp nếu app bị tắt giữa chừng). */
    fun saveTrip(lat: Double, lon: Double, name: String?) {
        sp.edit().putString("tripLat", lat.toString()).putString("tripLon", lon.toString())
            .putString("tripName", name ?: "").putLong("tripAt", System.currentTimeMillis()).apply()
    }

    fun clearTrip() = sp.edit().remove("tripLat").remove("tripLon").remove("tripName").remove("tripAt").apply()

    /** Trả về (lat, lon, tên) nếu có chuyến dở trong vòng 3 giờ. */
    fun pendingTrip(): Triple<Double, Double, String?>? {
        val at = sp.getLong("tripAt", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 3 * 3600_000L) return null
        val lat = sp.getString("tripLat", null)?.toDoubleOrNull() ?: return null
        val lon = sp.getString("tripLon", null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, sp.getString("tripName", "")?.ifBlank { null })
    }

    companion object {
        const val AREA_AUTO = 0
        const val AREA_URBAN = 1
        const val AREA_RURAL = 2
    }
}

/** Camera anh tự đánh dấu (phạt nguội, tốc độ...), lưu trong máy. */
object CameraStore {
    @Volatile
    private var cache: List<Camera>? = null

    fun all(ctx: Context): List<Camera> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val list = ArrayList<Camera>()
        try {
            val arr = JSONArray(sp.getString("user_cams", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Camera(
                        o.getString("id"), o.getDouble("lat"), o.getDouble("lon"),
                        o.optInt("kind", CamKind.FINE), o.optDouble("heading", -1.0), true,
                    ),
                )
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Camera>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id).put("lat", c.lat).put("lon", c.lon)
                    .put("kind", c.kind).put("heading", c.heading),
            )
        }
        ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putString("user_cams", arr.toString()).apply()
        cache = list
    }

    fun add(ctx: Context, lat: Double, lon: Double, kind: Int, heading: Double) {
        val list = ArrayList(all(ctx))
        list.add(Camera("u" + System.currentTimeMillis(), lat, lon, kind, heading, true))
        save(ctx, list)
    }

    /** Thêm nhiều camera (bỏ qua điểm trùng trong 20 m). Trả về số điểm mới. */
    fun addAll(ctx: Context, items: List<CameraIO.Item>): Int {
        val list = ArrayList(all(ctx))
        var added = 0
        val base = System.currentTimeMillis()
        for (it in items) {
            val dup = list.any { c -> Geo.distance(c.lat, c.lon, it.lat, it.lon) < 20 && c.kind == it.kind }
            if (dup) continue
            list.add(Camera("u$base-$added", it.lat, it.lon, it.kind, it.heading, true))
            added++
        }
        if (added > 0) save(ctx, list)
        return added
    }

    /** Xoá camera tự thêm gần vị trí nhất (trong 300 m). Trả về true nếu có xoá. */
    fun removeNearest(ctx: Context, lat: Double, lon: Double): Boolean {
        val list = ArrayList(all(ctx))
        val best = list.minByOrNull { Geo.distance(lat, lon, it.lat, it.lon) } ?: return false
        if (Geo.distance(lat, lon, best.lat, best.lon) > 300) return false
        list.remove(best)
        save(ctx, list)
        return true
    }

    fun clear(ctx: Context) = save(ctx, emptyList())
}

/**
 * Tốc độ cố định do người dùng tự gán cho TỪNG ĐOẠN ĐƯỜNG (theo way id của OSM) — không đoán theo
 * bán kính/hình tròn. Dùng khi chạy "ghi vùng": đang chạy qua đoạn nào thì đoạn đó (way id) được gán
 * thẳng mức tốc độ đã chọn, đúng hình dạng mạng lưới đường thật, không lòi ra đường ngoài khu.
 */
object WayLimits {
    @Volatile
    private var cache: HashMap<Long, Int>? = null

    private fun map(ctx: Context): HashMap<Long, Int> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val m = HashMap<Long, Int>()
        try {
            val arr = JSONArray(sp.getString("way_limits", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                m[o.getLong("w")] = o.getInt("kmh")
            }
        } catch (_: Exception) {
        }
        cache = m
        return m
    }

    private fun save(ctx: Context, m: HashMap<Long, Int>) {
        val arr = JSONArray()
        for ((w, kmh) in m) arr.put(JSONObject().put("w", w).put("kmh", kmh))
        ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putString("way_limits", arr.toString()).apply()
    }

    /** Gán đoạn đường [wayId] này mức [kmh] cố định, nếu chưa có hoặc khác giá trị cũ. */
    fun add(ctx: Context, wayId: Long, kmh: Int) {
        val m = map(ctx)
        if (m[wayId] == kmh) return
        m[wayId] = kmh
        save(ctx, m)
    }

    fun kmhAt(ctx: Context, wayId: Long): Int? = map(ctx)[wayId]

    fun count(ctx: Context): Int = map(ctx).size

    fun clear(ctx: Context) {
        cache = HashMap()
        save(ctx, HashMap())
    }
}

/**
 * Vùng tốc độ cố định theo ĐÚNG RANH GIỚI khu vực (đa giác) — ví dụ đường viền cam "Khu công nghệ
 * cao" trên bản đồ quy hoạch. Ghi bằng cách chạy một vòng theo đường viền khu (chỉ cần đi hết viền,
 * không cần đi hết từng con đường bên trong), rồi khép vòng lại: mọi vị trí nằm TRONG đa giác đó đều
 * được áp mức tốc độ đã chọn, kể cả những đường nhỏ bên trong app chưa từng đi qua.
 */
object ZonePolygons {
    class Zone(val id: String, val kmh: Int, val lats: DoubleArray, val lons: DoubleArray)

    @Volatile
    private var cache: List<Zone>? = null

    fun all(ctx: Context): List<Zone> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val list = ArrayList<Zone>()
        try {
            val arr = JSONArray(sp.getString("zone_polys", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("pts")
                val lats = DoubleArray(pts.length() / 2)
                val lons = DoubleArray(pts.length() / 2)
                for (k in lats.indices) { lats[k] = pts.getDouble(k * 2); lons[k] = pts.getDouble(k * 2 + 1) }
                list.add(Zone(o.getString("id"), o.getInt("kmh"), lats, lons))
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Zone>) {
        val arr = JSONArray()
        for (z in list) {
            val pts = JSONArray()
            for (i in z.lats.indices) { pts.put(z.lats[i]); pts.put(z.lons[i]) }
            arr.put(JSONObject().put("id", z.id).put("kmh", z.kmh).put("pts", pts))
        }
        ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putString("zone_polys", arr.toString()).apply()
        cache = list
    }

    fun add(ctx: Context, kmh: Int, lats: DoubleArray, lons: DoubleArray) {
        val list = ArrayList(all(ctx))
        list.add(Zone("p" + System.currentTimeMillis(), kmh, lats, lons))
        save(ctx, list)
    }

    /** Thuật toán ray-casting: điểm có nằm trong đa giác không. */
    private fun inside(lat: Double, lon: Double, lats: DoubleArray, lons: DoubleArray): Boolean {
        var inside = false
        var j = lats.size - 1
        for (i in lats.indices) {
            if ((lats[i] > lat) != (lats[j] > lat)) {
                val x = (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i]) + lons[i]
                if (lon < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun kmhAt(ctx: Context, lat: Double, lon: Double): Int? =
        all(ctx).firstOrNull { it.lats.size >= 3 && inside(lat, lon, it.lats, it.lons) }?.kmh

    fun count(ctx: Context): Int = all(ctx).size

    fun clear(ctx: Context) = save(ctx, emptyList())
}

/** Bộ nhớ tạm trong lúc đang ghi viền khu vực (xem [ZonePolygons]) — chỉ sống trong lúc service chạy. */
object ZoneRecorder {
    private val lats = ArrayList<Double>()
    private val lons = ArrayList<Double>()

    @Synchronized
    fun reset() { lats.clear(); lons.clear() }

    /** Thêm điểm khi đã đi được > 15 m kể từ điểm ghi trước (giữ số điểm vừa phải). */
    @Synchronized
    fun add(lat: Double, lon: Double) {
        if (lats.isEmpty() || Geo.distance(lats.last(), lons.last(), lat, lon) > 15.0) {
            lats.add(lat); lons.add(lon)
        }
    }

    @Synchronized
    fun size(): Int = lats.size

    /** Khép vòng và lưu lại. Trả về false nếu chưa đi đủ để tạo thành một vòng (< 3 điểm). */
    @Synchronized
    fun finish(ctx: Context, kmh: Int): Boolean {
        if (lats.size < 3) return false
        ZonePolygons.add(ctx, kmh, lats.toDoubleArray(), lons.toDoubleArray())
        reset()
        return true
    }
}

/** Vị trí người dùng tự báo "đường bị chặn/cấm" (dữ liệu bản đồ cũ), lưu trong máy, dùng để né khi tìm đường. */
object BlockedRoads {
    class Blocked(val id: String, val lat: Double, val lon: Double, val addedAt: Long)

    @Volatile
    private var cache: List<Blocked>? = null
    private const val MAX_AGE_MS = 60L * 24 * 3600_000L // tự hết hạn sau 60 ngày (có thể dữ liệu đã được sửa)

    fun all(ctx: Context): List<Blocked> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val list = ArrayList<Blocked>()
        try {
            val arr = JSONArray(sp.getString("blocked_roads", "[]"))
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val at = o.optLong("at", 0L)
                if (at in 1 until cutoff) continue
                list.add(Blocked(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), at))
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Blocked>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("id", b.id).put("lat", b.lat).put("lon", b.lon).put("at", b.addedAt))
        ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putString("blocked_roads", arr.toString()).apply()
        cache = list
    }

    fun add(ctx: Context, lat: Double, lon: Double) {
        val list = ArrayList(all(ctx))
        if (list.any { Geo.distance(it.lat, it.lon, lat, lon) < 25 }) return
        list.add(Blocked("b" + System.currentTimeMillis(), lat, lon, System.currentTimeMillis()))
        save(ctx, list)
    }

    /** Có điểm bị chặn nào cách đoạn đường [lats]/[lons] trong [radius] m không. */
    fun blocksPath(ctx: Context, lats: DoubleArray, lons: DoubleArray, radius: Double = 25.0): Boolean {
        val list = all(ctx)
        if (list.isEmpty()) return false
        for (i in lats.indices) {
            for (b in list) {
                if (Geo.distance(lats[i], lons[i], b.lat, b.lon) < radius) return true
            }
        }
        return false
    }

    fun clear(ctx: Context) = save(ctx, emptyList())
}

/** Vài điểm đến tìm gần đây nhất, để chọn lại nhanh không cần gõ lại. */
object SearchHistory {
    class Entry(val name: String, val lat: Double, val lon: Double, val at: Long)

    private const val MAX = 5

    @Volatile
    private var cache: List<Entry>? = null

    fun all(ctx: Context): List<Entry> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val list = ArrayList<Entry>()
        try {
            val arr = JSONArray(sp.getString("search_history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Entry(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optLong("at", 0L)))
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("name", e.name).put("lat", e.lat).put("lon", e.lon).put("at", e.at))
        ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putString("search_history", arr.toString()).apply()
        cache = list
    }

    /** Thêm điểm vừa đi tới đầu danh sách; bỏ bản cũ trùng tên, giữ tối đa [MAX] điểm. */
    fun add(ctx: Context, name: String, lat: Double, lon: Double) {
        if (name.isBlank()) return
        val list = ArrayList(all(ctx).filterNot { it.name == name })
        list.add(0, Entry(name, lat, lon, System.currentTimeMillis()))
        save(ctx, list.take(MAX))
    }

    fun clear(ctx: Context) = save(ctx, emptyList())
}
