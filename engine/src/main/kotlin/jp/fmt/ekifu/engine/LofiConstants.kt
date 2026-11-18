package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants.db

/**
 * 曲調「ローファイ」（段階7。ユーザーと決めたこと：生楽器をサウンドフォント GeneralUser GS から鳴らし、
 * テープの揺れ・高音を削る・レコードのノイズでローファイに加工する。テンポは速さで、音のこもり具合とノイズは太陽で、
 * 和音の並びはなじみ度で変える）。値はすべて仕様外・要調整
 */
object LofiConstants {

    // ---- 楽器（General MIDI の番号） ----
    /** 地点のテーマ・メロディ：グランドピアノ */
    private val LEAD = 0 to 0
    /** 和音：Tine Electric Piano（生音フュージョンと同じ楽器） */
    private val KEYS = 0 to 4
    /** ベース：Acoustic Bass（ウッドベース） */
    private val BASS = 0 to 32
    /** ドラム：Jazz キット（柔らかいキック・スネア） */
    private val DRUMS = 128 to 32

    val KIT = BandKit(
        name = "lofi",
        lead = LEAD,
        keys = KEYS,
        bass = BASS,
        drums = DRUMS,
        drumKeys = mapOf(
            DrumKind.KICK to 36, DrumKind.SNARE to 38, DrumKind.HAT to 42, DrumKind.OPEN_HAT to 46, DrumKind.CRASH to 49,
            DrumKind.RIM to 37,
        ),
        leadGain = db(0.0),
        // エレピは試聴で2回調整（＋4dB → ユーザーの要望で −2dB して ＋2dB）
        keysGain = db(-2.0),
        bassGain = db(0.0),
        drumGains = mapOf(
            DrumKind.KICK to db(-2.0), DrumKind.SNARE to db(-9.0), DrumKind.HAT to db(-14.0),
            DrumKind.OPEN_HAT to db(-16.0), DrumKind.CRASH to db(-16.0), DrumKind.RIM to db(-12.0),
        ),
        // 生音フュージョンより暗め（全体のこもりは太陽で決める）
        keysLowpassHz = 6000.0,
        bassLowpassHz = 1800.0,
        leadLowpassHz = 7000.0,
    )

    // ---- テンポとリズム ----
    /** 速さごとのテンポ（ユーザーと決めたこと：静止72・歩き80・乗り物86） */
    fun bpm(speed: Speed): Double = when (speed) {
        Speed.STILL -> 72.0
        Speed.WALK -> 80.0
        Speed.VEHICLE -> 86.0
    }

    /** 1小節の16分の数 */
    const val STEPS_PER_BAR = 16
    /**
     * スウィング：裏の16分を遅らせる割合（0.5 でまっすぐ、0.58 で 58:42）。
     * 乗り物のあいだは少しまっすぐにして前へ進む感じを出す
     */
    const val SWING = 0.6
    const val SWING_VEHICLE = 0.56

    /** 人間らしさ：音の時刻のずれ（秒）と強さの揺れ */
    const val JITTER_KEYS_SEC = 0.012
    const val JITTER_BASS_SEC = 0.006
    const val JITTER_DRUM_SEC = 0.004
    /** スネアは少し後ろに置く（もたつき） */
    const val SNARE_LAY_BACK_SEC = 0.018
    const val VELOCITY_JITTER = 0.08
    /** 和音を下の音から少しずつずらして弾く（ばらつき） */
    const val KEYS_STRUM_SEC = 0.014

    // ---- 和音（主音 F。テーマ・モチーフと同じ調） ----
    /** 和音の根音のベース音域 */
    const val BASS_MIN_MIDI = 36
    const val BASS_MAX_MIDI = 47
    /** 和音の音域（ルートを抜いたボイシングをこの範囲に置く） */
    const val KEYS_MIN_MIDI = 53
    const val KEYS_MAX_MIDI = 76

    // ---- 太陽：こもり具合とノイズ ----
    fun cutoffHz(sun: SunLevel): Double = when (sun) {
        SunLevel.DAY -> 5200.0
        SunLevel.TWILIGHT -> 3800.0
        SunLevel.NIGHT -> 2600.0
    }

