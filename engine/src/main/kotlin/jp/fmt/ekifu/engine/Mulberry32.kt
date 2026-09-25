package jp.fmt.ekifu.engine

/** シード付き乱数（Mulberry32）。同じシードなら同じ列を返す。 */
class Mulberry32(seed: Int) {
    private var state = seed

    fun copy(): Mulberry32 = Mulberry32(0).also { it.state = state }

    /** [0, 1) の一様乱数 */
    fun nextDouble(): Double {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        val r = t xor (t ushr 14)
        return (r.toLong() and 0xFFFF_FFFFL).toDouble() / 4_294_967_296.0
    }

    /** [0, bound) の整数 */
    fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt().coerceAtMost(bound - 1)

    /** [min, max) の実数 */
    fun nextDouble(min: Double, max: Double): Double = min + (max - min) * nextDouble()

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    companion object {
        /** 文字列から安定したシードを作る（FNV-1a 32bit）。マスIDなどに使う。 */
        fun seedOf(text: String): Int {
            var h = 0x811C9DC5.toInt()
            for (b in text.encodeToByteArray()) {
                h = h xor (b.toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }
    }
}
