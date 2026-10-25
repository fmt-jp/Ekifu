package jp.fmt.ekifu.engine.sf2

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * SF2（サウンドフォント2）を読み、指定したプリセットだけを [SampleBank] に抜き出す。
 * プリセットと楽器のジェネレーター（鍵盤の範囲・音程・音量・エンベロープなど）はここで解決する。
 * モジュレーターは読まない（強さによる音量は鳴らす側で決める）。
 */
object Sf2Reader {

    /** 抜き出すプリセット。keys が null なら全鍵盤、あればその鍵盤のゾーンだけ（ドラムセット用） */
    data class Pick(val bank: Int, val program: Int, val keys: Set<Int>? = null)

    fun extract(sf2: ByteArray, picks: List<Pick>): SampleBank {
        val f = Sf2File(sf2)
        val samples = ArrayList<BankSample>()
        val sampleIndex = HashMap<Int, Int>() // shdr の番号 → samples の番号
        val presets = picks.map { pick ->
            val p = f.presets.firstOrNull { it.bank == pick.bank && it.program == pick.program }
                ?: error("プリセット ${pick.bank}:${pick.program} がない")
            val zones = f.flatten(p).filter { z ->
                pick.keys == null || pick.keys.any { it in z.keyLo..z.keyHi }
            }.map { z ->
                val sIdx = sampleIndex.getOrPut(z.shdr) {
                    val h = f.shdrs[z.shdr]
                    samples += BankSample(h.name, h.sampleRate, f.sampleData(h.start, h.end + GUARD))
                    samples.size - 1
                }
                z.zone.copy(sample = sIdx)
            }.map { z -> if (pick.keys == null) z else z.copy(keyLo = maxOf(z.keyLo, pick.keys.min()), keyHi = minOf(z.keyHi, pick.keys.max())) }
            BankPreset(p.name, p.bank, p.program, zones)
        }
        return SampleBank(presets, samples)
    }

    /** サンプルの終わりのあとにも少し残す（補間で次の点を読むため） */
    private const val GUARD = 8

    // ---------------- SF2 の中身 ----------------

    private class Preset(val name: String, val program: Int, val bank: Int, val bag: Int)
    private class Inst(val name: String, val bag: Int)
    private class Shdr(
        val name: String, val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int,
        val sampleRate: Int, val originalPitch: Int, val pitchCorrection: Int,
    )
    private class FlatZone(val shdr: Int, val zone: BankZone, val keyLo: Int, val keyHi: Int)

    private class Sf2File(val bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val chunks = HashMap<String, Pair<Int, Int>>() // "pdta/phdr" → (位置, 長さ)

        init {
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "sfbk") { "SF2 ではない" }
            var off = 12
            while (off + 8 <= bytes.size) {
                val size = buf.getInt(off + 4)
                if (String(bytes, off, 4) == "LIST") {
                    val type = String(bytes, off + 8, 4)
                    var o = off + 12
                    while (o < off + 8 + size) {
                        val s = buf.getInt(o + 4)
                        chunks["$type/${String(bytes, o, 4)}"] = (o + 8) to s
                        o += 8 + s + (s and 1)
                    }
                }
                off += 8 + size + (size and 1)
            }
        }

        private fun chunk(name: String) = chunks[name] ?: error("$name がない")

