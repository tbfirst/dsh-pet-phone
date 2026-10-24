package com.dshpet.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话记忆：按人格分文件存，互不串味。
 * 上限 30 条（约 15 轮），发请求时只带最近 8 条——上下文越长越贵，这条要卡死。
 */
class MemoryStore(private val ctx: Context) {

    data class Msg(val role: String, val content: String, val ts: Long)

    private val cache = HashMap<String, MutableList<Msg>>()

    private fun fileOf(personaId: String): File {
        val safe = personaId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(ctx.filesDir, "memory_$safe.json")
    }

    fun load(personaId: String): MutableList<Msg> {
        cache[personaId]?.let { return it }
        val list = ArrayList<Msg>()
        runCatching {
            val f = fileOf(personaId)
            if (f.isFile) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        Msg(
                            o.optString("role", "user"),
                            o.optString("content"),
                            o.optLong("ts", 0L)
                        )
                    )
                }
            }
        }.onFailure { Diag.w("Memory", "读取失败 $personaId：${it.message}") }
        cache[personaId] = list
        return list
    }

    fun append(personaId: String, role: String, content: String) {
        val list = load(personaId)
        list.add(Msg(role, content, System.currentTimeMillis()))
        while (list.size > MAX_KEEP) list.removeAt(0)
        persist(personaId, list)
    }

    fun context(personaId: String, turns: Int = 8): List<Msg> {
        val list = load(personaId)
        return list.takeLast(turns * 2)
    }

    fun clear(personaId: String) {
        cache[personaId] = ArrayList()
        runCatching { fileOf(personaId).delete() }
    }

    fun size(personaId: String): Int = load(personaId).size

    private fun persist(personaId: String, list: List<Msg>) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("role", it.role)
                        .put("content", it.content)
                        .put("ts", it.ts)
                )
            }
            fileOf(personaId).writeText(arr.toString())
        }.onFailure { Diag.w("Memory", "写入失败 $personaId：${it.message}") }
    }

    companion object {
        const val MAX_KEEP = 30
    }
}
