package jp.fmt.ekifu.engine

import kotlin.math.abs
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.LofiConstants as L

/**
 * ローファイの和音。root は根音の音名（0=C … 5=F）、tones は根音から数えた鳴らす音（根音は抜く。ベースが弾く）
 */
data class LofiChord(val name: String, val root: Int, val tones: List<Int>) {
    companion object {
        private val MAJ9 = listOf(4, 7, 11, 14)
        private val MIN9 = listOf(3, 7, 10, 14)
        private val DOM13 = listOf(4, 10, 14, 21)
        private val DOM7B9 = listOf(4, 10, 13, 19)
        private val MIN7B5 = listOf(3, 6, 10, 14)

        val FMAJ9 = LofiChord("Fmaj9", 5, MAJ9)
        val DM9 = LofiChord("Dm9", 2, MIN9)
        val GM9 = LofiChord("Gm9", 7, MIN9)
        val C13 = LofiChord("C13", 0, DOM13)
        val BBMAJ9 = LofiChord("B♭maj9", 10, MAJ9)
        val AM9 = LofiChord("Am9", 9, MIN9)
        val EB9 = LofiChord("E♭13", 3, DOM13)
        val D7B9 = LofiChord("D7♭9", 2, DOM7B9)
        val EM7B5 = LofiChord("Em7♭5", 4, MIN7B5)
        val A7B9 = LofiChord("A7♭9", 9, DOM7B9)

        val ALL = listOf(FMAJ9, DM9, GM9, C13, BBMAJ9, AM9, EB9, D7B9, EM7B5, A7B9)

        fun byName(name: String?): LofiChord? = ALL.firstOrNull { it.name == name }
    }
}

/** 和音の並び（4小節。1小節に1つ）。なじみの場所ほど落ち着いた並び、初めての場所は色の多い並び */
object LofiHarmony {
    private val FAMILIAR = listOf(
        listOf(LofiChord.GM9, LofiChord.C13, LofiChord.FMAJ9, LofiChord.DM9),
        listOf(LofiChord.BBMAJ9, LofiChord.C13, LofiChord.AM9, LofiChord.DM9),
    )
    private val NORMAL = listOf(
        listOf(LofiChord.BBMAJ9, LofiChord.AM9, LofiChord.GM9, LofiChord.C13),
        listOf(LofiChord.FMAJ9, LofiChord.EM7B5, LofiChord.A7B9, LofiChord.DM9),
    )
    private val NEW = listOf(
        listOf(LofiChord.BBMAJ9, LofiChord.EB9, LofiChord.AM9, LofiChord.D7B9),
        listOf(LofiChord.GM9, LofiChord.C13, LofiChord.EM7B5, LofiChord.A7B9),
    )

    /** 接近中：主和音へ寄っていく並び */
    val APPROACH = listOf(
        listOf(LofiChord.BBMAJ9, LofiChord.C13, LofiChord.AM9, LofiChord.DM9),
        listOf(LofiChord.GM9, LofiChord.C13, LofiChord.FMAJ9, LofiChord.FMAJ9),
    )

    /** 滞在中：主和音のまわりをゆったり */
    val STAY = listOf(
        listOf(LofiChord.FMAJ9, LofiChord.BBMAJ9, LofiChord.FMAJ9, LofiChord.BBMAJ9),
        listOf(LofiChord.FMAJ9, LofiChord.DM9, LofiChord.BBMAJ9, LofiChord.C13),
    )

    /** 到着のブロック（8小節）：合図・テーマ（主和音）→ ゆっくり → 寄せて → 着地 */
    val ARRIVE = listOf(
        LofiChord.FMAJ9, LofiChord.FMAJ9, LofiChord.BBMAJ9, LofiChord.AM9,
        LofiChord.GM9, LofiChord.C13, LofiChord.FMAJ9, LofiChord.FMAJ9,
    )

