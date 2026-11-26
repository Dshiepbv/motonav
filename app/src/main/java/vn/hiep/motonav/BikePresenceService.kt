package vn.hiep.motonav

import android.companion.CompanionDeviceService
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log

/**
 * Android quản lý vòng đời service này theo trạng thái hiện diện của xe đã đăng ký.
 * Không giữ một vòng polling nền; chỉ khởi động dịch vụ dẫn đường khi xe xuất hiện.
 */
class BikePresenceService : CompanionDeviceService() {
    @Suppress("NewApi")
    override fun onDeviceAppeared(address: String) {
        super.onDeviceAppeared(address)
        val prefs = Prefs(this)
        if (address.equals(prefs.tftDevice, ignoreCase = true) &&
            prefs.tftLink &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (android.os.Build.VERSION.SDK_INT < 29 ||
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        ) {
            try {
                DriveService.start(this)
                Log.i("MotoNavBike", "Companion appeared; DriveService requested")
            } catch (e: Exception) {
                Log.w("MotoNavBike", "Unable to start DriveService", e)
            }
        }
    }

    @Suppress("NewApi")
    override fun onDeviceDisappeared(address: String) {
        super.onDeviceDisappeared(address)
        val prefs = Prefs(this)
        if (address.equals(prefs.tftDevice, ignoreCase = true)) {
            try {
                TftLink.stop()
                DriveService.stop(this)
                Log.i("MotoNavBike", "Companion disappeared; connection stopped")
            } catch (e: Exception) {
                Log.w("MotoNavBike", "Unable to stop bike session", e)
            }
        }
    }
}
