package jp.fmt.ekifu.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

data class FusionComposerConfig(
    /** 道中がこれだけ続くごとに区切り（ブレイク）を1回 */
    val interludeEverySec: Double = C.INTERLUDE_EVERY_SEC,
)

/** 1ブロック（8小節）の設計。画面の表示と、終わり・つなぎの音にも使う */
data class FusionBlock(
    val index: Int,
    val startSec: Double,
    val stepSec: Double,
    val phase: Phase,
    val section: FusionSection,
    val chords: List<FusionChord>,
    val heat: Double,
    /** キメを打つ小節（0始まり） */
    val kimeBars: Set<Int>,
    val scene: Scene,
    val place: Place?,
    val cutoffHz: Double,
) {
    val bpm: Double get() = 60.0 / (stepSec * 4)
    val endSec: Double get() = startSec + stepSec * F.STEPS_PER_BLOCK

    fun barAt(tSec: Double) = ((tSec - startSec) / (stepSec * F.STEPS_PER_BAR)).toInt().coerceIn(0, F.BARS_PER_BLOCK - 1)

    fun chordAt(tSec: Double): FusionChord = chords[barAt(tSec)]
}

/**
 * 曲調「フュージョン」の作曲（12.3〜12.7、12.9）。音符イベントだけを決め、波形は作らない。
 *
 * - 8小節（128ステップ）のブロック単位で組み立てる。入力（場面・地点）は次のブロックの頭で反映する
 * - 同じシード・同じ入力なら同じイベント列。copy() で状態をまるごと複製できる（チャンクの作り直し用）
 */
class FusionComposer(seed: Int, private val config: FusionComposerConfig = FusionComposerConfig()) : ComposerInput {

    private var rng = Mulberry32(seed)
    private val out = ArrayList<FusionEvent>()

    private var blockCount = 0
    private var nextBlockSec = 0.0
    private var blocks = listOf<FusionBlock>()

    private var phase = Phase.START
    private var journeyStartSec = 0.0
    /** 道中の A, A, B, B の位置 */
    private var journeySection = 0

    private var scene = Scene.DEFAULT
    private var place: Place? = null
    private var pendingScene: Scene? = null
    private var pendingPhase: Phase? = null
    private var pendingPlace: Place? = null
    private var pendingLeave = false

    /** リード：直前の音と、使い回し中のモチーフ */
    private var cur = F.LEAD_START_MIDI
    private var motif: MotifShape? = null
    /** 鍵盤：前の小節で次の和音を「食った」か */
    private var pushed = false

    // ---------------- 入力 ----------------

    override fun setScene(newScene: Scene) {
        pendingScene = newScene
    }

    override fun approach(target: Place) {
        pendingPhase = Phase.APPROACH
        pendingPlace = target
    }

    override fun arrive(target: Place) {
        pendingPhase = Phase.ARRIVE
        pendingPlace = target
    }

    override fun leave() {
        pendingPhase = Phase.JOURNEY
        pendingLeave = true
    }

    override fun stay(target: Place) {
        pendingPhase = Phase.STAY
        pendingPlace = target
    }

    // ---------------- 出力 ----------------

    /** tSec まで作曲を進め、新しく決まったイベントを返す（ブロック単位でまとめて作る） */
    fun composeUntil(tSec: Double): List<FusionEvent> {
        out.clear()
        while (nextBlockSec < tSec) composeBlock()
        return out.toList()
    }

    /** tSec に鳴っているブロック */
    fun blockAt(tSec: Double): FusionBlock? = blocks.lastOrNull { tSec >= it.startSec } ?: blocks.firstOrNull()

    /** 次のブロックの1拍の長さ（まだ作っていなければ場面から） */
    val stepSec: Double get() = blocks.lastOrNull()?.stepSec ?: (60.0 / F.DEMO_BPM / 4)

    fun copy() = FusionComposer(0, config).also {
        it.rng = rng.copy()
        it.blockCount = blockCount
        it.nextBlockSec = nextBlockSec
        it.blocks = blocks
        it.phase = phase
        it.journeyStartSec = journeyStartSec
        it.journeySection = journeySection
        it.scene = scene
        it.place = place
        it.pendingScene = pendingScene
        it.pendingPhase = pendingPhase
        it.pendingPlace = pendingPlace
        it.pendingLeave = pendingLeave
        it.cur = cur
        it.motif = motif
        it.pushed = pushed
    }

    // ---------------- ブロックの設計 ----------------

