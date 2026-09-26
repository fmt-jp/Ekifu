package jp.fmt.ekifu.engine

/**
 * 音楽生成のパラメータをすべてここに集約する（SPEC.md 4〜6章）。
 * 仕様書に数値のないもの（音量バランス、リバーブの構造、コンプレッサーなど）は
 * 「仕様外・要調整」と注記している。耳で聴いて調整する前提の初期値。
 */
object MusicConstants {

    // ---- 出力 ----
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2

    // ---- 調・拍 ----
    /** ルート音 F3 */
    const val ROOT_MIDI = 53
    /** 基準の1拍（約67BPM） */
    const val BASE_BEAT_SEC = 0.9
    /** 和音を切り替える拍数 */
    const val CHORD_BEATS = 8
    /** 到着フェーズで I を長く鳴らす拍数（仕様外・要調整：「I を長く」を和音2つぶんとした） */
    const val ARRIVE_CHORD_BEATS = 16
    /** 拍の裏（0.5拍）での発音確率の倍率 */
    const val OFFBEAT_PROB_FACTOR = 0.6

    // ---- メロディ ----
    const val MELODY_MIN_MIDI = 65
    const val MELODY_MAX_MIDI = 86
    /** 明るい（bright）地点でメロディ音域を上に広げる幅 */
    const val BRIGHT_MELODY_EXTRA_SEMITONES = 12
    /** Fメジャー・ペンタトニック（F, G, A, C, D）のピッチクラス */
    val PENTATONIC_PITCH_CLASSES = intArrayOf(5, 7, 9, 0, 2)
    /** 直前の音からの最大跳躍（半音） */
    const val MELODY_MAX_LEAP = 7
    /** 和音外の音を許可する確率 */
    const val NON_CHORD_TONE_PROB = 0.35
    /** プラックの音量のばらつき（最小〜1.0）（仕様外・要調整） */
    const val MELODY_VELOCITY_MIN = 0.6
    /** プラックの左右定位の幅（-1〜1）（仕様外・要調整） */
    const val MELODY_PAN_WIDTH = 0.7

    // ---- フェーズごとの発音確率（道中は場面で決まる） ----
    const val PROB_START = 0.18
    const val PROB_INTERLUDE = 0.38
    const val PROB_APPROACH = 0.38
    const val PROB_ARRIVE = 0.2
    const val PROB_STAY = 0.2

    // ---- フェーズの長さ ----
    /** 始まりの長さ */
    const val START_PHASE_SEC = 60.0
    /** 道中がこれだけ続くごとに区切りを1回入れる */
    const val INTERLUDE_EVERY_SEC = 20 * 60.0
    /** 終わりのフェードアウト */
    const val ENDING_FADE_SEC = 12.0

    // ---- 移り変わり ----
    /** 場面の切り替えでテンポ・発音確率・フィルターを移す拍数 */
    const val SCENE_TRANSITION_BEATS = 8
    /** 地点の雰囲気へ移る／道中へ戻る拍数 */
    const val MOOD_TRANSITION_BEATS = 16

    // ---- 移動の速さ（5章） ----
    const val STILL_BEAT_SEC = 1.1
    const val STILL_PROB = 0.2
    const val WALK_BEAT_SEC = 0.9
    const val WALK_PROB = 0.4
    const val VEHICLE_BEAT_SEC = 0.8
    const val VEHICLE_PROB = 0.55

    // ---- 場面の決め方（5章） ----
    /** 場面の区切り（位置を確かめる間隔） */
    const val SCENE_INTERVAL_SEC = 5 * 60.0
    /** これより古い位置は「取れなかった」とみなす（仕様外・要調整） */
    const val LOCATION_MAX_AGE_SEC = 6 * 60.0
    /** マスの大きさ（ジオハッシュの桁数。6桁 ≒ 1.2km × 0.6km） */
    const val GEOHASH_PRECISION = 6
    /** 静止とみなす速さ（km/h 未満） */
    const val STILL_MAX_KMH = 0.5
    /** 徒歩とみなす速さ（km/h まで）。これより速ければ乗り物 */
    const val WALK_MAX_KMH = 7.0
    /** なじみの場所とみなす訪問日数 */
    const val FAMILIAR_DAYS = 10
    /** 夜：太陽高度がこれ未満 */
    const val NIGHT_MAX_ELEVATION_DEG = -6.0
    /** 薄明：太陽高度がこれ未満（夜を除く） */
    const val TWILIGHT_MAX_ELEVATION_DEG = 6.0
    /** 位置の許可がないときに太陽の高さを概算する緯度（仕様外・要調整） */
    const val NO_LOCATION_LATITUDE = 35.0

