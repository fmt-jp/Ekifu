package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.Envelope
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/** 合成（音色・エンジン）のテスト */
class FusionEngineTest {

    private val sr = C.SAMPLE_RATE
    private val block = 2048

    private fun render(engine: SoundEngine, seconds: Double): ShortArray {
        val frames = (seconds * sr).toInt() / block * block
        return ShortArray(frames * 2).also { engine.render(it, frames) }
    }

    @Test
    fun copiedEngineContinuesIdentically() {
        val a = FusionEngine.demo()
        render(a, 23.0)
        val b = a.copy()
        assertContentEquals(render(a, 30.0), render(b, 30.0))
    }

    @Test
    fun chunkedOutputEqualsDirectRenderWithoutUnderruns() {
        val stream = ChunkStream(FusionEngine.demo())
        val frames = 90 * sr / block * block
        val out = ShortArray(frames * 2)
        val buf = ShortArray(block * 2)
        var done = 0
        while (done < frames) {
            while (stream.needsWork()) stream.work()
            assertEquals(block, stream.read(buf, block))
            System.arraycopy(buf, 0, out, done * 2, block * 2)
            done += block
        }
        assertContentEquals(render(FusionEngine.demo(), 90.0), out)
        assertEquals(0, stream.status().underruns)
    }

    @Test
    fun demoIsAudibleWithoutClippingAndFastEnough() {
        val engine = FusionEngine.demo()
        val started = System.nanoTime()
        // 台本の職場到着のあとまで（約9分半）
        val seconds = 580.0
        val frames = (seconds * sr).toInt() / block * block
        val pcm = ShortArray(frames * 2)
        val buf = ShortArray(block * 2)
        var done = 0
        var lastPhase: Phase? = null
        while (done < frames) {
            engine.render(buf, block)
            System.arraycopy(buf, 0, pcm, done * 2, block * 2)
            done += block
            val st = engine.status().composer
            if (st.phase != lastPhase) {
                lastPhase = st.phase
                println("%6.1f秒 %s  %s  熱量 %.2f  %.0f BPM".format(done.toDouble() / sr, st.phase.label, st.chordLabel, st.heat, 60 / st.beatSec))
            }
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        println("フュージョン：${"%.0f".format(seconds)}秒ぶん / ${"%.1f".format(elapsed)}秒（${"%.0f".format(seconds / elapsed)}倍速）")
        System.getenv("EKIFU_FUSION_WAV")?.let { writeWav(File(it), pcm) }

        var clipped = 0
        pcm.forEach { if (abs(it.toInt()) >= 32700) clipped++ }
        assertTrue(clipped < sr / 100, "音割れ: $clipped")
        val window = sr * 2 * 5
        var i = window // 最初の5秒（始まりの立ち上がり）は除く
        while (i + window <= pcm.size) {
            var s = 0.0
            for (j in i until i + window) s += pcm[j].toDouble() * pcm[j]
            val rms = sqrt(s / window) / 32768
            assertTrue(rms > 0.02, "${i / 2 / sr}秒あたりが小さすぎる: $rms")
            i += window
        }
        // 端末（JVM より遅い）でも余裕を持てるよう、JVM で実時間の10倍以上
        assertTrue(seconds / elapsed > 10, "合成が遅い: ${seconds / elapsed}倍速")
    }

    @Test
    fun stopPlaysKimeOnTheGridThenFades() {
        val engine = FusionEngine.demo()
        render(engine, 30.0)
        val st = engine.status()
        val now = engine.elapsedSec + 0.05
        val ending = engine.endingRenderer(st, now)
        val out = ShortArray((F.ENDING_FADE_SEC * sr).toInt() * 2)
        ending.render(out, out.size / 2, 0)
        val head = out.copyOfRange(0, sr * 2 * 2).maxOf { abs(it.toInt()) }
        val tail = out.copyOfRange(out.size - sr, out.size).maxOf { abs(it.toInt()) }
        assertTrue(head > 1000, "キメが聞こえない: $head")
        assertTrue(tail < head * 0.05, "フェードアウトしきっていない: $tail / $head")

        val filler = engine.fillerRenderer(st)
        val f = ShortArray(sr * 3 * 2)
        filler.render(f, sr * 3, 0)
        assertTrue(f.copyOfRange(sr * 2 * 2, f.size).maxOf { abs(it.toInt()) } > 500, "つなぎの和音が伸びていない")
    }

    @Test
    fun voicingFollowsReferenceFormula() {
        for (section in FusionSection.entries) {
            for (ch in section.chords) {
                val base = ch.keysVoicing[0] - ch.quality.voice[0]
                // 参照実装：ルート+48、54を超えたら−12、45未満なら+12（G・G♯ は範囲に収まらず 55・56 になる）
                var expected = ch.rootPc + 48
                if (expected > 54) expected -= 12
                if (expected < 45) expected += 12
                assertEquals(expected, base, ch.name)
                assertTrue(ch.bassRoot <= F.BASS_BASE_MAX, ch.name)
            }
        }
    }

    @Test
    fun envelopeRetriggerNeverJumps() {
        val env = Envelope(sr, F.LEAD_AMP_ENV)
        val maxStep = 1.0 / (F.LEAD_AMP_ENV.attackSec * sr) + 1e-9
        var prev = 0.0
        val rng = Mulberry32(3)
        repeat(200_000) { i ->
            if (i % 997 == 0) env.gateOn()
            if (i % 1531 == 0 || rng.nextDouble() < 0.0005) env.gateOff()
            val v = env.next()
            assertTrue(abs(v - prev) <= maxStep, "エンベロープが跳んだ: $prev → $v")
            prev = v
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size * 2)
            f.write(h.array())
            val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcm)
            f.write(bb.array())
        }
    }
}

