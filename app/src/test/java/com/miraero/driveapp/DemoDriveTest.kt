package com.miraero.driveapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemoDriveTest {
    @Test
    fun advance() {
        // 시속 36km로 1초 = 10m
        assertEquals(0.99, DemoDrive.advance(1.0, 36.0, 1.0), 0.0001)
        assertEquals(0.0, DemoDrive.advance(0.005, 36.0, 1.0), 0.0)   // 0 밑으로 안 내려감
    }

    @Test
    fun minutesToPass() {
        // 1km를 시속 60km → 1분
        assertEquals(1.0, DemoDrive.minutesToPass(1.0, 60.0)!!, 0.0001)
        assertNull(DemoDrive.minutesToPass(1.0, 0.0))   // 정차 중
    }
}
