package com.miraero.driveapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FestivalRepositoryTest {
    private val today = LocalDate.of(2026, 11, 14)

    private fun row() = JSONObject().apply {
        put("fstvlNm", "테스트 축제")
        put("opar", "테스트 공원")
        put("fstvlStartDate", "2026-11-14")
        put("fstvlEndDate", "2026-11-15")
        put("fstvlCo", "지역 문화 공연")
        put("latitude", "37.5")
        put("longitude", "127.0")
    }

    @Test fun parsesActualApiShapeAndRemovesDuplicates() {
        val body = JSONObject().put("totalCount", 2).put("items",
            JSONObject().put("item", JSONArray().put(row()).put(row())))
        val event = FestivalRepository.parseItems(FestivalRepository.items(body), today).single()
        assertEquals("테스트 축제", event.name)
        assertTrue(event.dateOnly)
        assertTrue(event.songs.isEmpty())
        assertEquals("", event.crowd)
        assertTrue(event.nearbyRoads.isEmpty())
    }

    @Test fun includesBothBoundaryDatesButRejectsPastAndFuture() {
        val rows = JSONArray().put(row())
        assertEquals(1, FestivalRepository.parseItems(rows, today).size)
        assertEquals(1, FestivalRepository.parseItems(rows, today.plusDays(1)).size)
        assertTrue(FestivalRepository.parseItems(rows, today.minusDays(1)).isEmpty())
        assertTrue(FestivalRepository.parseItems(rows, today.plusDays(2)).isEmpty())
    }

    @Test fun skipsInvalidAndCancelledRowsWithoutDroppingValidOnes() {
        val rows = JSONArray().put(row())
            .put(row().put("latitude", ""))
            .put(row().put("latitude", "NaN"))
            .put(row().put("longitude", "181"))
            .put(row().put("fstvlStartDate", "미정"))
            .put(row().put("fstvlEndDate", "2026-11-13"))
            .put(row().put("fstvlNm", "축제(취소)"))
            .put(row().put("fstvlCo", "우천으로 취소"))
        assertEquals(1, FestivalRepository.parseItems(rows, today).size)
    }

    @Test fun supportsEmptyAndSingleItemResponses() {
        assertEquals(0, FestivalRepository.items(JSONObject().put("totalCount", 0).put("items", "")).length())
        assertEquals(1, FestivalRepository.items(JSONObject().put("items", JSONObject().put("item", row()))).length())
    }

    @Test(expected = IllegalStateException::class)
    fun missingItemsIsAnErrorRatherThanNoNearbyFestivals() {
        FestivalRepository.items(JSONObject().put("totalCount", 3))
    }
}