    private fun composeBlock() {
        val index = blockCount++
        val start = nextBlockSec

        // 入力を反映する
        pendingScene?.let { scene = it }
        pendingScene = null
        pendingPlace?.let { place = it }
        pendingPlace = null
        var leaveTheme: MotifShape? = null
        if (pendingLeave) {
            leaveTheme = place?.let { FusionMotifs.theme(it.themeSeed) }
            place = null
            pendingLeave = false
        }

        val prev = phase
        phase = nextPhase(index, start)
        if (phase == Phase.JOURNEY && (index == 0 || prev != Phase.JOURNEY)) {
            journeyStartSec = start
            // 区切りからは循環を続け、それ以外（始まり・地点から離れた）では A からやり直す
            if (prev != Phase.INTERLUDE) journeySection = 0
        }

        val mood = if (phase == Phase.APPROACH || phase == Phase.ARRIVE || phase == Phase.STAY) place?.mood else null
        val bpm = speedBpm(scene.speed) + when (mood) {
            Mood.BRIGHT -> F.BRIGHT_BPM_DELTA
            Mood.CALM -> F.CALM_BPM_DELTA
            else -> 0.0
        }
        val stepSec = 60.0 / bpm / 4
        val heat = heatFor(index)

        val section = when (phase) {
            Phase.JOURNEY, Phase.INTERLUDE -> JOURNEY_CYCLE[journeySection++ % JOURNEY_CYCLE.size]
            Phase.APPROACH -> FusionSection.B
            else -> FusionSection.A
        }
        val chords = if (phase == Phase.ARRIVE) ARRIVAL_CHORDS else section.chords
        val kimeBars = when (phase) {
            Phase.JOURNEY, Phase.APPROACH -> if (index % 2 == 1) setOf(7) else emptySet()
            Phase.INTERLUDE -> setOf(6, 7)
            Phase.ARRIVE -> setOf(ARRIVAL_KIME_BAR)
            else -> emptySet()
        }
        val cutoff = when (scene.sun) {
            SunLevel.NIGHT -> F.NIGHT_CUTOFF_HZ
            SunLevel.TWILIGHT -> F.TWILIGHT_CUTOFF_HZ
            SunLevel.DAY -> F.DAY_CUTOFF_HZ
        }
        val block = FusionBlock(index, start, stepSec, phase, section, chords, heat, kimeBars, scene, place, cutoff)
        blocks = (blocks + block).takeLast(KEEP_BLOCKS)
        nextBlockSec = block.endSec

        val events = ArrayList<FusionEvent>()
        events += FusionControl(
            start,
            masterCutoffHz = cutoff,
            leadFilterBaseHz = F.LEAD_FILTER_BASE_HZ + F.LEAD_FILTER_HEAT_HZ * heat,
            delaySec = stepSec * F.DELAY_STEPS,
            delayFeedback = if (mood == Mood.NOSTALGIC) F.NOSTALGIC_DELAY_FEEDBACK else F.DELAY_FEEDBACK,
        )
        Band(block, nextFirstChord(), events).write()
        when (phase) {
            Phase.START -> Unit // 最初のブロックはリードを休む
            Phase.ARRIVE -> arrivalLead(block, events)
            else -> {
                // ブロックで最初に作るモチーフ：接近中は地点のテーマ、離れた直後は一度だけテーマ、ほかは場所のモチーフ
                val firstMotif = when {
                    phase == Phase.APPROACH && place != null -> FusionMotifs.theme(place!!.themeSeed)
                    leaveTheme != null -> leaveTheme
                    else -> FusionMotifs.place(scene.gridId)
                }
                Lead(block, firstMotif, events).write()
            }
        }
        out += Humanizer(Mulberry32(rng.nextInt(Int.MAX_VALUE))).apply(events, block)
    }

    private fun nextPhase(index: Int, start: Double): Phase {
        pendingPhase?.let {
            pendingPhase = null
            return it
        }
        if (index == 0) return Phase.START
        return when (phase) {
            Phase.START -> Phase.JOURNEY
            Phase.JOURNEY -> if (start - journeyStartSec >= config.interludeEverySec) Phase.INTERLUDE else Phase.JOURNEY
            Phase.INTERLUDE -> Phase.JOURNEY
            Phase.ARRIVE -> Phase.STAY
            else -> phase
        }
    }

    /** 熱量（12.5） */
    private fun heatFor(index: Int): Double {
        val jitter = rng.nextDouble(-F.HEAT_JITTER, F.HEAT_JITTER)
        val value = when (phase) {
            Phase.START -> F.START_HEAT
            Phase.STAY -> F.STAY_HEAT
            else -> {
                var h = speedHeat(scene.speed) + F.HEAT_WAVE[index % F.HEAT_WAVE.size] + jitter
                h += when (scene.familiarity) {
                    Familiarity.NEW -> F.NEW_PLACE_HEAT
                    Familiarity.FAMILIAR -> F.FAMILIAR_HEAT
                    Familiarity.NORMAL -> 0.0
                }
                if (scene.sun == SunLevel.NIGHT) h += F.NIGHT_HEAT
                if (phase == Phase.APPROACH) h += F.APPROACH_HEAT
                h
            }
        }
        return value.coerceIn(0.0, 1.0)
    }

