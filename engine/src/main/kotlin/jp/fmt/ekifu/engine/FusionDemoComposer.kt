package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants as F

/**
 * 段階6a の音色デモの作曲（固定の進行と固定の熱量）。
 * - 進行は A, A, B, B のくり返し（12.3）。奇数番目のブロックは最後の小節がキメ
 * - 熱量はブロックごとに 0.3 → 0.6 → 0.9 を巡回し、ブロックの中では固定
 * - 伴奏は 12.7 の型（どの型にするかはシード付きの乱数）
 * - リードは音色の確認用の固定フレーズ（グライド・しゃくり・フォール・ビブラート・ハイトーン・16分と32分の速弾き）
 * アドリブの作曲（12.6）と人間らしさ（12.9）は段階6bで作る。
 */
class FusionDemoComposer(seed: Int = F.DEMO_SEED, private val bpm: Double = F.DEMO_BPM) {

    /** ブロックの情報（画面の表示用） */
    data class BlockInfo(
        val index: Int,
        val startSec: Double,
        val stepSec: Double,
        val section: FusionSection,
        val heat: Double,
        val kime: Boolean,
    ) {
        val endSec: Double get() = startSec + stepSec * F.STEPS_PER_BLOCK

        fun chordAt(tSec: Double): FusionChord {
            val bar = ((tSec - startSec) / (stepSec * F.STEPS_PER_BAR)).toInt().coerceIn(0, F.BARS_PER_BLOCK - 1)
            return section.chords[bar]
        }
    }

    private var rng = Mulberry32(seed)
    private var block = 0
    private var nextBlockSec = 0.0
    /** 前のブロックの最後の小節で次の和音を「食った」か（次のブロックの頭を弾かない） */
    private var pushed = false
    private var blocks = listOf<BlockInfo>()
    private val out = ArrayList<FusionEvent>()

    val stepSec: Double get() = 60.0 / bpm / 4

    fun copy() = FusionDemoComposer(0, bpm).also {
        it.rng = rng.copy()
        it.block = block
        it.nextBlockSec = nextBlockSec
        it.pushed = pushed
        it.blocks = blocks
    }

    /** tSec まで作曲を進め、新しく決まったイベントを返す（ブロック単位でまとめて作る） */
    fun composeUntil(tSec: Double): List<FusionEvent> {
        out.clear()
        while (nextBlockSec < tSec) composeBlock()
        return out.toList()
    }

    /** tSec に鳴っているブロック */
    fun blockAt(tSec: Double): BlockInfo? = blocks.lastOrNull { tSec >= it.startSec } ?: blocks.firstOrNull()

    private fun sectionOf(index: Int) = if ((index / 2) % 2 == 0) FusionSection.A else FusionSection.B

    private fun composeBlock() {
        val index = block++
        val section = sectionOf(index)
        val heat = F.DEMO_HEATS[index % F.DEMO_HEATS.size]
        val kime = index % 2 == 1
        val info = BlockInfo(index, nextBlockSec, stepSec, section, heat, kime)
        blocks = (blocks + info).takeLast(KEEP_BLOCKS)
        nextBlockSec = info.endSec

        out += FusionControl(
            info.startSec,
            masterCutoffHz = F.DAY_CUTOFF_HZ,
            leadFilterBaseHz = F.LEAD_FILTER_BASE_HZ + F.LEAD_FILTER_HEAT_HZ * heat,
            delaySec = stepSec * F.DELAY_STEPS,
        )
        band(info, sectionOf(index + 1).chords[0])
        lead(info)
    }

    // ---------------- 伴奏（12.7） ----------------

