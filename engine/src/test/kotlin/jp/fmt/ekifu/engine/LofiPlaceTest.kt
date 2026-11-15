package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.LofiConstants as L

/** 曲調「ローファイ」段階7b：曲の組み立て（始まり・道中・区切り）と登録地点の演出 */
class LofiPlaceTest {

    private val park = Place("park", "公園", Mood.CALM, 12345)
    private val walkDay = Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)

    private class Run(val c: LofiComposer, val events: List<FusionEvent>, val bars: List<LofiComposer.BarInfo>)

    /** 0.1秒ずつ進め、inputs（秒 → 入力）を入れる。小節ごとの情報も集める */
    private fun run(
        sec: Double,
        inputs: List<Pair<Double, (ComposerInput) -> Unit>> = emptyList(),
        config: LofiComposerConfig = LofiComposerConfig(),
        chime: Boolean = true,
    ): Run {
        val c = LofiComposer(4, config).also { it.setScene(walkDay); it.setArrivalChime(chime) }
        val events = ArrayList<FusionEvent>()
        val bars = LinkedHashMap<Double, LofiComposer.BarInfo>()
        var next = 0
        var t = 0.0
        while (t < sec) {
            while (next < inputs.size && inputs[next].first <= t) inputs[next++].second(c)
            events += c.composeUntil(t + MusicConstants.COMPOSE_LOOKAHEAD_SEC)
            c.barAt(t)?.let { bars[it.startSec] = it }
            t += 0.1
        }
        events += c.composeUntil(sec + 10.0)
        return Run(c, events, bars.values.toList())
    }

    private fun inBar(r: Run, b: LofiComposer.BarInfo) =
        r.events.filter { it.timeSec >= b.startSec - 0.03 && it.timeSec < b.startSec + b.stepSec * 16 - 0.03 }

    @Test
    fun startIsMuffledChordsThenDrumsComeIn() {
        val r = run(40.0)
        val start = r.bars.filter { it.phase == Phase.START }
        assertEquals(L.START_BARS, start.size)
        for (b in start) {
            assertTrue(inBar(r, b).none { it is DrumHit }, "始まりにドラム")
            assertEquals(L.MUFFLED_CUTOFF_HZ, b.cutoffHz)
        }
        val journey = r.bars.first { it.phase == Phase.JOURNEY }
        assertTrue(inBar(r, journey).any { it is DrumHit && it.kind == DrumKind.KICK })
        assertEquals(L.cutoffHz(SunLevel.DAY), journey.cutoffHz)
    }

    @Test
    fun journeyMelodyIsInTheFirstHalfOfEachBlock() {
        val r = run(150.0)
        val journey = r.bars.filter { it.phase == Phase.JOURNEY }
        val firstJourney = r.bars.indexOfFirst { it.phase == Phase.JOURNEY }
        journey.forEach { b ->
            val k = (r.bars.indexOf(b) - firstJourney) % L.BLOCK_BARS
            val lead = inBar(r, b).filterIsInstance<LeadNote>()
            if (k >= L.MELODY_BARS) assertTrue(lead.isEmpty(), "後半の小節 $k でメロディ")
        }
        // 前半はモチーフから弾く（ブロックの1小節目はモチーフそのまま）
        val head = r.bars[firstJourney]
        assertEquals(Motifs.placeMotif(walkDay.gridId), inBar(r, head).filterIsInstance<LeadNote>().map { it.midi })
    }

    @Test
    fun interludeMufflesAndDropsTheDrums() {
        val r = run(150.0, config = LofiComposerConfig(interludeEverySec = 40.0))
        val inter = r.bars.filter { it.phase == Phase.INTERLUDE }
        assertTrue(inter.size >= L.INTERLUDE_BARS)
        inter.take(L.INTERLUDE_BARS).forEach { b ->
            assertTrue(inBar(r, b).none { it is DrumHit })
            assertEquals(L.MUFFLED_CUTOFF_HZ, b.cutoffHz)
        }
        val after = r.bars[r.bars.indexOf(inter[L.INTERLUDE_BARS - 1]) + 1]
        assertEquals(Phase.JOURNEY, after.phase)
    }

    @Test
    fun approachReactsNextBarWithBreakBellsThenPianoTheme() {
        val r = run(80.0, listOf(31.0 to { c -> c.approach(park) }))
        val first = r.bars.first { it.phase == Phase.APPROACH }
        assertTrue(first.startSec < 31.0 + 3.0 + 0.6, "次の小節から接近（${first.startSec}）")
        val i = r.bars.indexOf(first)
        val theme = Motifs.theme(park.themeSeed)
        // 最初の2小節はドラム（キック・ハイハット）なし、ベルでテーマを大きく
        for (k in 0 until L.ENTRY_BREAK_BARS) {
            val ev = inBar(r, r.bars[i + k])
            assertTrue(ev.none { it is DrumHit && (it.kind == DrumKind.KICK || it.kind == DrumKind.HAT) }, "ブレイクにドラム")
            val bells = ev.filterIsInstance<BellNote>()
            assertEquals(theme, bells.map { it.midi })
            assertEquals(if (k == 0) F.BELL_ENTRY_GAIN else F.BELL_ENTRY_ECHO_GAIN, bells.first().gain, 1e-9)
        }
        // 最後の拍のスネアでバンドが戻る
        assertTrue(inBar(r, r.bars[i + 1]).filterIsInstance<DrumHit>().count { it.kind == DrumKind.SNARE } >= 3)
        // 3小節目でピアノがテーマをそのまま
        val third = inBar(r, r.bars[i + 2])
        assertEquals(theme, third.filterIsInstance<LeadNote>().map { it.midi })
        assertTrue(third.any { it is DrumHit && it.kind == DrumKind.KICK })
        // 次のブロックの頭ではピアノとベルでテーマ
        val nextBlock = inBar(r, r.bars[i + L.BLOCK_BARS])
        assertEquals(theme, nextBlock.filterIsInstance<LeadNote>().map { it.midi })
        assertEquals(theme, nextBlock.filterIsInstance<BellNote>().map { it.midi })
    }

    @Test
    fun arrivalStopsWithChimeThenThemeThenLandsAndStays() {
        for (chime in listOf(true, false)) {
            val r = run(120.0, listOf(31.0 to { c -> c.arrive(park) }), chime = chime)
            val arrive = r.bars.filter { it.phase == Phase.ARRIVE }
            assertEquals(L.BLOCK_BARS, arrive.size)
            assertEquals(1, arrive.first().arrival)
            assertEquals(park, arrive.first().place)
            val bar0 = inBar(r, arrive[0])
            // 1小節目：伴奏を止めて合図（読み上げだけならベルなし）
            assertTrue(bar0.none { it is PolyNote }, "合図の小節に和音")
            assertTrue(bar0.none { it is DrumHit && it.kind != DrumKind.KICK })
            val chimeNotes = bar0.filterIsInstance<BellNote>().map { it.midi }
            assertEquals(if (chime) L.ARRIVAL_CHIME_MIDI.toList() else emptyList(), chimeNotes)
            // 2小節目：テーマ（ピアノとベル）
            val theme = Motifs.theme(park.themeSeed)
            assertEquals(theme, inBar(r, arrive[L.ARRIVAL_THEME_BAR]).filterIsInstance<LeadNote>().map { it.midi })
            // 着地は主和音
            assertEquals(LofiChord.FMAJ9, arrive[L.ARRIVAL_LAND_BAR].chord)
            // そのあと滞在：キックとリムだけ
            val stay = r.bars.filter { it.phase == Phase.STAY }
            assertTrue(stay.size >= 4)
            stay.forEach { b ->
                assertNull(b.arrival)
                assertTrue(inBar(r, b).filterIsInstance<DrumHit>().all { it.kind == DrumKind.KICK || it.kind == DrumKind.RIM })
            }
        }
    }

    @Test
    fun stayBellsEveryOtherBlockButNotRightAfterArrival() {
        val r = run(200.0, listOf(31.0 to { c -> c.arrive(park) }))
        val stayStart = r.bars.indexOfFirst { it.phase == Phase.STAY }
        val blocks = (0 until 3).map { r.bars[stayStart + it * L.BLOCK_BARS] }
        assertTrue(inBar(r, blocks[0]).none { it is BellNote }, "到着直後の滞在ブロックでベル")
        assertEquals(F.BELL_STAY_GAIN, inBar(r, blocks[1]).filterIsInstance<BellNote>().first().gain, 1e-9)
        assertTrue(inBar(r, blocks[2]).none { it is BellNote })
    }

    @Test
    fun leavingGoesToJourneyNextBarWithTheThemeOnce() {
        val r = run(120.0, listOf(20.0 to { c -> c.stay(park) }, 60.0 to { c -> c.leave() }))
        val leaveBar = r.bars.first { it.startSec > 60.0 && it.phase == Phase.JOURNEY }
        assertTrue(leaveBar.startSec < 60.0 + 3.0 + 0.6)
        assertNull(leaveBar.place)
        val ev = inBar(r, leaveBar)
        assertEquals(Motifs.theme(park.themeSeed), ev.filterIsInstance<LeadNote>().map { it.midi })
        assertEquals(F.BELL_LEAVE_GAIN, ev.filterIsInstance<BellNote>().first().gain, 1e-9)
        // 次のブロックの頭ではテーマを弾かない（場所のモチーフ）
        val i = r.bars.indexOf(leaveBar)
        assertEquals(Motifs.placeMotif(walkDay.gridId), inBar(r, r.bars[i + L.BLOCK_BARS]).filterIsInstance<LeadNote>().map { it.midi })
    }

    @Test
    fun stayWithoutArrivalHasNoChimeAndNoSerial() {
        val r = run(60.0, listOf(5.0 to { c -> c.stay(park) }))
        assertTrue(r.events.filterIsInstance<BellNote>().none { it.gain == F.BELL_CHIME_GAIN })
        assertTrue(r.bars.all { it.arrival == null })
    }

    @Test
    fun approachAfterStartBeginsWithTheEntryBreak() {
        val r = run(40.0, listOf(0.0 to { c -> c.approachAfterStart(park) }))
        val i = r.bars.indexOfFirst { it.phase == Phase.APPROACH }
        assertEquals(L.START_BARS, i)
        assertEquals(Motifs.theme(park.themeSeed), inBar(r, r.bars[i]).filterIsInstance<BellNote>().map { it.midi })
    }

    @Test
    fun reArrivalStartsTheArrivalAgain() {
        val r = run(60.0, listOf(10.0 to { c -> c.arrive(park) }, 20.0 to { c -> c.arrive(park) }))
        val serials = r.bars.mapNotNull { it.arrival }.distinct()
        assertEquals(listOf(1, 2), serials)
    }

    @Test
    fun demoEngineFollowsTheCommuteScript() {
        val e = LofiEngine.demo(null, 0.0, 8000)
        val phases = HashSet<Phase>()
        val buf = ShortArray(8000 * 2)
        repeat(DemoScript.COMMUTE.cues.last().atSec.toInt() + 90) {
            e.render(buf, 8000, 0)
            phases += e.status().composer.phase
        }
        for (p in listOf(Phase.START, Phase.JOURNEY, Phase.APPROACH, Phase.ARRIVE, Phase.STAY)) assertTrue(p in phases, "$p がない")
        assertNotNull(e.status().composer.scene)
    }
}