    /** 次のブロックの最初の和音（最後の小節で「食う」ため。いまの入力で予想する） */
    private fun nextFirstChord(): FusionChord = when (pendingPhase ?: phase) {
        Phase.APPROACH -> FusionSection.B.chords[0]
        Phase.JOURNEY, Phase.INTERLUDE, Phase.START -> JOURNEY_CYCLE[journeySection % JOURNEY_CYCLE.size].chords[0]
        else -> FusionSection.A.chords[0]
    }

    // ---------------- 伴奏（12.7） ----------------

    private inner class Band(val block: FusionBlock, val nextBlockFirst: FusionChord, val out: MutableList<FusionEvent>) {
        val h = block.heat
        fun t(step: Double) = block.startSec + step * block.stepSec

        fun write() {
            for (b in 0 until F.BARS_PER_BLOCK) {
                val ch = block.chords[b]
                val nx = if (b + 1 < F.BARS_PER_BLOCK) block.chords[b + 1] else nextBlockFirst
                val o = b * F.STEPS_PER_BAR.toDouble()
                when {
                    block.phase == Phase.ARRIVE && b > ARRIVAL_KIME_BAR -> {
                        if (b == ARRIVAL_KIME_BAR + 1) landOnTonic(o)
                        pushed = false
                    }
                    b in block.kimeBars -> {
                        kime(o, ch, crashOnLast = b == block.kimeBars.max())
                        pushed = false
                    }
                    else -> groove(b, o, ch, nx)
                }
            }
        }

        /** トニック（DM9）に着地して2小節伸ばす */
        private fun landOnTonic(o: Double) {
            val len = 2 * F.STEPS_PER_BAR * block.stepSec
            keys(t(o), 2.0 * F.STEPS_PER_BAR / F.KEYS_LENGTH_FACTOR, FusionChord.TONIC, 0.85)
            out += PolyNote(t(o), PolyPart.BASS, FusionChord.TONIC.bassRoot, len, 1.0)
            out += DrumHit(t(o), DrumKind.KICK, 1.0)
            out += DrumHit(t(o), DrumKind.CRASH, 0.9)
        }

        private fun kime(o: Double, ch: FusionChord, crashOnLast: Boolean) {
            KIME_HITS.forEachIndexed { i, k ->
                val last = i == KIME_HITS.size - 1
                keys(t(o + k), if (last) 3.0 else 0.6, ch, 0.8)
                out += PolyNote(
                    t(o + k), PolyPart.BASS, if (i % 2 == 1) ch.bassRoot + 12 else ch.bassRoot,
                    (if (last) 4.0 else 1.0) * block.stepSec, 0.95,
                )
                out += DrumHit(t(o + k), DrumKind.KICK, 1.0)
                out += DrumHit(t(o + k), DrumKind.SNARE, 0.9)
                if (last && crashOnLast) out += DrumHit(t(o + k), DrumKind.CRASH, 0.9)
            }
        }

        private fun groove(b: Int, o: Double, ch: FusionChord, nx: FusionChord) {
            val drumsFull = block.phase != Phase.START && block.phase != Phase.STAY

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
                out += PolyNote(t(o + n.step), PolyPart.BASS, midi, n.length * F.BASS_LENGTH_FACTOR * block.stepSec, v)
            }

            // ドラム（始まりはハイハットだけ、滞在はハイハットとキックだけ）
            if (block.phase != Phase.START) {
                val kicks = when {
                    h < 0.4 -> intArrayOf(0, 8, 10)
                    h < 0.75 -> intArrayOf(0, 6, 8, 14)
                    else -> intArrayOf(0, 3, 6, 8, 11, 14)
                }
                kicks.forEach { out += DrumHit(t(o + it), DrumKind.KICK, if (it == 0) 1.0 else 0.8) }
            }
            if (drumsFull) {
                BACKBEAT.forEach { out += DrumHit(t(o + it), DrumKind.SNARE, 0.9) }
                GHOST_STEPS.forEach {
                    if (rng.nextDouble() < 0.25 + 0.5 * h) out += DrumHit(t(o + it), DrumKind.SNARE, F.GHOST_VELOCITY)
                }
            }
            val lastBar = b == F.BARS_PER_BLOCK - 1
            val fillFrom = if (lastBar && drumsFull) (if (h > 0.6) 8 else 12) else Int.MAX_VALUE
            val hatStep = if (h < 0.45) 2 else 1
            var s = 0
            while (s < F.STEPS_PER_BAR && s < fillFrom) {
                out += DrumHit(t(o + s), DrumKind.HAT, if (s % 4 == 0) 0.8 else if (s % 2 == 0) 0.5 else 0.3)
                s += hatStep
            }
            if (rng.nextDouble() < F.OPEN_HAT_PROB && fillFrom > 14) out += DrumHit(t(o + 14), DrumKind.OPEN_HAT, 0.5)
            if (fillFrom < F.STEPS_PER_BAR) {
                val rot = if (F.STEPS_PER_BAR - fillFrom == 4) 4 else 0
                for ((k, st) in (fillFrom until F.STEPS_PER_BAR).withIndex()) {
                    out += DrumHit(t(o + st), FILL[(k + rot) % FILL.size], 0.6 + if (st % 4 == 0) 0.3 else 0.0)
                }
            }
            if (drumsFull && (b == 0 || (b == 4 && h > 0.6))) out += DrumHit(t(o), DrumKind.CRASH, 0.8)
        }