    fun progressions(f: Familiarity): List<List<LofiChord>> = when (f) {
        Familiarity.FAMILIAR -> FAMILIAR
        Familiarity.NORMAL -> NORMAL
        Familiarity.NEW -> NEW
    }
}

/**
 * ローファイの作曲（段階7b）。1小節ずつ、鳴る直前（先読みのぶんだけ前）に作る。
 * そのため、場面と登録地点の入力は次の小節から音に出る（8小節のブロックの途中でも新しいブロックを始める）。
 *
 * - 始まり：こもった音で和音とベースだけ4小節（レコードをかけたような入り）→ ドラムが入って道中へ
 * - 道中：跳ねたブーンバップ。ブロックの頭6小節は場所のモチーフを元にピアノが即興で弾き、残り2小節は休む
 * - 区切り：道中が続いたら、音をこもらせてドラムを抜く4小節
 * - 接近：毎ブロックの頭でピアノが地点のテーマをそのまま弾き、ベルでも重ねる。外側の円に入った最初のブロックは
 *   2小節ドラムを止めてベルでテーマを大きく2回、最後の拍のスネアでバンドが戻る
 * - 到着：1小節目は曲を止めて合図（ベルの上りの3音。読み上げだけならベルなし）、2小節目でテーマ、3〜5小節目でゆっくり、
 *   7・8小節目で主和音に着地。その次のブロックから滞在
 * - 滞在：キックとリムだけにして、主和音のまわりをゆったり。1ブロックおきにベルでテーマを静かに
 * - 離れる：次の小節から道中。そのブロックの頭でテーマを一度
 * テンポは速さ、こもり具合とノイズは太陽、道中の和音の並びはなじみ度で決める。
 * 同じシード・同じ入力なら同じ結果。時刻の単位は再生開始からの秒。
 */
class LofiComposer(seed: Int, private val config: LofiComposerConfig = LofiComposerConfig()) : ComposerInput {

    private var rng = Mulberry32(seed)
    private val out = ArrayList<FusionEvent>()

    private var scene = Scene.DEFAULT
    private var pendingScene: Scene? = null
    private var bar = 0
    private var barStartSec = 0.0
    private var stepSec = stepSecOf(scene)

    private var phase = Phase.START
    /** いまのブロックの何小節目か（0始まり）。ブロックは8小節 */
    private var blockBar = 0
    private var phaseBars = 0
    private var journeyStartSec = 0.0
    private var progression = LofiHarmony.progressions(scene.familiarity).first()
    private var chord = progression.first()
    private var lastVoicing: List<Int>? = null
    private var lastCutoff = -1.0
    private var lastNoise = -1.0
    private var lastStepSec = -1.0

    // 登録地点
    private var place: Place? = null
    private var pendingPhase: Phase? = null
    private var pendingPlace: Place? = null
    private var pendingLeave = false
    private var deferredApproach: Place? = null
    /** 離れた直後のブロックの頭でテーマを一度弾く地点 */
    private var leftPlace: Place? = null
    /** 外側の円に入った最初のブロック */
    private var entry = false
    private var stayBlocks = 0
    private var arrivalChimeOn = true
    private var arrivalSerial = 0
    private var arrival: Int? = null

    /** 鳴っている小節の情報（画面表示・終わりの音・読み上げのきっかけ用） */
    private val history = ArrayDeque<BarInfo>()

    data class BarInfo(
        val startSec: Double,
        val stepSec: Double,
        val chord: LofiChord,
        val scene: Scene,
        val phase: Phase,
        val place: Place?,
        val arrival: Int?,
        val cutoffHz: Double,
    )

    // ---------------- 入力 ----------------

    override fun setScene(newScene: Scene) {
        pendingScene = newScene
    }

    override fun approach(target: Place) {
        pendingPhase = Phase.APPROACH
        deferredApproach = null
        pendingPlace = target
    }

    override fun arrive(target: Place) {
        pendingPhase = Phase.ARRIVE
        deferredApproach = null
        pendingPlace = target
    }

