package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

/**
 * Camera theo thông báo chính thức của CSGT (tên ngã tư / đoạn đường + nguồn).
 * App tự tìm toạ độ ngã tư trên OpenStreetMap (điểm giao của 2 con đường), lưu lại để dùng.
 */
object OfficialCameras {
    private const val PREF = "official_cams"

    class Entry(
        val id: String,
        val a: String?,
        val b: String?,
        val landmark: String?,
        val place: String?,
        val kind: Int,
        val nearLat: Double,
        val nearLon: Double,
        val radius: Int,
        val src: String,
    ) {
        fun title(): String = when {
            place != null -> place
            b != null -> "${a?.replace("|", "/")} × ${b.replace("|", "/")}"
            landmark != null -> "${a?.replace("|", "/")} – $landmark"
            else -> a ?: "?"
        }
    }

    @Volatile private var cache: List<Camera>? = null

    fun entries(ctx: Context): List<Entry> {
        val txt = ctx.assets.open("official_cameras.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(txt)
        val ver = root.optInt("version", 1)
        val out = ArrayList<Entry>()
        val groups = root.getJSONArray("groups")
        for (g in 0 until groups.length()) {
            val gr = groups.getJSONObject(g)
            val kind = when (gr.optString("kind")) {
                "red" -> CamKind.RED_LIGHT
                "speed" -> CamKind.SPEED
                else -> CamKind.FINE
            }
            val near = gr.getJSONArray("near")
            val items = gr.getJSONArray("items")
            for (i in 0 until items.length()) {
                val it = items.getJSONArray(i)
                fun s(k: Int) = if (k < it.length() && !it.isNull(k)) it.getString(k) else null
                out.add(
                    Entry(
                        "v$ver-g$g-i$i", s(0), s(1), s(2), s(3), kind,
                        near.getDouble(0), near.getDouble(1), gr.optInt("r", 5000), gr.optString("src"),
                    ),
                )
            }
        }
        return out
    }

    /** Các camera đã có toạ độ. */
    fun cameras(ctx: Context): List<Camera> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val out = ArrayList<Camera>()
        try {
            val arr = JSONArray(sp.getString("resolved", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Camera("off-" + o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("kind"), -1.0, false))
            }
        } catch (_: Exception) {
        }
        cache = out
        return out
    }

    fun status(ctx: Context): Pair<Int, Int> {
        val total = try { entries(ctx).size } catch (_: Exception) { 0 }
        return Pair(cameras(ctx).size, total)
    }

    fun failedTitles(ctx: Context): List<String> {
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val failed = sp.getStringSet("failed", emptySet()) ?: emptySet()
        return try { entries(ctx).filter { it.id in failed }.map { it.title() } } catch (_: Exception) { emptyList() }
    }

    fun reset(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        cache = null
    }

    /** Cần chạy định vị không (có điểm chưa xử lý, hoặc điểm lỗi đã quá 1 ngày). */
    fun needsResolve(ctx: Context): Boolean {
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val done = (sp.getStringSet("done", emptySet()) ?: emptySet())
        val all = try { entries(ctx) } catch (_: Exception) { return false }
        if (all.any { it.id !in done }) return true
        val failed = sp.getStringSet("failed", emptySet()) ?: emptySet()
        return failed.isNotEmpty() && System.currentTimeMillis() - sp.getLong("lastRun", 0L) > 24 * 3600_000L
    }

    /** Chạy nền: tìm toạ độ cho các điểm chưa có. Gọi trên luồng phụ. */
    fun resolveAll(ctx: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val all = entries(app)
        val done = HashSet(sp.getStringSet("done", emptySet()) ?: emptySet())
        val failed = HashSet(sp.getStringSet("failed", emptySet()) ?: emptySet())
        val resolved = try { JSONArray(sp.getString("resolved", "[]")) } catch (_: Exception) { JSONArray() }
        val retryFailed = System.currentTimeMillis() - sp.getLong("lastRun", 0L) > 24 * 3600_000L
        val todo = all.filter { it.id !in done || (retryFailed && it.id in failed) }
        var n = 0
        for (e in todo) {
            n++
            onProgress(n, todo.size)
            val pt = try { locate(e) } catch (_: Exception) { null }
            if (pt != null) {
                resolved.put(
                    JSONObject().put("id", e.id).put("lat", pt.first).put("lon", pt.second)
                        .put("kind", e.kind).put("title", e.title()).put("src", e.src),
                )
                failed.remove(e.id)
            } else {
                failed.add(e.id)
            }
            done.add(e.id)
            sp.edit().putString("resolved", resolved.toString()).putStringSet("done", HashSet(done))
                .putStringSet("failed", HashSet(failed)).apply()
            cache = null
            Thread.sleep(1500) // không gửi dồn dập lên máy chủ OSM
        }
        sp.edit().putLong("lastRun", System.currentTimeMillis()).apply()
    }

