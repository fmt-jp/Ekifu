package jp.fmt.ekifu.engine.sf2

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * アプリに入れる音源（サウンドフォントから使う楽器だけを抜き出し、鳴らしやすい形に平らにしたもの）。
 * SF2 のプリセットと楽器の重なり（ジェネレーター）は抜き出すときに解決してあり、鍵盤の範囲ごとの「ゾーン」だけを持つ。
 */
class SampleBank(val presets: List<BankPreset>, val samples: List<BankSample>) {

    fun preset(bank: Int, program: Int): BankPreset? = presets.firstOrNull { it.bank == bank && it.program == program }

    /** 全サンプルの長さ（フレーム） */
    val totalFrames: Long get() = samples.sumOf { it.data.size.toLong() }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(presets.size)
        for (p in presets) {
            d.writeUTF(p.name)
            d.writeShort(p.bank)
            d.writeShort(p.program)
            d.writeInt(p.zones.size)
            for (z in p.zones) z.write(d)
        }
        d.writeInt(samples.size)
        for (s in samples) {
            d.writeUTF(s.name)
            d.writeInt(s.sampleRate)
            d.writeInt(s.data.size)
            val buf = ByteBuffer.allocate(s.data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(s.data)
            d.write(buf.array())
        }
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x454B4231 // "EKB1"

        fun read(input: InputStream): SampleBank {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "ekifu の音源ファイルではない" }
            val presets = List(d.readInt()) {
                val name = d.readUTF()
                val bank = d.readShort().toInt()
                val program = d.readShort().toInt()
                BankPreset(name, bank, program, List(d.readInt()) { BankZone.read(d) })
            }
            val samples = List(d.readInt()) {
                val name = d.readUTF()
                val rate = d.readInt()
                val n = d.readInt()
                val bytes = ByteArray(n * 2)
                d.readFully(bytes)
                val data = ShortArray(n)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
                BankSample(name, rate, data)
            }
            return SampleBank(presets, samples)
        }
    }
}

class BankPreset(val name: String, val bank: Int, val program: Int, val zones: List<BankZone>) {
    /** key と強さ（0〜127）に当たるゾーン（ステレオの左右など、重なることがある） */
    fun zonesFor(key: Int, velocity: Int): List<BankZone> =
        zones.filter { key in it.keyLo..it.keyHi && velocity in it.velLo..it.velHi }
            .ifEmpty { listOfNotNull(zones.minByOrNull { z -> minOf(kotlin.math.abs(key - z.keyLo), kotlin.math.abs(key - z.keyHi)) }) }
}

class BankSample(val name: String, val sampleRate: Int, val data: ShortArray)

/** 鍵盤の範囲ごとの鳴らし方。時間は秒、音量は cB（センチベル。大きいほど小さい音） */
data class BankZone(
    val keyLo: Int,
    val keyHi: Int,
    val velLo: Int,
    val velHi: Int,
    val sample: Int,
    /** サンプルの中で鳴らす範囲とループ（フレーム） */
    val start: Int,
    val end: Int,
    val loopStart: Int,
    val loopEnd: Int,
    /** 0：ループなし、1：ずっとループ、3：離すまでループして最後まで */
    val loopMode: Int,
    val rootKey: Int,
    /** 音程の補正（セント） */
    val tuneCents: Double,
    /** 1鍵あたりの音程（セント。ふつう100） */
    val scaleTuning: Int,
    val attenuationCb: Double,
    val filterHz: Double,
    val delaySec: Double,
    val attackSec: Double,
    val holdSec: Double,
    val decaySec: Double,
    val sustainCb: Double,
    val releaseSec: Double,
    val exclusiveClass: Int,
) {
    internal fun write(d: DataOutputStream) {
        intArrayOf(keyLo, keyHi, velLo, velHi, sample, start, end, loopStart, loopEnd, loopMode, rootKey, scaleTuning, exclusiveClass)
            .forEach { d.writeInt(it) }
        doubleArrayOf(tuneCents, attenuationCb, filterHz, delaySec, attackSec, holdSec, decaySec, sustainCb, releaseSec)
            .forEach { d.writeDouble(it) }
    }

    companion object {
        internal fun read(d: DataInputStream): BankZone {
            val i = IntArray(13) { d.readInt() }
            val f = DoubleArray(9) { d.readDouble() }
            return BankZone(
                keyLo = i[0], keyHi = i[1], velLo = i[2], velHi = i[3], sample = i[4],
                start = i[5], end = i[6], loopStart = i[7], loopEnd = i[8], loopMode = i[9],
                rootKey = i[10], scaleTuning = i[11], exclusiveClass = i[12],
                tuneCents = f[0], attenuationCb = f[1], filterHz = f[2], delaySec = f[3], attackSec = f[4],
                holdSec = f[5], decaySec = f[6], sustainCb = f[7], releaseSec = f[8],
            )
        }
    }
}
