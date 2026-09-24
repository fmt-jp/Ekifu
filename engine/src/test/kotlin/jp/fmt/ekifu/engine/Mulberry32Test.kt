package jp.fmt.ekifu.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Mulberry32Test {
    @Test
    fun matchesWebPrototypeImplementation() {
        // JavaScript の mulberry32(12345) の先頭 5 個
        val expected = doubleArrayOf(0.9797282677609473, 0.3067522644996643, 0.484205421525985, 0.817934412509203, 0.5094283693470061)
        val rng = Mulberry32(12345)
        for (e in expected) assertEquals(e, rng.nextDouble(), 0.0)
    }

    @Test
    fun sameSeedGivesSameSequence() {
        val a = Mulberry32(7)
        val b = Mulberry32(7)
        repeat(1000) { assertEquals(a.nextInt(), b.nextInt()) }
    }
}
