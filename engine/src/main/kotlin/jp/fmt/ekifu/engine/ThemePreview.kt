package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C

/** 地点の編集画面の「試聴」：I の和音の上で地点のテーマをベルで鳴らす（数秒） */
object ThemePreview {

    fun render(themeSeed: Int, mood: Mood, sampleRate: Int = C.SAMPLE_RATE): ShortArray {
        val beat = mood.beatSec ?: C.BASE_BEAT_SEC
        val cutoff = minOf(C.DAY_CUTOFF_HZ, mood.cutoffHz ?: Double.MAX_VALUE)
        val feedback = mood.delayFeedback ?: C.DELAY_FEEDBACK
        val theme = Motifs.theme(themeSeed)
        val events = ArrayList<MusicEvent>()
        events += ControlEvent(0.0, cutoff, feedback, 0.0)
        events += chordEvents(0.0, Chord.I, Scene.DEFAULT, padAttackSec = C.PREVIEW_PAD_ATTACK_SEC)
        theme.forEachIndexed { i, midi ->
            events += NoteEvent(C.PREVIEW_THEME_START_SEC + i * C.MOTIF_NOTE_BEATS * beat, Instrument.BELL, midi, C.THEME_BELL_GAIN)
        }
        events += FadeOutEvent(C.PREVIEW_SEC - C.PREVIEW_FADE_SEC, C.PREVIEW_FADE_SEC)

        val synth = Synth(sampleRate)
        synth.schedule(events)
        val frames = (C.PREVIEW_SEC * sampleRate).toInt()
        return ShortArray(frames * 2).also { synth.render(it, frames) }
    }
}
