package vn.hiep.motonav

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/**
 * Bộ tải dữ liệu bản đồ:
 *  - Tự gắn mã Tilemap vào mọi yêu cầu tới maps.vietmap.vn (mảnh bản đồ, font chữ, biểu tượng)
 *    nếu kiểu bản đồ không tự gắn.
 *  - Ghi lại lỗi tải để hiện lên màn hình (vd mã sai → HTTP 401/403).
 */
object MapHttp {
    @Volatile var key: String = ""
    @Volatile var okCount = 0
    @Volatile var failCount = 0
    @Volatile var lastFailCode = 0
    @Volatile var lastFailUrl: String? = null

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                var req = chain.request()
                val url = req.url
                // Kiểu bản đồ VietMap để trống "apikey=" trong link mảnh bản đồ → thay bằng mã Tilemap
                if (url.host.endsWith("vietmap.vn") && key.isNotBlank() && url.queryParameter("apikey") != key) {
                    req = req.newBuilder().url(url.newBuilder().setQueryParameter("apikey", key).build()).build()
                }
                val resp = chain.proceed(req)
                if (req.url.host.endsWith("vietmap.vn")) {
                    if (resp.isSuccessful || resp.code == 204 || resp.code == 304) {
                        okCount++
                    } else {
                        failCount++
                        lastFailCode = resp.code
                        lastFailUrl = req.url.encodedPath
                    }
                }
                resp
            })
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }

    @Volatile var lastLog: String? = null

    /** Bắt thông báo lỗi của thư viện bản đồ (vd "HTTP status code 401") để hiện lên màn hình. */
    fun captureLogs() {
        org.maplibre.android.log.Logger.setLoggerDefinition(object : org.maplibre.android.log.LoggerDefinition {
            private fun keep(msg: String?) {
                if (msg == null) return
                val m = msg.lowercase()
                if (m.contains("http") || m.contains("tile") || m.contains("failed") || m.contains("error")) lastLog = msg.take(160)
            }
            override fun v(tag: String, msg: String) {}
            override fun v(tag: String, msg: String, tr: Throwable) {}
            override fun d(tag: String, msg: String) {}
            override fun d(tag: String, msg: String, tr: Throwable) {}
            override fun i(tag: String, msg: String) {}
            override fun i(tag: String, msg: String, tr: Throwable) {}
            override fun w(tag: String, msg: String) = keep(msg)
            override fun w(tag: String, msg: String, tr: Throwable) = keep(msg)
            override fun e(tag: String, msg: String) = keep(msg)
            override fun e(tag: String, msg: String, tr: Throwable) = keep(msg)
        })
    }

    fun reset() {
        lastLog = null
        okCount = 0
        failCount = 0
        lastFailCode = 0
        lastFailUrl = null
    }

    /** Câu báo lỗi cho người dùng, null nếu bản đồ tải bình thường. */
    fun problem(): String? {
        if (failCount == 0) return null
        if (okCount > failCount * 3) return null
        return when (lastFailCode) {
            401, 403 -> "Bản đồ không tải được (lỗi $lastFailCode): mã Tilemap sai hoặc đang dán mã API vào ô Tilemap. Vào Cài đặt kiểm tra lại."
            429 -> "Bản đồ: đã hết lượt tải trong ngày của mã Tilemap."
            else -> "Bản đồ không tải được (lỗi $lastFailCode ${lastFailUrl ?: ""})."
        }
    }
}
