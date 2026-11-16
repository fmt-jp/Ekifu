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

    // ---- 曲の組み立て・登録地点（段階8b） ----
    /** 地点の判定が来たら、鳴っている和音をこの秒数で切り上げて次の和音（演出）へ */
    const val REACT_SEC = 1.5
    /** 区切り（道中20分ごと）：パッドを引いてドローンと粒だけにする和音の数 */
    const val INTERLUDE_CHORDS = 2
    /** ベルの空間への送り（ふつう）と、遠くに聞かせるとき */
    const val BELL_SEND = 0.85
    const val BELL_FAR_SEND = 1.6
    /** テーマ・モチーフのベルの音の間（秒） */
    const val THEME_GAP_SEC = 0.9
    /** マスが変わったときに場所のモチーフを鳴らすベルの音量 */
    val MOTIF_BELL_GAIN = db(-16.0)
    /** 接近：ベルのテーマは遠く（小さく）から始めて、和音ごとにこれだけ近く（大きく）。上限あり */
    val APPROACH_BELL_START = db(-24.0)
    const val APPROACH_BELL_STEP_DB = 3.0
    val APPROACH_BELL_MAX = db(-10.0)
    /** 接近：この数の和音のあとからピアノもテーマを弾く */
    const val APPROACH_PIANO_FROM = 2
    /** 到着：合図の和音の長さ（パッドを引いてベルの3音）、テーマの和音、着地の和音（和音の長さの倍率） */
    const val ARRIVE_CUE_SEC = 4.0
    const val ARRIVE_LAND_FACTOR = 1.6
    /** 到着の合図：ベルの上りの3音（F・A・C）と間 */
    val ARRIVAL_CHIME_MIDI = intArrayOf(77, 81, 84)
    const val ARRIVAL_CHIME_GAP_SEC = 0.35
    val ARRIVAL_CHIME_GAIN = db(-6.0)
    val ARRIVE_BELL_GAIN = db(-9.0)
    /** 着地の和音は音を足して大きく広げる */
    const val LAND_PAD_EXTRA = 1
    /** 滞在：粒を減らす割合、ベルでテーマを静かに鳴らす間隔（和音の数。到着直後の和音は鳴らさない） */
    const val STAY_GRAIN_FACTOR = 0.35
    const val STAY_BELL_EVERY_CHORDS = 3
    val STAY_BELL_GAIN = db(-20.0)
    /** 離れる：テーマを遠ざかるように（1音ごとにこれだけ小さく） */
    val LEAVE_BELL_GAIN = db(-12.0)
    const val LEAVE_FADE_DB_PER_NOTE = 4.0

    // ---- メロディ（場所のモチーフをピアノでゆっくり） ----
    /** モチーフの音の間（秒） */
    const val PIANO_NOTE_GAP_SEC = 1.4
    /** 何和音ごとにピアノを弾くか */
    const val PIANO_EVERY_CHORDS = 2

    /** デモ再生のシード */
    const val DEMO_SEED = 20_261_116
}
