package vn.hiep.motonav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Một biển báo trên OpenStreetMap. */
class RoadSign(val id: String, val lat: Double, val lon: Double, val type: Int, val value: Int, val heading: Double, val fromWay: Long = 0L)

/** Loại biển, giải mã thẻ traffic_sign của OSM (gồm mã biển Việt Nam P./W./R.). */
object SignType {
    const val NO_STOP = 1      // P.130 cấm dừng và đỗ
    const val NO_PARK = 2      // P.131 cấm đỗ
    const val SPEED = 3        // P.127 tốc độ tối đa
    const val NO_ENTRY = 4     // P.102 cấm đi ngược chiều
    const val NO_LEFT = 5      // P.123a cấm rẽ trái
    const val NO_RIGHT = 6     // P.123b cấm rẽ phải
    const val NO_UTURN = 7     // P.124 cấm quay đầu
    const val STOP = 8         // R.122 dừng lại
    const val GIVE_WAY = 9     // W.208 giao nhau với đường ưu tiên
    const val PEDESTRIAN = 10  // W.224 người đi bộ cắt ngang
    const val CHILDREN = 11    // W.225 trẻ em
    const val WARNING = 12     // biển nguy hiểm khác
    const val NO_MOTO = 13     // P.111a/b cấm xe máy
    const val SIGNALS = 14     // W.210 giao nhau có tín hiệu đèn
    const val BUMP = 15        // W.221b gồ giảm tốc
    const val ROUNDABOUT = 16  // W.203 / R.303 vòng xuyến
    const val ONE_WAY = 17     // I.407a đường một chiều

    fun label(t: Int, v: Int): String = when (t) {
        NO_STOP -> "Cấm dừng và đỗ xe"
        NO_PARK -> "Cấm đỗ xe"
        SPEED -> "Tốc độ tối đa $v"
        NO_ENTRY -> "Cấm đi ngược chiều"
        NO_LEFT -> "Cấm rẽ trái"
        NO_RIGHT -> "Cấm rẽ phải"
        NO_UTURN -> "Cấm quay đầu"
        STOP -> "Dừng lại"
        GIVE_WAY -> "Nhường đường"
        PEDESTRIAN -> "Người đi bộ cắt ngang"
        CHILDREN -> "Trẻ em"
        NO_MOTO -> "Cấm xe máy"
        SIGNALS -> "Đèn tín hiệu giao thông"
        BUMP -> "Gờ giảm tốc"
        ROUNDABOUT -> "Vòng xuyến"
        ONE_WAY -> "Đường một chiều"
        else -> "Chú ý nguy hiểm"
    }

    /** Biển có cần đọc thành tiếng không (ảnh hưởng trực tiếp tới việc lái). */
    fun important(t: Int) = t == NO_ENTRY || t == NO_LEFT || t == NO_RIGHT || t == NO_UTURN || t == NO_MOTO || t == STOP || t == BUMP

    private val speedRe = Regex("""(\d{2,3})""")