    override fun leave() {
        pendingPhase = Phase.JOURNEY
        deferredApproach = null
        pendingLeave = true
    }

    override fun stay(target: Place) {
        pendingPhase = Phase.STAY
        deferredApproach = null
        pendingPlace = target
    }

    override fun approachAfterStart(target: Place) {
        deferredApproach = target
    }

    override fun setArrivalChime(on: Boolean) {
        arrivalChimeOn = on
    }

    fun copy(): LofiComposer = LofiComposer(0, config).also {
        it.rng = rng.copy()
        it.scene = scene
        it.pendingScene = pendingScene
        it.bar = bar
        it.barStartSec = barStartSec
        it.stepSec = stepSec
        it.phase = phase
        it.blockBar = blockBar
        it.phaseBars = phaseBars
        it.journeyStartSec = journeyStartSec
        it.progression = progression
        it.chord = chord
        it.lastVoicing = lastVoicing
        it.lastCutoff = lastCutoff
        it.lastNoise = lastNoise
        it.lastStepSec = lastStepSec
        it.place = place
        it.pendingPhase = pendingPhase
        it.pendingPlace = pendingPlace
        it.pendingLeave = pendingLeave
        it.deferredApproach = deferredApproach
        it.leftPlace = leftPlace
        it.entry = entry
        it.stayBlocks = stayBlocks
        it.arrivalChimeOn = arrivalChimeOn
        it.arrivalSerial = arrivalSerial
        it.arrival = arrival
        it.history.addAll(history)
    }

    /** tSec までに始まる小節を作り、新しく決まったイベントを返す */
    fun composeUntil(tSec: Double): List<FusionEvent> {
        out.clear()
        while (barStartSec <= tSec) composeBar()
        return out.toList()
    }

    /** tSec に鳴っている小節 */
    fun barAt(tSec: Double): BarInfo? = history.lastOrNull { it.startSec <= tSec + 1e-9 }

    val currentStepSec: Double get() = stepSec

    // ---------------- 小節を作る ----------------

    private fun composeBar() {
        pendingScene?.let { newScene ->
            if (newScene.familiarity != scene.familiarity && phase == Phase.JOURNEY) progression = pickProgression(newScene)
            scene = newScene
        }
        pendingScene = null
        stepSec = stepSecOf(scene)
        decidePhase()

        val pos = blockBar % 4
        if (pos == 0) progression = progressionFor()
        chord = if (phase == Phase.ARRIVE) LofiHarmony.ARRIVE[blockBar] else progression[pos]
        val nextChord = when {
            phase == Phase.ARRIVE && blockBar + 1 < L.BLOCK_BARS -> LofiHarmony.ARRIVE[blockBar + 1]
            phase == Phase.ARRIVE -> LofiChord.FMAJ9
            else -> progression[(pos + 1) % progression.size]
        }

        val cutoff = control()
        when (phase) {
            Phase.START, Phase.INTERLUDE -> {
                keys(chord, sustain = true)
                bass(chord, nextChord, simple = true)
            }
            Phase.ARRIVE -> arriveBar(nextChord)
            Phase.STAY -> {
                keys(chord, sustain = true)
                bass(chord, nextChord, simple = true)
                stayDrums()
                if (blockBar == 0 && stayBlocks % L.STAY_BELL_EVERY_BLOCKS == L.STAY_BELL_EVERY_BLOCKS - 1) place?.let { bellTheme(it, 0.0, L.THEME_NOTE_STEPS, F.BELL_STAY_GAIN) }
            }
            Phase.APPROACH -> approachBar(nextChord)
            else -> journeyBar(nextChord)
        }

        history.addLast(BarInfo(barStartSec, stepSec, chord, scene, phase, place, arrival, cutoff))
        while (history.size > HISTORY_BARS) history.removeFirst()
        barStartSec += stepSec * L.STEPS_PER_BAR
        bar++
        blockBar++
        phaseBars++
        if (blockBar >= L.BLOCK_BARS) {
            blockBar = 0
            if (phase == Phase.STAY) stayBlocks++
            entry = false
            leftPlace = null
        }
    }

