package jp.fmt.ekifu.engine

import kotlin.math.abs
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

    fun progressions(f: Familiarity): List<List<LofiChord>> = when (f) {
        Familiarity.FAMILIAR -> FAMILIAR
        Familiarity.NORMAL -> NORMAL
        Familiarity.NEW -> NEW
    }
}

/**
 * ローファイの作曲（段階7a：伴奏とビート）。1小節ずつ作る。
 * - テンポは速さ（静止72・歩き80・乗り物86）、こもり具合とノイズは太陽、和音の並びはなじみ度で決める
 * - ドラムは跳ねたブーンバップ（キック・もたつくスネア・8分のハイハット。乗り物は16分）
 * - ベースは根音から入り、次の和音へ寄っていく。和音はルートを抜いたボイシングを、前の和音から近い形でつなぐ
 * - 4小節ごとに、場所のモチーフをピアノでさらっと弾く
 * previewScenes があれば（段階7aの試聴）、場面を [L.PREVIEW_BARS_PER_SCENE] 小節ごとに順に切り替える。
 * 同じシード・同じ入力なら同じ結果。時刻の単位は再生開始からの秒。
 */
class LofiComposer(seed: Int, private val previewScenes: List<Scene>? = null) : ComposerInput {

    private var rng = Mulberry32(seed)
    private val out = ArrayList<FusionEvent>()

    private var scene = Scene.DEFAULT
    private var pendingScene: Scene? = null
    private var bar = 0
    private var barStartSec = 0.0
    private var stepSec = stepSecOf(scene)
    private var progression = LofiHarmony.progressions(scene.familiarity).first()
    private var chord = progression.first()
    private var lastVoicing: List<Int>? = null
    private var lastCutoff = -1.0
    private var lastNoise = -1.0
    private var lastStepSec = -1.0

    /** 鳴っている小節の頭の時刻と、その小節の和音・場面（画面表示・終わりの音用） */
    private val history = ArrayDeque<BarInfo>()

    data class BarInfo(val startSec: Double, val stepSec: Double, val chord: LofiChord, val scene: Scene)

    // ---------------- 入力 ----------------

    override fun setScene(newScene: Scene) {
        pendingScene = newScene
    }

    // 段階7aでは登録地点の演出はまだ作らない（7bで作る）
    override fun approach(target: Place) {}
    override fun arrive(target: Place) {}
    override fun leave() {}
    override fun stay(target: Place) {}
    override fun approachAfterStart(target: Place) {}

    fun copy(): LofiComposer = LofiComposer(0, previewScenes).also {
        it.rng = rng.copy()
        it.scene = scene
        it.pendingScene = pendingScene
        it.bar = bar
        it.barStartSec = barStartSec
        it.stepSec = stepSec
        it.progression = progression
        it.chord = chord
        it.lastVoicing = lastVoicing
        it.lastCutoff = lastCutoff
        it.lastNoise = lastNoise
        it.lastStepSec = lastStepSec
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

    // ---------------- 内部 ----------------

    private fun composeBar() {
        // 場面（試聴では決まった順に切り替える）
        val preview = previewScenes
        val newScene = if (preview != null) preview[(bar / L.PREVIEW_BARS_PER_SCENE) % preview.size] else pendingScene
        pendingScene = null
        if (newScene != null && newScene != scene) {
            val familiarityChanged = newScene.familiarity != scene.familiarity
            scene = newScene
            if (familiarityChanged) progression = pickProgression()
        }
        stepSec = stepSecOf(scene)
        // 4小節ごとに和音の並びを選び直す
        val pos = bar % progression.size
        if (pos == 0 && bar > 0) progression = pickProgression()
        chord = progression[pos]
        val nextChord = progression[(pos + 1) % progression.size]

        control()
        keys(chord)
        bass(chord, nextChord)
        drums()
        if (bar % L.MELODY_EVERY_BARS == 1) melody()

        history.addLast(BarInfo(barStartSec, stepSec, chord, scene))
        while (history.size > HISTORY_BARS) history.removeFirst()
        barStartSec += stepSec * L.STEPS_PER_BAR
        bar++
    }

    private fun pickProgression(): List<LofiChord> = rng.pick(LofiHarmony.progressions(scene.familiarity))

    private fun control() {
        val cutoff = L.cutoffHz(scene.sun)
        if (cutoff != lastCutoff || stepSec != lastStepSec) {
            out += FusionControl(barStartSec, cutoff, FusionConstants.LEAD_FILTER_BASE_HZ, stepSec * L.DELAY_STEPS, L.DELAY_FEEDBACK)
            lastCutoff = cutoff
            lastStepSec = stepSec
        }
        val noise = L.noiseFactor(scene.sun)
        if (noise != lastNoise) {
            out += LofiControl(barStartSec, noise)
            lastNoise = noise
        }
    }

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
            c.tones.map { base + it }
        }.map { notes ->
            // はみ出した音はオクターブ内に戻す
            notes.map { n ->
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

    private fun keys(c: LofiChord) {
        val v = voicing(c)
        lastVoicing = v
        val len = stepSec * L.STEPS_PER_BAR
        val sparse = scene.familiarity == Familiarity.FAMILIAR || scene.speed == Speed.STILL
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

    private fun bass(c: LofiChord, next: LofiChord) {
        val root = bassNote(c.root)
        out += PolyNote(t(0.0) + jitter(L.JITTER_BASS_SEC).coerceAtLeast(0.0), PolyPart.BASS, root, stepSec * 6.5, vel(0.85))
        // 3拍目の前後で根音か5度
        val second = if (rng.nextDouble() < 0.5) root else root + 7
        val at = if (rng.nextDouble() < 0.5) 7.0 else 8.0
        out += PolyNote(t(at) + jitter(L.JITTER_BASS_SEC), PolyPart.BASS, second, stepSec * 5.0, vel(0.7))
        // 小節の終わりで次の和音の根音へ寄る（半音下か上から）
        if (rng.nextDouble() < 0.55) {
            val target = bassNote(next.root)
            val approach = if (rng.nextDouble() < 0.5) target - 1 else target + 1
            out += PolyNote(t(14.0) + jitter(L.JITTER_BASS_SEC), PolyPart.BASS, approach, stepSec * 1.8, vel(0.6))
        }
    }

    private fun drums() {
        val still = scene.speed == Speed.STILL
        val vehicle = scene.speed == Speed.VEHICLE
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

    /** 場所のモチーフをピアノでさらっと弾く（1拍ずつ、ときどき8分でつなぐ） */
    private fun melody() {
        val motif = Motifs.placeMotif(scene.gridId).map { it + 12 * L.MELODY_OCTAVE_UP }
        var step = 2.0
        motif.forEachIndexed { i, midi ->
            val len = if (i == motif.lastIndex) 6.0 else if (rng.nextDouble() < 0.3) 2.0 else 4.0
            out += LeadNote(t(step) + jitter(L.JITTER_KEYS_SEC).coerceAtLeast(0.0), stepSec * len * 0.9, midi, vel(0.6), 0.0)
            step += len
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
        const val HISTORY_BARS = 64
    }
}