    /** Giải mã một nút OSM. Trả về (loại, giá trị) hoặc null nếu không phải biển cần hiện. */
    fun parse(tags: Map<String, String>): Pair<Int, Int>? {
        when (tags["highway"]) {
            "stop" -> return Pair(STOP, 0)
            "give_way" -> return Pair(GIVE_WAY, 0)
            "traffic_signals" -> return Pair(SIGNALS, 0)
            "crossing" -> if (tags["traffic_sign"] == null) return Pair(PEDESTRIAN, 0)
            "speed_bump", "speed_hump" -> return Pair(BUMP, 0)
        }
        if (tags["traffic_calming"] in setOf("bump", "hump", "table", "rumble_strip", "cushion", "yes")) return Pair(BUMP, 0)
        val raw = tags["traffic_sign"] ?: return null
        for (part in raw.split(';', ',')) {
            val s = part.trim()
            val u = s.uppercase().replace(" ", "")
            val code = u.substringAfter(":", u)
            val t = when {
                code.startsWith("P.130") || code.startsWith("P130") || s.contains("no_stopping", true) -> NO_STOP
                code.startsWith("P.131") || code.startsWith("P131") || s.contains("no_parking", true) -> NO_PARK
                code.startsWith("P.127") || code.startsWith("P127") || s.startsWith("maxspeed", true) -> SPEED
                code.startsWith("P.102") || code.startsWith("P102") || s.equals("no_entry", true) -> NO_ENTRY
                code.startsWith("P.123A") || code.startsWith("P123A") || s.contains("no_left", true) -> NO_LEFT
                code.startsWith("P.123B") || code.startsWith("P123B") || s.contains("no_right", true) -> NO_RIGHT
                code.startsWith("P.124") || code.startsWith("P124") || s.contains("no_u_turn", true) -> NO_UTURN
                code.startsWith("P.111") || code.startsWith("P111") -> NO_MOTO
                code.startsWith("R.122") || s.equals("stop", true) -> STOP
                code.startsWith("W.208") || code.startsWith("W.209") || s.equals("give_way", true) -> GIVE_WAY
                code.startsWith("W.224") || s.contains("pedestrian", true) || s.contains("crossing", true) -> PEDESTRIAN
                code.startsWith("W.225") || s.contains("children", true) -> CHILDREN
                code.startsWith("W.") -> WARNING
                else -> null
            } ?: continue
            val v = if (t == SPEED) {
                val bracket = Regex("""\[(\d+)]""").find(s)?.groupValues?.get(1)?.toIntOrNull()
                bracket ?: tags["maxspeed"]?.let { speedRe.find(it)?.value?.toIntOrNull() }
                    ?: speedRe.find(code.substringAfter("127"))?.value?.toIntOrNull() ?: 0
            } else 0
            if (t == SPEED && v == 0) continue
            return Pair(t, v)
        }
        return null
    }

