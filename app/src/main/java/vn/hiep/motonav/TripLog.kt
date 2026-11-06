package vn.hiep.motonav

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.location.Location
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Nhật ký chuyến đi: ghi GPS, khớp đường, tốc độ giới hạn, dẫn đường, cảnh báo và ghi chú sự cố
 * vào bộ nhớ riêng của app (không hiện trong thư viện/tệp của máy, tự xoá sau 10 chuyến hoặc 7 ngày).
 * Mỗi dòng là một JSON: {"t": thời gian ms, "e": loại sự kiện, ...}
 */
object TripLog {
    private var w: BufferedWriter? = null
    private var file: File? = null
    private var lastFlush = 0L
    private var lines = 0

    private fun dir(ctx: Context) = File(ctx.filesDir, "trips").apply { mkdirs() }

    @Synchronized
    fun start(ctx: Context) {
        if (w != null) return
        cleanup(ctx)
        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".jsonl"
        val f = File(dir(ctx), name)
        file = f
        w = BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8))
        lastFlush = 0L
        lines = 0
        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        ev("start", "app" to (pi?.versionName ?: "?"), "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "android" to android.os.Build.VERSION.SDK_INT)
    }

    @Synchronized
    fun stop() {
        ev("stop")
        try { w?.close() } catch (_: Exception) {}
        w = null
        val raw = file
        if (raw != null && raw.name.endsWith(".jsonl") && raw.exists()) {
            val compressed = File(raw.parentFile, raw.name + ".gz")
            val temp = File(raw.parentFile, compressed.name + ".tmp")
            try {
                GZIPOutputStream(FileOutputStream(temp)).use { gzip ->
                    raw.inputStream().buffered().use { input -> input.copyTo(gzip) }
                }
                if (!temp.renameTo(compressed)) {
                    temp.copyTo(compressed, overwrite = true)
                    temp.delete()
                }
                raw.delete()
                file = compressed
            } catch (_: Exception) {
                temp.delete()
                // Giữ file JSONL gốc nếu nén thất bại; dữ liệu vẫn xuất được.
            }
        }
    }

    /** Ghi một sự kiện. */
    @Synchronized
    fun ev(type: String, vararg kv: Pair<String, Any?>) {
        val out = w ?: return
        try {
            val o = JSONObject()
            o.put("t", System.currentTimeMillis())
            o.put("te", android.os.SystemClock.elapsedRealtime())
            o.put("e", type)
            for ((k, v) in kv) if (v != null) o.put(k, if (v is Double) round(v) else v)
            out.write(o.toString())
            out.newLine()
            lines++
            val now = System.currentTimeMillis()
            if (now - lastFlush > 10_000 || type == "note" ||
                type in setOf("rerouteStart", "routeUpdateComplete", "roadSegmentChanged",
                    "tftLoadingStart", "tftLoadingEnd", "loadingStart", "loadingEnd")) {
                lastFlush = now
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun round(v: Double): Double = if (kotlin.math.abs(v) > 90) Math.round(v * 1e6) / 1e6 else Math.round(v * 1e7) / 1e7

    fun fix(l: Location) {
        // Event tên rõ nghĩa cho diagnostic trace; giữ "fix" để tương thích bộ phân tích log cũ.
        ev(
            "locationUpdate",
            "lat" to l.latitude, "lng" to l.longitude,
            "accuracy" to (if (l.hasAccuracy()) l.accuracy.toDouble() else null),
            "speedMps" to (if (l.hasSpeed()) l.speed.toDouble() else null),
            "bearing" to (if (l.hasBearing()) l.bearing.toDouble() else null),
            "provider" to l.provider,
        )
        ev(
            "fix",
            "lat" to l.latitude, "lon" to l.longitude,
            "acc" to (if (l.hasAccuracy()) l.accuracy.toDouble() else null),
            "spd" to (if (l.hasSpeed()) l.speed.toDouble() else null),
            "brg" to (if (l.hasBearing()) l.bearing.toDouble() else null),
            "pv" to l.provider,
        )
    }

    /** Lưu toàn bộ tuyến đường (để phát lại chuyến đi). */
    fun route(r: Route, reason: String) {
        val sb = StringBuilder()
        for (i in r.lats.indices) {
            if (i > 0) sb.append(';')
            sb.append(String.format(Locale.US, "%.6f,%.6f", r.lats[i], r.lons[i]))
        }
        ev("route", "why" to reason, "turns" to r.steps.count { it.sign != 0 }, "uturnAt" to RoutePick.uturnAt(r, 3000.0).toInt(), "dist" to r.distance, "timeMs" to r.timeMs, "pts" to sb.toString(),
            "steps" to r.steps.joinToString("|") { "${it.sign}:${it.from}:${it.street}" })
    }

    /**
     * Chép một log vào file TXT. Giữ khóa trong lúc flush/đọc để log đang chạy không bị
     * ghi đồng thời; log mới dùng JSONL thuần, log cũ vẫn giải nén GZIP.
     */
    @Synchronized
    fun appendToExport(source: File, writer: BufferedWriter) {
        try { if (file?.canonicalPath == source.canonicalPath) w?.flush() } catch (_: Exception) {}
        if (source.name.endsWith(".jsonl.gz")) {
            try {
                java.util.zip.GZIPInputStream(source.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        writer.append(line)
                        writer.newLine()
                    }
                }
            } catch (e: java.io.EOFException) {
                writer.append(JSONObject().put("e", "export_warning").put("source", source.name)
                    .put("message", "Legacy GZIP log ended before its trailer; decoded records before EOF were retained.").toString())
                writer.newLine()
            } catch (e: java.util.zip.ZipException) {
                writer.append(JSONObject().put("e", "export_warning").put("source", source.name)
                    .put("message", "Legacy GZIP log was incomplete or damaged; decoded records before the error were retained.").toString())
                writer.newLine()
            }
        } else {
            source.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    writer.append(line)
                    writer.newLine()
                }
            }
        }
    }

    /** Các file nhật ký, mới nhất trước. */
    @Synchronized
    fun files(ctx: Context): List<File> {
        try { w?.flush() } catch (_: Exception) {}
        return dir(ctx).listFiles { f ->
            f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl.gz")
        }?.sortedByDescending { it.name } ?: emptyList()
    }

    private fun cleanup(ctx: Context) {
        val list = dir(ctx).listFiles { f ->
            f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl.gz")
        }?.sortedByDescending { it.name } ?: return
        val old = System.currentTimeMillis() - 7 * 24 * 3600_000L
        list.forEachIndexed { i, f -> if (i >= 10 || f.lastModified() < old) f.delete() }
    }
}

/** Cho phép gửi file nhật ký sang Zalo/Drive/Gmail (không cần thư viện ngoài). */
class TripLogProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(File(ctx.filesDir, "trips"), name)
        return if (f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun uri(ctx: Context, f: File): Uri = Uri.parse("content://${ctx.packageName}.triplog/${f.name}")
    }
}
