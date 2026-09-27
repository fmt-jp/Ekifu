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
        for (b in approach) {
            val n = Motifs.theme(park.themeSeed).size
            val notes = leadIn(events, b, 0.0, ((n - 1) * F.THEME_NOTE_STEPS + 1).toDouble())
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
}