    fun noiseFactor(sun: SunLevel): Double = when (sun) {
        SunLevel.DAY -> 1.0
        SunLevel.TWILIGHT -> 1.3
        SunLevel.NIGHT -> 1.7
    }

    // ---- ローファイの加工 ----
    /** テープの揺れ：ゆっくりの揺れ（ワウ）と細かい揺れ（フラッター）。遅れの振れ幅（秒） */
    const val WOW_HZ = 0.55
    const val WOW_DEPTH_SEC = 0.0011
    const val FLUTTER_HZ = 6.5
    const val FLUTTER_DEPTH_SEC = 0.00012
    /** 揺れの基準の遅れ */
    const val WOBBLE_BASE_SEC = 0.004
    /** テープの飽和（tanh の駆動） */
    const val SATURATION_DRIVE = 1.4
    /** 低音を少し削る（ラジカセっぽさ） */
    const val HIGHPASS_HZ = 45.0
    /** レコードのノイズ：ヒス（サー）とプチプチ */
    val HISS_GAIN = db(-56.0)
    const val HISS_LOWPASS_HZ = 5000.0
    const val CRACKLE_PER_SEC = 3.5
    val CRACKLE_GAIN = db(-33.0)
    const val CRACKLE_DECAY_SEC = 0.0015

    /**
     * 全体の音量の持ち上げ（ユーザーの要望：ほかの曲調より約8dB小さかったため、癒し・フュージョンと同じくらいに）。
     * 最後の出力にかける（測ってみて音割れはなかった）
     */
    val OUTPUT_BOOST = db(8.0)

    // ---- 空間 ----
    /** ディレイは控えめ（付点8分・フィードバック少なめ） */
    const val DELAY_STEPS = 3
    const val DELAY_FEEDBACK = 0.22

    // ---- 曲の組み立て（段階7b） ----
    /** 1ブロックの小節数（和音の並び4小節×2） */
    const val BLOCK_BARS = 8
    /** 始まり：こもった音で和音とベースだけの小節数（レコードをかけたような入り） */
    const val START_BARS = 4
    /** 区切り：音をこもらせてドラムを抜く小節数 */
    const val INTERLUDE_BARS = 4
    /** 始まり・区切りのこもった音 */
    const val MUFFLED_CUTOFF_HZ = 1100.0
    /** 外側の円に入った最初のブロック：ドラムを止めてベルでテーマを鳴らす小節数 */
    const val ENTRY_BREAK_BARS = 2
    /**
     * 道中のメロディ：8小節のブロックのうち頭からこの小節数だけ弾き、残りは休む
     * （ユーザーの要望で4→6小節に増やした。5・6小節目はもう一度変形して落ち着く）
     */
    const val MELODY_BARS = 6
    /** メロディの強さ */
    const val MELODY_VELOCITY = 0.62
    /** 地点のテーマをピアノで弾く強さ */
    const val THEME_VELOCITY = 0.72

    /**
     * 到着の合図（両方の曲調と同じ形）：曲を止めてベルの上りの3音。主音 F の和音（F・A・C）を8分で。
     * 音量は仕様外・要調整
     */
    val ARRIVAL_CHIME_MIDI = intArrayOf(77, 81, 84)
    val ARRIVAL_CHIME_STEPS = intArrayOf(0, 2, 4)
    /** 到着のブロック：1小節目は合図、2小節目でテーマ（四分）、3〜5小節目でゆっくり（二分）、6小節目で寄せて、7・8小節目で着地 */
    const val ARRIVAL_THEME_BAR = 1
    const val ARRIVAL_SLOW_BAR = 2
    const val ARRIVAL_LAND_BAR = 6
    /** テーマの1音の長さ（16分の数）：ふつうは四分、ゆっくりは二分 */
    const val THEME_NOTE_STEPS = 4
    const val THEME_SLOW_STEPS = 8
    /** 滞在中は、この数のブロックごとにベルでテーマを静かに鳴らす（到着直後のブロックは鳴らさない） */
    const val STAY_BELL_EVERY_BLOCKS = 2

    /** デモ再生のシード */
    const val DEMO_SEED = 20_261_115
}
