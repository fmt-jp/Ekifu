package jp.fmt.ekifu.engine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.MusicConstants as C

/** 曲調の切り替え（段階6c、12.12） */
class StyleSwitchTest {

    private val sr = C.SAMPLE_RATE
    private val block = 2048

    private fun pump(stream: ChunkStream, seconds: Double, check: (StreamStatus) -> Unit = {}): ShortArray {
        val frames = (seconds * sr).toInt() / block * block
        val out = ShortArray(frames * 2)
        val buf = ShortArray(block * 2)
        var done = 0
        while (done < frames) {
            while (stream.needsWork()) stream.work()
            val n = stream.read(buf, block)
            assertEquals(block, n)
            System.arraycopy(buf, 0, out, done * 2, block * 2)
            done += block
            check(stream.status())
        }
        return out
    }

    private fun rms(a: ShortArray, fromFrame: Int, toFrame: Int): Double {
        var s = 0.0
        for (i in fromFrame * 2 until toFrame * 2) s += a[i].toDouble() * a[i]
        return sqrt(s / ((toFrame - fromFrame) * 2)) / 32768
    }

    private fun demo(style: MusicStyle, atSec: Double = 0.0) =
        StyleEngines.create(style, seed = 7, demo = true, scriptStartSec = atSec)

    @Test
    fun switchFadesOutAndContinuesWithTheNewEngineFromItsStart() {
        val stream = ChunkStream(demo(MusicStyle.HEALING))
        val before = pump(stream, 40.0)
        val switchFrame = (stream.status().playSec * sr).toLong()
        var timeline = -1.0
        assertTrue(stream.switchEngine { t -> timeline = t; demo(MusicStyle.FUSION, t) })
        val fadeFrames = (C.STYLE_SWITCH_FADE_SEC * sr).toInt()
        assertEquals((switchFrame + fadeFrames).toDouble() / sr, timeline, 1e-9, "台本は切り替えた位置の続きから")

        val after = pump(stream, 30.0)
        // 約2秒でフェードアウトする
        val head = rms(after, 0, sr / 10)
        val tail = rms(after, fadeFrames - sr / 10, fadeFrames)
        assertTrue(head > 0.01, "切り替え直後はまだ鳴っている: $head")
        assertTrue(tail < head * 0.1, "フェードアウトしていない: $head → $tail")
        assertTrue(before.isNotEmpty())

        // その先は新しい曲調のエンジンを直接鳴らしたのと同じ
        val fresh = demo(MusicStyle.FUSION, timeline)
        val n = after.size / 2 - fadeFrames
        val expected = ShortArray(n * 2).also { fresh.render(it, n) }
        assertContentEquals(expected, after.copyOfRange(fadeFrames * 2, after.size))
        assertEquals(0, stream.status().underruns)
        assertNotNull(stream.status().engine?.composer?.heat, "フュージョンの状態が出る")
    }

    @Test
    fun newStyleStartsFromStartPhase() {
        val stream = ChunkStream(demo(MusicStyle.FUSION))
        pump(stream, 90.0)
        stream.switchEngine { t -> demo(MusicStyle.HEALING, t) }
        val phases = ArrayList<Phase>()
        pump(stream, 10.0) { it.engine?.composer?.phase?.let(phases::add) }
        assertEquals(Phase.START, phases.last())
        assertNull(stream.status().engine?.composer?.heat)
    }

    @Test
    fun switchIsIgnoredWhileEnding() {
        val stream = ChunkStream(demo(MusicStyle.HEALING))
        pump(stream, 20.0)
        stream.stop()
        var called = false
        assertFalse(stream.switchEngine { called = true; demo(MusicStyle.FUSION) })
        assertFalse(called)
    }

