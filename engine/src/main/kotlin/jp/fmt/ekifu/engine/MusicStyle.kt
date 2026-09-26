package jp.fmt.ekifu.engine

/** 曲調（SPEC 12章） */
enum class MusicStyle(val label: String) {
    HEALING("癒し"),
    FUSION("フュージョン"),
}

/**
 * 曲調を切り替えるとき、新しいエンジンへ引き継ぐ状態（12.12）。
 * 内側の円にいれば到着の演出なしで滞在から、接近中なら「始まり」のあと接近に入る。
 */
data class CarryOver(
    val scene: Scene? = null,
    val stayAt: Place? = null,
    val approaching: Place? = null,
) {
    fun applyTo(c: ComposerInput) {
        scene?.let { c.setScene(it) }
        when {
            stayAt != null -> c.stay(stayAt)
            approaching != null -> c.approachAfterStart(approaching)
        }
    }
}

/** 曲調ごとのエンジンを作る */
object StyleEngines {

    /** デモ再生のシード（曲調ごと。切り替えても同じ台本の続きを同じ曲で鳴らす） */
    fun demoSeed(style: MusicStyle): Int = when (style) {
        MusicStyle.HEALING -> MusicConstants.DEMO_SEED
        MusicStyle.FUSION -> FusionConstants.DEMO_SEED
    }

    /** デモ再生（台本は曲調によらず同じ）。scriptStartSec から続きを鳴らす */
    fun demo(style: MusicStyle, scriptStartSec: Double = 0.0, sampleRate: Int = MusicConstants.SAMPLE_RATE): SoundEngine =
        create(style, demoSeed(style), demo = true, scriptStartSec = scriptStartSec, sampleRate = sampleRate)

    /**
     * @param demo デモ台本で鳴らすか（台本は曲調によらず同じ）
     * @param scriptStartSec デモ台本のどこから始めるか（曲調の切り替えで続きから鳴らすとき）
     * @param carry 引き継ぐ状態。デモで null なら台本の scriptStartSec までの流れから求める
     * @param playMotifAtStart 癒し：最初の場所のモチーフをベルで鳴らすか
     */
    fun create(
        style: MusicStyle,
        seed: Int,
        demo: Boolean,
        scriptStartSec: Double = 0.0,
        carry: CarryOver? = null,
        playMotifAtStart: Boolean = true,
        sampleRate: Int = MusicConstants.SAMPLE_RATE,
    ): SoundEngine {
        val script = if (demo) DemoScript.COMMUTE else null
        val interlude = if (demo) MusicConstants.DEMO_INTERLUDE_EVERY_SEC else MusicConstants.INTERLUDE_EVERY_SEC
        val engine: SoundEngine = when (style) {
            MusicStyle.HEALING -> MusicEngine(
                seed,
                ComposerConfig(interludeEverySec = interlude, playMotifAtStart = playMotifAtStart),
                script,
                sampleRate,
                scriptStartSec,
            )
            MusicStyle.FUSION -> FusionEngine(seed, FusionComposerConfig(interludeEverySec = interlude), script, sampleRate, scriptStartSec)
        }
        val state = carry ?: if (demo && scriptStartSec > 0) DemoScript.COMMUTE.carryOverAt(scriptStartSec) else null
        state?.let { s -> engine.apply { s.applyTo(it) } }
        return engine
    }
}
