package vn.hiep.motonav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Đọc cảnh báo tiếng Việt, tự giảm âm lượng nhạc khi đang nói. */
class Voice(ctx: Context) : TextToSpeech.OnInitListener {
    private val app = ctx.applicationContext
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val tts = TextToSpeech(app, this)
    private var tone: ToneGenerator? = null

    @Volatile var ready = false
        private set
    @Volatile var hasVietnamese = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale("vi", "VN"))
        hasVietnamese = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(1.05f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!tts.isSpeaking) am.abandonAudioFocusRequest(focus)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                am.abandonAudioFocusRequest(focus)
            }
        })
        ready = true
    }

    fun say(text: String, urgent: Boolean = false) {
        if (!ready) return
        am.requestAudioFocus(focus)
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "m" + System.nanoTime())
    }

    fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) {
        }
        tone?.release()
        tone = null
        am.abandonAudioFocusRequest(focus)
    }
}
