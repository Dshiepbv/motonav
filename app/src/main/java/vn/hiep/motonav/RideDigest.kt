package vn.hiep.motonav

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * build-453 (Sếp 15/11): rút gọn nhật ký các chuyến gần nhất thành một file chữ nhỏ (vài chục KB thay vì ~6 MB)
 * để gửi thẳng vào chat cho Claude phân tích (không dùng API, không tốn tiền). Chỉ giữ số liệu và sự kiện quan trọng về màn TFT,
 * kết nối, dẫn đường và điện thoại. Không có vị trí GPS thô.
 */
object RideDigest {
    /** Tạo file tóm tắt rồi mở bảng chia sẻ (Drive, Zalo, chat Claude…). */
    fun share(a: android.app.Activity) {
        android.widget.Toast.makeText(a, "Đang tóm tắt nhật ký…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val txt = try { build(a) } catch (e: Exception) { "" }
            a.runOnUiThread {
                if (txt.isEmpty()) { android.widget.Toast.makeText(a, "Không có nhật ký trong 3 giờ gần nhất", android.widget.Toast.LENGTH_LONG).show(); return@runOnUiThread }
                try {
                    val dir = File(a.filesDir, "trips")
                    dir.listFiles { f -> f.name.startsWith("MotoNav-tomtat-") }?.forEach { it.delete() }
                    val f = File(dir, "MotoNav-tomtat-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt")
                    f.writeText(txt)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, TripLogProvider.uri(a, f))
                        putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    a.startActivity(android.content.Intent.createChooser(send, "Gửi tóm tắt log"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(a, "Không chia sẻ được: ${e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val hm = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val hmm = SimpleDateFormat("HH:mm", Locale.US)
    private val day = SimpleDateFormat("dd/MM HH:mm", Locale.US)

    /** Sự kiện đưa nguyên vào dòng thời gian (bỏ t/te). */
    private val TIMELINE = setOf(
        "start", "tftSock", "tftSession", "tftNavMode", "tftNameTx", "tftInitSkip", "tftReset", "tftResume",
        "tftResumeFresh", "tftPause", "tftDeadReconnect", "tftNoHandshake", "tftWait12Dis", "tftWriteStall",
        "tftBtn", "reroute", "routeForced", "routeHold", "routeShift", "routeErr", "routeProvErr", "arrived",
        "btEvent", "phoneEv", "navStopBtn", "tftFrameStarve", "mapDns", "note"
    )
    private const val MAX_TIMELINE = 400
    private const val MAX_GAPS = 150

    private fun reader(f: File): BufferedReader =
        if (f.name.endsWith(".gz")) java.util.zip.GZIPInputStream(f.inputStream().buffered()).bufferedReader(Charsets.UTF_8)
        else f.bufferedReader(Charsets.UTF_8)

    private fun evName(line: String): String? {
        val i = line.indexOf("\"e\":\"")
        if (i < 0) return null
        val j = line.indexOf('"', i + 5)
        return if (j > i) line.substring(i + 5, j) else null
    }

    private class Minute {
        var gapMs = 0L; var frames = 0; var bytes = 0L; var maxKmh = 0.0; var rx41 = 0
        var acks = 0
    }

    /** Tóm tắt các file nhật ký trong [hours] giờ gần nhất (tối đa 4 file). */
    fun build(ctx: Context, hours: Int = 3): String {
        val cutoff = System.currentTimeMillis() - hours * 3600_000L
        val files = TripLog.files(ctx).filter { it.lastModified() >= cutoff }.take(4).reversed()
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        val minutes = sortedMapOf<Long, Minute>()
        val counts = HashMap<String, Int>()
        val timeline = ArrayList<String>()
        val gaps = ArrayList<String>()
        val ackMs = ArrayList<Int>()
        val wrMs = ArrayList<Int>()
        var lastAck = 0L
        var firstT = 0L; var lastT = 0L
        var gapTotal = 0L; var gap3 = 0
        var phoneFirst: JSONObject? = null; var phoneLast: JSONObject? = null
        var frameKeys: JSONObject? = null
        for (f in files) {
            try {
                reader(f).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val e = evName(line) ?: continue
                        counts[e] = (counts[e] ?: 0) + 1
                        val wanted = e in TIMELINE || e == "tftAck" || e == "tftFrame" || e == "locationUpdate" ||
                            e == "tftRx" || e == "phone" || e == "tft41Gap"
                        if (!wanted) continue
                        val o = try { JSONObject(line) } catch (_: Exception) { continue }
                        val t = o.optLong("t")
                        if (t <= 0) continue
                        if (firstT == 0L) firstT = t
                        lastT = maxOf(lastT, t)
                        val m = minutes.getOrPut(t / 60_000L) { Minute() }
                        when (e) {
                            "tftAck" -> if (o.optBoolean("accepted", true)) {
                                m.acks++
                                if (lastAck > 0 && t - lastAck > 3000) {
                                    val d = t - lastAck
                                    gapTotal += d; gap3++
                                    minutes.getOrPut(lastAck / 60_000L) { Minute() }.gapMs += d
                                    if (gaps.size < MAX_GAPS) gaps.add("${hm.format(Date(lastAck))} ${d / 100 / 10.0}s")
                                }
                                lastAck = t
                                if (o.has("ageMs")) ackMs.add(o.optInt("ageMs"))
                            }
                            "tftFrame" -> {
                                m.frames++; m.bytes += o.optLong("bytes")
                                if (o.has("wrMs")) wrMs.add(o.optInt("wrMs"))
                                if (frameKeys == null) frameKeys = o
                            }
                            "locationUpdate" -> m.maxKmh = maxOf(m.maxKmh, o.optDouble("speedMps", 0.0) * 3.6)
                            "tftRx" -> if (o.optInt("cmd") == 65) m.rx41++
                            "phone" -> { if (phoneFirst == null) phoneFirst = o; phoneLast = o }
                            "tft41Gap" -> if (o.optLong("gapMs") >= 3000 && timeline.size < MAX_TIMELINE)
                                timeline.add("${hm.format(Date(t))} tft41Gap ${o.optLong("gapMs")}ms waitingAck=${o.optBoolean("waitingAck")}")
                            else -> if (timeline.size < MAX_TIMELINE) {
                                o.remove("t"); o.remove("te"); o.remove("e")
                                timeline.add("${hm.format(Date(t))} $e ${o}")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                sb.append("(lỗi đọc ${f.name})\n")
            }
        }
        if (firstT == 0L) return ""
        val spanMs = (lastT - firstT).coerceAtLeast(1)
        fun pct(list: List<Int>, p: Double): Int = if (list.isEmpty()) -1 else list.sorted()[((list.size - 1) * p).toInt()]
        sb.append("TÓM TẮT NHẬT KÝ MOTONAV — ${day.format(Date(firstT))} → ${hmm.format(Date(lastT))} (${spanMs / 60000} phút)\n")
        sb.append("Files: ${files.joinToString { it.name }}\n")
        sb.append("Đứng hình (tổng khoảng trống ACK >3 s / thời gian): ${"%.1f".format(Locale.US, 100.0 * gapTotal / spanMs)}%  số lần: $gap3\n")
        sb.append("ACK ageMs p50/p90/p99: ${pct(ackMs, .5)}/${pct(ackMs, .9)}/${pct(ackMs, .99)}  wrMs p50/p90: ${pct(wrMs, .5)}/${pct(wrMs, .9)}\n")
        frameKeys?.let { fk -> sb.append("Cấu hình khung (mẫu): step=${fk.opt("step")} nmax=${fk.opt("nmax")} q=${fk.opt("q")} pace=${fk.opt("pace")} v=${fk.opt("v")}\n") }
        phoneFirst?.let { sb.append("Điện thoại đầu: $it\n") }
        phoneLast?.let { sb.append("Điện thoại cuối: $it\n") }
        sb.append("\nSỐ SỰ KIỆN:\n")
        counts.entries.sortedByDescending { it.value }.joinTo(sb, ", ") { "${it.key}=${it.value}" }
        sb.append("\n\nTHEO PHÚT (giờ | đứng% | khung | KB tb | 0x41 | km/h max):\n")
        for ((k, m) in minutes) {
            if (m.frames == 0 && m.acks == 0 && m.maxKmh < 1) continue
            sb.append("${hmm.format(Date(k * 60_000L))} | ${(m.gapMs * 100 / 60_000L).coerceAtMost(100)}% | ${m.frames} | ")
            sb.append(if (m.frames > 0) m.bytes / m.frames / 1024 else 0)
            sb.append(" | ${m.rx41} | ${m.maxKmh.toInt()}\n")
        }
        sb.append("\nKHOẢNG TRỐNG ACK >3 s (bắt đầu, độ dài):\n")
        gaps.joinTo(sb, "; ")
        if (gap3 > gaps.size) sb.append(" … (+${gap3 - gaps.size})")
        sb.append("\n\nDÒNG THỜI GIAN SỰ KIỆN:\n")
        timeline.joinTo(sb, "\n")
        if (timeline.size >= MAX_TIMELINE) sb.append("\n… (cắt bớt)")
        return sb.toString()
    }
}