    /** この小節のフェーズを決める。入力があれば次の小節から新しいブロック、なければブロックの区切りで自動の変わり目 */
    private fun decidePhase() {
        val pend = pendingPhase
        if (pend != null) {
            pendingPhase = null
            val prev = phase
            val prevPlace = place
            pendingPlace?.let { place = it }
            pendingPlace = null
            if (pendingLeave) {
                pendingLeave = false
                leftPlace = place
                place = null
            }
            startPhase(pend)
            entry = pend == Phase.APPROACH && (prev != Phase.APPROACH || prevPlace?.id != place?.id)
            return
        }
        when (phase) {
            Phase.START -> if (phaseBars >= L.START_BARS) {
                val d = deferredApproach
                if (d != null) {
                    deferredApproach = null
                    place = d
                    startPhase(Phase.APPROACH)
                    entry = true
                } else {
                    startPhase(Phase.JOURNEY)
                }
            }
            Phase.INTERLUDE -> if (phaseBars >= L.INTERLUDE_BARS) startPhase(Phase.JOURNEY, keepJourneyClock = true)
            Phase.ARRIVE -> if (phaseBars >= L.BLOCK_BARS) startPhase(Phase.STAY)
            // 区切りはブロックの頭で
            Phase.JOURNEY -> if (blockBar == 0 && phaseBars > 0 && barStartSec - journeyStartSec >= config.interludeEverySec) {
                startPhase(Phase.INTERLUDE)
            }
            else -> Unit
        }
    }

    private fun startPhase(p: Phase, keepJourneyClock: Boolean = false) {
        val prev = phase
        phase = p
        phaseBars = 0
        blockBar = 0
        if (p == Phase.JOURNEY && (!keepJourneyClock || prev != Phase.INTERLUDE)) journeyStartSec = barStartSec
        if (p == Phase.INTERLUDE) journeyStartSec = barStartSec
        if (p == Phase.STAY) stayBlocks = 0
        arrival = if (p == Phase.ARRIVE) ++arrivalSerial else null
    }

    private fun progressionFor(): List<LofiChord> = when (phase) {
        Phase.APPROACH -> rng.pick(LofiHarmony.APPROACH)
        Phase.STAY -> rng.pick(LofiHarmony.STAY)
        Phase.START -> LofiHarmony.progressions(Familiarity.FAMILIAR).first()
        else -> pickProgression(scene)
    }

    private fun pickProgression(s: Scene): List<LofiChord> = rng.pick(LofiHarmony.progressions(s.familiarity))

    /** 全体のこもり具合・テンポ・ノイズ。始まりと区切りはこもらせる。返り値はこの小節のこもり具合 */
    private fun control(): Double {
        val muffled = phase == Phase.START || phase == Phase.INTERLUDE
        val cutoff = if (muffled) L.MUFFLED_CUTOFF_HZ else L.cutoffHz(scene.sun)
        if (cutoff != lastCutoff || stepSec != lastStepSec) {
            out += FusionControl(barStartSec, cutoff, F.LEAD_FILTER_BASE_HZ, stepSec * L.DELAY_STEPS, L.DELAY_FEEDBACK)
            lastCutoff = cutoff
            lastStepSec = stepSec
        }
        val noise = L.noiseFactor(scene.sun)
        if (noise != lastNoise) {
            out += LofiControl(barStartSec, noise)
            lastNoise = noise
        }
        return cutoff
    }

    // ---------------- フェーズごとの小節 ----------------

    private fun journeyBar(next: LofiChord) {
        keys(chord)
        bass(chord, next)
        drums()
        val opening = leftPlace
        if (blockBar == 0 && opening != null) {
            // 離れた直後のブロック：頭でテーマを一度（静かに）
            pianoTheme(opening, 0.0, L.THEME_NOTE_STEPS, L.THEME_VELOCITY * 0.8)
            bellTheme(opening, 0.0, L.THEME_NOTE_STEPS, F.BELL_LEAVE_GAIN)
        } else if (blockBar < L.MELODY_BARS && !(blockBar < 2 && opening != null)) {
            melodyBar()
        }
    }

