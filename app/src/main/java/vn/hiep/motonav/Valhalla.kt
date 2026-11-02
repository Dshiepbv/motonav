package vn.hiep.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Định tuyến xe máy bằng Valhalla trên máy chủ cộng đồng OSM (valhalla1.openstreetmap.de):
 * miễn phí, không cần mã, dữ liệu OSM (tôn trọng thẻ cấm xe máy), không có kẹt xe.
 * Máy chủ cộng đồng → gọi thưa (≤1 lần/giây), lỗi thì NavEngine rơi về Goong/VietMap.
 */
object Valhalla {
    private const val BASE = "https://valhalla1.openstreetmap.de/route?json="

    fun routes(
        fromLat: Double, fromLon: Double, heading: Double?, toLat: Double, toLon: Double,
        alternative: Boolean, via: List<Pair<Double, Double>> = emptyList(),
    ): List<Route> {
        val locs = JSONArray()
        locs.put(JSONObject().apply {
            put("lat", fromLat); put("lon", fromLon); put("type", "break")
            if (heading != null && heading >= 0) { put("heading", heading.toInt()); put("heading_tolerance", 60) }
        })
        for (v in via) locs.put(JSONObject().apply { put("lat", v.first); put("lon", v.second); put("type", "through") })
        locs.put(JSONObject().apply { put("lat", toLat); put("lon", toLon); put("type", "break") })
        val req = JSONObject().apply {
            put("locations", locs)
            put("costing", "motorcycle")
            put("costing_options", JSONObject().put("motorcycle", JSONObject().apply {
                put("use_highways", 0.0)   // VN: xe máy không vào cao tốc
                put("use_primary", 0.8)    // ưu tiên đường chính, ít luồn hẻm
                put("use_trails", 0.0)
                put("use_tolls", 0.5)
            }))
            put("units", "kilometers")
            put("directions_options", JSONObject().put("units", "kilometers"))
            if (alternative && via.isEmpty()) put("alternates", 2)
        }
        val url = BASE + URLEncoder.encode(req.toString(), "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "MotoNav/1.0 (personal motorcycle navigation)")
        val body: String
        try {
            val code = c.responseCode
            if (code != 200) {
                val err = try { c.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw RuntimeException("Valhalla HTTP $code ${err?.take(150) ?: ""}")
            }
            body = c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
        val root = JSONObject(body)
        val out = ArrayList<Route>()
        root.optJSONObject("trip")?.let { out.add(parseTrip(it)) }
        root.optJSONArray("alternates")?.let { arr ->
            for (i in 0 until arr.length()) {
                try { arr.getJSONObject(i).optJSONObject("trip")?.let { out.add(parseTrip(it)) } } catch (_: Exception) {}
            }
        }
        if (out.isEmpty()) throw RuntimeException("Valhalla không trả về tuyến")
        return out
    }

    /** Mã thao tác Valhalla → mã sign kiểu GraphHopper dùng trong app. */
    private fun signOf(t: Int): Int = when (t) {
        4, 5, 6 -> 4
        9 -> 1
        10 -> 2
        11 -> 3
        12 -> 8
        13 -> -8
        14 -> -3
        15 -> -2
        16 -> -1
        18, 20, 23, 37 -> 7
        19, 21, 24, 38 -> -7
        26 -> 6
        else -> 0
    }

    private fun parseTrip(trip: JSONObject): Route {
        val legs = trip.optJSONArray("legs") ?: JSONArray()
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        val steps = ArrayList<Step>()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val (sla, slo) = Route.decodePolyline(leg.optString("shape", ""), 1e6)
            if (sla.isEmpty()) continue
            val skip = if (la.isNotEmpty() && sla[0] == la.last() && slo[0] == lo.last()) 1 else 0
            val off = la.size - skip
            for (k in skip until sla.size) { la.add(sla[k]); lo.add(slo[k]) }
            val mans = leg.optJSONArray("maneuvers") ?: JSONArray()
            for (mi in 0 until mans.length()) {
                val m = mans.getJSONObject(mi)
                val type = m.optInt("type", 0)
                // Điểm đến của chặng giữa (điểm đi qua) và lối ra vòng xuyến: không phải thao tác riêng
                if (type == 27 || (type in 4..6 && li < legs.length() - 1)) continue
                val names = m.optJSONArray("street_names")
                val street = if (names != null && names.length() > 0) names.optString(0) else ""
                val sign = signOf(type)
                val from = (off + m.optInt("begin_shape_index", 0)).coerceIn(0, la.size - 1)
                val to = (off + m.optInt("end_shape_index", 0)).coerceIn(from, la.size - 1)
                val st = Step(sign, "", street, m.optDouble("length", 0.0) * 1000.0, from, to, if (type == 26) m.optInt("roundabout_exit_count", 0) else 0)
                steps.add(Step(sign, Maneuver.phrase(st), street, st.distance, from, to, st.exitNumber))
            }
        }
        if (la.size < 2) throw RuntimeException("Tuyến Valhalla rỗng")
        val sum = trip.optJSONObject("summary")
        val dist = (sum?.optDouble("length", 0.0) ?: 0.0) * 1000.0
        val time = ((sum?.optDouble("time", 0.0) ?: 0.0) * 1000).toLong()
        return Route(la.toDoubleArray(), lo.toDoubleArray(), steps, dist, time)
    }
}
