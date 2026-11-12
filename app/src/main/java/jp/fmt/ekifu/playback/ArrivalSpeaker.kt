package jp.fmt.ekifu.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 到着の読み上げ（設定の「到着の知らせ方」）。Android の TextToSpeech で、端末内の日本語の声だけを使う
 * （通信を使う声は選ばない。再生中は通信しない方針のため）。日本語の声がなければ [ready] は false のまま。
 * 読み上げ用に音声フォーカスは取らない（取ると自分の曲が一時停止してしまう）。曲の音量は [onSpeaking] で下げてもらう。
 * メインスレッドで作る。
 */
class ArrivalSpeaker(
    context: Context,
    private val onReady: (Boolean) -> Unit = {},
    private val onSpeaking: (Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var released = false

    /** 日本語の端末内の声が使える */
    @Volatile var ready = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (released) return@post
                ready = status == TextToSpeech.SUCCESS && setUp()
                if (!ready) Log.w(TAG, "日本語の読み上げが使えない（status=$status）")
                onReady(ready)
            }
        }
    }

    private fun setUp(): Boolean {
        val t = tts ?: return false
        return try {
            if (t.isLanguageAvailable(Locale.JAPAN) < TextToSpeech.LANG_AVAILABLE) return false
            t.language = Locale.JAPAN
            // 端末内の日本語の声（入っているもの）から、いちばん質のよいもの
            val voice = t.voices.orEmpty()
                .filter { v ->
                    v.locale.language == Locale.JAPANESE.language && !v.isNetworkConnectionRequired &&
                        v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                .maxByOrNull { it.quality } ?: return false
            t.voice = voice
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = onSpeaking(true)
                override fun onDone(utteranceId: String?) = onSpeaking(false)
                @Deprecated("API 21 より前の形", ReplaceWith("onError(utteranceId, errorCode)"))
                override fun onError(utteranceId: String?) = onSpeaking(false)
                override fun onError(utteranceId: String?, errorCode: Int) = onSpeaking(false)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = onSpeaking(false)
            })
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "読み上げの準備に失敗", e)
            false
        }
    }

    /** 読む（前に読んでいたものは止める）。使えなければ false */
    fun speak(text: String): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        return t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.SUCCESS
    }

    fun release() {
        if (released) return
        released = true
        ready = false
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        onSpeaking(false)
    }

    companion object {
        private const val TAG = "ekifu"
        private const val UTTERANCE_ID = "ekifu-arrival"
        const val UNAVAILABLE_MESSAGE =
            "日本語の読み上げが使えません。Android の設定「テキスト読み上げ」で日本語の音声データを入れてください。到着はベルで知らせます。"

        /** 読む文言（ユーザーと決めたこと：「〇〇に着きました」） */
        fun sentence(name: String) = "${name}に着きました"

        /** 設定画面・地点の編集画面の「読み上げを試す」。読み終わったら片付ける。使えなければ onResult(false) */
        fun preview(context: Context, text: String, onResult: (Boolean) -> Unit) {
            var speaker: ArrivalSpeaker? = null
            speaker = ArrivalSpeaker(
                context,
                onReady = { ok ->
                    onResult(ok)
                    if (!ok || speaker?.speak(text) != true) speaker?.release()
                },
                onSpeaking = { speaking ->
                    if (!speaking) Handler(Looper.getMainLooper()).post { speaker?.release() }
                },
            )
        }
    }
}