    private fun approachBar(next: LofiChord) {
        val p = place
        if (entry && blockBar < L.ENTRY_BREAK_BARS) {
            // 外側の円に入った最初の2小節：ドラムを止めて和音とベースを伸ばし、ベルでテーマを大きく2回
            keys(chord, sustain = true)
            bass(chord, next, simple = true)
            if (blockBar == 0) out += hit(0.0, DrumKind.CRASH, 0.5)
            p?.let { bellTheme(it, 0.0, L.THEME_NOTE_STEPS, if (blockBar == 0) F.BELL_ENTRY_GAIN else F.BELL_ENTRY_ECHO_GAIN) }
            if (blockBar == L.ENTRY_BREAK_BARS - 1) {
                // 最後の拍のスネアでバンドが戻る
                for (s in 12..15) out += DrumHit(t(s.toDouble()) + jitter(L.JITTER_DRUM_SEC), DrumKind.SNARE, vel(0.25 + 0.12 * (s - 12)))
            }
            return
        }
        keys(chord)
        bass(chord, next)
        drums()
        val themeBar = if (entry) L.ENTRY_BREAK_BARS else 0
        if (p != null && blockBar == themeBar) {
            pianoTheme(p, 0.0, L.THEME_NOTE_STEPS, L.THEME_VELOCITY)
            if (!entry) bellTheme(p, 0.0, L.THEME_NOTE_STEPS, F.BELL_APPROACH_GAIN)
        } else if (p != null && blockBar in themeBar + 2 until themeBar + 4) {
            // テーマの輪郭で少しだけ弾く
            melodyBar(Motifs.theme(p.themeSeed))
        }
    }

    private fun arriveBar(next: LofiChord) {
        val p = place
        when {
            blockBar == 0 -> {
                // 曲を止めて合図：頭のキックだけ（読み上げだけならベルもなし。その間に読む）
                out += hit(0.0, DrumKind.KICK, 0.7)
                if (arrivalChimeOn) {
                    L.ARRIVAL_CHIME_MIDI.forEachIndexed { i, midi ->
                        out += BellNote(t(L.ARRIVAL_CHIME_STEPS[i].toDouble()), midi, F.BELL_CHIME_GAIN)
                    }
                }
            }
            blockBar == L.ARRIVAL_THEME_BAR -> {
                keys(chord, sustain = true)
                bass(chord, next)
                drums(light = true)
                p?.let {
                    pianoTheme(it, 0.0, L.THEME_NOTE_STEPS, L.THEME_VELOCITY)
                    bellTheme(it, 0.0, L.THEME_NOTE_STEPS, F.BELL_ARRIVE_GAIN)
                }
            }
            blockBar < L.ARRIVAL_LAND_BAR -> {
                keys(chord, sustain = true)
                bass(chord, next)
                drums(light = true)
                if (blockBar == L.ARRIVAL_SLOW_BAR) p?.let {
                    pianoTheme(it, 0.0, L.THEME_SLOW_STEPS, L.THEME_VELOCITY * 0.85)
                    bellTheme(it, 0.0, L.THEME_SLOW_STEPS, F.BELL_ARRIVE_SLOW_GAIN)
                }
            }
            blockBar == L.ARRIVAL_LAND_BAR -> {
                // 主和音に着地：2小節伸ばす
                keys(chord, sustain = true, bars = 2)
                out += PolyNote(t(0.0), PolyPart.BASS, bassNote(chord.root), stepSec * 30, vel(0.85))
                out += hit(0.0, DrumKind.KICK, 0.85)
                out += hit(0.0, DrumKind.CRASH, 0.35)
            }
            else -> Unit // 着地の2小節目は伸ばしたまま
        }
    }

