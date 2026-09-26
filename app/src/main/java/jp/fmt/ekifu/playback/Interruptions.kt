package jp.fmt.ekifu.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 割り込みへの対応（3章・11章）。
 * - 再生中はオーディオフォーカスを持つ。電話の着信・他アプリの再生でフォーカスを失ったら一時停止する
 *   （ユーザーと決めたとおり、自動では再開しない）
 * - 通知音など「音量を下げてほしい」割り込み（ダッキング）では止めず、Android に音量を下げてもらって続ける
 * - イヤホンが抜けたら（ACTION_AUDIO_BECOMING_NOISY）一時停止する
 * onInterrupted はメインスレッドで呼ばれる。
 */
class Interruptions(context: Context, private val onInterrupted: () -> Unit) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var held = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        // 通知音などでは、Android が自動で音量を下げ、鳴り終われば戻す（こちらでは何もしない）
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> interrupted()
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK（通知音など）は止めない。
                // 戻ってきた（GAIN）ときも、一時停止していれば自動では再開しない
                else -> Unit
            }
        }, handler)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupted()
        }
    }

    /** 再生を始める前に呼ぶ。フォーカスが取れなければ（通話中など）false */
    fun acquire(): Boolean {
        if (held) return true
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        held = true
        return true
    }

    /** 一時停止・停止したら手放す */
    fun release() {
        if (!held) return
        held = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }

    private fun interrupted() {
        if (held) onInterrupted()
    }
}