        private fun name(at: Int) = String(bytes, at, 20, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF

        val presets: List<Preset> = chunk("pdta/phdr").let { (o, s) ->
            List(s / 38) { i -> val a = o + i * 38; Preset(name(a), u16(a + 20), u16(a + 22), u16(a + 24)) }
        }
        private val pbag: List<Int> = chunk("pdta/pbag").let { (o, s) -> List(s / 4) { u16(o + it * 4) } }
        private val pgen: List<Pair<Int, Short>> = chunk("pdta/pgen").let { (o, s) ->
            List(s / 4) { u16(o + it * 4) to buf.getShort(o + it * 4 + 2) }
        }
        private val insts: List<Inst> = chunk("pdta/inst").let { (o, s) ->
            List(s / 22) { i -> val a = o + i * 22; Inst(name(a), u16(a + 20)) }
        }
        private val ibag: List<Int> = chunk("pdta/ibag").let { (o, s) -> List(s / 4) { u16(o + it * 4) } }
        private val igen: List<Pair<Int, Short>> = chunk("pdta/igen").let { (o, s) ->
            List(s / 4) { u16(o + it * 4) to buf.getShort(o + it * 4 + 2) }
        }
        val shdrs: List<Shdr> = chunk("pdta/shdr").let { (o, s) ->
            List(s / 46) { i ->
                val a = o + i * 46
                Shdr(
                    name(a), buf.getInt(a + 20), buf.getInt(a + 24), buf.getInt(a + 28), buf.getInt(a + 32),
                    buf.getInt(a + 36), bytes[a + 40].toInt() and 0xFF, bytes[a + 41].toInt(),
                )
            }
        }
        private val smpl = chunk("sdta/smpl")

        fun sampleData(from: Int, to: Int): ShortArray {
            val n = (minOf(to, smpl.second / 2) - from).coerceAtLeast(0)
            return ShortArray(n) { buf.getShort(smpl.first + (from + it) * 2) }
        }

        /** bag の範囲ごとのジェネレーター（番号 → 値） */
        private fun zonesOf(bags: List<Int>, gens: List<Pair<Int, Short>>, from: Int, to: Int): List<Map<Int, Short>> =
            (from until to).map { b -> (bags[b] until bags[b + 1]).associate { gens[it].first to gens[it].second } }

        fun flatten(p: Preset): List<FlatZone> {
            val pi = presets.indexOf(p)
            val pZones = zonesOf(pbag, pgen, p.bag, presets[pi + 1].bag)
            val pGlobal = pZones.firstOrNull()?.takeIf { INSTRUMENT !in it } ?: emptyMap()
            val out = ArrayList<FlatZone>()
            for (pz in pZones.filter { INSTRUMENT in it }) {
                val pg = pGlobal + pz
                val instIndex = pg.getValue(INSTRUMENT).toInt() and 0xFFFF
                val inst = insts[instIndex]
                val iZones = zonesOf(ibag, igen, inst.bag, insts[instIndex + 1].bag)
                val iGlobal = iZones.firstOrNull()?.takeIf { SAMPLE_ID !in it } ?: emptyMap()
                for (iz in iZones.filter { SAMPLE_ID in it }) {
                    val ig = iGlobal + iz
                    val (kLo, kHi) = intersect(range(pg[KEY_RANGE]), range(ig[KEY_RANGE])) ?: continue
                    val (vLo, vHi) = intersect(range(pg[VEL_RANGE]), range(ig[VEL_RANGE])) ?: continue
                    val sid = ig.getValue(SAMPLE_ID).toInt() and 0xFFFF
                    val h = shdrs[sid]
                    fun inst(gen: Int, def: Int) = ig[gen]?.toInt() ?: def
                    fun sum(gen: Int, def: Int) = inst(gen, def) + (pg[gen]?.toInt() ?: 0)
                    val start = inst(0, 0) + inst(4, 0) * 32768
                    val end = (h.end - h.start) + inst(1, 0) + inst(12, 0) * 32768
                    val loopStart = (h.loopStart - h.start) + inst(2, 0) + inst(45, 0) * 32768
                    val loopEnd = (h.loopEnd - h.start) + inst(3, 0) + inst(50, 0) * 32768
                    val root = inst(58, -1).takeIf { it >= 0 } ?: h.originalPitch
                    val zone = BankZone(
                        keyLo = kLo, keyHi = kHi, velLo = vLo, velHi = vHi, sample = sid,
                        start = start, end = end, loopStart = loopStart, loopEnd = loopEnd,
                        loopMode = inst(54, 0) and 3,
                        rootKey = root,
                        tuneCents = sum(51, 0) * 100.0 + sum(52, 0) + h.pitchCorrection,
                        scaleTuning = sum(56, 100),
                        // EMU の流儀に合わせ、減衰は 0.4 倍で効かせる（FluidSynth などと同じ）
                        attenuationCb = sum(48, 0) * ATTENUATION_FACTOR,
                        filterHz = 8.176 * 2.0.pow(sum(8, 13500) / 1200.0),
                        delaySec = timecents(sum(33, -12000)),
                        attackSec = timecents(sum(34, -12000)),
                        holdSec = timecents(sum(35, -12000)),
                        decaySec = timecents(sum(36, -12000)),
                        sustainCb = sum(37, 0).toDouble().coerceIn(0.0, 1440.0),
                        releaseSec = timecents(sum(38, -12000)),
                        exclusiveClass = inst(57, 0),
                    )
                    out += FlatZone(sid, zone, kLo, kHi)
                }
            }
            return out
        }

        private fun range(v: Short?): Pair<Int, Int> =
            if (v == null) 0 to 127 else (v.toInt() and 0xFF) to ((v.toInt() shr 8) and 0xFF)

        private fun intersect(a: Pair<Int, Int>, b: Pair<Int, Int>): Pair<Int, Int>? {
            val lo = maxOf(a.first, b.first)
            val hi = minOf(a.second, b.second)
            return if (lo <= hi) lo to hi else null
        }

        private fun timecents(tc: Int) = 2.0.pow(tc / 1200.0)
    }

    private const val INSTRUMENT = 41
    private const val KEY_RANGE = 43
    private const val VEL_RANGE = 44
    private const val SAMPLE_ID = 53
    private const val ATTENUATION_FACTOR = 0.4
}
