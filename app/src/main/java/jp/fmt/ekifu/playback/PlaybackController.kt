package jp.fmt.ekifu.playback

import android.content.Context
import android.os.PowerManager
import jp.fmt.ekifu.data.RouteRepository
import jp.fmt.ekifu.data.StoredRoute
import jp.fmt.ekifu.engine.DemoJourney
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.JourneyPlan
import jp.fmt.ekifu.engine.JourneySource
import jp.fmt.ekifu.engine.LocationSample
import jp.fmt.ekifu.engine.Route
import jp.fmt.ekifu.engine.StationMotif
import jp.fmt.ekifu.engine.TimeOnlyJourney
import jp.fmt.ekifu.engine.WanderJourney
import jp.fmt.ekifu.engine.seed
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/** 何を再生するか。デモは開発用（SPEC 8章）。 */
enum class PlaybackMode(val label: String) {
    REGISTERED("登録ルート"),
    /** ルートを決めず、約 5 分ごとの位置情報から作る。 */
    WANDER("ルートなし"),
    DEMO_SHORT("デモ 3分"),
    DEMO_LONG("長時間テスト 30分"),
}

data class PlaybackState(
    val mode: PlaybackMode = PlaybackMode.REGISTERED,
    /** 再生する旅程。登録ルートがまだないとき、ルートなしモードのときは null。 */
    val plan: JourneyPlan? = null,
    /** 再生の準備ができたか（通知・ロック画面に出すかどうか）。 */
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val status: EngineStatus? = null,
    val bufferedSeconds: Double = 0.0,
    val underruns: Int = 0,
    /** ルートなしモードで位置情報を使えているか（許可がなければ false）。 */
    val locationEnabled: Boolean = false,
    /** 最後に位置を受け取った時刻（端末の時計、ミリ秒）。 */
    val lastFixWallMillis: Long? = null,
) {
    /** 再生できるものがあるか。 */
    val playable: Boolean get() = mode == PlaybackMode.WANDER || plan != null

    /** いまフォアグラウンドサービスで位置を取る必要があるか。 */
    val usesLocation: Boolean get() = mode == PlaybackMode.WANDER && playing && locationEnabled
}

/**
 * アプリ全体で 1 つの再生の窓口。画面（Activity）とサービス（MediaSession）の両方から使う。
 * 状態の変化は [state] と [addListener] で知らせる（リスナーは任意のスレッドから呼ばれる）。
 */
class PlaybackController private constructor(context: Context) {
    private val routes = RouteRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registeredRoute: Route? = null
    private val tracker = LocationTracker(context)
    private var wander: WanderJourney? = null
    private var lastWakeLockRenewMillis = 0L

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ekifu:playback")
        .apply { setReferenceCounted(false) }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var pipeline: AudioPipeline? = null

