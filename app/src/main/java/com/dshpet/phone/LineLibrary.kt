package com.dshpet.phone

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * 离线台词库：情境 → 一组话。
 * 这是「零成本碎碎念」的基础——不配 API Key 也能有情绪，配了也只是偶尔用模型换点新鲜感。
 */
class LineLibrary private constructor(private val map: Map<String, List<String>>) {

    fun pick(situation: String): String? = map[situation]?.randomOrNull()

    fun list(situation: String): List<String> = map[situation] ?: emptyList()

    /** 按当前时间挑情境 */
    fun timeSituation(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "late_night"
            h < 11 -> "morning"
            h < 14 -> "noon"
            h < 19 -> "evening"
            h < 23 -> "evening"
            else -> "late_night"
        }
    }

    /** 碎碎念时优先用人格自己的台词，没有就按时间取通用池 */
    fun forWhisper(persona: Persona): String {
        if (persona.whisper.isNotEmpty() && Math.random() < 0.75) {
            return persona.whisper.random()
        }
        return pick(timeSituation()) ?: persona.whisper.randomOrNull() ?: "……"
    }

    fun forTap(persona: Persona): String =
        persona.tap.randomOrNull() ?: pick("tap") ?: "嗯？"

    companion object {
        fun load(ctx: Context): LineLibrary = try {
            val text = ctx.assets.open("lines.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val sit = root.optJSONObject("situations")
            val map = LinkedHashMap<String, List<String>>()
            if (sit != null) {
                for (key in sit.keys()) {
                    val arr = sit.optJSONArray(key) ?: continue
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotEmpty()) list.add(s)
                    }
                    if (list.isNotEmpty()) map[key] = list
                }
            }
            LineLibrary(map)
        } catch (t: Throwable) {
            Diag.w("Lines", "台词库读取失败：${t.message}")
            LineLibrary(emptyMap())
        }
    }
}