        /** 和音を下の音から7msずつずらして鳴らす */
        private fun keys(timeSec: Double, lengthSteps: Double, chord: FusionChord, velocity: Double) {
            chord.keysVoicing.forEachIndexed { i, midi ->
                out += PolyNote(
                    timeSec + i * F.KEYS_STAGGER_SEC, PolyPart.BRASS, midi,
                    lengthSteps * block.stepSec * F.KEYS_LENGTH_FACTOR, velocity,
                )
            }
        }
    }

    // ---------------- リード（12.6） ----------------

    /** リードの1音（ステップ単位）。書き出すときに秒へ直す */
    private data class LeadStep(
        val step: Double,
        val steps: Double,
        val midi: Int,
        val velocity: Double,
        val scoop: Int = 0,
        val fall: Boolean = false,
        val vibrato: Boolean = false,
        val glideSec: Double? = null,
        val chromatic: Boolean = false,
    )

    private class Result(val notes: List<LeadStep>, val end: Double)

    private inner class Lead(val block: FusionBlock, firstMotif: MotifShape, val events: MutableList<FusionEvent>) {
        val h = block.heat
        private var pendingMotif: MotifShape? = firstMotif

        fun at(step: Double): FusionChord =
            block.chords[floor(step / F.STEPS_PER_BAR).toInt().coerceIn(0, F.BARS_PER_BLOCK - 1)]

        fun write() {
            val firstKime = block.kimeBars.minOrNull()
            val endLead = (firstKime ?: F.BARS_PER_BLOCK) * F.STEPS_PER_BAR.toDouble()
            val notes = ArrayList<LeadStep>()
            var pos = 0.0
            while (pos < endLead - 1) {
                val type = if (endLead - pos < 4) "breath" else pick()
                val res = when (type) {
                    "run" -> run(pos)
                    "long" -> long(pos)
                    "seq" -> seq(pos)
                    "motif" -> motif(pos)
                    "enclose" -> enclose(pos)
                    "cry" -> cry(pos)
                    else -> breath(pos)
                }
                res.notes.forEach { if (it.step < endLead) notes += it }
                pos = maxOf(pos + 1, res.end)
            }
            if (firstKime != null) for (bar in block.kimeBars.sorted()) notes += kimeLead(bar)
            writeLead(block, notes, events)
        }

        private fun pick(): String {
            val w = linkedMapOf(
                "run" to 2 + 3 * h,
                "long" to 3 - h,
                "seq" to 1.2 + 1.5 * h,
                "motif" to 4 - h,
                "enclose" to 0.8,
                "cry" to if (h > 0.4) 3 * h else 0.0,
                "breath" to 1.6 - 1.2 * h,
            )
            val total = w.values.sumOf { maxOf(0.0, it) }
            var x = rng.nextDouble() * total
            for ((k, v) in w) {
                x -= maxOf(0.0, v)
                if (x <= 0) return k
            }
            return "breath"
        }

        private fun gravityDir() = when {
            cur > F.GRAVITY_HIGH -> -1
            cur < F.GRAVITY_LOW -> 1
            else -> 0
        }

        /** 速弾き：16分（熱量0.7以上で40%は32分）で音階を上るか下り、次の8分の頭で着地 */
        private fun run(pos: Double): Result {
            val notes = ArrayList<LeadStep>()
            val sub = if (h > 0.7 && rng.nextDouble() < 0.4) 0.5 else 1.0
            val n = 4 + floor(rng.nextDouble() * 4 + h * 6).toInt()
            var dir = gravityDir().takeIf { it != 0 } ?: if (rng.nextDouble() < 0.5) 1 else -1
            var m = cur
            for (i in 0 until n) {
                val s = pos + i * sub
                val nm = stepScale(m, at(s), dir * if (rng.nextDouble() < 0.15) 2 else 1)
                if (nm == m) dir = -dir // 端に当たったら折り返す
                m = nm
                notes += LeadStep(s, sub * 0.95, m, 0.62 + if (i % 4 == 0) 0.2 else 0.0)
            }
            var sl = ceil((pos + n * sub) / 2) * 2
            val target = land(m, at(sl), 0)
            val d = 2 + rng.nextInt(4)
            // 着地が小節の頭になるときは50%で8分早く「食う」（和音は次の小節のもの）
            if (sl % F.STEPS_PER_BAR == 0.0 && sl > pos + n * sub + 1.5 && rng.nextDouble() < 0.5) sl -= 2
            val len = d + if (sl % F.STEPS_PER_BAR == 14.0) 2 else 0
            notes += LeadStep(sl, len.toDouble(), target, 0.88, vibrato = d >= 4)
            cur = target
            return Result(notes, sl + d)
        }

        /** ロングトーン：テンションを伸ばす。しゃくり・フォールつき */
        private fun long(pos: Double): Result {
            val ch = at(pos)
            val aim = cur + when (gravityDir()) {
                -1 -> -4
                1 -> 4
                else -> if (rng.nextDouble() < 0.6) 2 else -2
            }
            val t = nearestPc(aim, ch.tensionPcs(), 0)
            val d = 6 + rng.nextInt(6)
            val scoop = if (rng.nextDouble() < 0.7) (if (rng.nextDouble() < 0.5) 1 else 2) else 0
            val fall = rng.nextDouble() < 0.25
            cur = t
            return Result(listOf(LeadStep(pos, d.toDouble(), t, 0.8, scoop, fall, vibrato = true)), pos + d + if (rng.nextDouble() < 0.5) 1 else 0)
        }

        /** シーケンス：4音のパターンを1度ずつずらして3〜4回、最後に着地 */
        private fun seq(pos: Double): Result {
            val cell = SEQ_CELLS[rng.nextInt(SEQ_CELLS.size)]
            val reps = 3 + if (h > 0.5) 1 else 0
            val shift = if (cur > 78) -1 else 1
            val notes = ArrayList<LeadStep>()
            for (k in 0 until reps) for (j in 0 until 4) {
                val s = pos + k * 4 + j
                notes += LeadStep(s, 0.95, stepScale(cur, at(s), cell[j] + k * shift), 0.6 + if (j == 0) 0.2 else 0.0)
            }
            val sl = pos + reps * 4
            val t = land(notes.last().midi, at(sl), 0)
            notes += LeadStep(sl, 3.0, t, 0.85)
            cur = t
            return Result(notes, sl + 3)
        }

        /** モチーフ：リズム型に輪郭を載せた動機。いったん作ったら使い回す */
        private fun motif(pos: Double): Result {
            val first = pendingMotif
            val shape = when {
                first != null -> first.also { pendingMotif = null }
                motif == null || rng.nextDouble() < F.MOTIF_RENEW_PROB -> FusionMotifs.random(rng)
                else -> motif!!
            }
            motif = shape
            val start = land(cur, at(pos), 0)
            val notes = shape.rhythm.mapIndexed { i, o ->
                val s = pos + o
                val next = shape.rhythm.getOrNull(i + 1)
                val d = if (next != null) (next - o) * 0.9 else 3.0
                LeadStep(s, d, stepScale(start, at(s), shape.contour[i]), 0.7 + if (i == 0) 0.15 else 0.0, vibrato = next == null)
            }
            cur = notes.last().midi
            return Result(notes, pos + shape.rhythm.last() + 3 + 2)
        }

        /** 回り込み：次の拍の頭の着地音を、半音上→半音下→着地音で挟む */
        private fun enclose(pos: Double): Result {
            val landStep = ceil((pos + 2) / 4) * 4
            val t = land(cur, at(landStep), 0)
            val d = 3 + rng.nextInt(4)
            cur = t
            return Result(
                listOf(
                    LeadStep(landStep - 2, 0.95, minOf(F.LEAD_MAX_MIDI, t + 1), 0.65, chromatic = true),
                    LeadStep(landStep - 1, 0.95, maxOf(F.LEAD_MIN_MIDI, t - 1), 0.65, chromatic = true),
                    LeadStep(landStep, d.toDouble(), t, 0.85, vibrato = d >= 4),
                ),
                landStep + d,
            )
        }

        /** 泣きのハイトーン：80〜88 へ140msで滑り上がって伸ばす。50%でフォール */
        private fun cry(pos: Double): Result {
            val ch = at(pos)
            val t = nearestPc(F.HIGH_TONE_TARGET, ch.tensionPcs() + ch.targetPcs(), 0, F.HIGH_TONE_MIN, F.LEAD_MAX_MIDI)
            val d = 8 + rng.nextInt(6)
            val fall = rng.nextDouble() < 0.5
            cur = if (fall) stepScale(t - 4, at(pos + d), 0) else t
            return Result(listOf(LeadStep(pos, d.toDouble(), t, 0.95, fall = fall, vibrato = true, glideSec = F.GLIDE_HIGH_TONE_SEC)), pos + d + 1)
        }

        /** ひと呼吸：2〜(2+3(1−h)) ステップの休み */
        private fun breath(pos: Double) = Result(emptyList(), pos + 2 + floor(rng.nextDouble() * 4 * (1 - h)))

        /** キメ：着地に使う音を下から順に上っていく。最後の音は4ステップ伸ばしてビブラート */
        private fun kimeLead(bar: Int): List<LeadStep> {
            val base = bar * F.STEPS_PER_BAR.toDouble()
            val ch = at(base)
            var m = maxOf(F.LEAD_MIN_MIDI, minOf(cur, F.KIME_START_CAP) - F.KIME_START_DROP)
            return KIME_HITS.mapIndexed { i, k ->
                m = land(m, ch, 1)
                val last = i == KIME_HITS.size - 1
                LeadStep(base + k, if (last) 4.0 else (KIME_HITS[i + 1] - k) * 0.6, m, 0.9, vibrato = last)
            }.also { cur = m }
        }
    }

    /** 到着：テーマをロングトーンで吹き（1〜5小節目）、キメのあと DM9 の上で主音を伸ばす */
    private fun arrivalLead(block: FusionBlock, events: MutableList<FusionEvent>) {
        val p = place
        val contour = if (p != null) FusionMotifs.themeContour(p.themeSeed) else listOf(0, 1, 2)
        val notes = ArrayList<LeadStep>()
        val startChord = block.chords[0]
        val start = land(cur, startChord, 0)
        contour.dropLast(1).forEachIndexed { i, c ->
            val step = i * F.STEPS_PER_BAR.toDouble()
            notes += LeadStep(step, 12.0, stepScale(start, block.chords[i], c), 0.85, scoop = if (i == 0) 2 else 0, vibrato = true)
        }
        // テーマの最後は主音（D）で終わる
        val lastStep = (contour.size - 1) * F.STEPS_PER_BAR.toDouble()
        val tonic = nearestPc(notes.lastOrNull()?.midi ?: start, setOf(F.TONIC_ROOT_PC), 0)
        notes += LeadStep(lastStep, 12.0, tonic, 0.9, vibrato = true)
        cur = tonic
        // キメ（6小節目）
        val kimeBase = ARRIVAL_KIME_BAR * F.STEPS_PER_BAR.toDouble()
        var m = maxOf(F.LEAD_MIN_MIDI, minOf(cur, F.KIME_START_CAP) - F.KIME_START_DROP)
        val kimeChord = block.chords[ARRIVAL_KIME_BAR]
        KIME_HITS.forEachIndexed { i, k ->
            m = nearestPc(m, kimeChord.targetPcs(), 1)
            val last = i == KIME_HITS.size - 1
            notes += LeadStep(kimeBase + k, if (last) 3.0 else (KIME_HITS[i + 1] - k) * 0.6, m, 0.9)
        }
        // 7・8小節目：DM9 の上で主音を2小節伸ばす
        val landStep = (ARRIVAL_KIME_BAR + 1) * F.STEPS_PER_BAR.toDouble()
        val home = nearestPc(m, setOf(F.TONIC_ROOT_PC), 0)
        notes += LeadStep(landStep, 2.0 * F.STEPS_PER_BAR - 2, home, 0.9, vibrato = true)
        cur = home
        writeLead(block, notes, events)
    }

    /** ステップ → 秒。次の音の手前で必ず離し、ブロックの終わりを越えない */
    private fun writeLead(block: FusionBlock, raw: List<LeadStep>, events: MutableList<FusionEvent>) {
        val notes = raw.sortedBy { it.step }
        val vibCents = F.VIBRATO_BASE_CENTS + F.VIBRATO_HEAT_CENTS * block.heat
        var lastOnset = Double.NEGATIVE_INFINITY
        for ((i, n) in notes.withIndex()) {
            val t = maxOf(block.startSec + n.step * block.stepSec, lastOnset + F.LEAD_MIN_ONSET_GAP_SEC)
            val nextT = if (i + 1 < notes.size) block.startSec + notes[i + 1].step * block.stepSec else block.endSec
            val dur = maxOf(F.LEAD_MIN_DURATION_SEC, minOf(n.steps * block.stepSec * F.LEAD_LENGTH_FACTOR, nextT - t - F.LEAD_RELEASE_GAP_SEC))
            val glide = n.glideSec ?: if (n.steps <= 1.0) F.GLIDE_SHORT_SEC else F.GLIDE_NORMAL_SEC
            events += LeadNote(t, dur, n.midi, n.velocity, glide, n.scoop, n.fall, if (n.vibrato) vibCents else 0.0, n.chromatic)
            lastOnset = t
        }
    }

    // ---------------- 音階の道具 ----------------

    private fun scaleOf(ch: FusionChord): List<Int> =
        SCALE_CACHE.getOrPut(ch.keyPc) { (F.LEAD_MIN_MIDI..F.LEAD_MAX_MIDI).filter { (it % 12) in ch.scalePcs } }

    /** 調の長音階の上で、m に一番近い音から n 度動かす */
    private fun stepScale(m: Int, ch: FusionChord, n: Int): Int {
        val l = scaleOf(ch)
        val i = l.indices.minBy { abs(l[it] - m) }
        return l[(i + n).coerceIn(0, l.size - 1)]
    }

    private fun land(m: Int, ch: FusionChord, dir: Int) = nearestPc(m, ch.targetPcs(), dir)

    /** pcs のうち m に一番近い音（dir>0 なら上、<0 なら下だけ） */
    private fun nearestPc(m: Int, pcs: Set<Int>, dir: Int, lo: Int = F.LEAD_MIN_MIDI, hi: Int = F.LEAD_MAX_MIDI): Int {
        var best: Int? = null
        var bestD = Int.MAX_VALUE
        for (x in lo..hi) {
            if ((x % 12) !in pcs) continue
            if (dir > 0 && x <= m) continue
            if (dir < 0 && x >= m) continue
            val d = abs(x - m)
            if (d < bestD) {
                bestD = d
                best = x
            }
        }
        return best ?: nearestPc(m, pcs, 0)
    }

    companion object {
        private const val KEEP_BLOCKS = 3
        /** 到着ブロック：6小節目（0始まりで5）がキメ、7・8小節目が DM9 */
        const val ARRIVAL_KIME_BAR = 5
        private val SCALE_CACHE = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>()

        val JOURNEY_CYCLE = listOf(FusionSection.A, FusionSection.A, FusionSection.B, FusionSection.B)

        /** 到着の和音：A セクションの1〜5小節目 → A7sus4（キメ）→ DM9 ×2 */
        val ARRIVAL_CHORDS: List<FusionChord> =
            FusionSection.A.chords.take(ARRIVAL_KIME_BAR) +
                FusionChord(9, FusionQuality.SUS4, 2) + FusionChord.TONIC + FusionChord.TONIC

        val KIME_HITS = intArrayOf(0, 3, 6, 8, 10, 12)
        private val BACKBEAT = intArrayOf(4, 12)
        private val GHOST_STEPS = intArrayOf(2, 7, 9, 15)
        private val SEQ_CELLS = listOf(intArrayOf(0, 1, 2, 4), intArrayOf(0, 2, 1, 3), intArrayOf(0, -1, 1, 2), intArrayOf(2, 1, 0, -1))
        private val FILL = arrayOf(
            DrumKind.SNARE, DrumKind.TOM_HIGH, DrumKind.TOM_HIGH, DrumKind.TOM_MID,
            DrumKind.TOM_MID, DrumKind.TOM_LOW, DrumKind.TOM_LOW, DrumKind.SNARE,
        )

        private data class KeysHit(val start: Int, val length: Int, val anticipate: Boolean = false)
        private data class BassHit(val step: Int, val degree: Char, val length: Int)

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

        fun speedBpm(speed: Speed) = when (speed) {
            Speed.STILL -> F.STILL_BPM
            Speed.WALK -> F.WALK_BPM
            Speed.VEHICLE -> F.VEHICLE_BPM
        }

        fun speedHeat(speed: Speed) = when (speed) {
            Speed.STILL -> F.STILL_HEAT
            Speed.WALK -> F.WALK_HEAT
            Speed.VEHICLE -> F.VEHICLE_HEAT
        }
    }
}

