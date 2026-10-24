package com.dshpet.phone

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout

/**
 * 长按菜单（对应电脑版右键菜单的「最小可用版」）。
 * 只放 5 个高频项，动作分类树留给后续版本——手机上点三级菜单比电脑更烦。
 */
class PetMenuWindow(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density

    private var root: LinearLayout? = null
    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    val isShowing: Boolean get() = root != null

    fun show(
        anchorCenterX: Int,
        anchorTop: Int,
        screenW: Int,
        screenH: Int,
        items: List<Pair<String, () -> Unit>>
    ) {
        dismiss()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F2141C2E"))
                setStroke(dp(1), Color.parseColor("#336FD3F7"))
            }
        }
        items.forEach { (label, action) ->
            container.addView(makeButton(label) {
                dismiss()
                action()
            })
        }
        container.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true
            } else false
        }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = container.measuredWidth.coerceAtLeast(dp(120))
        val h = container.measuredHeight

        params.width = w
        params.height = h
        params.x = (anchorCenterX - w / 2).coerceIn(dp(4), (screenW - w - dp(4)).coerceAtLeast(dp(4)))
        // 优先弹在宠物上方；上方放不下就弹到下方
        params.y = if (anchorTop - h - dp(8) > dp(30)) {
            anchorTop - h - dp(8)
        } else {
            (anchorTop + dp(60)).coerceAtMost((screenH - h - dp(8)).coerceAtLeast(dp(30)))
        }

        runCatching { wm.addView(container, params) }
            .onSuccess { root = container }
            .onFailure { Diag.w("Menu", "addView 失败：${it.message}") }
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        runCatching { wm.removeView(r) }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#E8EEF9"))
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.TRANSPARENT)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minHeight = 0
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * density).toInt()
}
