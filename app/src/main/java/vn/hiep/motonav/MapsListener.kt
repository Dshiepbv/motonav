package vn.hiep.motonav

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Đọc thông báo dẫn đường của Google Maps (mũi tên rẽ, khoảng cách, chỉ dẫn)
 * để hiện to trên màn hình MotoNav.
 */
class MapsListener : NotificationListenerService() {

    companion object {
        private const val MAPS = "com.google.android.apps.maps"
        private const val READ_MAPS_OFF = true
        private val MSG_APPS = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.google.android.apps.messaging" to "SMS",
            "com.android.mms" to "SMS",
            "com.samsung.android.messaging" to "SMS",
            "com.coloros.mms" to "SMS",
            "com.oplus.mms" to "SMS",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger",
            "com.facebook.katana" to "Facebook",
            "org.telegram.messenger" to "Telegram",
            "com.whatsapp" to "WhatsApp",
        )
        private val distRe = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|ft|mi|dặm)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    }

    private var navKey: String? = null

    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (_: Exception) {
        }
        try { notice(sbn) } catch (_: Exception) {}
    }

    /** build-454 (Sếp): cuộc gọi đến + tin nhắn Zalo/SMS/Messenger/Telegram → Live.notice* → vẽ lên ảnh màn xe.
     *  build-496 (Sếp 21/11): "Bỏ hết phần báo điện thoại, tn zalo mess đi. Ko cần thông báo gì cả" — tắt hẳn,
     *  không ghi Live.notice* nữa nên [TftLink.drawNotice] cũng không còn gì để vẽ. */
    private fun notice(sbn: StatusBarNotification) {
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == Live.noticeKey && Live.noticeCall) { Live.noticeTitle = null; Live.noticeKey = null }
        if (!READ_MAPS_OFF && sbn.packageName == MAPS && sbn.key == navKey && !Live.ownNav) {
            navKey = null
            Live.clearNav()
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (READ_MAPS_OFF) return // build-455 (Sếp): bỏ đọc chỉ dẫn Google Maps — chỉ dùng dẫn đường của MotoNav trên màn TFT
        if (sbn.packageName != MAPS) return
        if (Live.ownNav) return
        val n = sbn.notification
        val isNav = n.category == "navigation" || (sbn.isOngoing && n.getLargeIcon() != null)
        if (!isNav) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return

        val arrow = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { toBitmap(it) }
        } catch (_: Exception) {
            null
        }

        // Tìm khoảng cách (vd "250 m", "1,2 km") trong tiêu đề hoặc nội dung
        val distMatch = title?.let { distRe.find(it) } ?: text?.let { distRe.find(it) }
        val distance = distMatch?.value

        val titleIsOnlyDistance = title != null && distance != null &&
            title.replace(distance, "").trim(' ', '·', '•', '-', '–').isEmpty()

        var instruction: String?
        var extra: String?
        if (titleIsOnlyDistance) {
            instruction = text
            extra = sub
        } else {
            instruction = title
            extra = listOfNotNull(text, sub).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        }
        if (distance != null && instruction != null) {
            instruction = instruction.replace(distance, "").trim(' ', '·', '•', '-', '–', ',')
                .ifBlank { null }
        }
        if (extra != null && distance != null && extra.trim() == distance) extra = null

        navKey = sbn.key
        Live.setNav(arrow, distance, instruction, extra)
    }

    private fun toBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 192
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 192
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }
}
