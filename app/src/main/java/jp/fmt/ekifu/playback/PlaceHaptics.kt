package jp.fmt.ekifu.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import jp.fmt.ekifu.engine.PlaceConstants
import jp.fmt.ekifu.engine.PlaceEvent

/**
 * 登録地点の外側の円に入ったとき・内側の円に入ったときに、スマホを短く振動させる（ユーザーと決めたこと）。
 * 音に出るのは次の小節からなので、判定が出た瞬間はこちらで知らせる。接近は1回、到着は2回
 */
object PlaceHaptics {

    fun play(context: Context, events: List<PlaceEvent>) {
        val pattern = when {
            events.any { it is PlaceEvent.Arrive } -> PlaceConstants.ARRIVE_VIBRATION_MS
            events.any { it is PlaceEvent.Approach } -> PlaceConstants.APPROACH_VIBRATION_MS
            else -> return
        }
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(pattern, -1)
        // 画面オフ・裏での再生中でも届くよう、通知の振動として出す
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
            )
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
