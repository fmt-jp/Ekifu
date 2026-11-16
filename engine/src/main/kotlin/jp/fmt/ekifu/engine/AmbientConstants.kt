package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants.db

/**
 * 曲調「アンビエント」（段階8。ユーザーと決めたこと：自前シンセの長いパッド・持続音（ドローン）を中心に、
 * 音源のピアノを余韻たっぷりで少しだけ。深いリバーブ。拍はない。
 * 速さで和音が変わる速さと音の粒の多さ、太陽で音域と明るさ、なじみ度で和音のさまよい方を変える）。
 * 値はすべて仕様外・要調整
 */
object AmbientConstants {

    // ---- 時間（拍はないので秒で数える） ----
    /** 1つの和音を伸ばす長さ（速さ） */
    fun chordSec(speed: Speed): Double = when (speed) {
        Speed.STILL -> 18.0
        Speed.WALK -> 12.0
        Speed.VEHICLE -> 8.0
    }

    /** 音の粒（短いきらめき）の1秒あたりの数（速さ） */
    fun grainsPerSec(speed: Speed): Double = when (speed) {
        Speed.STILL -> 0.12
        Speed.WALK -> 0.45
        Speed.VEHICLE -> 1.3
    }

    // ---- 太陽：音域と明るさ ----
    /** 全体ローパス */
    fun brightnessHz(sun: SunLevel): Double = when (sun) {
        SunLevel.DAY -> 6500.0
        SunLevel.TWILIGHT -> 4000.0
        SunLevel.NIGHT -> 2200.0
    }

    /** パッドとピアノ・粒を移す半音（昼は高め、夜は低め） */
    fun registerShift(sun: SunLevel): Int = when (sun) {
        SunLevel.DAY -> 0
        SunLevel.TWILIGHT -> -2
        SunLevel.NIGHT -> -5
    }

    /** パッドのフィルターの基準（太陽） */
    fun padCutoffHz(sun: SunLevel): Double = when (sun) {
        SunLevel.DAY -> 2400.0
        SunLevel.TWILIGHT -> 1600.0
        SunLevel.NIGHT -> 1000.0
    }

    // ---- 音域 ----
    /** ドローン：主音 F の低い音（F1 と F2） */
    const val DRONE_LOW_MIDI = 29
    const val DRONE_MIDI = 41
    /** パッドの和音を置く範囲 */
    const val PAD_MIN_MIDI = 50
    const val PAD_MAX_MIDI = 74
    /** 粒とピアノの音域 */
    const val GRAIN_MIN_MIDI = 76
    const val GRAIN_MAX_MIDI = 96
    const val PIANO_MIN_MIDI = 62
    const val PIANO_MAX_MIDI = 86

    // ---- 音色 ----
    /** パッド：立ち上がりと余韻（和音の長さに対する割合と秒） */
    const val PAD_ATTACK_FACTOR = 0.4
    const val PAD_RELEASE_SEC = 7.0
    /** パッドの3本の波のずれ（セント）と、フィルターのゆっくりした揺れ */
    const val PAD_DETUNE_CENTS = 7.0
    const val PAD_FILTER_LFO_HZ = 0.07
    const val PAD_FILTER_LFO_DEPTH = 0.45
    /** ドローン：ゆっくり膨らむ */
    const val DRONE_ATTACK_SEC = 6.0
    const val DRONE_RELEASE_SEC = 9.0
    const val DRONE_CUTOFF_HZ = 600.0
    /** 粒：やわらかいサイン波の短い音 */
    const val GRAIN_ATTACK_SEC = 0.01
    const val GRAIN_DECAY_SEC = 1.8
    /** ピアノの音の長さ（ペダルを踏んだまま） */
    const val PIANO_HOLD_SEC = 6.0

    // ---- 音量 ----
    val PAD_GAIN = db(-17.0)
    val DRONE_GAIN = db(-15.0)
    val GRAIN_GAIN = db(-24.0)
    val PIANO_GAIN = db(-6.0)
    const val PIANO_VELOCITY = 0.5

    // ---- 空間 ----
    /** 深いリバーブ（残響7秒）と長いディレイ */
    const val REVERB_RT60_SEC = 7.0
    const val REVERB_SEND = 0.55
    const val REVERB_RETURN = 0.9
    const val DELAY_SEC = 0.9
    const val DELAY_FEEDBACK = 0.45
    const val DELAY_LOWPASS_HZ = 3000.0
    const val DELAY_SEND = 0.25
    const val DELAY_RETURN = 0.6
    /** 粒とピアノは空間に多めに送る */
    const val GRAIN_EXTRA_SEND = 0.3
    /** 全体の音量（癒し・フュージョンと同じくらいの大きさにそろえる） */
    const val OUTPUT_GAIN = 0.82
    const val COMP_THRESHOLD_DB = -16.0
    const val COMP_RATIO = 2.0
    const val COMP_ATTACK_SEC = 0.05
    const val COMP_RELEASE_SEC = 0.8

    // ---- メロディ（段階8aの試聴：場所のモチーフをピアノでゆっくり） ----
    /** モチーフの音の間（秒） */
    const val PIANO_NOTE_GAP_SEC = 1.4
    /** 何和音ごとにピアノを弾くか */
    const val PIANO_EVERY_CHORDS = 2

    /** 試聴（音色デモ）のシードと、1つの場面を鳴らす和音の数 */
    const val DEMO_SEED = 20_261_116
    const val PREVIEW_CHORDS_PER_SCENE = 3
}
