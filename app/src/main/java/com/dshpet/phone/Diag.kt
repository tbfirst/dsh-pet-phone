package com.dshpet.phone

import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断：Logcat + 内存环形缓冲。悬浮窗服务里的异常最容易「无声消失」，
 * 这里把最近 200 条留在内存里，设置页可以直接看到。
 */
object Diag {
    private const val TAG = "DshPet"
    private const val MAX = 200
    private val ring = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun i(tag: String, msg: String) {
        Log.i("$TAG/$tag", msg)
        add("I/$tag", msg)
    }

    fun w(tag: String, msg: String) {
        Log.w("$TAG/$tag", msg)
        add("W/$tag", msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e("$TAG/$tag", msg, tr)
        add("E/$tag", if (tr != null) "$msg · ${tr.javaClass.simpleName}: ${tr.message}" else msg)
    }

    private fun add(level: String, msg: String) {
        synchronized(ring) {
            ring.addLast("${fmt.format(Date())} $level $msg")
            while (ring.size > MAX) ring.removeFirst()
        }
    }

    fun tail(n: Int = 40): String = synchronized(ring) {
        ring.toList().takeLast(n).joinToString("\n").ifEmpty { "（暂无日志）" }
    }

    fun report(ctx: Context): String = buildString {
        appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("机型 ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("─".repeat(24))
        append(tail(60))
    }
}
