package vn.hiep.motonav

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Biết app nào đang ở màn hình (cần quyền "Truy cập dữ liệu sử dụng"). */
object ForegroundApp {
    const val MAPS = "com.google.android.apps.maps"
    private var lastPkg: String? = null

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Tên gói app đang ở màn hình, null nếu không biết. */
    fun current(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val ev = usm.queryEvents(end - 60_000, end)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                // 1 = MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                if (e.eventType == 1) lastPkg = e.packageName
            }
            lastPkg
        } catch (_: Exception) {
            lastPkg
        }
    }
}
