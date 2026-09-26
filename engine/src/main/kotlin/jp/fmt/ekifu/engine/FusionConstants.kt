package jp.fmt.ekifu.engine

import kotlin.math.pow

/**
 * 曲調「フュージョン」のパラメータ（SPEC.md 12章）。
 * 数値は参照実装 doc/fusion-session-reference.html（Tone.js）から移植した。
 * 参照実装にも仕様書にもない値は「仕様外・要調整」と書く。
 */
object FusionConstants {

    fun db(x: Double) = 10.0.pow(x / 20.0)

    // ---- 基本設定（12.2） ----
    const val STEPS_PER_BAR = 16
    const val BARS_PER_BLOCK = 8
    const val STEPS_PER_BLOCK = STEPS_PER_BAR * BARS_PER_BLOCK
    const val LEAD_MIN_MIDI = 60
    const val LEAD_MAX_MIDI = 88
    /** 太陽：昼の全体ローパス */
    const val DAY_CUTOFF_HZ = 7000.0
    const val TWILIGHT_CUTOFF_HZ = 5000.0
    const val NIGHT_CUTOFF_HZ = 3500.0

    // ---- 和音（12.3） ----
    /** 鍵盤の基準（ルート+48）と、収める範囲 */
    const val KEYS_BASE_OFFSET = 48
    const val KEYS_BASE_MIN = 45
    const val KEYS_BASE_MAX = 54
    /** ベースの根音（ルート+36、41を超えたら−12） */
    const val BASS_BASE_OFFSET = 36
    const val BASS_BASE_MAX = 41

    // ---- 段階6a の音色デモ（固定の進行と熱量） ----
    const val DEMO_BPM = 132.0
    /** ブロックごとに巡回する熱量 */
    val DEMO_HEATS = doubleArrayOf(0.3, 0.6, 0.9)
    const val DEMO_SEED = 20_260_926

    // ---- 伴奏（12.7） ----
    const val KEYS_STAGGER_SEC = 0.007
    const val KEYS_LENGTH_FACTOR = 1.05
    const val BASS_LENGTH_FACTOR = 0.9
    const val GHOST_VELOCITY = 0.14
    const val OPEN_HAT_PROB = 0.3

    // ---- リードの奏法（12.8） ----
    const val GLIDE_SHORT_SEC = 0.012
    const val GLIDE_NORMAL_SEC = 0.035
    const val GLIDE_HIGH_TONE_SEC = 0.14
    const val SCOOP_DELAY_SEC = 0.03
    const val SCOOP_GLIDE_SEC = 0.08
    const val FALL_BEFORE_END_SEC = 0.16
    const val FALL_GLIDE_SEC = 0.2
    const val FALL_SEMITONES = 4
    /** フォールは音の長さがこれを超えるときだけ */
    const val FALL_MIN_SEC = 0.5
    const val VIBRATO_HZ = 6.3
    /** ビブラートの深さ ±(20+15×熱量) セント（仕様外・要調整） */
    const val VIBRATO_BASE_CENTS = 20.0
    const val VIBRATO_HEAT_CENTS = 15.0
    const val VIBRATO_RAMP_MAX_SEC = 0.5
    const val VIBRATO_RAMP_FACTOR = 0.6
    /** ビブラートは音の長さがこれを超えるときだけ */
    const val VIBRATO_MIN_SEC = 0.3
    /** 次の音の前に離す（参照実装 10ms。仕様は3ms以上） */
    const val LEAD_RELEASE_GAP_SEC = 0.01
    const val LEAD_MIN_DURATION_SEC = 0.04
    const val LEAD_MIN_ONSET_GAP_SEC = 0.003
    const val LEAD_LENGTH_FACTOR = 0.95

    // ---- 音色（12.8） ----
    const val LEAD_DETUNE_CENTS = 4.0
    const val LEAD_FILTER_Q = 2.2
    const val LEAD_FILTER_BASE_HZ = 800.0
    const val LEAD_FILTER_HEAT_HZ = 700.0
    const val LEAD_FILTER_OCTAVES = 2.8
    val LEAD_FILTER_ENV = Adsr(0.03, 0.3, 0.65, 0.12)
    val LEAD_AMP_ENV = Adsr(0.018, 0.25, 0.8, 0.12)
    /** リードの後段のローパス（参照実装） */
    const val LEAD_POST_LOWPASS_HZ = 6000.0
    /** 1オクターブ下の矩形波の層（参照実装） */
    const val SUB_FILTER_Q = 0.7
    const val SUB_FILTER_BASE_HZ = 500.0
    const val SUB_FILTER_OCTAVES = 2.0
    val SUB_FILTER_ENV = Adsr(0.03, 0.3, 0.5, 0.12)
    val SUB_AMP_ENV = Adsr(0.02, 0.3, 0.7, 0.12)

