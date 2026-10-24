package com.dshpet.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 物理层的纯逻辑测试（不依赖 Android，`gradle test` 直接跑）。
 * 这些常量是「手感」的来源，改之前先跑一遍这里的用例。
 */
class PhysicsTest {

    @Test
    fun squashIsZeroAtBothEnds() {
        assertEquals(0f, Physics.squashAmount(0f), 0.0001f)
        assertEquals(0f, Physics.squashAmount(Physics.SQUASH_MS), 0.0001f)
        assertEquals(0f, Physics.squashAmount(-10f), 0.0001f)
        assertEquals(0f, Physics.squashAmount(9999f), 0.0001f)
    }

    @Test
    fun squashPeaksInTheMiddle() {
        val peak = Physics.squashAmount(Physics.SQUASH_MS / 2f)
        assertEquals(Physics.SQUASH_DEPTH, peak, 0.01f)
        assertTrue(Physics.squashScaleY(peak) < 1f)
        assertTrue(Physics.squashScaleX(peak) > 1f)
    }

    @Test
    fun springConvergesToTarget() {
        var x = 0f
        var v = 0f
        repeat(120) {
            val (nx, nv) = Physics.springStep(x, v, 300f, 1f / 60f)
            x = nx
            v = nv
        }
        assertEquals(300f, x, 2f)
    }

    @Test
    fun springDoesNotOvershootWildly() {
        var x = 0f
        var v = 0f
        var peak = 0f
        repeat(120) {
            val (nx, nv) = Physics.springStep(x, v, 300f, 1f / 60f)
            x = nx
            v = nv
            if (x > peak) peak = x
        }
        // 过阻尼：允许少量过冲，但不能像皮球一样来回弹
        assertTrue("过冲过大：$peak", peak < 300f * 1.35f)
    }

    @Test
    fun flingEventuallyStopsOnTheGround() {
        val b = Physics.Body(100f, 0f)
        b.vx = 900f
        b.vy = -200f
        val bounds = Physics.Bounds(0f, 0f, 500f, 400f)
        var frames = 0
        var moving = true
        while (moving && frames < 900) {
            moving = Physics.flingStep(b, 1f / 60f, bounds, 400f)
            frames++
        }
        assertTrue("15 秒内应该停稳，实际 $frames 帧", frames < 900)
        assertEquals(400f, b.y, 2f)
        assertTrue("停下来时水平速度应该很小：${b.vx}", kotlin.math.abs(b.vx) < Physics.DEADZONE)
    }

    @Test
    fun flingStaysInsideBounds() {
        val b = Physics.Body(10f, 200f)
        b.vx = -3000f
        b.vy = 0f
        val bounds = Physics.Bounds(0f, 0f, 500f, 400f)
        repeat(240) {
            Physics.flingStep(b, 1f / 60f, bounds, 400f)
            assertTrue("跑出左边界：${b.x}", b.x >= -1f)
            assertTrue("跑出右边界：${b.x}", b.x <= 501f)
        }
    }

    @Test
    fun velocityIsCapped() {
        val (vx, vy) = Physics.clampFlingVelocity(9000f, 9000f)
        assertTrue(hypot(vx, vy) <= Physics.MAX_SPEED + 1f)
    }

    @Test
    fun flingThresholdIsSane() {
        assertTrue(Physics.isFling(Physics.FLING_MIN))
        assertTrue(!Physics.isFling(Physics.FLING_MIN - 1f))
    }

    @Test
    fun safePositionKeepsPetOnScreen() {
        val bounds = Physics.Bounds(0f, 100f, 1000f, 2000f)
        val (x, y) = Physics.safePosition(-50f, 5000f, bounds, 200f, 300f)
        assertEquals(0f, x, 0.01f)
        assertEquals(1700f, y, 0.01f)
    }

    @Test
    fun roamFlipsOnlyWhenFacingOutward() {
        val (x1, flip1) = Physics.roamLeft(0f, 800f, 0f, -10f)
        assertEquals(0f, x1, 0.01f)
        assertTrue(flip1)

        val (_, flip2) = Physics.roamLeft(0f, 800f, 0f, 10f)
        assertTrue(!flip2)
    }
}
