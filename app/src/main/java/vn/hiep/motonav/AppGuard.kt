package vn.hiep.motonav

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import java.security.MessageDigest
import kotlin.system.exitProcess

/** Kiểm tra chứng thư ký APK và trạng thái debugger trước khi khởi tạo giao diện/dịch vụ. */
internal object AppGuard {
    fun enforce(context: Context) {
        val validSignature = runCatching {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return@runCatching false
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val actual = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching false
            val digest = MessageDigest.getInstance("SHA-256").digest(actual)
                .joinToString("") { "%02X".format(it) }
            digest.equals(BuildConfig.EXPECTED_SIGNING_CERT_SHA256, ignoreCase = true)
        }.getOrDefault(false)

        if (!validSignature || Debug.isDebuggerConnected()) {
            runCatching { Process.killProcess(Process.myPid()) }
            exitProcess(10)
        }
    }
}
