package com.miraero.driveapp

object EnergyPlanner {

    // ===== 기본값 (실차에서는 실제 값으로 대체) =====
    private const val BATTERY_CAPACITY_KWH = 77.4   // 배터리 용량 (Virtual Console 값)
    private const val DEFAULT_EFFICIENCY = 5.0      // km/kWh, 에뮬레이터가 전비 미지원
    private const val MARGIN_PERCENT = 10.0         // 도착 시 최소 남아야 하는 %

    // 정차 중 시간당 배터리 소모 (%/h)
    private const val IDLE_DRAIN_AC_ON = 3.0
    private const val IDLE_DRAIN_AC_OFF = 0.5

    // 배터리 1%로 갈 수 있는 거리 (km/%)
    private val kmPerPercent = BATTERY_CAPACITY_KWH * 0.01 * DEFAULT_EFFICIENCY

    data class Result(
        val needPercent: Double,     // 목적지까지 필요한 %
        val arrivePercent: Double,   // 도착했을 때 남는 %
        val isShort: Boolean         // 충전 필요?
    )

    fun check(socPercent: Int, remainKm: Double, jamHours: Double, acOn: Boolean): Result {
        val drive = remainKm / kmPerPercent
        val idle = jamHours * (if (acOn) IDLE_DRAIN_AC_ON else IDLE_DRAIN_AC_OFF)
        val need = drive + idle
        val arrive = socPercent - need
        return Result(need, arrive, arrive < MARGIN_PERCENT)
    }
}