    private fun stayDrums() {
        out += hit(0.0, DrumKind.KICK, 0.7)
        if (rng.nextDouble() < 0.5) out += hit(10.0, DrumKind.KICK, 0.5)
        out += DrumHit(t(4.0) + L.SNARE_LAY_BACK_SEC + jitter(L.JITTER_DRUM_SEC), DrumKind.RIM, vel(0.5))
        out += DrumHit(t(12.0) + L.SNARE_LAY_BACK_SEC + jitter(L.JITTER_DRUM_SEC), DrumKind.RIM, vel(0.5))
    }

    // ---------------- 楽器ごと ----------------

    /** 16分の位置 step の時刻（跳ねる：裏の16分と8分の裏を遅らせる） */
    private fun t(step: Double): Double {
        val swing = if (scene.speed == Speed.VEHICLE) L.SWING_VEHICLE else L.SWING
        val push = (2 * swing - 1) * stepSec
        val s = step.toInt()
        val frac = step - s
        val offset = when {
            frac > 0 -> 0.0
            s % 2 == 1 -> push
            s % 4 == 2 -> push * 0.5
            else -> 0.0
        }
        return barStartSec + step * stepSec + offset
    }

    private fun jitter(amount: Double) = (rng.nextDouble() * 2 - 1) * amount
    private fun vel(v: Double) = (v + jitter(L.VELOCITY_JITTER)).coerceIn(0.05, 1.0)

    /** ルートを抜いたボイシングを、前の和音から一番近い形で */
    private fun voicing(c: LofiChord): List<Int> {
        val candidates = (L.KEYS_MIN_MIDI..L.KEYS_MAX_MIDI).filter { it % 12 == c.root }.map { base ->
            c.tones.map { base + it }.map { n ->
                var m = n
                while (m > L.KEYS_MAX_MIDI) m -= 12
                while (m < L.KEYS_MIN_MIDI) m += 12
                m
            }.sorted()
        }.distinct()
        val prev = lastVoicing
        val center = (L.KEYS_MIN_MIDI + L.KEYS_MAX_MIDI) / 2
        return if (prev == null) {
            candidates.minBy { abs(it.average() - center) }
        } else {
            candidates.minBy { v -> v.zip(prev).sumOf { (a, b) -> abs(a - b) } + abs(v.average() - center) * 0.3 }
        }
    }

    private fun keys(c: LofiChord, sustain: Boolean = false, bars: Int = 1) {
        val v = voicing(c)
        lastVoicing = v
        val len = stepSec * L.STEPS_PER_BAR * bars
        val sparse = sustain || scene.familiarity == Familiarity.FAMILIAR || scene.speed == Speed.STILL
        // 1拍目に下から少しずつずらして弾き、ときどき裏で弾き直す
        val rehit = !sparse && rng.nextDouble() < 0.45
        val firstLen = if (rehit) stepSec * 9.5 else len * 0.95
        v.forEachIndexed { i, midi ->
            out += PolyNote(t(0.0) + i * L.KEYS_STRUM_SEC + jitter(L.JITTER_KEYS_SEC).coerceAtLeast(0.0), PolyPart.BRASS, midi, firstLen, vel(0.55))
        }
        if (rehit) {
            val at = if (rng.nextDouble() < 0.5) 10.0 else 11.0
            v.forEachIndexed { i, midi ->
                out += PolyNote(t(at) + i * L.KEYS_STRUM_SEC + jitter(L.JITTER_KEYS_SEC), PolyPart.BRASS, midi, stepSec * (16 - at) * 0.95, vel(0.42))
            }
        }
    }

    private fun bassNote(pc: Int): Int {
        var n = L.BASS_MIN_MIDI + ((pc - L.BASS_MIN_MIDI) % 12 + 12) % 12
        if (n > L.BASS_MAX_MIDI) n -= 12
        return n
    }