    private fun band(info: BlockInfo, nextBlockFirst: FusionChord) {
        val h = info.heat
        val chords = info.section.chords
        fun t(step: Double) = info.startSec + step * stepSec

        for (b in 0 until F.BARS_PER_BLOCK) {
            val ch = chords[b]
            val nx = if (b + 1 < F.BARS_PER_BLOCK) chords[b + 1] else nextBlockFirst
            val o = b * F.STEPS_PER_BAR.toDouble()

            if (info.kime && b == F.BARS_PER_BLOCK - 1) {
                KIME_HITS.forEachIndexed { i, k ->
                    val last = i == KIME_HITS.size - 1
                    keys(t(o + k), if (last) 3.0 else 0.6, ch, 0.8)
                    out += PolyNote(t(o + k), PolyPart.BASS, if (i % 2 == 1) ch.bassRoot + 12 else ch.bassRoot, (if (last) 4.0 else 1.0) * stepSec, 0.95)
                    out += DrumHit(t(o + k), DrumKind.KICK, 1.0)
                    out += DrumHit(t(o + k), DrumKind.SNARE, 0.9)
                    if (last) out += DrumHit(t(o + k), DrumKind.CRASH, 0.9)
                }
                pushed = false
                continue
            }

            // 鍵盤（シンセブラス）
            val ki = when {
                h < 0.4 -> if (rng.nextDouble() < 0.5) 0 else 3
                h < 0.75 -> if (rng.nextDouble() < 0.5) 1 else 3
                else -> if (rng.nextDouble() < 0.5) 1 else 2
            }
            for (hit in KEYS_RHYTHMS[ki]) {
                if (hit.start == 0 && pushed) continue // 前の小節で食っていたら頭は弾かない
                val chord = if (hit.anticipate) nx else ch
                keys(t(o + hit.start), hit.length.toDouble(), chord, if (hit.start == 0 || hit.anticipate) 0.75 else 0.5)
            }
            pushed = KEYS_RHYTHMS[ki].any { it.anticipate }

            // ベース
            val bp = when {
                h < 0.4 -> 0
                h < 0.75 -> rng.nextInt(2)
                else -> 1 + rng.nextInt(2)
            }
            for (n in BASS_PATTERNS[bp]) {
                val (midi, v) = when (n.degree) {
                    'R' -> ch.bassRoot to (if (n.step == 0) 1.0 else 0.75)
                    'O' -> ch.bassRoot + 12 to 0.75
                    '5' -> ch.bassRoot + 7 to 0.75
                    '7' -> ch.bassRoot + ch.quality.seventh to 0.75
                    else -> nx.bassRoot to 0.95 // N：次の小節の根音を食う
                }
                out += PolyNote(t(o + n.step), PolyPart.BASS, midi, n.length * F.BASS_LENGTH_FACTOR * stepSec, v)
            }

            // ドラム
            val kicks = when {
                h < 0.4 -> intArrayOf(0, 8, 10)
                h < 0.75 -> intArrayOf(0, 6, 8, 14)
                else -> intArrayOf(0, 3, 6, 8, 11, 14)
            }
            kicks.forEach { out += DrumHit(t(o + it), DrumKind.KICK, if (it == 0) 1.0 else 0.8) }
            intArrayOf(4, 12).forEach { out += DrumHit(t(o + it), DrumKind.SNARE, 0.9) }
            GHOST_STEPS.forEach { if (rng.nextDouble() < 0.25 + 0.5 * h) out += DrumHit(t(o + it), DrumKind.SNARE, F.GHOST_VELOCITY) }

            val lastBar = b == F.BARS_PER_BLOCK - 1
            val fillFrom = if (lastBar) (if (h > 0.6) 8 else 12) else Int.MAX_VALUE
            val hatStep = if (h < 0.45) 2 else 1
            var s = 0
            while (s < F.STEPS_PER_BAR && s < fillFrom) {
                out += DrumHit(t(o + s), DrumKind.HAT, if (s % 4 == 0) 0.8 else if (s % 2 == 0) 0.5 else 0.3)
                s += hatStep
            }
            if (rng.nextDouble() < F.OPEN_HAT_PROB && fillFrom > 14) out += DrumHit(t(o + 14), DrumKind.OPEN_HAT, 0.5)
            if (lastBar) {
                val rot = if (F.STEPS_PER_BAR - fillFrom == 4) 4 else 0
                for ((k, st) in (fillFrom until F.STEPS_PER_BAR).withIndex()) {
                    out += DrumHit(t(o + st), FILL[(k + rot) % FILL.size], 0.6 + if (st % 4 == 0) 0.3 else 0.0)
                }
            }
            if (b == 0 || (b == 4 && h > 0.6)) out += DrumHit(t(o), DrumKind.CRASH, 0.8)
        }
    }

    /** 和音を下の音から7msずつずらして鳴らす */
    private fun keys(timeSec: Double, lengthSteps: Double, chord: FusionChord, velocity: Double) {
        chord.keysVoicing.forEachIndexed { i, midi ->
            out += PolyNote(timeSec + i * F.KEYS_STAGGER_SEC, PolyPart.BRASS, midi, lengthSteps * stepSec * F.KEYS_LENGTH_FACTOR, velocity)
        }
    }

