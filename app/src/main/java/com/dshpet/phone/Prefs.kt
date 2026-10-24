package com.dshpet.phone

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置项。用 SharedPreferences 足够，避免为几个字段引入 DataStore。 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("dshpet_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var baseUrl: String
        get() = sp.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString("baseUrl", v.trim().ifEmpty { DEFAULT_BASE_URL }).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var personaId: String
        get() = sp.getString("personaId", "") ?: ""
        set(v) = sp.edit().putString("personaId", v).apply()

    /** 宠物宽度（dp） */
    var sizeDp: Int
        get() = sp.getInt("sizeDp", 150)
        set(v) = sp.edit().putInt("sizeDp", v.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()

    /** 同时显示几只（1–3） */
    var petCount: Int
        get() = sp.getInt("petCount", 1)
        set(v) = sp.edit().putInt("petCount", v.coerceIn(1, 3)).apply()

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubbleEnabled", true)
        set(v) = sp.edit().putBoolean("bubbleEnabled", v).apply()

    /** 离线碎碎念间隔（分钟），0 = 关闭 */
    var whisperMinutes: Int
        get() = sp.getInt("whisperMinutes", 10)
        set(v) = sp.edit().putInt("whisperMinutes", v).apply()

    /** 是否允许用大模型生成碎碎念（默认关：每分钟一次调用很容易烧钱） */
    var aiWhisper: Boolean
        get() = sp.getBoolean("aiWhisper", false)
        set(v) = sp.edit().putBoolean("aiWhisper", v).apply()

    /** 每天最多几次模型调用，防止意外跑飞 */
    var dailyLimit: Int
        get() = sp.getInt("dailyLimit", 80)
        set(v) = sp.edit().putInt("dailyLimit", v).apply()

    var keepAlive: Boolean
        get() = sp.getBoolean("keepAlive", true)
        set(v) = sp.edit().putBoolean("keepAlive", v).apply()

    var hideFromRecents: Boolean
        get() = sp.getBoolean("hideFromRecents", true)
        set(v) = sp.edit().putBoolean("hideFromRecents", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", false)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    /** 宠物是否在运行（用于开机恢复 / 进程重建） */
    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    // ── 每日调用配额 ──────────────────────────────────────────────
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    fun usedToday(): Int {
        if (sp.getString("quotaDate", "") != today()) return 0
        return sp.getInt("quotaCount", 0)
    }

    fun consumeQuota(): Boolean {
        val d = today()
        if (sp.getString("quotaDate", "") != d) {
            sp.edit().putString("quotaDate", d).putInt("quotaCount", 0).apply()
        }
        val used = sp.getInt("quotaCount", 0)
        if (used >= dailyLimit) return false
        sp.edit().putInt("quotaCount", used + 1).apply()
        return true
    }

    fun resetQuota() = sp.edit().putInt("quotaCount", 0).apply()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val MIN_SIZE_DP = 80
        const val MAX_SIZE_DP = 360
    }
}
