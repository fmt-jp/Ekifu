package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C

/** 和音の切り替え（ベースの発音）の時刻と和音 */
data class ChordAt(val timeSec: Double, val chord: Chord)

fun chordsOf(events: List<MusicEvent>): List<ChordAt> =
    events.filterIsInstance<NoteEvent>()
        .filter { it.instrument == Instrument.BASS }
        .map { e ->
            val pc = ((e.midi - C.ROOT_MIDI) % 12 + 12) % 12
            ChordAt(e.timeSec, Chord.entries.first { it.rootInterval == pc })
        }

/** 0.1秒刻みで作曲を進め、atSec になった入力を送る */
fun compose(
    composer: Composer,
    untilSec: Double,
    inputs: List<Pair<Double, (Composer) -> Unit>> = emptyList(),
): List<MusicEvent> {
    val events = ArrayList<MusicEvent>()
    var next = 0
    val sorted = inputs.sortedBy { it.first }
    var t = 0.0
    while (t < untilSec) {
        while (next < sorted.size && sorted[next].first <= t) sorted[next++].second(composer)
        events += composer.composeUntil(t + 0.1)
        t += 0.1
    }
    return events
}

val DAY_WALK = Scene("test01", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)

fun <T> cycle(list: List<T>, n: Int): List<T> = List(n) { list[it % list.size] }