    // ---------------- リード（固定のフレーズ） ----------------

    private fun lead(info: BlockInfo) {
        val transpose = if (info.section == FusionSection.B) 2 else 0
        val specs = ArrayList<LeadSpec>()
        // 1〜7小節目：B（サビ）では全音上へ移す
        LEAD_PHRASE_A.forEach { specs += it.copy(midi = it.midi + transpose) }
        // 8小節目（A7sus4、どちらのセクションもDの長音階）
        if (info.kime) specs += kimeLead(info.section.chords.last(), LEAD_PHRASE_A.last().midi + transpose)
        else specs += LEAD_FILL_BAR8

        val vibCents = F.VIBRATO_BASE_CENTS + F.VIBRATO_HEAT_CENTS * info.heat
        val sorted = specs.sortedBy { it.step }
        var lastOnset = Double.NEGATIVE_INFINITY
        for ((i, n) in sorted.withIndex()) {
            val t = maxOf(info.startSec + n.step * stepSec, lastOnset + F.LEAD_MIN_ONSET_GAP_SEC)
            // 次の音（このブロックの最後の音は、ブロックの終わり）の手前で必ず離す
            val nextT = if (i + 1 < sorted.size) info.startSec + sorted[i + 1].step * stepSec else info.endSec
            val dur = maxOf(F.LEAD_MIN_DURATION_SEC, minOf(n.steps * stepSec * F.LEAD_LENGTH_FACTOR, nextT - t - F.LEAD_RELEASE_GAP_SEC))
            val glide = n.glideSec ?: if (n.steps <= 1.0) F.GLIDE_SHORT_SEC else F.GLIDE_NORMAL_SEC
            out += LeadNote(t, dur, n.midi, n.velocity, glide, n.scoop, n.fall, if (n.vibrato) vibCents else 0.0)
            lastOnset = t
        }
    }

    /** キメ（12.6）：着地に使う音を下から順に上っていく。最後の音は4ステップ伸ばしてビブラート */
    private fun kimeLead(chord: FusionChord, current: Int): List<LeadSpec> {
        var m = maxOf(F.LEAD_MIN_MIDI, minOf(current, 72) - 8)
        val targets = chord.targetPcs()
        val base = (F.BARS_PER_BLOCK - 1) * F.STEPS_PER_BAR
        return KIME_HITS.mapIndexed { i, k ->
            m = (m + 1..F.LEAD_MAX_MIDI).firstOrNull { (it % 12) in targets } ?: m
            val last = i == KIME_HITS.size - 1
            val len = if (last) 4.0 else (KIME_HITS[i + 1] - k) * 0.6
            LeadSpec((base + k).toDouble(), len, m, 0.9, vibrato = last)
        }
    }

    private data class KeysHit(val start: Int, val length: Int, val anticipate: Boolean = false)
    private data class BassHit(val step: Int, val degree: Char, val length: Int)

    /** リードの1音の設計図（ステップ単位） */
    data class LeadSpec(
        val step: Double,
        val steps: Double,
        val midi: Int,
        val velocity: Double = 0.8,
        val scoop: Int = 0,
        val fall: Boolean = false,
        val vibrato: Boolean = false,
        val glideSec: Double? = null,
    )

