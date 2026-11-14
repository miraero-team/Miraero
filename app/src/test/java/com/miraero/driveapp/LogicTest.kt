package com.miraero.driveapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    @Test
    fun nearbyFestivalsUsesTwoKmAndRealDatesEvenInDemoMode() {
        val today = LocalDate.of(2026, 11, 14)
        fun festival(id: String, lat: Double) = EventCard(
            id, id, "FESTIVAL", "공원", lat, 127.0,
            today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
            emptyList(), "", "", emptyList(), dateOnly = true
        )
        val nearest = festival("near", 37.501)
        val farther = festival("within", 37.51)
        val outside = festival("outside", 37.519)
        val old = nearest.copy(id = "old", start = today.minusDays(2).atStartOfDay(),
            end = today.minusDays(1).atStartOfDay())
        val future = nearest.copy(id = "future", start = today.plusDays(1).atStartOfDay())
        val events = listOf(farther, old, outside, future, nearest)
        assertEquals(listOf("near", "within"),
            EventMatcher.nearbyFestivals(events, 37.5, 127.0, today).map { it.id })
        assertTrue(EventMatcher.nearbyFestivals(events, 0.0, 0.0, today).isEmpty())
        assertTrue(EventMatcher.nearbyFestivals(events, Double.NaN, 127.0, today).isEmpty())
        assertTrue(EventMatcher.nearbyFestivals(events, 37.5, 127.0, today.plusDays(2)).isEmpty())
    }

    @Test
    fun minutesToPass() {
        // 15km를 30분 → 분당 0.5km. 2km 남았으면 4분 뒤 통과
        assertEquals(4.0, EventMatcher.minutesToPass(2.0, 15.0, 30)!!, 0.001)
        assertNull(EventMatcher.minutesToPass(2.0, 0.0, 0))   // 안내 중 아님
    }

    @Test
    fun energy() {
        // 배터리 18%, 30km, 정체 30분, 에어컨 On → 도착 약 8.7% → 부족 (팀 기록 7장 값)
        val r = EnergyPlanner.check(18, 30.0, 0.5, true)
        assertEquals(8.7, r.arrivePercent, 0.1)
        assertTrue(r.isShort)
    }
}
