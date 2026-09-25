package jp.fmt.ekifu.engine

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * 太陽の高さ（通信不要）。NOAA の太陽位置の計算式による。
 */
object Sun {

    /** 太陽の中心の高度（度、大気差なし） */
    fun elevationDeg(latDeg: Double, lngDeg: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525.0

        val l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = Math.toRadians(m)
        val center = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) +
            sin(3 * mr) * 0.000289
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val lambda = Math.toRadians(l0 + center - 0.00569 - 0.00478 * sin(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = Math.toRadians(eps0 + 0.00256 * cos(omega))
        val decl = asin(sin(eps) * sin(lambda))

        val y = tan(eps / 2) * tan(eps / 2)
        val l0r = Math.toRadians(l0)
        val eqTimeMin = 4 * Math.toDegrees(
            y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr),
        )

        val minutesUtc = Math.floorMod(epochMs, 86_400_000L) / 60_000.0
        val trueSolarMin = ((minutesUtc + eqTimeMin + 4 * lngDeg) % 1440 + 1440) % 1440
        val hourAngle = Math.toRadians(trueSolarMin / 4 - 180)
        val lat = Math.toRadians(latDeg)
        val cosZenith = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle)
        return 90.0 - Math.toDegrees(acos(cosZenith.coerceIn(-1.0, 1.0)))
    }

    fun level(elevationDeg: Double): SunLevel = when {
        elevationDeg < C.NIGHT_MAX_ELEVATION_DEG -> SunLevel.NIGHT
        elevationDeg < C.TWILIGHT_MAX_ELEVATION_DEG -> SunLevel.TWILIGHT
        else -> SunLevel.DAY
    }

    fun level(latDeg: Double, lngDeg: Double, epochMs: Long): SunLevel = level(elevationDeg(latDeg, lngDeg, epochMs))

    private fun norm360(x: Double) = ((x % 360) + 360) % 360
}
