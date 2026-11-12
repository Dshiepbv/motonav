package vn.hiep.motonav

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * build-409: log 12/11 08:23 (và 6/11 tối) — điện thoại báo "Unable to resolve host tiles.openfreemap.org" dù 4G vẫn chạy
 * → không tải được mảnh bản đồ → TFT mất bản đồ màu. Tra tên miền theo thứ tự: DNS của máy → địa chỉ đã tra được lần trước
 * (nhớ trong bộ nhớ, 24 h) → hỏi DNS qua HTTPS thẳng vào IP 1.1.1.1 / 8.8.8.8 (không cần DNS của nhà mạng).
 */
object MapDns : Dns {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val doh by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    }
    @Volatile private var lastLogAt = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val r = Dns.SYSTEM.lookup(hostname)
            if (r.isNotEmpty()) { cache[hostname] = System.currentTimeMillis() to r; return r }
        } catch (_: UnknownHostException) {}
        cache[hostname]?.let { (at, ips) -> if (System.currentTimeMillis() - at < 24 * 3600_000L) { note(hostname, "cache"); return ips } }
        for (url in listOf("https://1.1.1.1/dns-query?name=$hostname&type=A", "https://8.8.8.8/resolve?name=$hostname&type=A")) {
            try {
                doh.newCall(Request.Builder().url(url).header("accept", "application/dns-json").build()).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val ans = JSONObject(body).optJSONArray("Answer") ?: return@use
                    val ips = ArrayList<InetAddress>()
                    for (i in 0 until ans.length()) {
                        val a = ans.getJSONObject(i)
                        if (a.optInt("type") == 1) ips.add(InetAddress.getByName(a.getString("data")))
                    }
                    if (ips.isNotEmpty()) { cache[hostname] = System.currentTimeMillis() to ips; note(hostname, "doh"); return ips }
                }
            } catch (_: Exception) {}
        }
        throw UnknownHostException("MapDns: không tra được $hostname")
    }

    private fun note(host: String, via: String) {
        val now = System.currentTimeMillis()
        if (now - lastLogAt < 60_000L) return
        lastLogAt = now
        try { TripLog.ev("mapDns", "host" to host, "via" to via) } catch (_: Exception) {}
    }
}
