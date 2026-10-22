package vn.hiep.motonav

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6371000.0

    /** Khoảng cách (mét) giữa 2 điểm. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    /** Hướng (độ, 0 = Bắc, theo chiều kim đồng hồ) từ điểm 1 tới điểm 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return norm(Math.toDegrees(atan2(y, x)))
    }

    fun norm(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Độ lệch nhỏ nhất giữa 2 hướng (0..180). */
    fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180) d = 360 - d
        return d
    }
}

/** Phép chiếu phẳng quanh vị trí hiện tại, đủ chính xác trong bán kính vài km. */
class LocalProjection(private val lat0: Double, private val lon0: Double) {
    private val kx = cos(Math.toRadians(lat0)) * 111320.0
    private val ky = 110540.0
    fun x(lon: Double) = (lon - lon0) * kx
    fun y(lat: Double) = (lat - lat0) * ky
}
