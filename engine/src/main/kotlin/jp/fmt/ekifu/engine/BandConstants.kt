package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants.db
import jp.fmt.ekifu.engine.sf2.Sf2Reader

/**
 * 曲調「生音フュージョン」（ユーザーと決めたこと：作曲はフュージョンのまま、音だけサウンドフォントの生楽器にする）。
 * 音源は GeneralUser GS v2.0.3（S. Christian Collins）。音量などの値はすべて仕様外・要調整
 */
object BandConstants {
    /** 使う楽器（General MIDI の番号） */
    const val LEAD_BANK = 0
    const val LEAD_PROGRAM = 65 // Alto Sax
    const val KEYS_BANK = 0
    const val KEYS_PROGRAM = 4 // Tine Electric Piano
    const val BASS_BANK = 0
    const val BASS_PROGRAM = 33 // Finger Bass
    const val DRUM_BANK = 128
    const val DRUM_PROGRAM = 0 // Standard

    /** ドラムの鍵盤（General MIDI のドラムの並び） */
    const val KICK_KEY = 36
    const val SNARE_KEY = 38
    const val HAT_KEY = 42
    const val OPEN_HAT_KEY = 46
    const val TOM_HIGH_KEY = 50
    const val TOM_MID_KEY = 47
    const val TOM_LOW_KEY = 45
    const val CRASH_KEY = 49
    val DRUM_KEYS = setOf(KICK_KEY, SNARE_KEY, HAT_KEY, OPEN_HAT_KEY, TOM_HIGH_KEY, TOM_MID_KEY, TOM_LOW_KEY, CRASH_KEY)

    /** サウンドフォントから抜き出すもの */
    val BANK_PICKS = listOf(
        Sf2Reader.Pick(LEAD_BANK, LEAD_PROGRAM),
        Sf2Reader.Pick(KEYS_BANK, KEYS_PROGRAM),
        Sf2Reader.Pick(BASS_BANK, BASS_PROGRAM),
        Sf2Reader.Pick(DRUM_BANK, DRUM_PROGRAM, DRUM_KEYS),
    )

    /** パートごとの音量（シンセ版の BRASS_GAIN などにあたる。シンセ版と同じくらいの大きさにそろえた） */
    /** サックスは中音域が耳につきやすいので、シンセ版のリードより14dB下げる（実機で大きすぎたため2回下げた） */
    val LEAD_GAIN = db(-12.0)
    val KEYS_GAIN = db(-3.0)
    val BASS_GAIN = db(-1.0)
    val KICK_GAIN = db(-4.0)
    val SNARE_GAIN = db(-12.0)
    val TOM_GAIN = db(-6.0)
    val CRASH_GAIN = db(-10.0)
    val HAT_GAIN = db(-10.0)

    /** パートの後ろのローパス（生楽器はシンセ版より明るいまま通す） */
    const val KEYS_LOWPASS_HZ = 9000.0
    const val BASS_LOWPASS_HZ = 4000.0
    const val LEAD_LOWPASS_HZ = 12000.0

    /** 強さ（0〜1）→ 音量。強さの2乗（SF2 の既定の強さ→音量の曲線に近い） */
    const val VELOCITY_EXPONENT = 2.0
    /** リード：前の音を消すまでの時間（つぎの音の立ち上がりと重ねる） */
    const val LEAD_RETRIGGER_FADE_SEC = 0.03
    /** 音程の計算を何フレームごとにやり直すか */
    const val PITCH_UPDATE_FRAMES = 16
}