    init {
        scope.launch {
            routes.route.collect { stored ->
                if (stored != StoredRoute.Loading) onRegisteredRouteChanged((stored as? StoredRoute.Registered)?.route)
            }
        }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    @Synchronized
    fun setMode(mode: PlaybackMode) {
        if (mode == _state.value.mode) return
        stop()
        setState { PlaybackState(mode = mode, plan = planFor(mode)) }
    }

    /** 登録ルートが保存・変更されたら、再生中の登録ルートは止めて新しいルートに切り替える。 */
    @Synchronized
    private fun onRegisteredRouteChanged(route: Route?) {
        if (route == registeredRoute && _state.value.plan != null) return
        registeredRoute = route
        if (_state.value.mode != PlaybackMode.REGISTERED) return
        stop()
        setState { PlaybackState(mode = PlaybackMode.REGISTERED, plan = planFor(PlaybackMode.REGISTERED)) }
    }

    private fun planFor(mode: PlaybackMode): JourneyPlan? = when (mode) {
        PlaybackMode.REGISTERED -> registeredRoute?.let { TimeOnlyJourney(it) }
        PlaybackMode.WANDER -> null
        PlaybackMode.DEMO_SHORT -> DemoJourney.create(DemoJourney.SHORT_PLAYBACK_MINUTES)
        PlaybackMode.DEMO_LONG -> DemoJourney.create(DemoJourney.LONG_PLAYBACK_MINUTES)
    }

    @Synchronized
    fun prepare() {
        setState { it.copy(prepared = true) }
    }

    @Synchronized
    fun play() {
        val state = _state.value
        if (!state.playable) return
        val current = pipeline
        val p = if (current == null || state.ended) {
            current?.release()
            newPipeline(state).also { pipeline = it }
        } else {
            current
        }
        renewWakeLock()
        p.play()
        // 一時停止中は位置も取らない（SPEC 7章）ので、再生のたびに取り直す
        val locationEnabled = wander?.let { journey -> tracker.start { onLocation(journey, it) } } ?: false
        setState { it.copy(prepared = true, playing = true, ended = false, locationEnabled = locationEnabled) }
    }

    @Synchronized
    fun pause() {
        pipeline?.pause()
        tracker.stop()
        releaseWakeLock()
        setState { it.copy(playing = false) }
    }

    /** ルートなしモードで位置を受け取った。場所や移動状態が変わったら、先読みした音を作り直す。 */
    @Synchronized
    private fun onLocation(journey: WanderJourney, sample: LocationSample) {
        if (journey !== wander) return
        if (journey.onLocation(sample)) pipeline?.requestResync()
        setState { it.copy(lastFixWallMillis = System.currentTimeMillis()) }
    }

    /** 最初から再生し直す。 */
    @Synchronized
    fun restart() {
        pipeline?.release()
        pipeline = null
        tracker.stop()
        wander = null
        setState { PlaybackState(mode = it.mode, plan = it.plan) }
        play()
    }

    @Synchronized
    fun stop() {
        pipeline?.release()
        pipeline = null
        tracker.stop()
        wander = null
        releaseWakeLock()
        setState { PlaybackState(mode = it.mode, plan = it.plan) }
    }

    private fun newPipeline(state: PlaybackState): AudioPipeline {
        val source: JourneySource
        val seed: Int
        val plan = state.plan
        if (plan != null) {
            wander = null
            source = plan
            seed = plan.route.seed()
        } else {
            val journey = WanderJourney { LocalTime.now().let { it.hour + it.minute / 60.0 } }
            wander = journey
            source = journey
            // 日ごとに曲の土台を変える（場所のモチーフは日によらず同じ）
            seed = StationMotif.fnv1a("wander:" + LocalDate.now())
        }
        lateinit var created: AudioPipeline
        created = AudioPipeline(source, seed) { snapshot ->
            // 作り直し前の古いパイプラインからの通知は無視する
            if (pipeline !== created) return@AudioPipeline
            // ルートなしモードは終わりがないので、ウェイクロックの期限を延ばし続ける
            if (System.currentTimeMillis() - lastWakeLockRenewMillis > WAKE_LOCK_RENEW_MS && _state.value.playing) {
                renewWakeLock()
            }
            val endedNow = snapshot.ended && !_state.value.ended
            _state.update {
                it.copy(
                    status = snapshot.status,
                    bufferedSeconds = snapshot.bufferedSeconds,
                    underruns = snapshot.underruns,
                    ended = snapshot.ended,
                    playing = it.playing && !snapshot.ended,
                )
            }
            if (endedNow) {
                releaseWakeLock()
                notifyListeners()
            }
        }
        return created
    }

    private fun renewWakeLock() {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        lastWakeLockRenewMillis = System.currentTimeMillis()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun setState(transform: (PlaybackState) -> PlaybackState) {
        _state.update(transform)
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners) l()
    }

    companion object {
        /** 念のための上限。再生中は [WAKE_LOCK_RENEW_MS] ごとに延ばす。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

        @Volatile private var instance: PlaybackController? = null

        fun get(context: Context): PlaybackController =
            instance ?: synchronized(this) {
                instance ?: PlaybackController(context.applicationContext).also { instance = it }
            }
    }
}