    // ---- なじみ度（5章） ----
    /** 初めての場所で高音ベルの装飾を入れる確率（1拍あたり）（仕様外・要調整） */
    const val NEW_PLACE_ORNAMENT_PROB = 0.07
    /** なじみの場所で音数を減らす倍率（仕様外・要調整） */
    const val FAMILIAR_PROB_FACTOR = 0.55
    /** なじみの場所でパッドを持ち上げる倍率（仕様外・要調整） */
    const val FAMILIAR_PAD_GAIN_FACTOR = 1.3
    /** 装飾ベルの音域（仕様外・要調整） */
    const val ORNAMENT_MIN_MIDI = 86
    const val ORNAMENT_MAX_MIDI = 98

    // ---- 太陽の高さ（5章） ----
    const val NIGHT_CUTOFF_HZ = 2500.0
    const val TWILIGHT_CUTOFF_HZ = 4000.0
    const val DAY_CUTOFF_HZ = 6000.0
    /** 夜は和音を1オクターブ下げる */
    const val NIGHT_CHORD_SHIFT = -12

    // ---- 雰囲気（6章） ----
    const val CALM_BEAT_SEC = 1.0
    const val CALM_CUTOFF_HZ = 3500.0
    const val CALM_PROB_FACTOR = 0.8
    const val BRIGHT_BEAT_SEC = 0.85
    /** 明るい地点で「ベルの装飾を増やす」確率（1拍あたり）（仕様外・要調整） */
    const val BRIGHT_ORNAMENT_PROB = 0.08
    const val NOSTALGIC_DELAY_FEEDBACK = 0.45

    // ---- モチーフ（4章） ----
    const val MOTIF_MIN_LENGTH = 3
    const val MOTIF_MAX_LENGTH = 4
    /** モチーフの音域（仕様外・要調整。F5 を1つだけ含む） */
    const val MOTIF_MIN_MIDI = 72
    const val MOTIF_MAX_MIDI = 84
    /** モチーフ内の最大跳躍（仕様外・要調整） */
    const val MOTIF_MAX_LEAP = 5
    /** モチーフの音の間隔（拍） */
    const val MOTIF_NOTE_BEATS = 0.5
    /** 道中で直前の場面のモチーフを再現する間隔（拍） */
    const val JOURNEY_MOTIF_ECHO_BEATS = 24
    /** 主音 F のピッチクラス */
    const val TONIC_PITCH_CLASS = 5

    // ---- 音量（仕様外・要調整） ----
    const val PAD_GAIN = 0.075
    const val BASS_GAIN = 0.2
    const val PLUCK_GAIN = 0.22
    const val BELL_GAIN = 0.16
    /** 場所のモチーフの再現・接近時のテーマ（小さく／静かに） */
    const val QUIET_PLUCK_GAIN = 0.11
    /** 装飾ベル */
    const val ORNAMENT_BELL_GAIN = 0.06
    /** 到着時のテーマ（ベル） */
    const val THEME_BELL_GAIN = 0.2

    // ---- 音色（4章） ----
    const val PAD_DETUNE_CENTS = 6.0
    const val PAD_LOWPASS_HZ = 1400.0
    const val PAD_ATTACK_SEC = 2.5
    const val PAD_RELEASE_SEC = 3.5
    const val BASS_ATTACK_SEC = 3.0
    /** ベースの余韻（仕様外・要調整） */
    const val BASS_RELEASE_SEC = 3.5
    /** プラック：この時間で -60dB まで指数減衰 */
    const val PLUCK_DECAY_SEC = 2.2
    /** 立ち上がり（クリック防止）（仕様外・要調整） */
    const val PLUCK_ATTACK_SEC = 0.004
    val BELL_PARTIAL_RATIOS = doubleArrayOf(1.0, 2.76, 5.4)
    /** 各倍音の音量（仕様外・要調整） */
    val BELL_PARTIAL_GAINS = doubleArrayOf(1.0, 0.45, 0.22)
    /** 各倍音の減衰時間の倍率。高い倍音ほど早く消える（仕様外・要調整） */
    val BELL_PARTIAL_DECAY_FACTORS = doubleArrayOf(1.0, 0.6, 0.35)
    const val BELL_DECAY_SEC = 4.0
    const val BELL_ATTACK_SEC = 0.002
    /** 減衰の終点（-60dB） */
    const val DECAY_FLOOR = 0.001

