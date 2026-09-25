package jp.fmt.ekifu.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 位置の1回ぶんの測定。保存はしない（訪問履歴にはマスIDと日付だけを残す） */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
)

object Geo {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** ジオハッシュ（6桁で約1km四方のマス） */
    fun geohash(lat: Double, lng: Double, precision: Int = MusicConstants.GEOHASH_PRECISION): String {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        val sb = StringBuilder(precision)
        var bit = 0
        var ch = 0
        var evenBit = true
        while (sb.length < precision) {
            if (evenBit) {
                val mid = (lngMin + lngMax) / 2
                if (lng >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lngMin = mid
                } else {
                    lngMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }
            evenBit = !evenBit
            if (bit < 4) {
                bit++
            } else {
                sb.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return sb.toString()
    }

    /** 2点間の距離（メートル、大圏距離） */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: LocationFix, b: LocationFix) = distanceM(a.lat, a.lng, b.lat, b.lng)
}