    private fun bass(c: LofiChord, next: LofiChord, simple: Boolean = false) {
        val root = bassNote(c.root)
        if (simple) {
            out += PolyNote(t(0.0) + jitter(L.JITTER_BASS_SEC).coerceAtLeast(0.0), PolyPart.BASS, root, stepSec * 15, vel(0.75))
            return
        }
        out += PolyNote(t(0.0) + jitter(L.JITTER_BASS_SEC).coerceAtLeast(0.0), PolyPart.BASS, root, stepSec * 6.5, vel(0.85))
        // 3拍目の前後で根音か5度
        val second = if (rng.nextDouble() < 0.5) root else root + 7
        val at = if (rng.nextDouble() < 0.5) 7.0 else 8.0
        out += PolyNote(t(at) + jitter(L.JITTER_BASS_SEC), PolyPart.BASS, second, stepSec * 5.0, vel(0.7))
        // 小節の終わりで次の和音の根音へ寄る（半音下か上から）
        if (rng.nextDouble() < 0.55 && next != c) {
            val target = bassNote(next.root)
            val approach = if (rng.nextDouble() < 0.5) target - 1 else target + 1
            out += PolyNote(t(14.0) + jitter(L.JITTER_BASS_SEC), PolyPart.BASS, approach, stepSec * 1.8, vel(0.6))
        }
    }

    private fun drums(light: Boolean = false) {
        val still = scene.speed == Speed.STILL || light
        val vehicle = scene.speed == Speed.VEHICLE && !light
        // キック：ブーンバップの型から選ぶ
        val kicks = rng.pick(KICK_PATTERNS)
        for (k in kicks) out += hit(k.toDouble(), DrumKind.KICK, if (k == 0) 0.95 else 0.8)
        // スネア：2拍目と4拍目（少しもたつかせる）。ときどきゴースト
        for (s in listOf(4, 12)) {
            out += DrumHit(t(s.toDouble()) + L.SNARE_LAY_BACK_SEC + jitter(L.JITTER_DRUM_SEC), DrumKind.SNARE, vel(0.75))
        }
        if (!still && rng.nextDouble() < 0.4) out += hit(if (rng.nextDouble() < 0.5) 7.0 else 15.0, DrumKind.SNARE, 0.22)
        // ハイハット：静止は4分、歩きは8分、乗り物は16分（強弱をつける）
        val every = when {
            still -> 4
            vehicle -> 1
            else -> 2
        }
        var s = 0
        while (s < L.STEPS_PER_BAR) {
            val accent = when {
                s % 4 == 0 -> 0.6
                s % 2 == 0 -> 0.42
                else -> 0.28
            }
            if (s == 14 && !still && rng.nextDouble() < 0.2) {
                out += hit(s.toDouble(), DrumKind.OPEN_HAT, 0.4)
            } else {
                out += hit(s.toDouble(), DrumKind.HAT, accent)
            }
            s += every
        }
    }

    private fun hit(step: Double, kind: DrumKind, v: Double) =
        DrumHit(t(step) + jitter(L.JITTER_DRUM_SEC), kind, vel(v))

    /** 地点のテーマを試聴と同じ音の並びでピアノがそのまま弾く（noteSteps：1音の長さ） */
    private fun pianoTheme(p: Place, fromStep: Double, noteSteps: Int, velocity: Double) {
        val theme = Motifs.theme(p.themeSeed)
        theme.forEachIndexed { i, midi ->
            val last = i == theme.lastIndex
            val len = if (last) noteSteps * 2 else noteSteps
            out += LeadNote(t(fromStep + i * noteSteps), stepSec * len * 0.92, midi, vel(velocity), 0.0)
        }
    }

    /** 試聴と同じベルでテーマを重ねる */
    private fun bellTheme(p: Place, fromStep: Double, noteSteps: Int, gain: Double) {
        Motifs.theme(p.themeSeed).forEachIndexed { i, midi ->
            out += BellNote(t(fromStep + i * noteSteps), midi, gain)
        }
    }

