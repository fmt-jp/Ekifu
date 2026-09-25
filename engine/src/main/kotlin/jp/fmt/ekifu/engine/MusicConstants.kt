package jp.fmt.ekifu.engine

/**
 * 音楽生成のパラメータをすべてここに集約する（SPEC 4章・5章の初期値）。
 * 調整はこのファイルだけで行い、他のコードには数値を直接書かない。
 */
object MusicConstants {
    // ---- 出力フォーマット ----
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2

    // ---- 基本設定 ----
    /** ルート音 F3。 */
    const val ROOT_MIDI = 53
    const val BEAT_SECONDS = 0.9
    const val BEATS_PER_CHORD = 8

    /** メロディ音域（F メジャー・ペンタトニック）。 */
    const val MELODY_MIN_MIDI = 65
    const val MELODY_MAX_MIDI = 86

    /** F, G, A, C, D のピッチクラス。 */
    val PENTATONIC_PITCH_CLASSES = setOf(5, 7, 9, 0, 2)

    /** メロディの最初の基準音（C5）。 */
    const val MELODY_START_MIDI = 72

    /** 直前の音からの最大跳躍（半音）。 */
    const val MELODY_MAX_LEAP = 7

    /** 和音外の音を許可する確率。 */
    const val NON_CHORD_TONE_PROBABILITY = 0.35

    /** 裏拍（0.5拍）の発音確率の倍率。 */
    const val OFFBEAT_PROBABILITY_FACTOR = 0.6
    const val OFFBEAT_POSITION = 0.5

    // ---- フェーズ境界（p = 進み具合） ----
    const val PHASE_MIDDLE_START = 0.2
    const val PHASE_PRE_ARRIVAL_START = 0.75
    const val PHASE_ARRIVAL_START = 1.0

    // ---- フェーズごとの 1 拍あたりのメロディ発音確率 ----
    const val MELODY_PROBABILITY_DEPARTURE = 0.18
    const val MELODY_PROBABILITY_MIDDLE = 0.55
    const val MELODY_PROBABILITY_PRE_ARRIVAL = 0.38
    const val MELODY_PROBABILITY_ARRIVAL = 0.0

    // ---- 到着 ----
    /** 到着時に I を鳴らし続ける長さ（拍）。フェードアウトより長くする。 */
    const val ARRIVAL_HOLD_BEATS = 16.0
    const val ARRIVAL_FADE_SECONDS = 12.0

    // ---- メロディのばらつき ----
    const val PLUCK_VELOCITY_MIN = 0.6
    const val PLUCK_VELOCITY_RANGE = 0.4
    /** プラックの左右定位の最大幅（-1..1 のうちの割合）。 */
    const val PLUCK_PAN_WIDTH = 0.7
    /** パッドの構成音を左右に広げる幅。 */
    const val PAD_PAN_SPREAD = 0.3

    // ---- 駅のメロディ ----
    const val STATION_MOTIF_MIN_NOTES = 3
    const val STATION_MOTIF_MAX_NOTES = 4
    /** モチーフの最初の音を選ぶ範囲（ベルが耳につきすぎない中音域）。 */
    const val STATION_MOTIF_START_MIN_MIDI = 69
    const val STATION_MOTIF_START_MAX_MIDI = 81
    /** 終点のモチーフの最後の音のピッチクラス（F）。 */
    const val TONIC_PITCH_CLASS = 5
    /** 駅通過時のベルの間隔（拍）。 */
    const val STATION_BELL_STEP_BEATS = 0.5
    const val STATION_BELL_VELOCITY = 1.0
    /** 道中フェーズでモチーフを再現する間隔（拍）。 */
    const val MOTIF_REPRISE_INTERVAL_BEATS = 24
    const val MOTIF_REPRISE_STEP_BEATS = 0.5
    const val MOTIF_REPRISE_VELOCITY = 0.35

    // ---- 地上 / 地下 ----
    const val MASTER_LOWPASS_ABOVE_HZ = 6000.0
    const val MASTER_LOWPASS_UNDERGROUND_HZ = 850.0
    const val MASTER_LOWPASS_Q = 0.707
    const val REVERB_MIX_ABOVE = 0.35
    const val REVERB_MIX_UNDERGROUND = 0.7
    const val UNDERGROUND_OCTAVE_SHIFT = -12
    const val UNDERGROUND_PROBABILITY_FACTOR = 0.7
    /** 地上/地下の変化の時定数（秒）。 */
    const val UNDERGROUND_SMOOTHING_SECONDS = 2.5

