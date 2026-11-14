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

    fun nearbyFestivals(events: List<EventCard>, lat: Double, lon: Double,
                        today: java.time.LocalDate): List<EventCard> {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return emptyList()
        return events.filter {
            !today.isBefore(it.start.toLocalDate()) && !today.isAfter(it.end.toLocalDate())
        }.map { it to Geo.distanceKm(lat, lon, it.lat, it.lon) }
            .filter { it.second <= NEAR_KM }.sortedBy { it.second }.map { it.first }
    }

    data class Match(
        val event: EventCard,
        val distToDestKm: Double,
        val distToNowKm: Double,
        val matchedRoads: List<String>,   // 정체 구간 중 행사장 주변 도로
        val timeOverlap: Boolean,
        val score: Int
    )

    // 행사장 앞 통과까지 남은 분 = 행사장까지 거리 ÷ 평균 속도(남은 거리 ÷ 남은 시간). 계산 못 하면 null
    // 직선거리 기준. 도로가 많이 돌아가면 일찍 울림 → 그땐 TBT·경로 offset으로 교체
    fun minutesToPass(distKm: Double, remainKm: Double, remainMin: Int): Double? {
        if (remainKm <= 0.0 || remainMin <= 0) return null
        return distKm / (remainKm / remainMin)
    }

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
