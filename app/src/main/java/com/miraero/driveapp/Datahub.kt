package com.miraero.driveapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

// 충전소 한 곳
data class ChargerInfo(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val available: Boolean = true   // 지금 비어 있는지 (API가 알려주면)
)

// ===== 플러그 모양 (친구들이 이 모양대로 만듦) =====

// 행사 목록 주는 쪽. 친구 B: 축제 API로 하나 만들기
interface EventSource {
    fun fetch(context: Context): List<EventCard>   // 인터넷 써도 됨 (뒤에서 실행됨)
}

// 충전소 주는 쪽. 친구 A: 충전소 API로 하나 만들기
interface ChargerSource {
    // 현재 위치 근처 충전소를 좋은 순서대로 (비어 있음 → 행사장에서 먼 곳)
    fun findNear(lat: Double, lon: Double, events: List<EventCard>): List<ChargerInfo>
}

// ===== 샘플 플러그 (API 없거나 실패할 때 대신 씀) =====

object SampleEventSource : EventSource {
    override fun fetch(context: Context) = EventRepository.load(context)
}

object SampleChargerSource : ChargerSource {
    override fun findNear(lat: Double, lon: Double, events: List<EventCard>) = listOf(
        ChargerInfo("성남시청 전기차 충전소", 37.4200, 127.1267, "경기 성남시 중원구 성남대로 997")
    )
}

// ===== 콘센트 =====
object DataHub {
    private const val TAG = "PLEOS_TEST"

    // 친구들이 만든 플러그를 여기 꽂음. 비어 있으면 샘플 사용
    var eventSource: EventSource? = null
    var chargerSource: ChargerSource? = null

    // MainActivity가 계속 알려주는 현재 위치
    var lastLat = 0.0
    var lastLon = 0.0
    var lastEvents: List<EventCard> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())

    // 행사 목록: 실데이터 → 실패하면 샘플. 결과는 화면 쪽(메인)으로 돌려줌
    fun loadEvents(context: Context, done: (List<EventCard>) -> Unit) {
        Thread {
            val real = tryOrNull("행사 API") { eventSource?.fetch(context) }
            val list = if (!real.isNullOrEmpty()) {
                Log.d(TAG, "[데이터] 행사: 실데이터 ${real.size}건")
                real
            } else {
                Log.d(TAG, "[데이터] 행사: 샘플 사용")
                SampleEventSource.fetch(context)
            }
            lastEvents = list
            main.post { done(list) }
        }.start()
    }

    // 충전소 1곳: 실데이터 1등 → 실패하면 샘플
    fun findCharger(done: (ChargerInfo) -> Unit) {
        val lat = lastLat
        val lon = lastLon
        val events = lastEvents
        Thread {
            val real = tryOrNull("충전소 API") { chargerSource?.findNear(lat, lon, events) }
            val c = real?.firstOrNull()
                ?.also { Log.d(TAG, "[데이터] 충전소: 실데이터 ${it.name}") }
                ?: SampleChargerSource.findNear(lat, lon, events).first()
                    .also { Log.d(TAG, "[데이터] 충전소: 샘플 ${it.name}") }
            main.post { done(c) }
        }.start()
    }

    private fun <T> tryOrNull(what: String, block: () -> T?): T? =
        try { block() } catch (e: Exception) {
            Log.e(TAG, "[데이터] $what 실패 → 샘플로 대체: ${e.message}")
            null
        }
}