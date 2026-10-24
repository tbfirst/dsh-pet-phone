package com.dshpet.phone

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 一只宠物的悬浮窗：窗口位置 = 宠物位置，窗口大小 = 身体包围盒。
 *
 * 为什么不做「全屏透明窗口 + 逐像素穿透」：
 * Android 的悬浮窗命中判定是**矩形**，全屏窗口会把整块屏幕的触摸都吃掉。
 * 所以这里反过来做——把窗口收紧到身体的 alpha 包围盒，窗口之外的区域天然不响应，
 * 这就是手机端能做到的「点击穿透」。
 */
class PetWindow(
    private val ctx: Context,
    private val slot: Int,
    private val config: PetConfig,
    private val pack: AnimationPack,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onTap(pet: PetWindow)
        fun onLongPress(pet: PetWindow)
        fun onFling(pet: PetWindow, speed: Float)
    }

    val view = PetView(ctx)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density = ctx.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        gravity = Gravity.TOP or Gravity.START
    }

    val body = Physics.Body(0f, 0f)

    var state: String = PetState.IDLE
        private set

    private var attached = false
    private var winW = 0
    private var winH = 0
    private var screenW = 0
    private var screenH = 0
    private var statusBarH = 0
    private var navBarH = 0

    private var displayMask: AnimationPack.Mask? = null
    private var currentFile: String? = null
    /** 没有素材的状态记在这里，避免每帧重复尝试解码（否则 idle→sleep→idle 会变成解码风暴） */
    private val missingStates = HashSet<String>()
    private var oneShotUntil = 0L
    private var lastInteraction = SystemClock.uptimeMillis()
    private var idleSince = SystemClock.uptimeMillis()

    // 触摸
    private var downRawX = 0f
    private var downRawY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private var dragging = false
    private var dragTargetX = 0f
    private var dragTargetY = 0f
    private var velocity: VelocityTracker? = null

    // 漫游
    private var roamUntil = 0L
    private var roamDir = 1f

    private val longPressRunnable = Runnable {
        if (!dragging) {
            Diag.i("Pet", "长按 → 菜单")
            callbacks.onLongPress(this)
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────────

    fun show() {
        if (attached) return
        updateScreenMetrics()
        prepareDisplayMask()
        computeSize()

        body.x = (screenW - winW - dp(16) - slot * dp(14)).coerceAtLeast(0f)
        body.y = groundY()

        params.width = winW
        params.height = winH
        params.x = body.x.toInt()
        params.y = body.y.toInt()

        view.setOnTouchListener { _, ev -> handleTouch(ev) }

        runCatching { wm.addView(view, params) }
            .onSuccess {
                attached = true
                Diag.i("Pet", "已上屏 slot=$slot 尺寸=${winW}x$winH 素材=${pack.config.hasAnimations}")
                setState(PetState.IDLE)
            }
            .onFailure { Diag.e("Pet", "addView 失败", it) }
    }

    fun hide() {
        if (!attached) return
        attached = false
        view.removeCallbacks(longPressRunnable)
        runCatching { wm.removeView(view) }
        view.setFrame(null, null, config.canvasW, config.canvasH)
    }

    val isShowing: Boolean get() = attached

    fun centerX(): Int = (body.x + winW / 2f).toInt()

    fun topY(): Int = body.y.toInt()

    fun release() {
        hide()
        runCatching { velocity?.recycle() }
        velocity = null
    }

    // ── 尺寸与屏幕 ────────────────────────────────────────────────

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        statusBarH = systemDimen("status_bar_height", dp(24))
        navBarH = systemDimen("navigation_bar_height", dp(48))
    }

    private fun systemDimen(name: String, fallback: Int): Int {
        val id = ctx.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else fallback
    }

    /** 用待机动画的 alpha 包围盒裁剪窗口——这是「透明区不挡点击」的关键 */
    private fun prepareDisplayMask() {
        val idleFile = pack.candidates(PetState.IDLE).firstOrNull()
        displayMask = if (idleFile != null) pack.maskOf(idleFile) else null
    }

    private fun computeSize() {
        val baseW = dp(prefs.sizeDp)
        val baseH = (baseW * config.canvasH.toFloat() / config.canvasW).toInt()
        val c = displayMask?.bounds
        if (c != null && c.width() > 0.25f && c.height() > 0.25f) {
            winW = (baseW * c.width()).toInt().coerceAtLeast(dp(32))
            winH = (baseH * c.height()).toInt().coerceAtLeast(dp(32))
            view.setCrop(RectF(c))
        } else {
            winW = baseW
            winH = baseH
            view.setCrop(null)
        }
    }

    fun resize() {
        if (!attached) return
        updateScreenMetrics()
        // 素材可能在外部被替换过，这里顺手清一次「缺素材」缓存
        missingStates.clear()
        prepareDisplayMask()
        computeSize()
        params.width = winW
        params.height = winH
        body.x = body.x.coerceIn(0f, maxX())
        body.y = body.y.coerceIn(minY(), groundY())
        applyLayout(force = true)
    }

    private fun minY(): Float = statusBarH + dp(4).toFloat()

    private fun groundY(): Float = (screenH - navBarH - winH).toFloat().coerceAtLeast(minY())

    private fun maxX(): Float = (screenW - winW).toFloat().coerceAtLeast(0f)

    private fun bounds(): Physics.Bounds =
        Physics.Bounds(0f, minY(), maxX(), groundY())

    // ── 帧推进 ────────────────────────────────────────────────────

    fun step(dtSec: Float) {
        if (!attached) return
        val now = SystemClock.uptimeMillis()
        val dt = dtSec.coerceIn(0.001f, 0.05f)

        if (dragging) {
            val (nx, nvx) = Physics.springStep(body.x, body.vx, dragTargetX, dt)
            val (ny, nvy) = Physics.springStep(body.y, body.vy, dragTargetY, dt)
            body.x = nx
            body.vx = nvx
            body.y = ny
            body.vy = nvy
        } else when (state) {
            PetState.FLING -> {
                val moving = Physics.flingStep(body, dt, bounds(), groundY())
                if (!moving) {
                    body.vx = 0f
                    body.vy = 0f
                    view.squish()
                    setState(PetState.IDLE)
                }
            }
            PetState.WALK -> {
                body.x += Physics.WALK_DP_PER_SEC * density * dt * roamDir
                if (body.x <= 0f) {
                    body.x = 0f
                    roamDir = 1f
                    view.facingRight = true
                } else if (body.x >= maxX()) {
                    body.x = maxX()
                    roamDir = -1f
                    view.facingRight = false
                }
                if (now >= roamUntil) setState(PetState.IDLE)
            }
            else -> {}
        }

        // 一次性动画播完回待机
        if (oneShotUntil > 0L && now >= oneShotUntil) {
            oneShotUntil = 0L
            if (state == PetState.TAP || state == PetState.FLING) setState(PetState.IDLE)
        }

        // 久置 → 睡觉
        if (now - lastInteraction > SLEEP_AFTER_MS && state == PetState.IDLE && !dragging) {
            setState(PetState.SLEEP)
        }

        // 待机久了偶尔自己走两步
        if (state == PetState.IDLE && !dragging && now - idleSince > ROAM_AFTER_MS && Math.random() < 0.004) {
            roamDir = if (Math.random() < 0.5) -1f else 1f
            view.facingRight = roamDir > 0
            roamUntil = now + (2500..5500).random()
            setState(PetState.WALK)
        }

        body.x = body.x.coerceIn(0f, maxX())
        body.y = body.y.coerceIn(minY(), groundY())
        applyLayout()
    }

    private fun applyLayout(force: Boolean = false) {
        val x = body.x.toInt()
        val y = body.y.toInt()
        if (force || params.x != x || params.y != y) {
            params.x = x
            params.y = y
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    // ── 动画 ──────────────────────────────────────────────────────

    fun setState(newState: String) {
        if (!attached) {
            state = newState
            return
        }
        if (missingStates.contains(newState)) return
        val sameLooping = newState == state && config.isLoop(newState) &&
            newState != PetState.IDLE // 待机允许换一段，避免一直播同一段
        if (sameLooping) return

        val file = pack.pick(newState, currentFile)
        if (file == null) {
            missingStates.add(newState)
            // 没有这段素材：退回待机；如果连待机都没有，就用占位宠物
            if (newState != PetState.IDLE) {
                setState(PetState.IDLE)
            } else {
                state = newState
                oneShotUntil = 0L
                view.setFrame(null, displayMask, config.canvasW, config.canvasH)
            }
            return
        }

        state = newState
        currentFile = file
        idleSince = if (newState == PetState.IDLE) SystemClock.uptimeMillis() else idleSince

        val loop = config.isLoop(newState)
        val drawable = pack.decodeDrawable(file, loop)
        view.setFrame(drawable, displayMask, config.canvasW, config.canvasH)
        oneShotUntil = if (loop) 0L else SystemClock.uptimeMillis() + config.durationOf(file)
    }

    /** 点播一段动画（长按菜单「换个动作」用） */
    fun playRandomAction(): String? {
        val all = config.categories.values.flatten().ifEmpty { pack.candidates(PetState.TAP) }
        if (all.isEmpty()) return null
        val name = all.random()
        val d = pack.decodeDrawable(name, false)
        if (d == null) return null
        state = PetState.TAP
        currentFile = name
        view.setFrame(d, displayMask, config.canvasW, config.canvasH)
        view.squish()
        oneShotUntil = SystemClock.uptimeMillis() + config.durationOf(name)
        return name
    }

    fun flip() {
        view.facingRight = !view.facingRight
    }

    fun jumpHome() {
        body.x = (screenW - winW - dp(16)).coerceAtLeast(0f)
        body.y = groundY()
        body.stop()
        applyLayout(force = true)
        setState(PetState.IDLE)
    }

    // ── 触摸 ──────────────────────────────────────────────────────

    private fun handleTouch(ev: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        lastInteraction = now
        if (state == PetState.SLEEP) {
            setState(PetState.IDLE)
            view.squish()
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                grabDx = ev.rawX - body.x
                grabDy = ev.rawY - body.y
                dragging = false
                dragTargetX = body.x
                dragTargetY = body.y
                runCatching { velocity?.recycle() }
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                view.removeCallbacks(longPressRunnable)
                view.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop * 1.4f) {
                    dragging = true
                    view.removeCallbacks(longPressRunnable)
                    body.stop()
                    setState(PetState.DRAG)
                }
                if (dragging) {
                    dragTargetX = ev.rawX - grabDx
                    dragTargetY = ev.rawY - grabDy
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                var vx = 0f
                var vy = 0f
                velocity?.let {
                    it.computeCurrentVelocity(1000)
                    vx = it.xVelocity
                    vy = it.yVelocity
                }

                if (dragging) {
                    val (cx, cy) = Physics.clampFlingVelocity(vx, vy)
                    val speed = hypot(cx, cy)
                    if (up && Physics.isFling(speed)) {
                        body.vx = cx
                        body.vy = cy
                        body.y -= dp(2)
                        setState(PetState.FLING)
                        callbacks.onFling(this, speed)
                    } else {
                        setState(PetState.IDLE)
                    }
                } else if (up && hitAt(ev.rawX, ev.rawY)) {
                    view.squish()
                    setState(PetState.TAP)
                    callbacks.onTap(this)
                }

                runCatching { velocity?.recycle() }
                velocity = null
                dragging = false
            }
        }
        return true
    }

    private fun hitAt(rawX: Float, rawY: Float): Boolean {
        if (winW <= 0 || winH <= 0) return false
        val u = (rawX - body.x) / winW
        val v = (rawY - body.y) / winH
        if (u < 0f || u > 1f || v < 0f || v > 1f) return false
        return view.hitTest(u, v)
    }

    companion object {
        const val LONG_PRESS_MS = 460L
        const val SLEEP_AFTER_MS = 3 * 60 * 1000L
        const val ROAM_AFTER_MS = 25 * 1000L
    }
}
