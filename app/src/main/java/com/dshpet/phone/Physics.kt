package com.dshpet.phone

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 桌宠物理。
 *
 * 常量与公式对齐上游 PC2005-cloud/dsh-pet 的 `src/shared/physics.ts`：
 * 弹簧 K=200 / C=30、重力 1400、恢复系数 0.78、地面摩擦 2.5、
 * 死区 500px/s、钳速 3600px/s、Q 弹 220ms / 压深 0.55。
 * 纯函数 + 无 Android 依赖，可在 JVM 单测里直接跑（见 PhysicsTest）。
 */
object Physics {

    const val SPRING_K = 200f
    const val SPRING_C = 30f
    const val GRAVITY = 1400f
    const val RESTITUTION = 0.78f
    const val GROUND_FRICTION = 2.5f
    const val DEADZONE = 500f
    const val MAX_SPEED = 3600f
    const val SQUASH_MS = 220f
    const val SQUASH_DEPTH = 0.55f

    /** 掉到地面后，垂直速度低于该值就不再弹跳 */
    const val BOUNCE_STOP = 150f

    /** 甩出去时低于该速度直接判定为「轻放」，原地停住 */
    const val FLING_MIN = 600f

    /** 漫游速度（dp/秒），实际像素由调用方乘以 density */
    const val WALK_DP_PER_SEC = 60f

    class Body(var x: Float, var y: Float) {
        var vx: Float = 0f
        var vy: Float = 0f

        fun stop() {
            vx = 0f
            vy = 0f
        }
    }

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun clampX(x: Float): Float = x.coerceIn(left, right)
        fun clampY(y: Float): Float = y.coerceIn(top, bottom)
    }

    /** 拖拽跟手：过阻尼弹簧（临界阻尼附近，不抖不飘） */
    fun springStep(current: Float, velocity: Float, target: Float, dt: Float): Pair<Float, Float> {
        val ax = (target - current) * SPRING_K - velocity * SPRING_C
        var v = velocity + ax * dt
        v = v.coerceIn(-MAX_SPEED, MAX_SPEED)
        val next = current + v * dt
        return next to v
    }

    /**
     * 甩出后的自由飞行：重力 → 撞边反弹 → 落地摩擦。
     * @return true 表示还在动，需要继续逐帧推进
     */
    fun flingStep(body: Body, dt: Float, bounds: Bounds, groundY: Float): Boolean {
        body.vy += GRAVITY * dt
        body.x += body.vx * dt
        body.y += body.vy * dt

        var grounded = false
        if (body.y >= groundY) {
            body.y = groundY
            if (body.vy > 0f) {
                body.vy = -body.vy * RESTITUTION
                if (abs(body.vy) < BOUNCE_STOP) {
                    body.vy = 0f
                    grounded = true
                }
            }
        }
        if (body.x <= bounds.left) {
            body.x = bounds.left
            body.vx = abs(body.vx) * RESTITUTION
        } else if (body.x >= bounds.right) {
            body.x = bounds.right
            body.vx = -abs(body.vx) * RESTITUTION
        }
        if (body.y <= bounds.top) {
            body.y = bounds.top
            if (body.vy < 0f) body.vy = -body.vy * RESTITUTION
        }
        if (grounded) {
            body.vx *= max(0f, 1f - GROUND_FRICTION * dt)
        }
        return !(grounded && abs(body.vx) < DEADZONE)
    }

    /** Q 弹曲线：t ∈ [0,1]，两端为 0，中间最大 */
    fun squashAmount(elapsedMs: Float): Float {
        if (elapsedMs <= 0f || elapsedMs >= SQUASH_MS) return 0f
        val t = elapsedMs / SQUASH_MS
        return sin(t * Math.PI).toFloat() * SQUASH_DEPTH
    }

    fun squashScaleY(amount: Float): Float = 1f - amount * 0.75f

    fun squashScaleX(amount: Float): Float = 1f + amount * 0.45f

    /** 把速度换算成「一次甩掷的初速度」，超限钳制 */
    fun clampFlingVelocity(vx: Float, vy: Float): Pair<Float, Float> {
        val speed = kotlin.math.hypot(vx, vy)
        if (speed <= MAX_SPEED) return vx to vy
        val k = MAX_SPEED / speed
        return vx * k to vy * k
    }

    /** 是否算作「甩」而不是「轻放」 */
    fun isFling(speed: Float): Boolean = speed >= FLING_MIN

    /**
     * 漫游边界：以宠物中心计算，允许半个身位贴边但不许整只出屏。
     */
    fun roamLeft(minLeft: Float, maxLeft: Float, x: Float, vx: Float): Pair<Float, Boolean> {
        var nx = x
        var flip = false
        if (nx <= minLeft) {
            nx = minLeft
            flip = vx < 0f
        } else if (nx >= maxLeft) {
            nx = maxLeft
            flip = vx > 0f
        }
        return nx to flip
    }

    /** 限制在屏幕内的安全位置 */
    fun safePosition(x: Float, y: Float, bounds: Bounds, w: Float, h: Float): Pair<Float, Float> {
        val nx = min(max(x, bounds.left), max(bounds.left, bounds.right - w))
        val ny = min(max(y, bounds.top), max(bounds.top, bounds.bottom - h))
        return nx to ny
    }
}
