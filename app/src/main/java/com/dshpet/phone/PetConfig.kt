package com.dshpet.phone

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 素材清单。字段与上游 dsh-pet 的动画池思路一致（待机 / 点击 / 拖拽 / 落地 / 移动），
 * 但手机上只需要「状态 → 一组 WebP 文件」这一层，不需要 web/desktop 双端与余额事件。
 */
data class PetConfig(
    val pack: String = "default",
    val fps: Int = 15,
    val canvasW: Int = 480,
    val canvasH: Int = 270,
    val baseWidthDp: Int = 150,
    val states: Map<String, StateSpec> = emptyMap(),
    val durations: Map<String, Long> = emptyMap(),
    val categories: Map<String, List<String>> = emptyMap()
) {
    data class StateSpec(val files: List<String>, val loop: Boolean)

    /** 没有任何动画文件时，App 使用内置矢量占位宠物（保证克隆即可运行） */
    val hasAnimations: Boolean get() = states.values.any { it.files.isNotEmpty() }

    fun filesOf(state: String): List<String> = states[state]?.files ?: emptyList()

    fun isLoop(state: String): Boolean = states[state]?.loop ?: true

    fun durationOf(file: String): Long = durations[file] ?: 2000L

    companion object {
        const val FILE_NAME = "pet-config.json"

        fun load(ctx: Context): PetConfig {
            // 1) 用户放到 /Android/data/<pkg>/files/pet-config.json 的优先
            val userFile = File(ctx.filesDir, FILE_NAME)
            if (userFile.isFile) {
                runCatching { return parse(JSONObject(userFile.readText())) }
                    .onFailure { Diag.w("PetConfig", "用户配置解析失败，回落内置：${it.message}") }
            }
            // 2) 内置 assets
            return runCatching {
                val text = ctx.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
                parse(JSONObject(text))
            }.getOrElse {
                Diag.w("PetConfig", "内置配置读取失败：${it.message}")
                PetConfig()
            }
        }

        fun parse(o: JSONObject): PetConfig {
            val canvas = o.optJSONObject("canvas")
            val statesObj = o.optJSONObject("states")
            val states = LinkedHashMap<String, StateSpec>()
            if (statesObj != null) {
                for (key in statesObj.keys()) {
                    val s = statesObj.optJSONObject(key) ?: continue
                    val arr = s.optJSONArray("files")
                    val files = ArrayList<String>()
                    if (arr != null) for (i in 0 until arr.length()) files.add(arr.optString(i))
                    states[key] = StateSpec(files, s.optBoolean("loop", true))
                }
            }
            val durations = LinkedHashMap<String, Long>()
            o.optJSONObject("durations")?.let { d ->
                for (key in d.keys()) durations[key] = d.optLong(key, 2000L)
            }
            val categories = LinkedHashMap<String, List<String>>()
            o.optJSONObject("categories")?.let { c ->
                for (key in c.keys()) {
                    val arr = c.optJSONArray(key) ?: continue
                    val list = ArrayList<String>()
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    categories[key] = list
                }
            }
            return PetConfig(
                pack = o.optString("pack", "default"),
                fps = o.optInt("fps", 15),
                canvasW = canvas?.optInt("w", 480) ?: 480,
                canvasH = canvas?.optInt("h", 270) ?: 270,
                baseWidthDp = o.optInt("baseWidthDp", 150),
                states = states,
                durations = durations,
                categories = categories
            )
        }
    }
}

/** 状态名常量，避免各处手写字符串 */
object PetState {
    const val IDLE = "idle"
    const val TAP = "tap"
    const val DRAG = "drag"
    const val FLING = "fling"
    const val WALK = "walk"
    const val SLEEP = "sleep"

    val all = listOf(IDLE, TAP, DRAG, FLING, WALK, SLEEP)
}