    // ---- エフェクト（4章） ----
    const val DELAY_SEC = 0.675
    const val DELAY_FEEDBACK = 0.32
    const val DELAY_LOWPASS_HZ = 2200.0
    const val REVERB_RT60_SEC = 3.5
    /** リバーブ内部の高域減衰（仕様外・要調整） */
    const val REVERB_DAMPING_HZ = 5000.0
    /** リバーブの8本の遅延線（ミリ秒）（仕様外・要調整） */
    val REVERB_DELAYS_MS = doubleArrayOf(29.7, 37.1, 41.1, 43.7, 53.9, 59.3, 67.1, 73.9)
    const val REVERB_PREDELAY_SEC = 0.02

    // 各パートからエフェクトへの送り量（仕様外・要調整）
    const val PAD_DELAY_SEND = 0.15
    const val PAD_REVERB_SEND = 0.5
    const val BASS_DELAY_SEND = 0.0
    const val BASS_REVERB_SEND = 0.2
    const val PLUCK_DELAY_SEND = 0.45
    const val PLUCK_REVERB_SEND = 0.55
    const val BELL_DELAY_SEND = 0.35
    const val BELL_REVERB_SEND = 0.7

    /** エフェクト戻りの音量（仕様外・要調整） */
    const val DELAY_RETURN = 0.6
    const val REVERB_RETURN = 0.5

    // ---- マスター（仕様外・要調整） ----
    /** 全体ローパスの初期値（昼） */
    const val MASTER_CUTOFF_HZ = DAY_CUTOFF_HZ
    const val COMP_THRESHOLD_DB = -18.0
    const val COMP_RATIO = 3.0
    const val COMP_ATTACK_SEC = 0.01
    const val COMP_RELEASE_SEC = 0.3
    const val COMP_MAKEUP_DB = 4.0
    const val MASTER_GAIN = 0.9
    /** これを超えた分をやわらかく丸める */
    const val SOFT_CLIP_KNEE = 0.85

    // ---- 再生 ----
    /** 作曲を先回りしておく時間（秒） */
    const val COMPOSE_LOOKAHEAD_SEC = 0.5
    /** 作曲の先読みを区切る単位（フレーム） */
    const val RENDER_BLOCK_FRAMES = 2048

    // ---- チャンク生成とバッファ（7章） ----
    /** 1チャンクの長さ */
    const val CHUNK_SEC = 10.0
    /** 再生開始・作り直しの最初のチャンク（すぐ鳴らすために短くする） */
    const val FIRST_CHUNK_SEC = 2.0
    /** 再生待ちがこれを切ったら合成を始める */
    const val BUFFER_LOW_SEC = 30.0
    /** 再生待ちをこれ以上は作らない（60秒 ≒ 10.6MB） */
    const val BUFFER_HIGH_SEC = 60.0
    /** 状態の変化で作り直すのは、再生位置からこれ以上先のチャンクから（仕様外・要調整） */
    const val REWIND_MARGIN_SEC = 1.0
    /** 停止時、作り置きの音をこの秒数で消し、新しく鳴らす I へ移る（仕様外・要調整） */
    const val ENDING_BUFFER_FADE_SEC = 4.0
    /** バッファ不足のつなぎのパッドの立ち上がり（仕様外・要調整） */
    const val FILLER_ATTACK_SEC = 0.3
    /** バッファ不足から戻るときのクロスフェード（仕様外・要調整） */
    const val UNDERRUN_CROSSFADE_SEC = 0.5
    /** 曲調を切り替えるときのフェードアウト（12.12） */
    const val STYLE_SWITCH_FADE_SEC = 2.0
    /** 画面表示用に状態を記録する間隔 */
    const val STATUS_INTERVAL_SEC = 0.25

    // ---- 地点のテーマの試聴（仕様外・要調整） ----
    const val PREVIEW_SEC = 7.0
    const val PREVIEW_FADE_SEC = 2.0
    const val PREVIEW_THEME_START_SEC = 0.6
    const val PREVIEW_PAD_ATTACK_SEC = 0.6

    // ---- デモ再生（9章） ----
    /** 5分の場面を30秒に縮める */
    const val DEMO_SCENE_SEC = 30.0
    /** デモでは区切りを道中2分ごとに入れる（場面と同じく1/10） */
    const val DEMO_INTERLUDE_EVERY_SEC = 120.0
    /** デモの乱数シード（毎回同じ曲になる） */
    const val DEMO_SEED = 20_240_601
}
