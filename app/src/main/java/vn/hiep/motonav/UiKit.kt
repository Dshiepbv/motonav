package vn.hiep.motonav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/** Bảng màu theo logo NMAX NAVI: đỏ là chủ đạo, xám than + bạc làm nền/phụ, trắng cho chữ. */
object Brand {
    val red = Color.parseColor("#E0262B")        // đỏ logo — nút chính, thẻ chỉ dẫn gần ngã rẽ
    val redDeep = Color.parseColor("#A81E22")    // đỏ sẫm — thẻ chỉ dẫn, mục đang chọn, công tắc BẬT
    val redIcon = Color.parseColor("#FF4B4B")    // đỏ sáng cho nét icon trên nền tối (dễ đọc hơn)
    val redSoftText = Color.parseColor("#FFDCDC") // chữ phụ trên nền đỏ
    val charcoal = Color.parseColor("#2B3038")   // xám than — nền nút phụ, công tắc TẮT
    val panel = Color.parseColor("#181B20")      // nền khung/bảng
    val border = Color.parseColor("#2E3540")     // viền mảnh
    val silver = Color.parseColor("#C9CED6")     // bạc — nhóm phụ/nâng cao, nút viền
}

/**
 * Thanh tiến độ dạng các nấc hình bình hành vát chéo (thay thanh trơn), tô đỏ dần theo % — không hiện
 * số MB. [indeterminate] = đang kiểm tra (chưa biết %): 1 cụm nấc chạy qua lại.
 */
class SegmentProgress(ctx: Context) : View(ctx) {
    var segments = 18
    var progress = 0
        set(v) {
            field = v.coerceIn(0, 100)
            invalidate()
        }
    var indeterminate = false
        set(v) {
            field = v
            invalidate()
        }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.red }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.charcoal }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return
        val skew = h * 0.55f
        val gap = h * 0.35f
        val segW = (w - skew - gap * (segments - 1)) / segments
        val filled = Math.round(progress / 100f * segments)
        val runPos = if (indeterminate) ((SystemClock.uptimeMillis() / 90L) % (segments + 4)).toInt() - 2 else -100
        for (i in 0 until segments) {
            val x = i * (segW + gap)
            path.reset()
            path.moveTo(x + skew, 0f)
            path.lineTo(x + skew + segW, 0f)
            path.lineTo(x + segW, h)
            path.lineTo(x, h)
            path.close()
            val lit = if (indeterminate) Math.abs(i - runPos) <= 1 else i < filled
            c.drawPath(path, if (lit) on else off)
        }
        if (indeterminate) postInvalidateOnAnimation()
    }
}
