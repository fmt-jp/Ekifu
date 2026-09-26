package jp.fmt.ekifu.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/** フュージョンのキメの型とフェーズのつなぎ */
class FusionTransitionTest {

    private val walkDay = Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)
    private val park = Place("park", "公園", Mood.CALM, 12345)

    private class Run(val events: List<FusionEvent>, val blocks: List<FusionBlock>)

    /** エンジンと同じく、少しずつ時刻を進めて先読みぶんまで作曲する。入力は (時刻, 入力) */
    private fun run(
        composer: FusionComposer,
        untilSec: Double,
        inputs: List<Pair<Double, (ComposerInput) -> Unit>> = emptyList(),
    ): Run {
        val events = ArrayList<FusionEvent>()
        val blocks = LinkedHashMap<Int, FusionBlock>()
        val sorted = inputs.sortedBy { it.first }
        var next = 0
        var t = 0.0
        while (t < untilSec) {
            while (next < sorted.size && sorted[next].first <= t) sorted[next++].second(composer)
            events += composer.composeUntil(t + C.COMPOSE_LOOKAHEAD_SEC)
            composer.blockAt(t)?.let { blocks[it.index] = it }
            t += 0.05
        }
        return Run(events, blocks.values.sortedBy { it.index })
    }

    private fun newComposer(seed: Int = 1) = FusionComposer(seed).also { it.setScene(walkDay) }

    /** ブロックの最後の小節にある、ステップ from..to（小節内）のイベント */
    private fun lastBar(r: Run, b: FusionBlock, from: Int = 0, to: Int = F.STEPS_PER_BAR - 1): List<FusionEvent> {
        val barStart = b.startSec + (F.STEPS_PER_BLOCK - F.STEPS_PER_BAR) * b.stepSec
        return r.events.filter {
            val step = ((it.timeSec - barStart) / b.stepSec).roundToInt()
            it.timeSec > barStart - b.stepSec / 2 && step in from..to
        }
    }

    private fun snares(events: List<FusionEvent>) = events.filterIsInstance<DrumHit>().filter { it.kind == DrumKind.SNARE }

    /** 16分のスネアをだんだん強く打っている（つなぎのロール） */
    private fun hasSnareRoll(r: Run, b: FusionBlock, from: Int) = snares(lastBar(r, b, from)).size >= F.STEPS_PER_BAR - from

    private fun blockStartingAfter(r: Run, sec: Double) = r.blocks.first { it.startSec > sec }

    @Test
    fun kimeRhythmsVaryAndDoNotRepeat() {
        val r = run(newComposer(), 60 * 14.6)
        val kimes = r.blocks.flatMap { b -> b.kimes.toSortedMap().values }
        assertTrue(kimes.size >= 20)
        assertTrue(kimes.toSet().size >= 4, "キメの型が少ない: ${kimes.toSet()}")
        kimes.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "同じ型が続いた: $kimes") }
    }

    @Test
    fun kimeHitsFollowTheChosenRhythm() {
        val r = run(newComposer(3), 40 * 14.6)
        for (b in r.blocks.filter { it.phase == Phase.JOURNEY && 7 in it.kimes }) {
            val kime = b.kimes.getValue(7)
            val next = r.blocks.firstOrNull { it.index == b.index + 1 } ?: continue
            if (next.phase != Phase.JOURNEY) continue
            val kicks = lastBar(r, b).filterIsInstance<DrumHit>().filter { it.kind == DrumKind.KICK }
            val barStart = b.startSec + (F.STEPS_PER_BLOCK - F.STEPS_PER_BAR) * b.stepSec
            val steps = kicks.map { ((it.timeSec - barStart) / b.stepSec).roundToInt() }.toSet()
            assertEquals(kime.hits.toSet(), steps, "${kime.label} のキックの位置")
            if (kime == FusionKime.BREAK) {
                // 頭の1発のあとは最後の1拍（スネア）までリード以外は休む
                val middle = lastBar(r, b, 1, F.BREAK_PICKUP_FROM - 1).filter { it !is LeadNote && it !is FusionControl }
                assertTrue(middle.isEmpty(), "ブレイク中に伴奏が鳴っている: $middle")
                assertEquals(4, snares(lastBar(r, b, F.BREAK_PICKUP_FROM)).size)
                assertTrue(lastBar(r, b, 1, F.BREAK_PICKUP_FROM - 1).any { it is LeadNote }, "ブレイクでリードが鳴らない")
            }
        }
    }

    @Test
    fun arrivalAndInterludeKimes() {
        val c = FusionComposer(1, FusionComposerConfig(interludeEverySec = 60.0)).also { it.setScene(walkDay) }
        val r = run(c, 14 * 14.6, listOf(8 * 14.6 to { it.arrive(park) }))
        val interlude = r.blocks.first { it.phase == Phase.INTERLUDE }
        assertEquals(setOf(6, 7), interlude.kimeBars)
        assertTrue(interlude.kimes[6] != interlude.kimes[7])
        assertTrue(interlude.kimes[6] != FusionKime.BREAK)
        val arrive = r.blocks.first { it.phase == Phase.ARRIVE }
        assertEquals(mapOf(FusionComposer.ARRIVAL_KIME_BAR to FusionKime.REFERENCE), arrive.kimes)
    }

    @Test
    fun approachIsBuiltUpInTheLastBarBeforeIt() {
        val c = newComposer()
        val probe = run(c.copy(), 3 * 14.6)
        val inputAt = probe.blocks[2].startSec + 3.0
        val r = run(c, 6 * 14.6, listOf(inputAt to { it.approach(park) }))
        val before = r.blocks.last { it.startSec < inputAt }
        assertEquals(Phase.APPROACH, blockStartingAfter(r, inputAt).phase)
        assertTrue(hasSnareRoll(r, before, F.BUILD_FULL_FROM), "盛り上げのスネアがない")
        // リードが16分で駆け上がる
        val lead = lastBar(r, before, F.BUILD_LEAD_FROM).filterIsInstance<LeadNote>().sortedBy { it.timeSec }
        assertEquals(F.STEPS_PER_BAR - F.BUILD_LEAD_FROM, lead.size)
        lead.zipWithNext().forEach { (a, b) -> assertTrue(b.midi >= a.midi, "駆け上がっていない") }
        // 接近しなければ、同じブロックの最後はふだんどおり
        assertTrue(!hasSnareRoll(probe, probe.blocks[2], F.BUILD_HALF_FROM))
    }

    @Test
    fun arrivalIsPrecededByABreak() {
        val c = newComposer()
        val r = run(c, 10 * 14.6, listOf(2 * 14.6 to { it.approach(park) }, 5.3 * 14.6 to { it.arrive(park) }))
        val arrive = r.blocks.first { it.phase == Phase.ARRIVE }
        val before = r.blocks.first { it.index == arrive.index - 1 }
        assertEquals(Phase.APPROACH, before.phase)
        val middle = lastBar(r, before, 1, F.BREAK_PICKUP_FROM - 1).filter { it !is LeadNote && it !is FusionControl }
        assertTrue(middle.isEmpty(), "ブレイク中に伴奏が鳴っている: $middle")
        assertEquals(4, snares(lastBar(r, before, F.BREAK_PICKUP_FROM)).size)
        val high = lastBar(r, before).filterIsInstance<LeadNote>().single()
        assertTrue(high.midi >= F.HIGH_TONE_MIN, "ハイトーンで残っていない: ${high.midi}")
    }

    @Test
    fun startCallsInTheJourney() {
        val r = run(newComposer(), 3 * 14.6)
        val start = r.blocks[0]
        assertEquals(Phase.START, start.phase)
        assertEquals(Phase.JOURNEY, r.blocks[1].phase)
        assertTrue(hasSnareRoll(r, start, F.BUILD_HALF_FROM), "始まりから道中への呼び込みがない")
    }

    @Test
    fun leavingAndStayingHaveTheirOwnTransitions() {
        val c = newComposer()
        val stayAt = 3.2 * 14.6
        // 滞在はテンポが落ちてブロックが長くなるので、離れる時刻は試しに流して決める
        val probe = run(c.copy(), 10 * 14.6, listOf(stayAt to { it.stay(park) }))
        val leaveAt = probe.blocks.filter { it.phase == Phase.STAY }[2].startSec + 3.0
        val r = run(c, 16 * 14.6, listOf(stayAt to { it.stay(park) }, leaveAt to { it.leave() }))
        val stay = r.blocks.first { it.phase == Phase.STAY }
        val beforeStay = r.blocks.first { it.index == stay.index - 1 }
        // 落ち着き：スネアなし、ハイハットがだんだん弱く
        assertTrue(snares(lastBar(r, beforeStay)).isEmpty())
        val hats = lastBar(r, beforeStay).filterIsInstance<DrumHit>().filter { it.kind == DrumKind.HAT }.sortedBy { it.timeSec }
        assertTrue(hats.size >= 6 && hats.first().velocity > hats.last().velocity)

        val journey = r.blocks.first { it.index > stay.index && it.phase == Phase.JOURNEY }
        val beforeJourney = r.blocks.first { it.index == journey.index - 1 }
        assertEquals(Phase.STAY, beforeJourney.phase)
        assertTrue(hasSnareRoll(r, beforeJourney, F.BUILD_HALF_FROM), "滞在から道中への呼び込みがない")
    }

    @Test
    fun noTransitionBetweenJourneyBlocks() {
        val r = run(newComposer(5), 30 * 14.6)
        r.blocks.zipWithNext().filter { (a, b) -> a.phase == Phase.JOURNEY && b.phase == Phase.JOURNEY }.forEach { (a, _) ->
            assertTrue(snares(lastBar(r, a, F.BUILD_HALF_FROM)).size <= 6, "道中どうしでつなぎが入った: ${a.index}")
        }
    }

    @Test
    fun inputDuringTheLastBarChangesPhaseWithoutTransition() {
        val c = newComposer()
        val probe = run(c.copy(), 4 * 14.6)
        val b = probe.blocks[2]
        // 最後の小節が決まったあと（鳴り始めの直前）に接近
        val inputAt = b.startSec + (F.STEPS_PER_BLOCK - F.STEPS_PER_BAR + 2) * b.stepSec
        val r = run(c, 5 * 14.6, listOf(inputAt to { it.approach(park) }))
        assertEquals(Phase.APPROACH, r.blocks[3].phase)
        assertTrue(!hasSnareRoll(r, r.blocks[2], F.BUILD_FULL_FROM))
    }

    @Test
    fun copyWhileLastBarIsHeldContinuesIdentically() {
        val a = newComposer()
        val inputs = listOf<Pair<Double, (ComposerInput) -> Unit>>(20.0 to { it.approach(park) }, 50.0 to { it.arrive(park) })
        // 最後の小節を持っている時刻まで進めてから複製する
        val mid = 10.0
        run(a, mid)
        val b = a.copy()
        fun rest(c: FusionComposer): List<FusionEvent> {
            val events = ArrayList<FusionEvent>()
            var t = mid
            var next = 0
            while (t < 90.0) {
                while (next < inputs.size && inputs[next].first <= t) inputs[next++].second(c)
                events += c.composeUntil(t + C.COMPOSE_LOOKAHEAD_SEC)
                t += 0.05
            }
            return events
        }
        assertEquals(rest(a), rest(b))
    }
}
