package com.miraero.driveapp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 한국환경공단 전기자동차 충전소 정보 (공공데이터포털)
// 키: local.properties 의 DATA_GO_KR_KEY (Decoding 키)
object EvChargerApiSource : ChargerSource {
    private const val TAG = "PLEOS_TEST"
    private const val BASE = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo"
    private const val MAX_KM = 5.0           // 현재 위치에서 이 거리 안만
    private const val MIN_KM = 1.0           // 출발지 바로 옆(1km 안)은 제외
    private const val AVOID_EVENT_KM = 1.5   // 행사장 반경 안 충전소는 피함 (거기도 막히니까)
    private const val CACHE_MS = 10 * 60 * 1000L

    // 시군구 코드(시 단위) + 대략 중심 좌표. 현재 위치에서 제일 가까운 1곳만 받음 (경기도 전체는 15만 대라 너무 큼)
    private data class Area(val code: String, val name: String, val lat: Double, val lon: Double)
    private val AREAS = listOf(
        Area("41130", "성남시", 37.420, 127.127),
        Area("41290", "과천시", 37.429, 126.988),
        Area("11650", "서울 서초구", 37.484, 127.033),
        Area("11680", "서울 강남구", 37.517, 127.047),
        Area("11710", "서울 송파구", 37.514, 127.106),
        Area("11740", "서울 강동구", 37.530, 127.124)
    )

    // 충전소 1곳 (충전기 여러 대를 묶음)
    private data class Station(
        val id: String, val name: String, val addr: String,
        val lat: Double, val lon: Double, var available: Boolean
    )

    private var cache: List<Station> = emptyList()
    private var cacheKey = ""
    private var cacheAt = 0L

    // @Synchronized: 미리 받기가 진행 중일 때 버튼을 누르면, 끝날 때까지 기다렸다가 그 결과를 씀
    @Synchronized
    override fun findNear(lat: Double, lon: Double, events: List<EventCard>): List<ChargerInfo> {
        val key = BuildConfig.DATA_GO_KR_KEY
        if (key.isBlank()) throw IllegalStateException("API 키 없음 (local.properties)")
        if (lat == 0.0) throw IllegalStateException("현재 위치 모름")

        // ① 가장 가까운 지역 받아오기 (10분 동안은 다시 안 받음)
        val area = AREAS.minByOrNull { Geo.distanceKm(lat, lon, it.lat, it.lon) }!!
        if (area.code != cacheKey || System.currentTimeMillis() - cacheAt > CACHE_MS) {
            val t0 = System.currentTimeMillis()
            val map = mutableMapOf<String, Station>()
            fetchArea(key, area, map)
            cache = map.values.toList()
            cacheKey = area.code
            cacheAt = System.currentTimeMillis()
            Log.d(TAG, "[충전소API] ${area.name} 받기 완료: 충전소 ${cache.size}곳, ${(cacheAt - t0) / 1000.0}초")
        }

        // ② 고르기: 1~5km → 행사장 근처 제외 → 비어 있는 곳 먼저 → '돌아가는 거리'가 작은 순
        //    돌아가는 거리 = (지금→충전소) + (충전소→목적지) − (지금→목적지)
        val dLat = DataHub.destLat
        val dLon = DataHub.destLon
        val direct = if (dLat != 0.0) Geo.distanceKm(lat, lon, dLat, dLon) else 0.0

        data class Cand(val s: Station, val dist: Double, val detour: Double)
        val picked = cache
            .map { s ->
                val d = Geo.distanceKm(lat, lon, s.lat, s.lon)
                val detour = if (dLat != 0.0) d + Geo.distanceKm(s.lat, s.lon, dLat, dLon) - direct else d
                Cand(s, d, detour)
            }
            .filter { it.dist in MIN_KM..MAX_KM }
            .filter { c -> events.none { e -> Geo.distanceKm(c.s.lat, c.s.lon, e.lat, e.lon) < AVOID_EVENT_KM } }
            .sortedWith(compareByDescending<Cand> { it.s.available }.thenBy { it.detour })

        Log.d(TAG, "[충전소API] 조건 맞는 곳 ${picked.size}곳 (목적지 ${if (dLat != 0.0) "앎" else "모름"})")
        picked.take(3).forEach { c ->
            Log.d(TAG, "[충전소API]  ${c.s.name} (${"%.1f".format(c.dist)}km, 돌아가는 거리 +${"%.1f".format(c.detour)}km, ${if (c.s.available) "비어 있음" else "사용 중"})")
        }

        return picked.map { c -> ChargerInfo(c.s.name, c.s.lat, c.s.lon, c.s.addr, c.s.available) }
    }

    private fun fetchArea(key: String, area: Area, out: MutableMap<String, Station>) {
        val url = "$BASE?serviceKey=${URLEncoder.encode(key, "UTF-8")}" +
                "&pageNo=1&numOfRows=9999&dataType=JSON&zcode=${area.code.take(2)}&zscode=${area.code}"
        val text = get(url)
        if (text.trimStart().startsWith("<")) throw Exception("API 오류 응답: ${text.take(200)}")

        val root = JSONObject(text)
        val items = root.optJSONObject("items")
            ?: throw Exception("응답에 items 없음: ${text.take(200)}")
        val arr = items.optJSONArray("item") ?: JSONArray().put(items.getJSONObject("item"))
        Log.d(TAG, "[충전소API] ${area.name}: 충전기 ${arr.length()}대 / 전체 ${root.optInt("totalCount")}대")

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("delYn") == "Y") continue   // 삭제된 충전기 제외
            if (o.optString("limitYn") == "Y") continue   // 아파트·회사 전용 등 이용 제한 충전기 제외
            val id = o.optString("statId")
            val la = o.optString("lat").toDoubleOrNull() ?: continue
            val lo = o.optString("lng").toDoubleOrNull() ?: continue
            val free = o.optString("stat") == "2"   // 2 = 충전대기(비어 있음)
            val s = out.getOrPut(id) {
                Station(id, o.optString("statNm"), o.optString("addr"), la, lo, false)
            }
            if (free) s.available = true   // 충전기 한 대라도 비어 있으면 '비어 있음'
        }
    }


    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 60000   // 응답이 커서 넉넉히 (미리 받기라 운전자는 안 기다림)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}