    /**
     * 道中のメロディ：ブロックの頭6小節（5・6小節目は変形と落ち着きをもう一度）。1小節目は元のモチーフ、2小節目は短い答え、
     * 3小節目は音階をずらした変形、4小節目は和音の音に落ち着いて伸ばす。
     * 静止・なじみの場所は音を減らし、初めての場所は少し増やす
     */
    private fun melodyBar(source: List<Int>? = null) {
        val motif = source ?: Motifs.placeMotif(scene.gridId)
        val pool = Scale.pentatonic(MELODY_MIN, MELODY_MAX)
        val quiet = scene.speed == Speed.STILL || scene.familiarity == Familiarity.FAMILIAR
        val busy = scene.familiarity == Familiarity.NEW
        // 0：モチーフ・1：答え・2：変形・3：落ち着く。5・6小節目（4・5）は変形と落ち着きをもう一度
        val k0 = blockBar % L.MELODY_BARS
        val k = if (k0 >= 4) k0 - 2 else k0
        if (quiet && k % 2 == 1) return
        val notes: List<Int> = when (k) {
            0 -> motif
            1 -> {
                val last = motif.last()
                val i = pool.indexOf(pool.minBy { abs(it - last) })
                listOfNotNull(pool.getOrNull(i + if (rng.nextDouble() < 0.5) 1 else -1), last).take(if (busy) 2 else 1 + rng.nextInt(2))
            }
            2 -> {
                val shift = rng.pick(listOf(-2, -1, 1, 2))
                val shifted = motif.map { m ->
                    val i = pool.indexOf(pool.minBy { abs(it - m) })
                    pool[(i + shift).coerceIn(0, pool.lastIndex)]
                }
                if (rng.nextDouble() < 0.3) shifted.reversed() else shifted
            }
            else -> {
                // 和音の音のうち、モチーフの最後の音に近いもので落ち着く
                val last = motif.last()
                val tones = pool.filter { n -> (n - chord.root + 120) % 12 in listOf(0) + chord.tones.map { it % 12 } }
                listOf(tones.minByOrNull { abs(it - last) } ?: last)
            }
        }
        val rhythm = if (notes.size <= 1) listOf(rng.pick(listOf(0, 2, 3))) else rng.pick(RHYTHMS).take(notes.size).let { r ->
            if (r.size < notes.size) r + List(notes.size - r.size) { i -> (r.last() + 2 * (i + 1)).coerceAtMost(14) } else r
        }
        notes.forEachIndexed { i, midi ->
            val start = rhythm[i]
            val end = if (i + 1 < notes.size) rhythm[i + 1] else if (k == 3) 16 else minOf(16, start + 6)
            val len = (end - start).coerceAtLeast(1)
            out += LeadNote(t(start.toDouble()) + jitter(L.JITTER_KEYS_SEC).coerceAtLeast(0.0), stepSec * len * 0.9, midi, vel(L.MELODY_VELOCITY), 0.0)
        }
    }

    private fun stepSecOf(s: Scene) = 60.0 / L.bpm(s.speed) / 4

    private companion object {
        /** キックの型（16分の位置） */
        val KICK_PATTERNS = listOf(
            listOf(0, 10),
            listOf(0, 7, 10),
            listOf(0, 3, 10),
            listOf(0, 10, 15),
        )
        /** メロディのリズム（16分の位置。ゆったり、裏から入るものも） */
        val RHYTHMS = listOf(
            listOf(2, 6, 8, 12),
            listOf(0, 4, 10, 12),
            listOf(3, 6, 10, 14),
            listOf(0, 6, 8, 11),
        )
        const val MELODY_MIN = 67
        const val MELODY_MAX = 86
        const val HISTORY_BARS = 64
    }
}

/** ローファイの作曲の設定 */
data class LofiComposerConfig(
    /** 道中がこれだけ続くごとに区切りを1回 */
    val interludeEverySec: Double = MusicConstants.INTERLUDE_EVERY_SEC,
)
