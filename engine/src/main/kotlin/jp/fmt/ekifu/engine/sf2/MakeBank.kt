package jp.fmt.ekifu.engine.sf2

import jp.fmt.ekifu.engine.BandConstants
import jp.fmt.ekifu.engine.LofiConstants
import java.io.File

/**
 * サウンドフォントから曲調で使う楽器だけを抜き出して、アプリの音源ファイルを作る。
 * `gradle :engine:makeBandBank --args="<GeneralUser-GS.sf2> <app/src/main/assets/band.ekb> [band|lofi]"`
 * （3つ目を省くと生音フュージョン。ローファイは lofi で app/src/main/assets/lofi.ekb）
 */
object MakeBank {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3) { "使い方：MakeBank <入力.sf2> <出力.ekb> [band|lofi]" }
        val kit = when (args.getOrNull(2) ?: "band") {
            "band" -> BandConstants.KIT
            "lofi" -> LofiConstants.KIT
            else -> error("楽器セットは band か lofi")
        }
        val bank = Sf2Reader.extract(File(args[0]).readBytes(), kit.picks)
        File(args[1]).outputStream().use { bank.write(it) }
        for (p in bank.presets) {
            println("${p.bank}:${p.program} ${p.name}：ゾーン ${p.zones.size}（鍵盤 ${p.zones.minOf { it.keyLo }}〜${p.zones.maxOf { it.keyHi }}）")
        }
        println("サンプル ${bank.samples.size}、${bank.totalFrames * 2 / 1024} KB → ${File(args[1]).length() / 1024} KB")
    }
}
