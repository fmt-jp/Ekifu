package jp.fmt.ekifu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private val route = Route(
        name = "いつもの通勤",
        expectedMinutes = 18.0,
        stations = listOf(
            Station("若葉台", 35.000, 139.000, false),
            Station("川辺", 35.010, 139.020, true),
            Station("中央", 35.020, 139.040, true),
            Station("港町", 35.030, 139.060, false),
            Station("汐見", 35.040, 139.080, false),
        ),
    )

    @Test
    fun jsonRoundTrip() {
        assertEquals(route, RouteJson.decode(RouteJson.encode(route)))
    }

    @Test
    fun readsSpecExampleFormat() {
        // SPEC 5章の例（expectedMinutes は整数でも読める）
        val text = """
            {
              "name": "いつもの通勤",
              "expectedMinutes": 18,
              "stations": [
                { "name": "若葉台", "lat": 35.000, "lng": 139.000, "undergroundAfter": false },
                { "name": "川辺",   "lat": 35.010, "lng": 139.020, "undergroundAfter": true }
              ]
            }
        """.trimIndent()
        val decoded = RouteJson.decode(text)!!
        assertEquals(18.0, decoded.expectedMinutes, 0.0)
        assertEquals(listOf("若葉台", "川辺"), decoded.stations.map { it.name })
        assertTrue(decoded.stations[1].undergroundAfter)
    }

    @Test
    fun brokenJsonGivesNull() {
        assertNull(RouteJson.decode("{ not json"))
        assertNull(RouteJson.decode("""{"name": "x"}"""))
    }

    @Test
    fun validationCatchesProblems() {
        assertTrue(RouteValidation.problems(route).isEmpty())
        assertFalse(RouteValidation.problems(route.copy(name = " ")).isEmpty())
        assertFalse(RouteValidation.problems(route.copy(expectedMinutes = 0.0)).isEmpty())
        assertFalse(RouteValidation.problems(route.copy(stations = route.stations.take(1))).isEmpty())
        val unnamed = route.stations.toMutableList().apply { this[2] = this[2].copy(name = "") }
        assertFalse(RouteValidation.problems(route.copy(stations = unnamed)).isEmpty())
    }
}
