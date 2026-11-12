package com.miraero.driveapp

import android.util.Log

// 알림 폭탄 방지: "언제 알림을 보낼지"만 판단
object AlertManager {

    private const val COOLDOWN_MS = 15 * 60 * 1000L   // 같은 부족 알림은 15분 간격
    private const val BIG_DROP = 5.0                  // 도착 배터리가 5%p 이상 더 나빠지면 "큰 변화"

    private var lastShort: Boolean? = null    // 직전 판단 (null = 아직 판단 안 함)
    private var lastAlertAt = 0L
    private var lastAlertArrive = 0.0
    private var dismissedArrive: Double? = null   // "그냥 가기" 누를 때의 도착 배터리
    private var accepted = false                  // "충전소 들르기" 누름

    // 목적지가 바뀌면 처음부터
    fun reset() {
        lastShort = null; lastAlertAt = 0L; lastAlertArrive = 0.0
        dismissedArrive = null; accepted = false
        Log.d("PLEOS_TEST", "[알림규칙] 새 목적지 → 초기화")
    }

    fun onAccepted() { accepted = true }
    fun onDismissed() { dismissedArrive = lastAlertArrive }

    // 이번 판단 결과로 알림을 보낼지
    fun shouldAlert(isShort: Boolean, arrive: Double): Boolean {
        val prev = lastShort
        lastShort = isShort
        val now = System.currentTimeMillis()

        val (send, reason) = when {
            accepted -> false to "충전소 경유 선택함 → 생략"
            prev == null -> true to "첫 판단"
            !isShort -> false to "충분 유지 → 생략"
            dismissedArrive != null && arrive > dismissedArrive!! - BIG_DROP ->
                false to "그냥 가기 후 큰 변화 없음 → 생략"
            prev == false -> true to "충분 → 부족으로 바뀜"
            now - lastAlertAt >= COOLDOWN_MS && arrive <= lastAlertArrive - BIG_DROP ->
                true to "계속 부족 + 더 나빠짐"
            else -> false to "계속 부족, 쿨다운 중 → 생략"
        }
        if (send) {
            lastAlertAt = now
            lastAlertArrive = arrive
            dismissedArrive = null
        }
        Log.d("PLEOS_TEST", "[알림규칙] ${if (send) "보냄" else "안 보냄"}: $reason (도착 ${"%.1f".format(arrive)}%)")
        return send
    }
}