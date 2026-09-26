package jp.fmt.ekifu.engine

/**
 * 作曲への入力（場面・登録地点）。曲調（癒し・フュージョン）によらず同じ。
 * いずれも次の区切り（癒しは和音の切り替え、フュージョンは小節やブロックの頭）で反映する。
 */
interface ComposerInput {
    fun setScene(newScene: Scene)
    fun approach(target: Place)
    fun arrive(target: Place)
    fun leave()
    fun stay(target: Place)
}

/** PCM を順番に書き出すもの（終わりのフェードアウトやバッファ不足のつなぎに使う） */
interface Renderer {
    /** frames 個のステレオフレームを out の offsetFrames 以降（L, R 交互）に書く */
    fun render(out: ShortArray, frames: Int, offsetFrames: Int)
}

/**
 * 曲調ごとのエンジン（作曲＋合成）。ChunkStream はこれだけを通して使う。
 * 1つのスレッドからだけ使う。copy() で状態をまるごと複製できる（チャンクの作り直し用）。
 */
interface SoundEngine {
    val sampleRate: Int

    /** これまでに合成したフレーム数 */
    val frame: Long

    fun render(out: ShortArray, frames: Int, offsetFrames: Int = 0)

    fun copy(): SoundEngine

    fun status(): EngineStatus

    fun apply(action: (ComposerInput) -> Unit)

    /**
     * 停止ボタン：status の時点の曲に続けて鳴らす「終わり」（約12秒でフェードアウト）。
     * nowSec は停止した再生位置（リズムの刻みに合わせるため）
     */
    fun endingRenderer(status: EngineStatus, nowSec: Double): Renderer

    /** 停止ボタンで、それまでの演奏を消す時間 */
    val endingCrossfadeSec: Double get() = MusicConstants.ENDING_BUFFER_FADE_SEC

    /** バッファ不足のあいだ、status の時点の和音を伸ばしてつなぐ音 */
    fun fillerRenderer(status: EngineStatus): Renderer
}
