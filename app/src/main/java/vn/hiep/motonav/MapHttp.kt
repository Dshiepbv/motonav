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
                if (url.host.endsWith("vietmap.vn")) {
                    val rb = req.newBuilder()
                    // Kiểu bản đồ VietMap để trống "apikey=" trong link mảnh bản đồ → thay bằng mã Tilemap
                    if (key.isNotBlank() && url.queryParameter("apikey") != key) {
                        rb.url(url.newBuilder().setQueryParameter("apikey", key).build())
                    }
                    // Lỗi "invalid tag" (đọc mảnh gzip thô, xem bên dưới) vẫn còn dù đã thử đổi thứ tự
                    // khởi tạo (gây crash, đã bỏ) — nghi thật ra là do thư viện bản đồ (mã gốc C++/Java
                    // của SDK) tự gắn sẵn header "Accept-Encoding" khi tạo yêu cầu tải mảnh. OkHttp CHỈ
                    // tự giải nén gzip trong suốt khi KHÔNG có ai tự set header này — nếu tầng gọi (thư
                    // viện bản đồ) đã tự set, OkHttp coi là tầng gọi tự lo giải nén, trả nguyên byte nén
                    // thô cho interceptor này — rồi phần dò gzip thủ công bên dưới có thể bỏ sót (vd
                    // nếu máy chủ trả kiểu nén khác gzip như brotli/deflate). Bỏ hẳn header này ở đây để
                    // OkHttp tự thương lượng + tự giải nén lại theo kiểu mặc định của nó, không phụ
                    // thuộc thư viện bản đồ tự set gì.
                    rb.removeHeader("Accept-Encoding")
                    req = rb.build()
                }
                var resp = chain.proceed(req)
                // Mảnh bản đồ VietMap: tự giải nén nếu bị nén gzip mà không báo
                val path = req.url.encodedPath
                if (req.url.host.endsWith("vietmap.vn") && resp.isSuccessful && !path.endsWith(".json") &&
                    !path.contains("sprite") && resp.body != null
                ) {
                    val raw = resp.body!!.bytes()
                    var data = raw
                    var rounds = 0
                    while (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte() && rounds < 3) {
                        data = java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
                        rounds++
                    }
                    if (lastTileInfo == null && !path.contains("fonts")) {
                        // Lấy nhiều byte đầu hơn (8→24) + header nén/độ dài máy chủ khai báo (trước khi
                        // interceptor này tự xoá đi ở dưới) — để so khớp xem máy chủ có báo nén gì
                        // không, và dữ liệu nhận được có đủ dài như khai báo hay bị cắt giữa chừng.
                        val head = raw.take(24).joinToString(" ") { String.format("%02x", it) }
                        lastTileInfo = "mảnh ${raw.size}B đầu[$head] gzipx$rounds ct=${resp.header("Content-Type") ?: "?"} " +
                            "ce=${resp.header("Content-Encoding") ?: "none"} cl=${resp.header("Content-Length") ?: "?"}"
                    }
                    // Dữ liệu không phải mảnh bản đồ (vd thông báo lỗi dạng chữ) → coi như mảnh trống
                    val looksText = data.isNotEmpty() && (data[0] == '{'.code.toByte() || data[0] == '<'.code.toByte())
                    val outBytes = if (looksText) ByteArray(0) else data
                    resp = resp.newBuilder()
                        .removeHeader("Content-Encoding")
                        .removeHeader("Content-Length")
                        .code(if (outBytes.isEmpty()) 204 else resp.code)
                        .body(okhttp3.ResponseBody.create(resp.body!!.contentType(), outBytes))
                        .build()
                }
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
    @Volatile var lastTileInfo: String? = null

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
        lastTileInfo = null
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
