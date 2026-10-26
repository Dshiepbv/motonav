package vn.hiep.motonav

/** Một bước chỉ dẫn trên tuyến (theo định dạng VietMap Route v4). */
class Step(
    val sign: Int,
    val text: String,
    val street: String,
    val distance: Double,
    /** Chỉ số điểm bắt đầu bước này trên tuyến = nơi thực hiện thao tác rẽ. */
    val from: Int,
    val to: Int,
    val exitNumber: Int,
)

class Route(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val steps: List<Step>,
    val distance: Double,
    val timeMs: Long,
    /** Mức kẹt xe cho từng đoạn (low/moderate/heavy/severe/unknown), có thể rỗng. */
    val congestion: List<String> = emptyList(),
) {
    /** Mức kẹt xe của đoạn i (giữa điểm i và i+1). */
    fun congestionAt(i: Int): String {
        if (congestion.isEmpty()) return "unknown"
        val segs = lats.size - 1
        if (congestion.size == segs) return congestion[i]
        val k = ((i.toDouble() / segs) * congestion.size).toInt().coerceIn(0, congestion.size - 1)
        return congestion[k]
    }

    /** Tuyến chia thành nhiều đoạn theo mức kẹt xe, để tô màu. */
    fun toCongestionGeoJson(): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var i = 0
        var firstF = true
        val n = lats.size
        while (i < n - 1) {
            val c = congestionAt(i)
            var j = i
            while (j < n - 1 && congestionAt(j) == c) j++
            if (!firstF) sb.append(',')
            firstF = false
            sb.append("""{"type":"Feature","properties":{"c":"""").append(c)
                .append(""""},"geometry":{"type":"LineString","coordinates":[""")
            for (k in i..j) {
                if (k > i) sb.append(',')
                sb.append('[').append(lons[k]).append(',').append(lats[k]).append(']')
            }
            sb.append("]}}")
            i = j
        }
        sb.append("]}")
        return sb.toString()
    }
    /** Quãng đường cộng dồn (m) tới từng điểm. */
    val cum: DoubleArray = DoubleArray(lats.size).also {
        for (i in 1 until lats.size) {
            it[i] = it[i - 1] + Geo.distance(lats[i - 1], lons[i - 1], lats[i], lons[i])
        }
    }
    val total: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]

    fun toGeoJsonLine(): String {
        val sb = StringBuilder("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
        for (i in lats.indices) {
            if (i > 0) sb.append(',')
            sb.append('[').append(lons[i]).append(',').append(lats[i]).append(']')
        }
        sb.append("]}}")
        return sb.toString()
    }

    companion object {
        /** Giải mã Google polyline (độ chính xác 5). */
        fun decodePolyline(enc: String): Pair<DoubleArray, DoubleArray> {
            val la = ArrayList<Double>()
            val lo = ArrayList<Double>()
            var index = 0
            var lat = 0
            var lng = 0
            while (index < enc.length) {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    b = enc[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < enc.length)
                lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
                result = 0
                shift = 0
                do {
                    b = enc[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < enc.length)
                lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
                la.add(lat / 1e5)
                lo.add(lng / 1e5)
            }
            return Pair(la.toDoubleArray(), lo.toDoubleArray())
        }
    }
}

/** Cách đọc thao tác theo mã sign (chuẩn GraphHopper, VietMap dùng chung). */
object Maneuver {
    fun verb(sign: Int): String = when (sign) {
        -3 -> "quẹo gắt trái"
        -2 -> "rẽ trái"
        -1 -> "chếch trái"
        0 -> "đi thẳng"
        1 -> "chếch phải"
        2 -> "rẽ phải"
        3 -> "quẹo gắt phải"
        4 -> "đến nơi"
        5 -> "đến điểm dừng"
        6 -> "vào vòng xuyến"
        -7 -> "đi về bên trái"
        7 -> "đi về bên phải"
        -8, -98 -> "quay đầu"
        8 -> "quay đầu"
        else -> "đi tiếp"
    }

    /** Câu chỉ dẫn đầy đủ, vd "Rẽ phải vào Lê Văn Việt". */
    fun phrase(s: Step): String {
        if (s.sign == 4) return "Đến nơi"
        if (s.sign == 6 && s.exitNumber > 0) {
            val street = if (s.street.isNotBlank()) " vào ${s.street}" else ""
            return "Vào vòng xuyến, ra lối thứ ${s.exitNumber}$street"
        }
        val v = verb(s.sign).replaceFirstChar { it.uppercase() }
        return if (s.street.isNotBlank()) "$v vào ${s.street}" else v
    }

    /** Ký tự mũi tên đơn giản theo sign, dùng cho nhãn chỗ rẽ trên màn TFT (kèm khoảng cách đếm
     * ngược, theo yêu cầu "chỉ dẫn đường cần có hướng và đếm ngược khoảng cách"). */
    fun glyph(sign: Int): String = when (sign) {
        -3 -> "↩"
        -2 -> "⬅"
        -1, -7 -> "↖"
        0 -> "⬆"
        1, 7 -> "↗"
        2 -> "➡"
        3 -> "↪"
        4, 5 -> "⚑"
        6 -> "⟳"
        -8, -98, 8 -> "↩"
        else -> "⬆"
    }

    /** Góc mũi tên (0 = thẳng lên, âm = trái, dương = phải). */
    fun angle(sign: Int): Float = when (sign) {
        -3 -> -135f
        -2 -> -90f
        -1, -7 -> -45f
        1, 7 -> 45f
        2 -> 90f
        3 -> 135f
        else -> 0f
    }
}