    // ---------- Tìm toạ độ trên OSM ----------

    private val diacriticPairs = listOf(
        "òa" to "oà", "óa" to "oá", "ỏa" to "oả", "õa" to "oã", "ọa" to "oạ",
        "òe" to "oè", "óe" to "oé", "ỏe" to "oẻ", "õe" to "oẽ", "ọe" to "oẹ",
        "ùy" to "uỳ", "úy" to "uý", "ủy" to "uỷ", "ũy" to "uỹ", "ụy" to "uỵ",
    )

    /** Thoát ký tự đặc biệt bằng [x] (tránh dấu \ vì chuỗi truy vấn Overpass xử lý \ khác). */
    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch in ".+*?^$(){}|[]") sb.append('[').append(ch).append(']') else sb.append(ch)
        }
        return sb.toString()
    }

    /** Mẫu regex khớp tên (bỏ qua tiền tố Đường/Phố/Đại lộ, cách viết òa/oà). */
    internal fun pattern(spec: String): String {
        val alts = spec.split('|').map { it.trim() }.flatMap { name ->
            val ql = Regex("""^QL\s*(\d+[A-Z]?)$""", RegexOption.IGNORE_CASE).find(name)
            if (ql != null) {
                val num = ql.groupValues[1].uppercase()
                listOf("(Quốc lộ|QL[.]?) ?$num(A)?")
            } else {
                var p = esc(name)
                diacriticPairs.forEachIndexed { i, (x, y) -> p = p.replace(x, "\u0001$i\u0002").replace(y, "\u0001$i\u0002") }
                diacriticPairs.forEachIndexed { i, (x, y) -> p = p.replace("\u0001$i\u0002", "($x|$y)") }
                listOf("((Đường|Phố|Đại lộ|Đ[.]) )?$p( nối dài)?")
            }
        }
        return "^(" + alts.joinToString("|") + ")(;.*)?$"
    }

    private fun q(lat: Double, lon: Double, r: Int) = String.format(Locale.US, "around:%d,%.5f,%.5f", r, lat, lon)

    class W(val name: String, val ref: String, val la: DoubleArray, val lo: DoubleArray)

    private fun runQuery(query: String): JSONArray = Overpass.raw(query).optJSONArray("elements") ?: JSONArray()

    private fun ways(els: JSONArray): List<W> {
        val out = ArrayList<W>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val g = e.optJSONArray("geometry") ?: continue
            val t = e.optJSONObject("tags") ?: JSONObject()
            val la = DoubleArray(g.length()) { g.getJSONObject(it).optDouble("lat") }
            val lo = DoubleArray(g.length()) { g.getJSONObject(it).optDouble("lon") }
            out.add(W(t.optString("name"), t.optString("ref"), la, lo))
        }
        return out
    }

    private fun matches(w: W, re: Regex) = (w.name.isNotEmpty() && re.matches(w.name)) || (w.ref.isNotEmpty() && re.matches(w.ref))

    fun locate(e: Entry): Pair<Double, Double>? {
        val around = q(e.nearLat, e.nearLon, e.radius)
        // 1. Ngã tư có tên riêng (vd "Ngã tư Bình Thái")
        if (e.place != null) {
            val p = pattern(e.place)
            val els = runQuery(
                "[out:json][timeout:25];nwr($around)[\"name\"~\"$p\",i];out center 5;",
            )
            for (i in 0 until els.length()) {
                val o = els.getJSONObject(i)
                val c = o.optJSONObject("center")
                if (c != null) return Pair(c.getDouble("lat"), c.getDouble("lon"))
                if (o.has("lat")) return Pair(o.getDouble("lat"), o.getDouble("lon"))
            }
            return null
        }
        val a = e.a ?: return null
        val pa = pattern(a)
        val tagA = "[\"highway\"][~\"^(name|ref)$\"~\"$pa\",i]"
        // 2. Đường A gần một mốc (cầu, trường, toà nhà…)
        if (e.b == null && e.landmark != null) {
            val pl = ".*" + esc(e.landmark) + ".*"
            val els = runQuery(
                "[out:json][timeout:25];way($around)$tagA;out tags geom;" +
                    "nwr($around)[\"name\"~\"$pl\",i];out center 10;",
            )
            val reA = Regex(pa, RegexOption.IGNORE_CASE)
            val roads = ways(els).filter { matches(it, reA) }
            var best: Pair<Double, Double>? = null
            var bestD = Double.MAX_VALUE
            for (i in 0 until els.length()) {
                val o = els.getJSONObject(i)
                val tags = o.optJSONObject("tags") ?: continue
                if (tags.has("highway") && !(tags.optString("name").contains(e.landmark, true))) continue
                val (la, lo) = when {
                    o.has("center") -> Pair(o.getJSONObject("center").getDouble("lat"), o.getJSONObject("center").getDouble("lon"))
                    o.has("lat") -> Pair(o.getDouble("lat"), o.getDouble("lon"))
                    o.has("geometry") -> {
                        val g = o.getJSONArray("geometry")
                        val m = g.getJSONObject(g.length() / 2)
                        Pair(m.getDouble("lat"), m.getDouble("lon"))
                    }
                    else -> continue
                }
                // Điểm gần nhất trên đường A tính tới mốc
                for (w in roads) {
                    val (pt, d) = closestOn(w, la, lo)
                    if (d < bestD && d < 250) { bestD = d; best = pt }
                }
            }
            return best
        }
        // 3. Ngã tư 2 đường
        val b = e.b ?: return null
        val pb = pattern(b)
        val tagB = "[\"highway\"][~\"^(name|ref)$\"~\"$pb\",i]"
        val els = runQuery(
            "[out:json][timeout:25];(way($around)$tagA;way($around)$tagB;);out tags geom;",
        )
        val reA = Regex(pa, RegexOption.IGNORE_CASE)
        val reB = Regex(pb, RegexOption.IGNORE_CASE)
        val all = ways(els)
        val wa = all.filter { matches(it, reA) }
        val wb = all.filter { matches(it, reB) && !matches(it, reA) }
        if (wa.isEmpty() || wb.isEmpty()) return null
        // Các điểm giao: điểm chung hoặc chỗ 2 đường cách nhau < 25 m
        val pts = ArrayList<Pair<Double, Double>>()
        for (x in wa) for (y in wb) {
            for (k in x.la.indices) {
                val (pt, d) = closestOn(y, x.la[k], x.lo[k])
                if (d < 25) pts.add(Pair((x.la[k] + pt.first) / 2, (x.lo[k] + pt.second) / 2))
            }
        }
        if (pts.isEmpty()) return null
        // Gom cụm, chọn cụm gần tâm khu vực nhất (đường đôi thường cho 2–4 điểm sát nhau)
        val clusters = ArrayList<MutableList<Pair<Double, Double>>>()
        for (p in pts) {
            val c = clusters.firstOrNull { Geo.distance(it[0].first, it[0].second, p.first, p.second) < 120 }
            if (c != null) c.add(p) else clusters.add(mutableListOf(p))
        }
        val best = clusters.minByOrNull { c ->
            val la = c.sumOf { it.first } / c.size
            val lo = c.sumOf { it.second } / c.size
            Geo.distance(e.nearLat, e.nearLon, la, lo) - c.size * 5
        } ?: return null
        return Pair(best.sumOf { it.first } / best.size, best.sumOf { it.second } / best.size)
    }

    /** Điểm gần nhất trên đường w tới (lat, lon) và khoảng cách (m). */
    private fun closestOn(w: W, lat: Double, lon: Double): Pair<Pair<Double, Double>, Double> {
        val proj = LocalProjection(lat, lon)
        var best = Pair(w.la[0], w.lo[0])
        var bestD = Double.MAX_VALUE
        for (i in 0 until w.la.size - 1) {
            val ax = proj.x(w.lo[i]); val ay = proj.y(w.la[i])
            val bx = proj.x(w.lo[i + 1]); val by = proj.y(w.la[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 0.01) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) {
                bestD = d
                best = Pair(w.la[i] + t * (w.la[i + 1] - w.la[i]), w.lo[i] + t * (w.lo[i + 1] - w.lo[i]))
            }
        }
        if (w.la.size == 1) bestD = Geo.distance(lat, lon, w.la[0], w.lo[0])
        return Pair(best, bestD)
    }
}
