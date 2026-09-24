package jp.fmt.ekifu.engine

/** チャンク生成とバッファの設定（SPEC 6章）。 */
object BufferConstants {
    /** 最初のチャンク（すぐ再生を始めるため短くする）。 */
    const val FIRST_CHUNK_SECONDS = 2.0
    const val CHUNK_SECONDS = 10.0
    /** 再生待ちがこれを切ったら、まとめて合成する。 */
    const val LOW_WATER_SECONDS = 30.0
    /** 再生待ちの上限（44.1kHz ステレオ 16bit で約 10.6MB）。 */
    const val HIGH_WATER_SECONDS = 60.0
    /** 作り直すとき、再生位置からこの長さぶんのチャンクは残す（状態変化の反映は最大でこの遅れ）。 */
    const val RESYNC_KEEP_SECONDS = 10.0
    /** 画面表示用の状態を記録する間隔。 */
    const val STATUS_INTERVAL_SECONDS = 0.25
    /** 音源の切り替え時に段差を消す時定数。 */
    const val DECLICK_SECONDS = 0.005
    /** 音切れのつなぎに伸ばすパッドの音量（本線のコンプレッサーと出力ゲインのおおよその値）。 */
    const val FILLER_GAIN = 1.3
}
