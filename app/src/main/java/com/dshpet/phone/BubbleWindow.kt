package com.dshpet.phone

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 台词气泡。独立窗口、不可点击：既不挡操作，也不影响宠物本体的事件。
 * 位置按「宠物身体中心」对齐到头顶，并夹在屏幕内（旧移植版气泡掉到屏幕顶上的问题就出在这里）。
 */
class BubbleWindow(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val text = TextView(ctx).apply {
        setTextColor(Color.parseColor("#0C1220"))
        textSize = 13f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#F4FBFF"))
            setStroke(dp(1), Color.parseColor("#B8E4F5"))
        }
    }

    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private var attached = false
    private val hideRunnable = Runnable { hide() }

    fun show(message: String, centerX: Int, anchorTop: Int, screenW: Int, durationMs: Long = 4200L) {
        if (message.isBlank()) return
        handler.removeCallbacks(hideRunnable)
        text.text = message

        val maxW = dp(240)
        text.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = text.measuredWidth.coerceIn(dp(48), maxW)
        val h = text.measuredHeight

        params.width = w
        params.height = h
        params.x = (centerX - w / 2).coerceIn(dp(4), (screenW - w - dp(4)).coerceAtLeast(dp(4)))
        params.y = (anchorTop - h - dp(6)).coerceAtLeast(dp(24))

        if (!attached) {
            runCatching { wm.addView(text, params) }
                .onSuccess {
                    attached = true
                    text.alpha = 0f
                    text.animate().alpha(1f).setDuration(120).start()
                }
                .onFailure { Diag.w("Bubble", "addView 失败：${it.message}") }
        } else {
            runCatching { wm.updateViewLayout(text, params) }
        }
        handler.postDelayed(hideRunnable, durationMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (!attached) return
        runCatching { wm.removeView(text) }
        attached = false
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
