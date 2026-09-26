package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants as F

/** フュージョンの和音の種類（12.3。ルートからの半音数） */
enum class FusionQuality(
    val label: String,
    val voice: IntArray,
    /** 着地に使う音 */
    val targets: IntArray,
    /** ロングトーンで狙うテンション */
    val tensions: IntArray,
    /** 7度（ベース用） */
    val seventh: Int,
) {
    M7("M7", intArrayOf(4, 7, 11, 14), intArrayOf(4, 7, 11, 14), intArrayOf(2, 11, 9), 11),
    DOM7("7", intArrayOf(4, 7, 10, 14), intArrayOf(4, 10, 7, 14), intArrayOf(2, 9, 4), 10),
    M7_MINOR("m7", intArrayOf(3, 7, 10, 14), intArrayOf(3, 7, 10, 14), intArrayOf(2, 10, 5), 10),
    SUS4("7sus4", intArrayOf(5, 7, 10, 14), intArrayOf(5, 10, 14, 9), intArrayOf(2, 9, 5), 10),
    /** 終わりのトニック（M7 と同じ積み方で、9度を含む） */
    M9("M9", intArrayOf(4, 7, 11, 14), intArrayOf(4, 7, 11, 14), intArrayOf(2, 11, 9), 11),
}

/** 1小節ぶんの和音。keyPc はリードの音階に使う調（その調の長音階） */
data class FusionChord(val rootPc: Int, val quality: FusionQuality, val keyPc: Int) {

    val name: String get() = NAMES[rootPc] + quality.label

    /** 鍵盤の構成音（ルート+48 を基準に 45〜54 に収まるオクターブ） */
    val keysVoicing: IntArray
        get() {
            var base = rootPc + F.KEYS_BASE_OFFSET
            if (base > F.KEYS_BASE_MAX) base -= 12
            if (base < F.KEYS_BASE_MIN) base += 12
            return IntArray(quality.voice.size) { base + quality.voice[it] }
        }

    /** ベースの根音（ルート+36、41 を超えたら −12） */
    val bassRoot: Int
        get() = (rootPc + F.BASS_BASE_OFFSET).let { if (it > F.BASS_BASE_MAX) it - 12 else it }

    /** リードの音階（調の長音階）のピッチクラス */
    val scalePcs: Set<Int> get() = MAJOR.map { (keyPc + it) % 12 }.toSet()

    fun targetPcs(): Set<Int> = quality.targets.map { (rootPc + it) % 12 }.toSet()

    companion object {
        private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val NAMES = arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")

        val TONIC = FusionChord(F.TONIC_ROOT_PC, FusionQuality.M9, F.TONIC_ROOT_PC)
    }
}

/** セクション（12.3）。A は D、B（サビ）は全音上の E。B の最後の小節だけ D に戻る */
enum class FusionSection(val chords: List<FusionChord>) {
    A(
        listOf(
            FusionChord(7, FusionQuality.M7, 2),
            FusionChord(9, FusionQuality.DOM7, 2),
            FusionChord(6, FusionQuality.M7_MINOR, 2),
            FusionChord(11, FusionQuality.M7_MINOR, 2),
            FusionChord(4, FusionQuality.M7_MINOR, 2),
            FusionChord(6, FusionQuality.M7_MINOR, 2),
            FusionChord(7, FusionQuality.M7, 2),
            FusionChord(9, FusionQuality.SUS4, 2),
        ),
    ),
    B(
        listOf(
            FusionChord(9, FusionQuality.M7, 4),
            FusionChord(11, FusionQuality.DOM7, 4),
            FusionChord(8, FusionQuality.M7_MINOR, 4),
            FusionChord(1, FusionQuality.M7_MINOR, 4),
            FusionChord(6, FusionQuality.M7_MINOR, 4),
            FusionChord(8, FusionQuality.M7_MINOR, 4),
            FusionChord(9, FusionQuality.M7, 4),
            FusionChord(9, FusionQuality.SUS4, 2),
        ),
    ),
}

/** 音符イベント（フュージョン用）。時刻は再生開始からの秒 */
sealed interface FusionEvent {
    val timeSec: Double
}

/** リード（モノフォニック）。奏法の情報つき */
data class LeadNote(
    override val timeSec: Double,
    val durSec: Double,
    val midi: Int,
    val velocity: Double,
    /** 前の音からこの音へ滑らせる時間 */
    val glideSec: Double,
    /** しゃくり：この半音数だけ下から入る（0 ならなし） */
    val scoopSemitones: Int = 0,
    /** フォール：終わりの160ms前から4半音下へ */
    val fall: Boolean = false,
    /** ビブラートの深さ（セント、0 ならなし） */
    val vibratoCents: Double = 0.0,
) : FusionEvent

enum class PolyPart { BRASS, BASS }

/** シンセブラス・ベース（音ごとに別の発音体） */
data class PolyNote(
    override val timeSec: Double,
    val part: PolyPart,
    val midi: Int,
    val durSec: Double,
    val velocity: Double,
) : FusionEvent

enum class DrumKind { KICK, SNARE, HAT, OPEN_HAT, TOM_HIGH, TOM_MID, TOM_LOW, CRASH }

data class DrumHit(
    override val timeSec: Double,
    val kind: DrumKind,
    val velocity: Double,
    /** キック・タムの音程の倍率（人間らしさ、段階6b） */
    val pitchFactor: Double = 1.0,
) : FusionEvent

/** 全体ローパス（太陽）・リードのフィルターの基準・ディレイの長さ（テンポ） */
data class FusionControl(
    override val timeSec: Double,
    val masterCutoffHz: Double,
    val leadFilterBaseHz: Double,
    val delaySec: Double,
) : FusionEvent

data class FusionFadeOut(override val timeSec: Double, val durationSec: Double) : FusionEvent
