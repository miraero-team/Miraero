package com.miraero.driveapp

import android.os.Handler
import android.os.Looper
import android.util.Log

// 시연용 가상 주행.
// 에뮬레이터 내비는 Virtual Console 속도를 올려도 현재 위치가 움직이지 않음.
// 그래서 "행사장까지 남은 거리"를 차 속도로 직접 줄여 가며 통과 장면을 재현한다.
// 실차에서는 쓰지 않음 (실차: 내비 현재 위치 + EventMatcher.minutesToPass)
class DemoDrive(
    private val leadMin: Double,                  // 통과 몇 분 전에 장면을 일으킬지
    private val onChange: () -> Unit,             // 화면 갱신
    private val onPass: (EventCard) -> Unit       // 통과 직전 (음악 / 경기 스코어)
) {
    companion object {
        const val TICK_SEC = 1.0

        // 속도로 tick초 동안 간 만큼 남은 거리 줄이기
        fun advance(remainKm: Double, kmh: Double, sec: Double): Double =
            (remainKm - kmh * sec / 3600.0).coerceAtLeast(0.0)

        // 남은 거리를 지금 속도로 가면 몇 분 뒤 통과하나. 멈춰 있으면 null
        fun minutesToPass(remainKm: Double, kmh: Double): Double? =
            if (kmh <= 0.0) null else remainKm / kmh * 60.0
    }

    var event: EventCard? = null
        private set
    var remainKm = 0.0
        private set
    var speedKmh = 0.0
        private set
    var carSpeedKmh: Double? = null      // 차량 속도 (Virtual Console). MainActivity가 넣어 줌
    private var fixedKmh: Double? = null // 차량 속도를 못 읽을 때 adb로 지정한 속도
    private var passed = false

    val running get() = event != null

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val tick = object : Runnable {
        override fun run() {
            step()
            if (running) handler.postDelayed(this, (TICK_SEC * 1000).toLong())
        }
    }

    fun start(e: EventCard, km: Double, kmh: Double?) {
        stop()
        event = e; remainKm = km; fixedKmh = kmh; passed = false
        Log.d("PLEOS_TEST", "[시연 주행] 시작: ${e.venue}까지 ${km}km, 속도=${kmh ?: "차량 속도"}")
        handler.postDelayed(tick, (TICK_SEC * 1000).toLong())
        onChange()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        if (event != null) Log.d("PLEOS_TEST", "[시연 주행] 끝")
        event = null
        onChange()
    }

    private fun step() {
        val e = event ?: return
        speedKmh = fixedKmh ?: carSpeedKmh ?: 0.0
        remainKm = advance(remainKm, speedKmh, TICK_SEC)
        val min = minutesToPass(remainKm, speedKmh)
        Log.d("PLEOS_TEST", "[시연 주행] ${e.venue}까지 ${"%.2f".format(remainKm)}km, ${"%.0f".format(speedKmh)}km/h, 통과까지 ${min?.let { "%.1f".format(it) } ?: "-"}분")
        if (!passed && min != null && min <= leadMin) {
            passed = true
            onPass(e)
        }
        if (remainKm <= 0.0) stop() else onChange()
    }
}
