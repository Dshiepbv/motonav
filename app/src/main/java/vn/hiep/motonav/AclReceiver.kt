package vn.hiep.motonav

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Dự phòng cho sự kiện ACL; chỉ xử lý MAC xe đã được người dùng đăng ký. */
class AclReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val expected = prefs.tftDevice
        if (expected.isBlank()) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice ?: return
        if (!device.address.equals(expected, ignoreCase = true)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!prefs.tftLink ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) return
                try {
                    DriveService.start(context)
                    Log.i("MotoNavBike", "ACL connected; DriveService requested")
                } catch (e: Exception) {
                    Log.w("MotoNavBike", "Unable to start DriveService", e)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                try {
                    TftLink.stop()
                    DriveService.stop(context)
                    Log.i("MotoNavBike", "ACL disconnected; bike session stopped")
                } catch (e: Exception) {
                    Log.w("MotoNavBike", "Unable to stop bike session", e)
                }
            }
        }
    }
}