/**
 * 人間らしさ（12.9）。合成の直前に、ブロックごとのシードで発音時刻と強さを揺らす。
 * 同じ楽器の発音は時刻順に並べ直して4ms以上空け、リードは次の音の3ms前までに離す。
 */
internal class Humanizer(private val rng: Mulberry32) {

    fun apply(events: List<FusionEvent>, block: FusionBlock): List<FusionEvent> {
        val jittered = events.map { e ->
            when (e) {
                is PolyNote -> e.copy(
                    timeSec = maxOf(block.startSec, e.timeSec + jitter(if (e.part == PolyPart.BASS) F.JITTER_BASS_SEC else F.JITTER_KEYS_SEC)),
                    velocity = vel(e.velocity),
                )
                is DrumHit -> {
                    val backbeat = e.kind == DrumKind.SNARE && e.velocity > F.GHOST_VELOCITY && isBackbeat(e.timeSec, block)
                    val amount = when (e.kind) {
                        DrumKind.KICK -> F.JITTER_KICK_SEC
                        DrumKind.HAT, DrumKind.OPEN_HAT -> F.JITTER_HAT_SEC
                        DrumKind.CRASH -> 0.0
                        else -> F.JITTER_SNARE_SEC
                    }
                    val pitched = e.kind == DrumKind.KICK || e.kind.name.startsWith("TOM")
                    e.copy(
                        timeSec = maxOf(block.startSec, e.timeSec + jitter(amount) + if (backbeat) F.BACKBEAT_DELAY_SEC else 0.0),
                        velocity = vel(e.velocity),
                        pitchFactor = if (pitched) 1.0 + jitter(F.PITCH_VARIATION) else 1.0,
                    )
                }
                is LeadNote -> e.copy(timeSec = maxOf(block.startSec, e.timeSec + jitter(F.JITTER_LEAD_SEC)), velocity = vel(e.velocity))
                else -> e
            }
        }
        // 同じ楽器ごとに時刻順に並べ直し、4ms以上空ける
        val result = ArrayList<FusionEvent>(jittered.size)
        jittered.filterNot { it is PolyNote || it is DrumHit || it is LeadNote }.forEach { result += it }
        jittered.filterIsInstance<PolyNote>().filter { it.part == PolyPart.BASS }.let { result += spaced(it) { e, t -> e.copy(timeSec = t) } }
        result += spacedChords(jittered.filterIsInstance<PolyNote>().filter { it.part == PolyPart.BRASS })
        jittered.filterIsInstance<DrumHit>().groupBy { drumGroup(it.kind) }.values.forEach { list ->
            result += spaced(list) { e, t -> e.copy(timeSec = t) }
        }
        result += leadSpaced(jittered.filterIsInstance<LeadNote>())
        return result.sortedBy { it.timeSec }
    }

