package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants as F

/**
 * リードのモチーフの形（12.6）：リズム型（ステップ）と音程の輪郭（度数。最初の音からのずれ）。
 * 和音が変わっても形を保って移す。
 */
data class MotifShape(val rhythm: List<Int>, val contour: List<Int>) {
    init {
        require(rhythm.size == contour.size)
    }
}

object FusionMotifs {

    val RHYTHMS = listOf(
        listOf(0, 2, 4, 6),
        listOf(0, 3, 6, 8, 10),
        listOf(0, 2, 3, 6, 8),
        listOf(0, 1, 2, 4, 6, 7),
        listOf(0, 3, 6, 10, 14),
    )

    /** 新しく作るモチーフ：リズム型は5つから、輪郭は各音 −1〜+2 度 */
    fun random(rng: Mulberry32): MotifShape {
        val rh = RHYTHMS[rng.nextInt(RHYTHMS.size)]
        return MotifShape(rh, rh.indices.map { if (it == 0) 0 else rng.nextInt(4) - 1 })
    }

    /** 場所のモチーフ（マスIDがシード）の音程の動きを輪郭に、リズム型もマスIDから選ぶ。同じマスなら同じ形 */
    fun place(gridId: String): MotifShape =
        fromNotes(Motifs.placeMotif(gridId), Mulberry32.seedOf(gridId))

    /** 地点のテーマ（癒しと同じシード）の音程の動きを輪郭に、リズム型もテーマのシードから選ぶ */
    fun theme(themeSeed: Int): MotifShape = fromNotes(Motifs.theme(themeSeed), themeSeed)

    /** 地点のテーマの輪郭（度数）。到着で吹くときに使う */
    fun themeContour(themeSeed: Int): List<Int> = contourOf(Motifs.theme(themeSeed))

    private fun fromNotes(notes: List<Int>, seed: Int): MotifShape {
        val contour = contourOf(notes)
        val rh = RHYTHMS[Mulberry32(seed).nextInt(RHYTHMS.size)]
        val n = minOf(rh.size, contour.size)
        return MotifShape(rh.take(n), contour.take(n))
    }

    /** ペンタトニック上の並びの差を「度」とみなす */
    private fun contourOf(notes: List<Int>): List<Int> {
        val scale = Scale.pentatonic(F.LEAD_MIN_MIDI - 12, F.LEAD_MAX_MIDI + 12)
        val first = scale.indexOf(notes.first())
        return notes.map { scale.indexOf(it) - first }
    }
}
