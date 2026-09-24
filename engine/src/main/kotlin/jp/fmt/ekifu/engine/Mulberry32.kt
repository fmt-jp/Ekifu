package jp.fmt.ekifu.engine

/** シード付き乱数（Web 試作と同じ Mulberry32）。同じシードなら同じ列を返す。 */
class Mulberry32(seed: Int) {
    private var state = seed

    fun nextInt(): Int {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = (t + (t xor (t ushr 7)) * (t or 61)) xor t
        return t xor (t ushr 14)
    }

    fun copyFrom(other: Mulberry32) {
        state = other.state
    }

    /** [0, 1) の一様乱数。 */
    fun nextDouble(): Double = (nextInt().toLong() and 0xFFFFFFFFL) / 4_294_967_296.0

    /** [0, bound) の整数。 */
    fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt().coerceAtMost(bound - 1)
}
