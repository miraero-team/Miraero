package com.miraero.driveapp

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// 추천곡 한 줄
data class Song(
    val priority: Int,   // 1: 출연 아티스트 대표곡, 2: 행사·지역 테마곡, 3: 응원가, 4: 분위기 플레이리스트
    val title: String,
    val artist: String,
    val reason: String,
    val clip: String = ""   // res/raw 하이라이트 파일 이름 (확장자 빼고). 없으면 재생 안 함
)

// 행사 카드 한 장
data class EventCard(
    val id: String,
    val name: String,
    val type: String,          // BASEBALL, CONCERT, FESTIVAL, EXPO ...
    val venue: String,
    val lat: Double,
    val lon: Double,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val nearbyRoads: List<String>,
    val crowd: String,         // LOW, MEDIUM, HIGH
    val summary: String,
    val songs: List<Song>,
    val liveScore: String? = null,   // 경기 중 스코어 한 줄 (시연: json 고정값 / 실서비스: 경기 데이터 API)
    val nextShow: String? = null,    // 도착 카드의 다음 공연·경기 일정
    val dateOnly: Boolean = false
)

// 행사 카드 꺼내오기 (지금: 앱 안 JSON / 나중: 서버에서 받기)
object EventRepository {

    private var cache: List<EventCard>? = null

    fun load(context: Context): List<EventCard> {
        cache?.let { return it }

        val text = context.assets.open("events.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val list = mutableListOf<EventCard>()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            // "today"면 오늘 날짜로 (시연을 언제 하든 동작하게)
            val dateStr = o.getString("date")
            val date = if (dateStr == "today") LocalDate.now() else LocalDate.parse(dateStr)

            val roads = o.getJSONArray("nearbyRoads").let { r -> List(r.length()) { r.getString(it) } }
            val songs = o.getJSONArray("songs").let { s ->
                List(s.length()) {
                    val so = s.getJSONObject(it)
                    Song(so.getInt("priority"), so.getString("title"), so.getString("artist"), so.getString("reason"), so.optString("clip"))
                }
            }

            list += EventCard(
                id = o.getString("id"),
                name = o.getString("name"),
                type = o.getString("type"),
                venue = o.getString("venue"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                start = LocalDateTime.of(date, LocalTime.parse(o.getString("startTime"))),
                end = LocalDateTime.of(date, LocalTime.parse(o.getString("endTime"))),
                nearbyRoads = roads,
                crowd = o.getString("crowd"),
                summary = o.getString("summary"),
                songs = songs.sortedBy { it.priority },
                liveScore = o.optString("liveScore").ifBlank { null },
                nextShow = o.optString("nextShow").ifBlank { null }
            )
        }
        cache = list
        return list
    }
}