    @Test
    fun inputAfterSwitchDoesNotRewindToTheOldStyle() {
        val stream = ChunkStream(demo(MusicStyle.HEALING))
        pump(stream, 40.0)
        stream.switchEngine { t -> demo(MusicStyle.FUSION, t) }
        pump(stream, 1.5)
        stream.post { it.setScene(DAY_WALK) }
        pump(stream, 30.0) { st ->
            if (st.playSec > 42.5) assertNotNull(st.engine?.composer?.heat, "癒しに戻った: ${st.playSec}")
        }
        assertEquals(0, stream.status().underruns)
    }

    @Test
    fun stopRightAfterSwitchEndsInTheNewStyle() {
        val stream = ChunkStream(demo(MusicStyle.HEALING))
        pump(stream, 30.0)
        stream.switchEngine { t -> demo(MusicStyle.FUSION, t) }
        pump(stream, 0.5)
        stream.stop()
        val out = pump(stream, 11.0)
        assertTrue(rms(out, 3 * sr, 4 * sr) > 0.01, "終わりのキメが鳴る")
    }

    @Test
    fun styleDemoEqualsTheEngineDemos() {
        val n = 20 * sr
        fun out(e: SoundEngine) = ShortArray(n * 2).also { e.render(it, n) }
        assertContentEquals(out(MusicEngine.demo()), out(StyleEngines.demo(MusicStyle.HEALING)))
        assertContentEquals(out(FusionEngine.demo()), out(StyleEngines.demo(MusicStyle.FUSION)))
    }

    @Test
    fun demoCarryOverFollowsTheScript() {
        val s = DemoScript.COMMUTE
        val atPark = s.carryOverAt(60.0 + 7.5 * C.DEMO_SCENE_SEC)
        assertEquals(DemoScript.PARK, atPark.stayAt)
        assertNull(atPark.approaching)

        val nearPark = s.carryOverAt(60.0 + 6.5 * C.DEMO_SCENE_SEC)
        assertEquals(DemoScript.PARK, nearPark.approaching)
        assertNull(nearPark.stayAt)

        val afterPark = s.carryOverAt(60.0 + 9 * C.DEMO_SCENE_SEC)
        assertNull(afterPark.stayAt)
        assertNull(afterPark.approaching)
        assertEquals("demo07", afterPark.scene?.gridId)

        assertEquals(CarryOver(), s.carryOverAt(0.0).copy(scene = null))
    }

    @Test
    fun switchingInsideTheInnerCircleGoesStraightToStay() {
        for (style in MusicStyle.entries) {
            val e = demo(style, 60.0 + 7.5 * C.DEMO_SCENE_SEC)
            val buf = ShortArray(sr * 2)
            val phases = ArrayList<Phase>()
            repeat(20) { e.render(buf, sr); phases += e.status().composer.phase }
            assertTrue(Phase.ARRIVE !in phases, "$style: 到着の演出は鳴らさない $phases")
            assertEquals(Phase.STAY, phases.last(), "$style: $phases")
            assertEquals(DemoScript.PARK, e.status().composer.place, "$style")
        }
    }

    @Test
    fun switchingWhileApproachingStartsThenApproaches() {
        for (style in MusicStyle.entries) {
            val carry = CarryOver(DAY_WALK, approaching = DemoScript.PARK)
            val e = StyleEngines.create(style, seed = 7, demo = false, carry = carry)
            val buf = ShortArray(sr * 2)
            val phases = ArrayList<Phase>()
            repeat(80) { e.render(buf, sr); phases += e.status().composer.phase }
            assertEquals(Phase.START, phases.first(), "$style: $phases")
            val firstApproach = phases.indexOf(Phase.APPROACH)
            assertTrue(firstApproach > 0, "$style: 始まりのあと接近に入る $phases")
            assertTrue(phases.subList(0, firstApproach).all { it == Phase.START }, "$style: $phases")
            assertEquals(DemoScript.PARK, e.status().composer.place, "$style")
        }
    }

    @Test
    fun approachAfterStartIsCancelledByLaterInput() {
        val c = Composer(3).also { it.setScene(DAY_WALK); it.approachAfterStart(DemoScript.PARK); it.leave() }
        compose(c, 80.0)
        assertTrue(c.status().phase != Phase.APPROACH)
    }
}