/** 作曲のテスト（12.10） */
class FusionComposerTest {

    private val walkDay = Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)
    private val park = Place("park", "公園", Mood.CALM, 12345)

    /** 1ブロックずつ作曲を進め、ブロックの頭で入力を入れる */
    private fun compose(
        composer: FusionComposer,
        blocks: Int,
        inputs: Map<Int, (ComposerInput) -> Unit> = emptyMap(),
    ): Pair<List<FusionEvent>, List<FusionBlock>> {
        val events = ArrayList<FusionEvent>()
        val infos = ArrayList<FusionBlock>()
        var t = 0.0
        for (b in 0 until blocks) {
            inputs[b]?.invoke(composer)
            events += composer.composeUntil(t + 1e-6)
            val info = composer.blockAt(t + 1e-6)!!
            infos += info
            t = info.endSec
        }
        return events to infos
    }

    private fun newComposer(seed: Int = 1) = FusionComposer(seed).also { it.setScene(walkDay) }

    private fun eventsIn(events: List<FusionEvent>, b: FusionBlock) =
        events.filter { it.timeSec >= b.startSec - 0.02 && it.timeSec < b.endSec - 0.02 }

    @Test
    fun sameSeedGivesSameEvents() {
        val inputs = mapOf<Int, (ComposerInput) -> Unit>(4 to { it.approach(park) }, 6 to { it.arrive(park) }, 9 to { it.leave() })
        val a = compose(newComposer(), 14, inputs).first
        val b = compose(newComposer(), 14, inputs).first
        assertEquals(a, b)
        assertNotEquals(a, compose(newComposer(2), 14, inputs).first)
    }

    @Test
    fun leadStaysInRangeAndInKey() {
        val (events, blocks) = compose(newComposer(), 40, mapOf(10 to { it.setScene(walkDay.copy(speed = Speed.VEHICLE)) }))
        val lead = events.filterIsInstance<LeadNote>()
        assertTrue(lead.size > 500)
        lead.forEach { assertTrue(it.midi in F.LEAD_MIN_MIDI..F.LEAD_MAX_MIDI, "音域外: ${it.midi}") }
        // 調の外の音（回り込みの半音を除く）が3%以下
        var outside = 0
        var counted = 0
        for (n in lead) {
            if (n.chromatic) continue
            val b = blocks.last { n.timeSec >= it.startSec - 0.01 }
            val chord = b.chordAt(n.timeSec + 0.001)
            counted++
            if ((n.midi % 12) !in chord.scalePcs) outside++
        }
        println("調の外の音: $outside / $counted")
        assertTrue(outside.toDouble() / counted <= 0.03, "調の外の音が多い: $outside / $counted")
    }

    @Test
    fun onsetsOfEachInstrumentIncreaseWithGap() {
        val events = compose(newComposer(), 20).first
        fun check(list: List<Double>, gap: Double, name: String) {
            list.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= gap - 1e-9, "$name の発音が近すぎる: $a → $b") }
        }
        check(events.filterIsInstance<LeadNote>().map { it.timeSec }, F.LEAD_MIN_ONSET_GAP_SEC, "リード")
        check(events.filterIsInstance<PolyNote>().filter { it.part == PolyPart.BASS }.map { it.timeSec }, F.MIN_ONSET_GAP_SEC, "ベース")
        check(events.filterIsInstance<PolyNote>().filter { it.part == PolyPart.BRASS }.map { it.timeSec }, F.MIN_ONSET_GAP_SEC, "鍵盤")
        for (kind in listOf(DrumKind.KICK, DrumKind.SNARE, DrumKind.CRASH)) {
            check(events.filterIsInstance<DrumHit>().filter { it.kind == kind }.map { it.timeSec }, F.MIN_ONSET_GAP_SEC, kind.name)
        }
        check(
            events.filterIsInstance<DrumHit>().filter { it.kind == DrumKind.HAT || it.kind == DrumKind.OPEN_HAT }.map { it.timeSec },
            F.MIN_ONSET_GAP_SEC, "ハイハット",
        )
        // リードは次の音の3ms前までに離す
        events.filterIsInstance<LeadNote>().zipWithNext().forEach { (a, b) ->
            assertTrue(a.timeSec + a.durSec <= b.timeSec - 0.003 + 1e-9, "リードが重なる: $a / $b")
        }
    }

    @Test
    fun heatStaysInRange() {
        for (scene in listOf(
            walkDay.copy(speed = Speed.VEHICLE, familiarity = Familiarity.NEW),
            walkDay.copy(speed = Speed.STILL, familiarity = Familiarity.FAMILIAR, sun = SunLevel.NIGHT),
        )) {
            val c = FusionComposer(7).also { it.setScene(scene) }
            val blocks = compose(c, 30, mapOf(12 to { it.approach(park) })).second
            blocks.forEach { assertTrue(it.heat in 0.0..1.0, "熱量が範囲外: ${it.heat}") }
        }
    }

    @Test
    fun startRestsLeadAndUsesOnlyHats() {
        val (events, blocks) = compose(newComposer(), 2)
        assertEquals(Phase.START, blocks[0].phase)
        val first = eventsIn(events, blocks[0])
        assertTrue(first.none { it is LeadNote }, "始まりのブロックでリードが鳴っている")
        // 最後の小節は道中への呼び込み（スネアとキック）
        val lastBarSec = blocks[0].startSec + (F.STEPS_PER_BLOCK - F.STEPS_PER_BAR - 0.5) * blocks[0].stepSec
        assertTrue(
            first.filterIsInstance<DrumHit>().filter { it.timeSec < lastBarSec }
                .all { it.kind == DrumKind.HAT || it.kind == DrumKind.OPEN_HAT },
        )
        assertEquals(F.START_HEAT, blocks[0].heat)
        assertEquals(Phase.JOURNEY, blocks[1].phase)
        assertTrue(eventsIn(events, blocks[1]).any { it is LeadNote })
    }

    @Test
    fun approachSwitchesToSectionBFromNextBlock() {
        val (_, blocks) = compose(newComposer(), 8, mapOf(4 to { it.approach(park) }))
        assertEquals(Phase.APPROACH, blocks[4].phase)
        (4 until 8).forEach { assertEquals(FusionSection.B, blocks[it].section) }
        // 落ち着く地点はテンポ −12
        assertEquals(F.WALK_BPM + F.CALM_BPM_DELTA, blocks[5].bpm, 1e-9)
    }

    @Test
    fun arrivalLandsOnTonicThenStays() {
        val (events, blocks) = compose(newComposer(), 9, mapOf(3 to { it.approach(park) }, 5 to { it.arrive(park) }))
        val arrive = blocks[5]
        assertEquals(Phase.ARRIVE, arrive.phase)
        assertEquals(FusionChord.TONIC, arrive.chords[6])
        val barSec = arrive.stepSec * F.STEPS_PER_BAR
        val landing = arrive.startSec + 6 * barSec
        val brassAtLanding = events.filterIsInstance<PolyNote>()
            .filter { it.part == PolyPart.BRASS && abs(it.timeSec - landing) < 0.05 }.map { it.midi }.sorted()
        assertEquals(FusionChord.TONIC.keysVoicing.sorted(), brassAtLanding)
        val bass = events.filterIsInstance<PolyNote>().first { it.part == PolyPart.BASS && abs(it.timeSec - landing) < 0.05 }
        assertTrue(bass.durSec > barSec * 1.9, "DM9 を2小節伸ばしていない")
        val leadHome = events.filterIsInstance<LeadNote>().first { abs(it.timeSec - landing) < 0.05 }
        assertEquals(F.TONIC_ROOT_PC, leadHome.midi % 12)
        assertEquals(Phase.STAY, blocks[6].phase)
        assertEquals(FusionSection.A, blocks[6].section)
        assertEquals(F.STAY_HEAT, blocks[6].heat)
        // 滞在のドラムはハイハットとキックだけ
        assertTrue(eventsIn(events, blocks[6]).filterIsInstance<DrumHit>().all {
            it.kind == DrumKind.HAT || it.kind == DrumKind.OPEN_HAT || it.kind == DrumKind.KICK
        })
    }

    @Test
    fun leavingReturnsToJourneyFromSectionA() {
        val (_, blocks) = compose(newComposer(), 12, mapOf(3 to { it.arrive(park) }, 6 to { it.leave() }))
        assertEquals(Phase.JOURNEY, blocks[6].phase)
        assertEquals(FusionSection.A, blocks[6].section)
        assertEquals(null, blocks[6].place)
    }

    @Test
    fun interludeBreaksWithKimeAtTheEnd() {
        val c = FusionComposer(1, FusionComposerConfig(interludeEverySec = 60.0)).also { it.setScene(walkDay) }
        val blocks = compose(c, 12).second
        val interlude = blocks.first { it.phase == Phase.INTERLUDE }
        assertEquals(setOf(6, 7), interlude.kimeBars)
        assertEquals(Phase.JOURNEY, blocks[interlude.index + 1].phase)
    }

    @Test
    fun sameGridGivesSameFirstMotifShape() {
        assertEquals(FusionMotifs.place("xn76ur"), FusionMotifs.place("xn76ur"))
        val shapes = (0 until 100).map { FusionMotifs.place("grid$it") }.toSet()
        assertTrue(shapes.size > 20, "場所のモチーフの形が少なすぎる: ${shapes.size}")
        // 接近中はテーマの形
        assertEquals(FusionMotifs.theme(park.themeSeed), FusionMotifs.theme(park.themeSeed))
    }

    @Test
    fun tempoFollowsSpeedAndChangesAtBlockHead() {
        val (events, blocks) = compose(newComposer(), 6, mapOf(3 to { it.setScene(walkDay.copy(speed = Speed.VEHICLE)) }))
        assertEquals(F.WALK_BPM, blocks[2].bpm, 1e-9)
        assertEquals(F.VEHICLE_BPM, blocks[3].bpm, 1e-9)
        // ディレイは付点8分
        val control = events.filterIsInstance<FusionControl>().first { abs(it.timeSec - blocks[3].startSec) < 1e-9 }
        assertEquals(blocks[3].stepSec * F.DELAY_STEPS, control.delaySec, 1e-9)
    }

    @Test
    fun nightDarkensAndCoolsHeat() {
        val night = FusionComposer(1).also { it.setScene(walkDay.copy(sun = SunLevel.NIGHT)) }
        val day = FusionComposer(1).also { it.setScene(walkDay) }
        val nb = compose(night, 6).second
        val db = compose(day, 6).second
        assertEquals(F.NIGHT_CUTOFF_HZ, nb[3].cutoffHz)
        // 2ブロック目までは乱数の進み方が同じなので、熱量はちょうど −0.1
        assertEquals(db[1].heat - 0.1, nb[1].heat, 1e-9)
    }
}
