package vn.hiep.motonav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.cos

/**
 * Mũi tên vị trí xe vẽ bằng View nằm trên bản đồ (không nháy như biểu tượng trong bản đồ
 * mỗi lần cập nhật). Tự xoay theo hướng xe so với bản đồ và nghiêng theo góc 3D.
 */
class CarMarkerView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Góc xoay trên màn hình (độ). */
    var rot = 0f
    /** Góc nghiêng bản đồ (độ). */
    var tilt = 0f

    init {
        isClickable = false
        isFocusable = false
    }

    fun place(x: Float, y: Float, rotation: Float, tiltDeg: Float) {
        translationX = x - width / 2f
        translationY = y - height / 2f
        if (kotlin.math.abs(rotation - rot) > 0.3f || kotlin.math.abs(tiltDeg - tilt) > 0.5f) {
            rot = rotation
            tilt = tiltDeg
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.save()
        c.translate(cx, cy)
        // Nghiêng theo bản đồ 3D: ép dẹt theo chiều dọc màn hình, rồi xoay theo hướng xe
        c.scale(1f, cos(Math.toRadians(tilt.toDouble())).toFloat().coerceAtLeast(0.45f))
        c.rotate(rot)
        c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, paint)
        c.restore()
    }
}
