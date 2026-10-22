package vn.hiep.motonav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Vẽ mũi tên rẽ theo mã sign. */
object ArrowIcon {
    private val cache = HashMap<String, Bitmap>()

    fun get(sign: Int, size: Int): Bitmap {
        val key = "$sign/$size"
        cache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = s * 0.13f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val cx = s / 2
        val cy = s * 0.55f

        when (sign) {
            4, 5 -> { // đến nơi: ghim
                c.drawCircle(cx, s * 0.4f, s * 0.22f, fill)
                val p = Path().apply {
                    moveTo(cx - s * 0.16f, s * 0.5f); lineTo(cx, s * 0.88f); lineTo(cx + s * 0.16f, s * 0.5f); close()
                }
                c.drawPath(p, fill)
                c.drawCircle(cx, s * 0.4f, s * 0.08f, Paint(fill).apply { color = Color.parseColor("#0B6E4F") })
            }
            6 -> { // vòng xuyến
                c.drawCircle(cx, cy, s * 0.2f, stroke)
                c.drawLine(cx, s * 0.92f, cx, cy + s * 0.2f, stroke)
                head(c, cx + s * 0.2f, cy - s * 0.25f, -45f, s, fill)
                c.drawLine(cx + s * 0.14f, cy - s * 0.14f, cx + s * 0.24f, cy - s * 0.29f, stroke)
            }
            -8, -98, 8 -> { // quay đầu
                val left = sign != 8
                val r = s * 0.18f
                val x1 = if (left) cx + r else cx - r
                val x2 = if (left) cx - r else cx + r
                c.drawLine(x1, s * 0.9f, x1, s * 0.4f, stroke)
                val oval = RectF(cx - r, s * 0.4f - r, cx + r, s * 0.4f + r)
                c.drawArc(oval, 180f, 180f, false, stroke)
                c.drawLine(x2, s * 0.4f, x2, s * 0.62f, stroke)
                head(c, x2, s * 0.72f, 180f, s, fill)
            }
            else -> {
                val a = Maneuver.angle(sign)
                // thân mũi tên từ dưới lên giữa
                c.drawLine(cx, s * 0.92f, cx, cy, stroke)
                val len = s * 0.32f
                val rad = Math.toRadians(a.toDouble())
                val ex = cx + (len * Math.sin(rad)).toFloat()
                val ey = cy - (len * Math.cos(rad)).toFloat()
                c.drawLine(cx, cy, ex, ey, stroke)
                head(c, ex, ey, a, s, fill)
            }
        }
        cache[key] = bmp
        return bmp
    }

    private fun head(c: Canvas, x: Float, y: Float, angle: Float, s: Float, p: Paint) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        val h = s * 0.2f
        val path = Path().apply {
            moveTo(0f, -h)
            lineTo(-h * 0.85f, h * 0.15f)
            lineTo(h * 0.85f, h * 0.15f)
            close()
        }
        c.drawPath(path, p)
        c.restore()
    }
}
