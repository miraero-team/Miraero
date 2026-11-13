package com.miraero.driveapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun minutesToPass() {
        // 15km를 30분 → 분당 0.5km. 2km 남았으면 4분 뒤 통과
        assertEquals(4.0, EventMatcher.minutesToPass(2.0, 15.0, 30)!!, 0.001)
        assertNull(EventMatcher.minutesToPass(2.0, 0.0, 0))   // 안내 중 아님
    }

    @Test
    fun energy() {
        // 배터리 18%, 30km, 정체 30분, 에어컨 On → 도착 약 8.7% → 부족 (팀 기록 7장 값)
        val r = EnergyPlanner.check(18, 30.0, 0.5, true)
        assertEquals(8.7, r.arrivePercent, 0.1)
        assertTrue(r.isShort)
    }
}
