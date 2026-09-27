package jp.fmt.ekifu.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/** フュージョンで地点のテーマ（試聴と同じ音の並び）をリードがそのまま吹く */
class FusionThemeTest {

    private val walkDay = Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)
    private val park = Place("park", "公園", Mood.CALM, 12345)
    private val station = Place("station", "駅", Mood.BRIGHT, 777)

    /** 0.05秒ずつ進めて先読みぶんまで作曲する。入力はブロック番号の頭の少し後で入れる */
    private fun run(blocks: Int, inputs: Map<Int, (ComposerInput) -> Unit>): Pair<List<FusionEvent>, List<FusionBlock>> {
        val c = FusionComposer(1).also { it.setScene(walkDay) }
        val events = ArrayList<FusionEvent>()
        val infos = LinkedHashMap<Int, FusionBlock>()
        val done = HashSet<Int>()
        var t = 0.0
        while (infos.size <= blocks) {
            val b = c.blockAt(t)
            if (b != null) {
                infos[b.index] = b
                if (t > b.startSec + 1.0 && done.add(b.index)) inputs[b.index]?.invoke(c)
            }
            events += c.composeUntil(t + C.COMPOSE_LOOKAHEAD_SEC)
            t += 0.05
        }
        return events to infos.values.sortedBy { it.index }
    }

    private fun leadIn(events: List<FusionEvent>, b: FusionBlock, fromStep: Double, toStep: Double) =
        events.filterIsInstance<LeadNote>()
            .filter { it.timeSec >= b.startSec + (fromStep - 0.5) * b.stepSec && it.timeSec < b.startSec + (toStep - 0.5) * b.stepSec }
            .sortedBy { it.timeSec }

    private fun intervals(notes: List<Int>) = notes.zipWithNext { a, b -> b - a }

    private fun stepOf(n: LeadNote, b: FusionBlock) = ((n.timeSec - b.startSec) / b.stepSec).roundToInt()

    private fun assertTheme(place: Place, notes: List<LeadNote>, b: FusionBlock, noteSteps: Int, what: String) {
        val theme = Motifs.theme(place.themeSeed)
        assertEquals(theme.size, notes.size, "$what：テーマの音の数")
        assertEquals(intervals(theme), intervals(notes.map { it.midi }), "$what：テーマの音の並び")
        notes.forEach { assertTrue(it.midi in F.LEAD_MIN_MIDI..F.LEAD_MAX_MIDI) }
        val first = stepOf(notes.first(), b)
        notes.forEachIndexed { i, n -> assertEquals(first + i * noteSteps, stepOf(n, b), "$what：${i + 1}音目の位置") }
        // 調に移しているので、どの音も調の音階に入る
        val ch = b.chordAt(notes.first().timeSec)
        notes.forEach { assertTrue((it.midi % 12) in ch.scalePcs, "$what：調の外の音 ${it.midi}") }
    }

    @Test
    fun everyApproachBlockOpensWithTheTheme() {
        val (events, blocks) = run(8, mapOf(2 to { it.approach(park) }))
        val approach = blocks.filter { it.phase == Phase.APPROACH }
        assertTrue(approach.size >= 3)
        assertTrue(approach.first().entry && approach.drop(1).none { it.entry })
        for (b in approach) {
            val n = Motifs.theme(park.themeSeed).size
            // 入った最初のブロックはベルだけの2小節のあとからリードが吹く
            val from = if (b.entry) (F.ENTRY_BREAK_BARS * F.STEPS_PER_BAR).toDouble() else 0.0
            val notes = leadIn(events, b, from, from + (n - 1) * F.THEME_NOTE_STEPS + 1)
            assertTheme(park, notes, b, F.THEME_NOTE_STEPS, "接近 ${b.index}")
        }
    }

    @Test
    fun arrivalPlaysTheThemeThenSlowlyAgain() {
        val (events, blocks) = run(8, mapOf(2 to { it.approach(station) }, 4 to { it.arrive(station) }))
        val arrive = blocks.first { it.phase == Phase.ARRIVE }
        val bar = F.STEPS_PER_BAR.toDouble()
        assertTheme(station, leadIn(events, arrive, 0.0, 2 * bar), arrive, F.THEME_NOTE_STEPS, "到着1・2小節目")
        assertTheme(station, leadIn(events, arrive, 2 * bar, 5 * bar), arrive, F.ARRIVAL_THEME_SLOW_STEPS, "到着3〜5小節目")
        // テーマは主音（D）で終わる
        assertEquals(F.TONIC_ROOT_PC, leadIn(events, arrive, 2 * bar, 5 * bar).last().midi % 12)
    }

    @Test
    fun leavingOpensTheNextBlockWithTheThemeOnce() {
        val (events, blocks) = run(10, mapOf(1 to { it.arrive(park) }, 4 to { it.leave() }))
        val leave = blocks.first { it.index > 2 && it.phase == Phase.JOURNEY }
        val n = Motifs.theme(park.themeSeed).size
        val window = ((n - 1) * F.THEME_NOTE_STEPS + 1).toDouble()
        assertTheme(park, leadIn(events, leave, 0.0, window), leave, F.THEME_NOTE_STEPS, "離れる")
        // 次のブロックはふだんの道中（頭がテーマと同じ並び・同じ間隔にはならない）
        val next = blocks.first { it.index == leave.index + 1 }
        val notes = leadIn(events, next, 0.0, window)
        val same = notes.size == n && intervals(notes.map { it.midi }) == intervals(Motifs.theme(park.themeSeed)) &&
            notes.withIndex().all { (i, x) -> abs(stepOf(x, next) - i * F.THEME_NOTE_STEPS) == 0 }
        assertTrue(!same, "離れたあとも毎ブロックテーマを吹いている")
    }

    /** ブロック b のベル。次の小節で区切られたブロックは、次のブロックの頭までを見る */
    private fun bellsIn(events: List<FusionEvent>, b: FusionBlock, blocks: List<FusionBlock>): List<BellNote> {
        val end = blocks.firstOrNull { it.index == b.index + 1 }?.startSec ?: b.endSec
        return events.filterIsInstance<BellNote>().filter { it.timeSec >= b.startSec - 0.01 && it.timeSec < end - 0.01 }.sortedBy { it.timeSec }
    }

    @Test
    fun bellRingsTheThemeLikeThePreview() {
        val (events, blocks) = run(14, mapOf(2 to { it.approach(park) }, 4 to { it.arrive(park) }, 10 to { it.leave() }))
        val theme = Motifs.theme(park.themeSeed)
        fun check(b: FusionBlock, gain: Double, what: String, passes: Int = 1) {
            val bells = bellsIn(events, b, blocks)
            assertEquals(theme.size * passes, bells.size, "$what：ベルの数")
            bells.chunked(theme.size).forEach { pass ->
                // 試聴と同じ音の並び（調だけ移す）
                assertEquals(intervals(theme), intervals(pass.map { it.midi }), "$what：ベルの音の並び")
                assertTrue(abs(pass.first().midi - theme.first()) <= 6, "$what：試聴の音域から離れすぎ")
            }
            assertEquals(gain, bells.first().gain, 1e-9, "$what：ベルの音量")
        }
        blocks.filter { it.phase == Phase.APPROACH }.forEach {
            if (it.entry) check(it, F.BELL_ENTRY_GAIN, "接近の入り ${it.index}", passes = 2) else check(it, F.BELL_APPROACH_GAIN, "接近 ${it.index}")
        }
        val arrive = blocks.first { it.phase == Phase.ARRIVE }
        check(arrive, F.BELL_ARRIVE_GAIN, "到着", passes = 2)
        // 滞在：到着の直後のブロックは鳴らさず、そこから1ブロックおき
        val stays = blocks.filter { it.phase == Phase.STAY }
        assertTrue(stays.size >= 4)
        stays.forEachIndexed { i, b ->
            if ((i + 1) % F.BELL_STAY_EVERY_BLOCKS == 0) check(b, F.BELL_STAY_GAIN, "滞在 ${b.index}")
            else assertTrue(bellsIn(events, b, blocks).isEmpty(), "滞在 ${b.index} で鳴った")
        }
        val leave = blocks.first { it.index > arrive.index && it.phase == Phase.JOURNEY }
        check(leave, F.BELL_LEAVE_GAIN, "離れる")
        assertTrue(bellsIn(events, blocks.first { it.index == leave.index + 1 }, blocks).isEmpty(), "離れたあとも鳴り続けた")
        // 道中だけのブロックでは鳴らさない
        assertTrue(blocks.filter { it.index < 2 }.all { bellsIn(events, it, blocks).isEmpty() })
    }

    @Test
    fun bellIsAudibleInTheMix() {
        val synth = FusionSynth()
        val theme = Motifs.theme(park.themeSeed)
        synth.schedule(theme.mapIndexed { i, m -> BellNote(0.1 + i * 0.45, m, F.BELL_ARRIVE_GAIN) })
        val frames = C.SAMPLE_RATE * 3
        val out = ShortArray(frames * 2)
        synth.render(out, frames, 0)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue(peak > 3000, "ベルが小さすぎる: $peak")
        assertTrue(peak < 32767, "ベルが割れている")
    }
}
