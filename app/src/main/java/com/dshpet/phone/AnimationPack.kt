package com.dshpet.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer

/**
 * 动画素材访问层。
 *
 * 上游用 VP9-alpha 的 `.webm`，Android 原生播不了透明视频（MediaCodec 不暴露 alpha 平面），
 * 所以这里沿用安卓移植版验证过的方案：**动画 WebP + AnimatedImageDrawable**（Android 9+ 原生支持）。
 *
 * 查找顺序：`filesDir/pet/<名>.webp`（用户可自行投喂）→ `assets/pet/<名>.webp`（随包分发）。
 */
class AnimationPack(private val ctx: Context, val config: PetConfig) {

    /** 命中测试用的 alpha 蒙版（缩到 64px 宽，足够判定） */
    data class Mask(
        /** 不透明区域在整帧中的相对矩形（0..1），用于把悬浮窗裁到身体范围 */
        val bounds: RectF,
        val grid: BooleanArray,
        val gw: Int,
        val gh: Int
    ) {
        fun hit(u: Float, v: Float): Boolean {
            if (gw <= 0 || gh <= 0) return true
            val gx = (u * gw).toInt().coerceIn(0, gw - 1)
            val gy = (v * gh).toInt().coerceIn(0, gh - 1)
            return grid[gy * gw + gx]
        }
    }

    private val maskCache = LruCache<String, Mask>(12)

    fun exists(name: String): Boolean = locate(name) != null

    private fun locate(name: String): File? {
        val user = File(File(ctx.filesDir, "pet"), "$name.webp")
        return if (user.isFile) user else null
    }

    fun readBytes(name: String): ByteArray? {
        locate(name)?.let { f ->
            runCatching { return f.readBytes() }
                .onFailure { Diag.w("AnimationPack", "读取用户素材失败 $name：${it.message}") }
        }
        return runCatching { ctx.assets.open("pet/$name.webp").readBytes() }.getOrNull()
    }

    /** 每次切换动画都解一个新的实例：一次性动画需要从头播放 */
    fun decodeDrawable(name: String, loop: Boolean): AnimatedImageDrawable? {
        val bytes = readBytes(name) ?: return null
        return runCatching {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val d = ImageDecoder.decodeDrawable(src) as? AnimatedImageDrawable ?: return null
            d.repeatCount = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
            d
        }.getOrElse {
            Diag.w("AnimationPack", "解码失败 $name：${it.message}")
            null
        }
    }

    fun maskOf(name: String): Mask? {
        maskCache.get(name)?.let { return it }
        val bytes = readBytes(name) ?: return null
        val mask = runCatching { buildMask(bytes) }.getOrNull() ?: return null
        maskCache.put(name, mask)
        return mask
    }

    private fun buildMask(bytes: ByteArray): Mask? {
        val bmp: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            if (w > 64) {
                val h = (info.size.height.toLong() * 64 / w).toInt().coerceAtLeast(1)
                decoder.setTargetSize(64, h)
            }
        }
        val gw = bmp.width
        val gh = bmp.height
        if (gw <= 0 || gh <= 0) return null
        val grid = BooleanArray(gw * gh)
        var minX = gw
        var minY = gh
        var maxX = -1
        var maxY = -1
        val row = IntArray(gw)
        for (y in 0 until gh) {
            bmp.getPixels(row, 0, gw, 0, y, gw, 1)
            for (x in 0 until gw) {
                val a = (row[x] ushr 24) and 0xFF
                if (a > 24) {
                    grid[y * gw + x] = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        bmp.recycle()
        if (maxX < minX || maxY < minY) {
            return Mask(RectF(0f, 0f, 1f, 1f), grid, gw, gh)
        }
        val rect = RectF(
            minX.toFloat() / gw,
            minY.toFloat() / gh,
            (maxX + 1).toFloat() / gw,
            (maxY + 1).toFloat() / gh
        )
        return Mask(rect, grid, gw, gh)
    }

    /** 状态对应的候选文件（支持一个状态多段随机） */
    fun candidates(state: String): List<String> = config.filesOf(state)

    fun pick(state: String, avoid: String? = null): String? {
        val list = candidates(state)
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]
        val filtered = if (avoid != null && list.size > 1) list.filter { it != avoid } else list
        return (filtered.ifEmpty { list }).random()
    }
}
