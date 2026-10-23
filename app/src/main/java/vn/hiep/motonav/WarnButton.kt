package vn.hiep.motonav

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Nút báo cảnh báo hình tam giác vàng nhạt, nổi khối với bóng mềm + dấu "!" bo tròn mềm mại. */
class WarnButton(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE58A")
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3200")
        style = Paint.Style.STROKE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3200")
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        // BlurMaskFilter cần layer phần mềm mới vẽ được bóng mờ
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.16f // bo tròn hơn, mềm mại hơn
        fill.pathEffect = CornerPathEffect(r)
        stroke.pathEffect = CornerPathEffect(r)
        shadow.pathEffect = CornerPathEffect(r)
        shadow.maskFilter = BlurMaskFilter(w * 0.10f, BlurMaskFilter.Blur.NORMAL)
        stroke.strokeWidth = w * 0.05f
        fun tri(dy: Float = 0f) = Path().apply {
            moveTo(w / 2, h * 0.08f + dy)
            lineTo(w * 0.95f, h * 0.9f + dy)
            lineTo(w * 0.05f, h * 0.9f + dy)
            close()
        }
        // Bóng mềm đổ xuống dưới, cho cảm giác tam giác nổi lên mặt kính thay vì dán phẳng
        c.drawPath(tri(h * 0.08f), shadow)
        val t = tri()
        c.drawPath(t, fill)
        c.drawPath(t, stroke)
        // Dấu chấm than mềm mại: thanh bo tròn + chấm tròn, đậm và to hơn chữ "!" thường
        val cx = w / 2
        val barW = w * 0.12f
        c.drawRoundRect(cx - barW / 2, h * 0.32f, cx + barW / 2, h * 0.60f, barW / 2, barW / 2, mark)
        c.drawCircle(cx, h * 0.72f, barW * 0.62f, mark)
    }
}
