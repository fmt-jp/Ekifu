package jp.fmt.ekifu.engine

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SunTest {

    private val jst = ZoneId.of("Asia/Tokyo")

    /** 日の出・日の入り：太陽の上端が地平線（大気差込み、中心高度 -0.833°）を横切る時刻を1分刻みで探す */
    private fun riseSet(lat: Double, lng: Double, date: LocalDate): Pair<LocalTime, LocalTime> {
        val start = date.atStartOfDay(jst)
        var rise: LocalTime? = null
        var set: LocalTime? = null
        var prev = Sun.elevationDeg(lat, lng, start.toInstant().toEpochMilli()) + 0.833
        for (m in 1..1440) {
            val t = start.plusMinutes(m.toLong())
            val e = Sun.elevationDeg(lat, lng, t.toInstant().toEpochMilli()) + 0.833
            if (prev < 0 && e >= 0 && rise == null) rise = t.toLocalTime()
            if (prev >= 0 && e < 0) set = t.toLocalTime()
            prev = e
        }
        return rise!! to set!!
    }

    private data class Case(val name: String, val lat: Double, val lng: Double, val date: String, val rise: String, val set: String)

    // 国立天文台「暦計算室」の公表値
    private val cases = listOf(
        Case("東京 夏至", 35.6581, 139.7414, "2024-06-21", "04:25", "19:00"),
        Case("東京 冬至", 35.6581, 139.7414, "2024-12-21", "06:47", "16:32"),
        Case("東京 春分", 35.6581, 139.7414, "2024-03-20", "05:45", "17:53"),
        Case("札幌 夏至", 43.0642, 141.3469, "2024-06-21", "03:55", "19:18"),
        Case("那覇 冬至", 26.2124, 127.6792, "2024-12-21", "07:13", "17:43"),
    )

    @Test
    fun sunriseAndSunsetMatchPublishedTimesWithinFiveMinutes() {
        for (c in cases) {
            val (rise, set) = riseSet(c.lat, c.lng, LocalDate.parse(c.date))
            println("${c.name}: 計算 $rise / $set　公表 ${c.rise} / ${c.set}")
            val dRise = abs(rise.toSecondOfDay() - LocalTime.parse(c.rise).toSecondOfDay()) / 60.0
            val dSet = abs(set.toSecondOfDay() - LocalTime.parse(c.set).toSecondOfDay()) / 60.0
            assertTrue(dRise <= 5, "${c.name} 日の出のずれ $dRise 分")
            assertTrue(dSet <= 5, "${c.name} 日の入りのずれ $dSet 分")
        }
    }

    @Test
    fun levelsFollowElevation() {
        fun at(h: Int, m: Int) = ZonedDateTime.of(LocalDate.parse("2024-06-21"), LocalTime.of(h, m), jst)
            .toInstant().toEpochMilli()
        assertEquals(SunLevel.DAY, Sun.level(35.6581, 139.7414, at(12, 0)))
        assertEquals(SunLevel.NIGHT, Sun.level(35.6581, 139.7414, at(0, 0)))
        // 日の出直後は薄明（高度 -6°〜6°）
        assertEquals(SunLevel.TWILIGHT, Sun.level(35.6581, 139.7414, at(4, 30)))
        assertEquals(SunLevel.TWILIGHT, Sun.level(35.6581, 139.7414, at(19, 10)))
    }
}
