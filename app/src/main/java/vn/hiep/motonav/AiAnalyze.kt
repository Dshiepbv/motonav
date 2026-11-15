package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * build-452 (Sếp 15/11): "AI phân tích chuyến đi". App rút gọn nhật ký (RideDigest) rồi gửi tới máy chủ trung gian
 * của Sếp (Cloudflare Worker, thư mục worker/ trong repo). Máy chủ giữ khoá Anthropic và gọi Claude, trả về bản
 * chẩn đoán tiếng Việt. Kết quả lưu ở filesDir/ai/ và hiện trong hộp thoại.
 */
object AiAnalyze {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).callTimeout(330, TimeUnit.SECONDS).build()
    }

    fun open(a: Activity) {
        val p = Prefs(a)
        if (p.aiUrl.isEmpty() || p.aiToken.isEmpty()) { setup(a); return }
        val last = File(a.filesDir, "ai").listFiles()?.maxByOrNull { it.name }
        val items = mutableListOf("Phân tích 3 giờ gần nhất")
        if (last != null) items.add("Xem kết quả lần trước")
        items.add("Cài máy chủ AI")
        AlertDialog.Builder(a).setTitle("AI phân tích chuyến đi")
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Phân tích 3 giờ gần nhất" -> run(a, null)
                    "Xem kết quả lần trước" -> show(a, last!!.readText())
                    else -> setup(a)
                }
            }.show()
    }

    private fun setup(a: Activity) {
        val p = Prefs(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val url = EditText(a).apply { hint = "https://….workers.dev"; setText(p.aiUrl); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val tok = EditText(a).apply { hint = "Mã app (APP_TOKEN)"; setText(p.aiToken); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(url); box.addView(tok)
        AlertDialog.Builder(a).setTitle("Cài máy chủ AI").setView(box)
            .setPositiveButton("Lưu") { _, _ -> p.aiUrl = url.text.toString(); p.aiToken = tok.text.toString() }
            .setNegativeButton("Huỷ", null).show()
    }

    /** [question]: câu hỏi thêm của Sếp (tuỳ chọn). */
    fun run(a: Activity, question: String?) {
        val p = Prefs(a)
        val wait = AlertDialog.Builder(a).setTitle("AI đang phân tích…")
            .setMessage("Đang rút gọn nhật ký và gửi cho Claude. Thường mất 30–120 giây.").setCancelable(false).show()
        Thread {
            val out: String = try {
                val digest = RideDigest.build(a)
                if (digest.isEmpty()) "Không có nhật ký trong 3 giờ gần nhất."
                else {
                    val body = JSONObject().put("digest", digest).put("app", Updater.currentVersion(a))
                    if (!question.isNullOrBlank()) body.put("question", question)
                    val req = Request.Builder().url(p.aiUrl.trimEnd('/') + "/analyze")
                        .header("X-App-Token", p.aiToken)
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    http.newCall(req).execute().use { r ->
                        val txt = r.body?.string() ?: ""
                        if (!r.isSuccessful) "Lỗi máy chủ ${r.code}: ${txt.take(400)}"
                        else JSONObject(txt).optString("text", txt)
                    }
                }
            } catch (e: Exception) {
                "Không gửi được: ${e.javaClass.simpleName} ${e.message ?: ""}"
            }
            try {
                val dir = File(a.filesDir, "ai").apply { mkdirs() }
                File(dir, "ai-${System.currentTimeMillis()}.txt").writeText(out)
                dir.listFiles()?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
            } catch (_: Exception) {}
            try { TripLog.ev("aiAnalyze", "len" to out.length) } catch (_: Exception) {}
            a.runOnUiThread {
                try { wait.dismiss() } catch (_: Exception) {}
                if (!a.isFinishing) show(a, out)
            }
        }.start()
    }

    private fun show(a: Activity, text: String) {
        val tv = TextView(a).apply { setText(text); setTextIsSelectable(true); textSize = 14f; setPadding(48, 24, 48, 24) }
        val ask = EditText(a)
        AlertDialog.Builder(a).setTitle("Kết quả AI").setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Hỏi thêm") { _, _ ->
                AlertDialog.Builder(a).setTitle("Hỏi AI về chuyến này").setView(ask)
                    .setPositiveButton("Gửi") { _, _ -> run(a, ask.text.toString()) }
                    .setNegativeButton("Huỷ", null).show()
            }
            .setNegativeButton("Chia sẻ") { _, _ ->
                try {
                    a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }, "Chia sẻ kết quả AI"))
                } catch (_: Exception) { Toast.makeText(a, "Không mở được chia sẻ", Toast.LENGTH_SHORT).show() }
            }.show()
    }
}