    /** Hướng xe áp dụng (độ), -1 nếu cả hai chiều. */
    fun headingOf(tags: Map<String, String>): Double {
        val d = tags["direction"] ?: tags["traffic_sign:direction"] ?: return -1.0
        d.toDoubleOrNull()?.let { return (it + 180) % 360 } // biển quay mặt về hướng d → xe đi theo hướng ngược lại
        val compass = mapOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)
        compass[d.uppercase()]?.let { return (it + 180) % 360 }
        return -1.0
    }

    // ---------- Vẽ biển ----------

    private val cache = HashMap<String, Bitmap>()

    fun icon(t: Int, v: Int, size: Int): Bitmap {
        val key = "$t/$v/$size"
        cache[key]?.let { return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = size.toFloat()
        val cx = s / 2
        val r = s / 2 - s * 0.03f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val red = Color.parseColor("#D32F2F")
        val blue = Color.parseColor("#1565C0")
        val yellow = Color.parseColor("#FFD600")
        fun ring(fill: Int) {
            p.style = Paint.Style.FILL; p.color = red; c.drawCircle(cx, cx, r, p)
            p.color = fill; c.drawCircle(cx, cx, r * 0.78f, p)
        }
        fun slash(width: Float = 0.12f) {
            p.style = Paint.Style.STROKE; p.color = red; p.strokeWidth = s * width; p.strokeCap = Paint.Cap.BUTT
            c.drawLine(cx - r * 0.55f, cx - r * 0.55f, cx + r * 0.55f, cx + r * 0.55f, p)
        }
        fun text(t: String, size: Float, color: Int, y: Float = cx) {
            p.style = Paint.Style.FILL; p.color = color; p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); p.textSize = size
            c.drawText(t, cx, y + size * 0.35f, p)
        }
        fun arrow(dir: Int) { // 0 lên, -1 trái, 1 phải, 2 quay đầu
            p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = s * 0.09f; p.strokeCap = Paint.Cap.ROUND
            when (dir) {
                -1, 1 -> {
                    c.drawLine(cx + dir * r * 0.15f, cx + r * 0.45f, cx + dir * r * 0.15f, cx - r * 0.05f, p)
                    c.drawLine(cx + dir * r * 0.15f, cx - r * 0.05f, cx - dir * r * 0.35f, cx - r * 0.05f, p)
                    p.style = Paint.Style.FILL
                    val tip = cx - dir * r * 0.5f
                    val ph = Path().apply {
                        moveTo(tip, cx - r * 0.05f); lineTo(tip + dir * r * 0.22f, cx - r * 0.25f); lineTo(tip + dir * r * 0.22f, cx + r * 0.15f); close()
                    }
                    c.drawPath(ph, p)
                }
                2 -> {
                    c.drawLine(cx + r * 0.25f, cx + r * 0.45f, cx + r * 0.25f, cx - r * 0.1f, p)
                    c.drawArc(cx - r * 0.25f, cx - r * 0.35f, cx + r * 0.25f, cx + r * 0.15f, 0f, -180f, false, p)
                    c.drawLine(cx - r * 0.25f, cx - r * 0.1f, cx - r * 0.25f, cx + r * 0.2f, p)
                }
            }
        }
        when (t) {
            NO_STOP -> { ring(blue); slash(0.11f); p.style = Paint.Style.STROKE; c.drawLine(cx + r * 0.55f, cx - r * 0.55f, cx - r * 0.55f, cx + r * 0.55f, p) }
            NO_PARK -> { ring(blue); text("P", s * 0.42f, Color.WHITE); slash(0.11f) }
            SPEED -> { ring(Color.WHITE); text(v.toString(), if (v >= 100) s * 0.34f else s * 0.44f, Color.BLACK) }
            NO_ENTRY -> {
                p.style = Paint.Style.FILL; p.color = red; c.drawCircle(cx, cx, r, p)
                p.color = Color.WHITE; c.drawRect(cx - r * 0.62f, cx - r * 0.16f, cx + r * 0.62f, cx + r * 0.16f, p)
            }
            NO_LEFT -> { ring(Color.WHITE); arrow(-1); slash() }
            NO_RIGHT -> { ring(Color.WHITE); arrow(1); slash() }
            NO_UTURN -> { ring(Color.WHITE); arrow(2); slash() }
            NO_MOTO -> { ring(Color.WHITE); text("🏍", s * 0.4f, Color.BLACK); slash() }
            ONE_WAY -> {
                p.style = Paint.Style.FILL; p.color = blue
                c.drawRoundRect(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f, s * 0.1f, s * 0.1f, p)
                p.color = Color.WHITE
                c.drawRect(cx - s * 0.08f, s * 0.42f, cx + s * 0.08f, s * 0.8f, p)
                val ah = Path().apply { moveTo(cx, s * 0.18f); lineTo(cx + s * 0.24f, s * 0.46f); lineTo(cx - s * 0.24f, s * 0.46f); close() }
                c.drawPath(ah, p)
            }
            STOP -> {
                p.style = Paint.Style.FILL; p.color = red
                val oct = Path()
                for (i in 0 until 8) {
                    val a = Math.toRadians(22.5 + i * 45.0)
                    val x = cx + r * Math.cos(a).toFloat(); val y = cx + r * Math.sin(a).toFloat()
                    if (i == 0) oct.moveTo(x, y) else oct.lineTo(x, y)
                }
                oct.close(); c.drawPath(oct, p)
                text("STOP", s * 0.24f, Color.WHITE)
            }
            GIVE_WAY -> {
                p.style = Paint.Style.FILL; p.color = red
                val tri = Path().apply { moveTo(s * 0.06f, s * 0.12f); lineTo(s * 0.94f, s * 0.12f); lineTo(cx, s * 0.92f); close() }
                c.drawPath(tri, p)
                p.color = Color.WHITE
                val inner = Path().apply { moveTo(s * 0.22f, s * 0.22f); lineTo(s * 0.78f, s * 0.22f); lineTo(cx, s * 0.72f); close() }
                c.drawPath(inner, p)
            }
            else -> {
                p.style = Paint.Style.FILL; p.color = red
                val tri = Path().apply { moveTo(cx, s * 0.06f); lineTo(s * 0.96f, s * 0.9f); lineTo(s * 0.04f, s * 0.9f); close() }
                c.drawPath(tri, p)
                p.color = yellow
                val inner = Path().apply { moveTo(cx, s * 0.22f); lineTo(s * 0.82f, s * 0.82f); lineTo(s * 0.18f, s * 0.82f); close() }
                c.drawPath(inner, p)
                val sym = when (t) {
                    PEDESTRIAN -> "🚶"
                    CHILDREN -> "🚸"
                    SIGNALS -> "🚦"
                    BUMP -> "⌒"
                    ROUNDABOUT -> "⟳"
                    else -> "!"
                }
                text(sym, s * 0.3f, Color.BLACK, s * 0.6f)
            }
        }
        cache[key] = b
        return b
    }
}
