package com.miraero.driveapp

import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// 두 좌표 사이 거리 (하버사인 공식: 지구가 둥근 걸 반영한 거리 계산)
object Geo {
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0  // 지구 반지름 (km)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

// 내 경로·시간에 걸리는 행사 찾기
object EventMatcher {

    const val DEMO_IGNORE_TIME = true   // 시연용: 시간 조건 무시 (밤에 테스트해도 동작). 실서비스는 false
    private const val NEAR_KM = 2.0
    private const val TIME_MARGIN_MIN = 60L

    data class Match(
        val event: EventCard,
        val distToDestKm: Double,
        val distToNowKm: Double,
        val matchedRoads: List<String>,   // 정체 구간 중 행사장 주변 도로
        val timeOverlap: Boolean,
        val score: Int
    )

    fun findMatches(
        events: List<EventCard>,
        curLat: Double, curLon: Double,
        destLat: Double, destLon: Double,
        congestedRoads: List<String>,
        now: LocalDateTime,
        arrival: LocalDateTime
    ): List<Match> {
        return events.mapNotNull { e ->
            // ① 경로에 걸리나?
            val dDest = Geo.distanceKm(destLat, destLon, e.lat, e.lon)
            val dNow = Geo.distanceKm(curLat, curLon, e.lat, e.lon)
            val nearDest = dDest <= NEAR_KM
            val nearNow = dNow <= NEAR_KM
            val roads = congestedRoads.filter { r -> e.nearbyRoads.any { r.contains(it) } }.distinct()
            val onRoute = nearDest || nearNow || roads.isNotEmpty()

            // ② 시간이 겹치나? (지금~도착 vs 행사 시간 ±60분)
            val windowStart = e.start.minusMinutes(TIME_MARGIN_MIN)
            val windowEnd = e.end.plusMinutes(TIME_MARGIN_MIN)
            val overlap = !now.isAfter(windowEnd) && !arrival.isBefore(windowStart)
            val timeOk = DEMO_IGNORE_TIME || overlap

            if (!onRoute || !timeOk) null
            else Match(
                event = e,
                distToDestKm = dDest,
                distToNowKm = dNow,
                matchedRoads = roads,
                timeOverlap = overlap,
                score = (if (nearDest) 3 else 0) + (if (nearNow) 2 else 0) + roads.size
            )
        }.sortedByDescending { it.score }
    }
}