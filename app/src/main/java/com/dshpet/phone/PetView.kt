package com.dshpet.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * 宠物渲染层。
 *
 * - 有素材：播放动画 WebP（AnimatedImageDrawable，Android 9+ 原生支持 alpha）
 * - 没素材：画一只内置的占位小鲸鱼（呼吸 + 眨眼），保证 clone 下来就能跑
 *
 * 两处「对齐上游手感」的细节：
 *  1) 挤压（Q 弹）以**脚底**为锚点，不是以中心——否则会像气球一样原地膨胀；
 *  2) 挤压曲线用 sin，220ms 内 0→0.55→0，与 physics.ts 的 SQUASH 常量一致。
 */
class PetView(context: Context) : View(context), Drawable.Callback {

    private var frame: AnimatedImageDrawable? = null
    private var mask: AnimationPack.Mask? = null

    /** 裁剪区域（整帧坐标），把悬浮窗收紧到身体范围，减少透明区吃点击 */
    private var crop: RectF? = null
    private var frameW = 480f
    private var frameH = 270f

    var facingRight = true
        set(v) {
            field = v
            invalidate()
        }

    private var squashStart = -10000L
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bellyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPath = Path()

    private val cPrimary = ContextCompat.getColor(context, R.color.primary)
    private val cBelly = Color.parseColor("#CFEFFF")
    private val cEye = Color.parseColor("#0C1220")

    init {
        bodyPaint.color = cPrimary
        bellyPaint.color = cBelly
        eyePaint.color = cEye
        setWillNotDraw(false)
    }

    // ── 素材帧 ────────────────────────────────────────────────────

    fun setFrame(drawable: AnimatedImageDrawable?, m: AnimationPack.Mask?, fw: Int, fh: Int) {
        frame?.let {
            runCatching { it.stop() }
            it.callback = null
        }
        frame = drawable
        mask = m
        frameW = fw.toFloat().coerceAtLeast(1f)
        frameH = fh.toFloat().coerceAtLeast(1f)
        if (drawable != null) {
            drawable.callback = this
            runCatching { drawable.start() }
        }
        invalidate()
    }

    /** 传入归一化裁剪框（0..1），null 表示整帧 */
    fun setCrop(rect: RectF?) {
        crop = rect
        invalidate()
    }

    fun squish() {
        squashStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidate()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        postDelayed(what, `when` - SystemClock.uptimeMillis())
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        removeCallbacks(what)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val amount = Physics.squashAmount((now - squashStart).toFloat())

        canvas.save()
        // 裁剪：只显示身体范围
        val c = crop
        if (c != null && c.width() > 0f && c.height() > 0f) {
            canvas.translate(-c.left * frameW, -c.top * frameH)
            canvas.scale(
                width / (c.width() * frameW),
                height / (c.height() * frameH)
            )
        }
        canvas.scale(Physics.squashScaleX(amount), Physics.squashScaleY(amount), width / 2f, height.toFloat())
        if (!facingRight) canvas.scale(-1f, 1f, width / 2f, height / 2f)

        val d = frame
        if (d != null) {
            d.setBounds(0, 0, frameW.toInt(), frameH.toInt())
            d.draw(canvas)
        } else {
            drawPlaceholder(canvas, now)
        }
        canvas.restore()

        if (amount > 0f) postInvalidateOnAnimation()
    }

    /** 内置占位宠物：一只会呼吸、会眨眼的小鲸鱼 */
    private fun drawPlaceholder(canvas: Canvas, now: Long) {
        val t = now / 1000f
        val cx = width * 0.52f
        val cy = height * 0.58f
        val bw = width * 0.40f
        val bh = height * 0.34f

        val breathe = 1f + 0.025f * sin(t * 1.9f)
        val drift = width * 0.008f * sin(t * 0.7f)

        // 尾巴
        bodyPath.reset()
        bodyPath.moveTo(cx - bw * 0.85f, cy)
        bodyPath.lineTo(cx - bw * 1.45f, cy - bh * 0.55f)
        bodyPath.lineTo(cx - bw * 1.18f, cy)
        bodyPath.lineTo(cx - bw * 1.45f, cy + bh * 0.55f)
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        // 身体
        canvas.drawOval(
            cx - bw * breathe + drift, cy - bh * breathe,
            cx + bw * breathe + drift, cy + bh * breathe,
            bodyPaint
        )
        // 肚皮
        canvas.drawOval(
            cx - bw * 0.62f + drift, cy - bh * 0.18f,
            cx + bw * 0.62f + drift, cy + bh * 0.78f,
            bellyPaint
        )
        // 眼睛（每 4 秒眨一次，闭眼 120ms）
        val phase = (t % 4f)
        val blink = phase in 3.1f..3.22f
        val eyeR = bh * 0.115f
        val eyeY = cy - bh * 0.18f
        val eyeDx = bw * 0.30f
        if (blink) {
            canvas.drawRect(
                cx - eyeDx - eyeR, eyeY - eyeR * 0.22f,
                cx - eyeDx + eyeR, eyeY + eyeR * 0.22f, eyePaint
            )
            canvas.drawRect(
                cx + eyeDx - eyeR, eyeY - eyeR * 0.22f,
                cx + eyeDx + eyeR, eyeY + eyeR * 0.22f, eyePaint
            )
        } else {
            canvas.drawCircle(cx - eyeDx + drift, eyeY, eyeR, eyePaint)
            canvas.drawCircle(cx + eyeDx + drift, eyeY, eyeR, eyePaint)
        }

        postInvalidateOnAnimation()
    }

    /**
     * 触摸命中：有蒙版按蒙版，没蒙版按椭圆身体。
     * 注意 u/v 是**窗口坐标**，窗口被裁剪过，要先映射回整帧坐标再采样蒙版。
     */
    fun hitTest(u: Float, v: Float): Boolean {
        val c = crop
        val fu = if (c != null && c.width() > 0f) c.left + u * c.width() else u
        val fv = if (c != null && c.height() > 0f) c.top + v * c.height() else v
        mask?.let { return it.hit(fu, fv) }
        val nx = (fu - 0.52f) / 0.46f
        val ny = (fv - 0.58f) / 0.40f
        return nx * nx + ny * ny <= 1f
    }
}