    // ---- ルートなしモード（位置情報だけで作る） ----
    /** 場所ごとのモチーフを決める区画の一辺（メートル）。 */
    const val PLACE_CELL_METERS = 500.0
    /** 移動状態を判定するのに最低限あける、位置の間隔（秒）。 */
    const val MOTION_MIN_SAMPLE_SECONDS = 60.0
    /** これ未満の速さ（m/s）なら止まっているとみなす（約 1.8km/h）。 */
    const val MOTION_WALK_MIN_SPEED = 0.5
    /** これ以上の速さ（m/s）なら乗り物とみなす（約 11km/h）。 */
    const val MOTION_RIDE_MIN_SPEED = 3.0
    /** 時間帯（時）ごとの音のこもり具合。間は直線でつなぐ。夜ほどこもり、残響が深くなる。 */
    val DARKNESS_HOURS = doubleArrayOf(0.0, 5.0, 7.0, 10.0, 16.0, 18.0, 20.0, 22.0, 24.0)
    val DARKNESS_VALUES = doubleArrayOf(0.55, 0.5, 0.15, 0.0, 0.0, 0.15, 0.35, 0.5, 0.55)

    // ---- 音色：パッド ----
    const val PAD_DETUNE_CENTS = 6.0
    const val PAD_LOWPASS_HZ = 1400.0
    const val PAD_LOWPASS_Q = 0.707
    const val PAD_ATTACK_SECONDS = 2.5
    const val PAD_RELEASE_SECONDS = 3.5
    const val PAD_GAIN = 0.075
    const val PAD_DELAY_SEND = 0.0
    const val PAD_REVERB_SEND = 0.6

    // ---- 音色：ベース ----
    const val BASS_OCTAVE_SHIFT = -12
    const val BASS_ATTACK_SECONDS = 3.0
    const val BASS_RELEASE_SECONDS = 3.5
    const val BASS_GAIN = 0.16
    const val BASS_DELAY_SEND = 0.0
    const val BASS_REVERB_SEND = 0.2

    // ---- 音色：プラック ----
    const val PLUCK_ATTACK_SECONDS = 0.004
    const val PLUCK_DECAY_SECONDS = 2.2
    const val PLUCK_GAIN = 0.16
    const val PLUCK_DELAY_SEND = 0.35
    const val PLUCK_REVERB_SEND = 0.5

    // ---- 音色：ベル ----
    val BELL_PARTIAL_RATIOS = doubleArrayOf(1.0, 2.76, 5.4)
    val BELL_PARTIAL_AMPS = doubleArrayOf(1.0, 0.45, 0.22)
    /** 各倍音の減衰時間（秒）。基音が仕様の 4 秒、高次ほど早く消える。 */
    val BELL_PARTIAL_DECAY_SECONDS = doubleArrayOf(4.0, 2.6, 1.5)
    const val BELL_ATTACK_SECONDS = 0.002
    const val BELL_GAIN = 0.13
    const val BELL_DELAY_SEND = 0.3
    const val BELL_REVERB_SEND = 0.6

    /** 減衰の終点とみなす振幅（-60dB）。 */
    const val DECAY_FLOOR = 0.001
    /** これ以下になったボイスは破棄する。 */
    const val VOICE_SILENCE = 0.0001

    // ---- ディレイ ----
    const val DELAY_SECONDS = 0.675
    const val DELAY_FEEDBACK = 0.32
    const val DELAY_LOWPASS_HZ = 2200.0
    const val DELAY_WET = 0.5

    // ---- リバーブ ----
    const val REVERB_DECAY_SECONDS = 3.5
    const val REVERB_DAMPING_HZ = 5000.0
    const val REVERB_OUTPUT_GAIN = 0.35

    // ---- コンプレッサー ----
    const val COMPRESSOR_THRESHOLD_DB = -16.0
    const val COMPRESSOR_RATIO = 3.0
    const val COMPRESSOR_ATTACK_SECONDS = 0.01
    const val COMPRESSOR_RELEASE_SECONDS = 0.25
    const val COMPRESSOR_MAKEUP_DB = 4.0

    // ---- 出力 ----
    const val MASTER_GAIN = 0.9
    /** 合成の内部ブロック長（サンプル）。イベント発音とパラメータ更新の粒度。 */
    const val CONTROL_BLOCK_FRAMES = 32
}