    const val BRASS_DETUNE_CENTS = 5.0
    const val BRASS_LOWPASS_HZ = 2600.0
    val BRASS_ENV = Adsr(0.012, 0.5, 0.35, 0.35)
    const val CHORUS_HZ = 1.2
    const val CHORUS_DELAY_SEC = 0.003
    const val CHORUS_DEPTH = 0.35
    /** コーラスの原音と揺らした音の混ぜ方（仕様外・要調整） */
    const val CHORUS_WET = 0.5

    const val BASS_LOWPASS_HZ = 820.0
    val BASS_ENV = Adsr(0.01, 0.3, 0.5, 0.1)

    const val KICK_HZ = 38.0
    const val KICK_OCTAVES = 4.0
    const val KICK_PITCH_DECAY_SEC = 0.035
    const val KICK_DECAY_SEC = 0.32
    const val SNARE_BANDPASS_HZ = 1900.0
    const val SNARE_DECAY_BASE_SEC = 0.1
    const val SNARE_DECAY_VEL_SEC = 0.12
    const val HAT_HIGHPASS_HZ = 7000.0
    const val HAT_DECAY_BASE_SEC = 0.03
    const val HAT_DECAY_VEL_SEC = 0.015
    const val OPEN_HAT_DECAY_SEC = 0.22
    const val HAT_PAN = 0.35
    const val TOM_HIGH_HZ = 190.0
    const val TOM_MID_HZ = 145.0
    const val TOM_LOW_HZ = 108.0
    const val TOM_OCTAVES = 1.5
    const val TOM_PITCH_DECAY_SEC = 0.02
    const val TOM_DECAY_SEC = 0.3
    const val CRASH_HIGHPASS_HZ = 4500.0
    const val CRASH_DECAY_SEC = 1.4
    const val DRUM_ATTACK_SEC = 0.002
    /**
     * Tone.js の減衰（指数で0へ近づく）に合わせ、減衰時間でこの倍率まで下げる（仕様外・要調整）。
     * 0.02 ≒ −34dB
     */
    const val DECAY_TARGET = 0.02

    // ---- 空間系（12.8） ----
    /** ディレイは付点8分 = 16分3つ */
    const val DELAY_STEPS = 3
    const val DELAY_FEEDBACK = 0.3
    const val DELAY_LOWPASS_HZ = 2400.0
    /** テンポの下限（ディレイの最大長を決める） */
    const val MIN_BPM = 100.0
    const val REVERB_RT60_SEC = 2.4

    val LEAD_SEND = Sends(reverb = 0.3, delay = 0.36)
    val BRASS_SEND = Sends(reverb = 0.3, delay = 0.06)
    val BASS_SEND = Sends(reverb = 0.04, delay = 0.0)
    val DRUM_SEND = Sends(reverb = 0.1, delay = 0.0)

    const val BRASS_PAN = -0.3
    const val LEAD_PAN = 0.1

    // ---- 音量（参照実装の dB） ----
    val LEAD_GAIN = db(-8.0)
    val SUB_GAIN = db(-20.0)
    val BRASS_GAIN = db(-12.0)
    val BASS_GAIN = db(-6.0)
    val KICK_GAIN = db(-6.0)
    val SNARE_GAIN = db(-5.0)
    val HAT_GAIN = db(-25.0)
    val TOM_GAIN = db(-8.0)
    val CRASH_GAIN = db(-24.0)
    /** リバーブの戻り（既存の FDN を使うので畳み込みとの差を合わせる。仕様外・要調整） */
    const val REVERB_RETURN = 0.6
    const val DELAY_RETURN = 1.0

    // ---- マスター（12.8） ----
    const val COMP_THRESHOLD_DB = -18.0
    const val COMP_RATIO = 3.0
    const val COMP_ATTACK_SEC = 0.015
    const val COMP_RELEASE_SEC = 0.25
    const val LIMITER_CEILING_DB = -1.0
    const val LIMITER_RELEASE_SEC = 0.1
    /** 癒しと同じくらいの大きさに揃える（仕様外・要調整） */
    const val OUTPUT_GAIN = 1.0

    // ---- 終わり・つなぎ ----
    /** 終わりに鳴らすトニック DM9 */
    const val TONIC_ROOT_PC = 2
    const val ENDING_FADE_SEC = 12.0
    /** バッファ不足のつなぎで和音を伸ばす長さ（仕様外・要調整） */
    const val FILLER_HOLD_SEC = 60.0
}

/** 立ち上がり（直線）・減衰（指数で保持レベルへ）・保持・余韻（指数） */
data class Adsr(val attackSec: Double, val decaySec: Double, val sustain: Double, val releaseSec: Double)

data class Sends(val reverb: Double, val delay: Double)
