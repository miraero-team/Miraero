package com.miraero.driveapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime

object FestivalRepository {
    private const val ENDPOINT = "https://api.data.go.kr/openapi/tn_pubr_public_cltur_fstvl_api"

    // shortcut: 앱 실행마다 전국 목록을 조회, 배포 규모가 커지면 서버 캐시로 전환.
    fun fetch(apiKey: String, today: LocalDate): List<EventCard> {
        require(apiKey.isNotBlank())
        val events = mutableListOf<EventCard>()
        var page = 1
        var received = 0
        do {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val connection = URL("$ENDPOINT?serviceKey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&type=json&pageNo=$page&numOfRows=1000").openConnection() as HttpURLConnection
            val json = try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                check(connection.responseCode == 200) { "축제 데이터 HTTP 오류" }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            } finally {
                connection.disconnect()
            }
            val response = json.optJSONObject("response") ?: json
            val code = response.getJSONObject("header").getString("resultCode")
            if (code == "03") {
                check(page == 1) { "축제 데이터 페이지 누락" }
                return emptyList()
            }
            check(code == "00") { "축제 데이터 응답 오류" }
            val body = response.getJSONObject("body")
            val rows = items(body)
            received += rows.length()
            events += parseItems(rows, today)
            val total = body.getInt("totalCount")
            check(rows.length() > 0 || received >= total) { "축제 데이터 페이지 누락" }
            page++
        } while (received < total)
        return events.distinctBy { it.id }
    }

    internal fun items(body: JSONObject): JSONArray {
        val value = body.opt("items")
        if (value is JSONArray) return value
        if (value is JSONObject) {
            return when (val item = value.opt("item")) {
                is JSONArray -> item
                is JSONObject -> JSONArray().put(item)
                else -> error("축제 목록 형식 오류")
            }
        }
        check(body.getInt("totalCount") == 0) { "축제 목록 누락" }
        return JSONArray()
    }

    internal fun parseItems(rows: JSONArray, today: LocalDate): List<EventCard> =
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            fun field(key: String) = row.optString(key).trim().takeUnless { it == "null" }.orEmpty()
            val name = field("fstvlNm")
            val summary = field("fstvlCo")
            if (name.isBlank() || name.contains("취소") || summary.contains("취소")) return@mapNotNull null
            val start = runCatching { LocalDate.parse(field("fstvlStartDate")) }.getOrNull()
                ?: return@mapNotNull null
            val end = runCatching { LocalDate.parse(field("fstvlEndDate")) }.getOrNull()
                ?: return@mapNotNull null
            if (end < start || today < start || today > end) return@mapNotNull null
            val lat = field("latitude").toDoubleOrNull() ?: return@mapNotNull null
            val lon = field("longitude").toDoubleOrNull() ?: return@mapNotNull null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
            val venue = field("opar").ifBlank { field("rdnmadr").ifBlank { field("lnmadr") } }
            if (venue.isBlank()) return@mapNotNull null
            EventCard(
                id = "festival:$name:$start:$end:$lat:$lon",
                name = name, type = "FESTIVAL", venue = venue, lat = lat, lon = lon,
                start = start.atStartOfDay(), end = end.atTime(LocalTime.MAX),
                nearbyRoads = emptyList(), crowd = "", summary = summary,
                songs = emptyList(), dateOnly = true
            )
        }.distinctBy { it.id }
}
