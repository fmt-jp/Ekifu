package jp.fmt.ekifu.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** 再生サービスへの独自の操作（通知・ロック画面・再生画面から） */
object PlaybackCommands {
    /** 「着いた」：近くの登録地点に到着したことにする（地下の駅など位置が取れないとき） */
    const val ARRIVED_ACTION = "jp.fmt.ekifu.ARRIVED"
    val ARRIVED = SessionCommand(ARRIVED_ACTION, Bundle.EMPTY)
}