    private fun jitter(amount: Double) = (rng.nextDouble() - 0.5) * 2 * amount

    private fun vel(v: Double) = minOf(1.0, v * (1 - F.VELOCITY_VARIATION + rng.nextDouble() * 2 * F.VELOCITY_VARIATION))

    private fun isBackbeat(t: Double, block: FusionBlock): Boolean {
        val step = Math.round((t - block.startSec) / block.stepSec) % F.STEPS_PER_BAR
        return step == 4L || step == 12L
    }

    private fun drumGroup(k: DrumKind) = when (k) {
        DrumKind.HAT, DrumKind.OPEN_HAT -> "hat"
        DrumKind.TOM_HIGH, DrumKind.TOM_MID, DrumKind.TOM_LOW -> "tom"
        else -> k.name
    }

    private fun <T : FusionEvent> spaced(list: List<T>, withTime: (T, Double) -> T): List<T> {
        var last = Double.NEGATIVE_INFINITY
        return list.sortedBy { it.timeSec }.map { e ->
            val t = maxOf(e.timeSec, last + F.MIN_ONSET_GAP_SEC)
            last = t
            withTime(e, t)
        }
    }

    /** シンセブラスは和音（同時に弾く音の組）ごとに揃えたまま、組どうしを4ms以上空ける */
    private fun spacedChords(list: List<PolyNote>): List<PolyNote> = spaced(list) { e, t -> e.copy(timeSec = t) }

    private fun leadSpaced(list: List<LeadNote>): List<LeadNote> {
        val sorted = list.sortedBy { it.timeSec }
        var last = Double.NEGATIVE_INFINITY
        val moved = sorted.map { e ->
            val t = maxOf(e.timeSec, last + F.LEAD_MIN_ONSET_GAP_SEC)
            last = t
            e.copy(timeSec = t)
        }
        // 次の音の3ms以上前に離す
        return moved.mapIndexed { i, e ->
            val next = moved.getOrNull(i + 1) ?: return@mapIndexed e
            val maxDur = next.timeSec - e.timeSec - F.LEAD_RELEASE_GAP_SEC
            if (e.durSec > maxDur) e.copy(durSec = maxOf(F.LEAD_MIN_DURATION_SEC.coerceAtMost(maxDur), maxDur)) else e
        }
    }
}