    companion object {
        private const val KEEP_BLOCKS = 3
        private val KIME_HITS = intArrayOf(0, 3, 6, 8, 10, 12)
        private val GHOST_STEPS = intArrayOf(2, 7, 9, 15)
        private val FILL = arrayOf(
            DrumKind.SNARE, DrumKind.TOM_HIGH, DrumKind.TOM_HIGH, DrumKind.TOM_MID,
            DrumKind.TOM_MID, DrumKind.TOM_LOW, DrumKind.TOM_LOW, DrumKind.SNARE,
        )

        private val KEYS_RHYTHMS = listOf(
            listOf(KeysHit(0, 14)),
            listOf(KeysHit(0, 3), KeysHit(6, 2), KeysHit(10, 4), KeysHit(14, 2, true)),
            listOf(KeysHit(0, 1), KeysHit(3, 1), KeysHit(6, 1), KeysHit(8, 2), KeysHit(11, 1), KeysHit(14, 2, true)),
            listOf(KeysHit(0, 6), KeysHit(6, 2), KeysHit(10, 2), KeysHit(14, 2, true)),
        )

        private val BASS_PATTERNS = listOf(
            listOf(0, 2, 4, 6, 8, 10, 12).map { BassHit(it, 'R', 2) } + BassHit(14, 'N', 2),
            listOf(
                BassHit(0, 'R', 2), BassHit(3, 'R', 1), BassHit(4, 'O', 1), BassHit(6, 'R', 2), BassHit(8, '5', 2),
                BassHit(10, 'O', 1), BassHit(11, 'R', 1), BassHit(12, '7', 2), BassHit(14, 'N', 2),
            ),
            listOf(
                BassHit(0, 'R', 1), BassHit(2, 'O', 1), BassHit(3, 'R', 1), BassHit(4, 'R', 1), BassHit(6, 'O', 1),
                BassHit(7, 'R', 1), BassHit(8, '5', 1), BassHit(10, 'O', 1), BassHit(11, 'R', 1), BassHit(12, '7', 1),
                BassHit(14, 'N', 2),
            ),
        )

        /**
         * A セクション（D）の1〜7小節目の固定フレーズ。
         * GM7 | A7 | F♯m7 | Bm7 | Em7 | F♯m7 | GM7
         */
        val LEAD_PHRASE_A: List<LeadSpec> = buildList {
            // 1小節目 GM7：F♯ を2半音下からしゃくってロングトーン、16分で駆け上がる
            add(LeadSpec(0.0, 6.0, 78, scoop = 2, vibrato = true))
            listOf(69, 71, 73, 74, 76, 78, 79, 81).forEachIndexed { i, m ->
                add(LeadSpec(8.0 + i, 1.0, m, if (i % 4 == 0) 0.82 else 0.62))
            }
            // 2小節目 A7：C♯ に着地して伸ばす → 下って、半音上下から A に回り込む
            add(LeadSpec(16.0, 6.0, 85, 0.88, vibrato = true))
            listOf(83, 81, 79, 78).forEachIndexed { i, m -> add(LeadSpec(24.0 + i, 1.0, m, 0.62)) }
            add(LeadSpec(30.0, 1.0, 82, 0.65))
            add(LeadSpec(31.0, 1.0, 80, 0.65))
            // 3小節目 F♯m7：A に着地、ひと呼吸、シーケンス
            add(LeadSpec(32.0, 4.0, 81, 0.85, vibrato = true))
            listOf(76, 78, 79, 81, 78, 79, 81, 83).forEachIndexed { i, m ->
                add(LeadSpec(40.0 + i, 1.0, m, if (i % 4 == 0) 0.8 else 0.6))
            }
            // 4小節目 Bm7：泣きのハイトーン（140msで滑り上がり、最後にフォール）
            add(LeadSpec(48.0, 11.0, 86, 0.95, fall = true, vibrato = true, glideSec = F.GLIDE_HIGH_TONE_SEC))
            // 5小節目 Em7：モチーフ（リズム型 [0,3,6,8,10]）
            listOf(0 to 74, 3 to 76, 6 to 79, 8 to 78).forEach { (o, m) -> add(LeadSpec(64.0 + o, 2.7, m, 0.72)) }
            add(LeadSpec(74.0, 3.0, 74, 0.8, vibrato = true))
            // 6小節目 F♯m7：32分で駆け下り、16分で戻って C♯ に着地
            listOf(85, 83, 81, 79, 78, 76, 74, 73).forEachIndexed { i, m ->
                add(LeadSpec(80.0 + i * 0.5, 0.5, m, if (i % 4 == 0) 0.8 else 0.62))
            }
            listOf(71, 73, 74, 76).forEachIndexed { i, m -> add(LeadSpec(84.0 + i, 1.0, m, 0.62)) }
            add(LeadSpec(88.0, 6.0, 73, 0.88, vibrato = true))
            // 7小節目 GM7：B を半音下からしゃくってロングトーン、最後にフォール
            add(LeadSpec(96.0, 10.0, 83, 0.8, scoop = 1, fall = true, vibrato = true))
        }

        /** 8小節目（キメでないとき、A7sus4）：16分で下って戻り、D に着地 */
        private val LEAD_FILL_BAR8: List<LeadSpec> = buildList {
            listOf(76, 74, 73, 71, 69, 71, 73, 74).forEachIndexed { i, m -> add(LeadSpec(112.0 + i, 1.0, m, 0.62)) }
            add(LeadSpec(120.0, 6.0, 74, 0.85, vibrato = true))
        }
    }
}
