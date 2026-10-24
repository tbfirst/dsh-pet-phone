package com.dshpet.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 人格库：内置（assets/personas.json）+ 用户自定义（filesDir/personas.custom.json）。
 * 自定义人格与内置人格走同一套字段，所以「内置一个 → 复制改两句 → 存成自己的」是天然支持的。
 */
class PersonaLibrary(private val ctx: Context) {

    private val builtinList = ArrayList<Persona>()
    private val customList = ArrayList<Persona>()
    private var defaultId: String = ""

    private val customFile: File get() = File(ctx.filesDir, "personas.custom.json")

    fun load(): PersonaLibrary {
        builtinList.clear()
        customList.clear()
        runCatching {
            val text = ctx.assets.open("personas.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            defaultId = root.optString("default")
            val arr = root.optJSONArray("personas") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Persona.fromJson(o, builtin = true)?.let { builtinList.add(it) }
            }
            Diag.i("Persona", "载入内置人格 ${builtinList.size} 个")
        }.onFailure { Diag.e("Persona", "内置人格解析失败", it) }

        runCatching {
            if (customFile.isFile) {
                val root = JSONObject(customFile.readText())
                val arr = root.optJSONArray("personas") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    Persona.fromJson(o, builtin = false)?.let { customList.add(it) }
                }
            }
        }.onFailure { Diag.w("Persona", "自定义人格读取失败：${it.message}") }

        if (builtinList.isEmpty()) {
            // 极端兜底：assets 损坏也不至于没有可用人格
            builtinList.add(Persona.template())
        }
        if (defaultId.isEmpty()) defaultId = builtinList.first().id
        return this
    }

    fun all(): List<Persona> = builtinList + customList

    fun builtins(): List<Persona> = builtinList.toList()

    fun customs(): List<Persona> = customList.toList()

    fun find(id: String): Persona? = all().firstOrNull { it.id == id }

    /** 当前使用的人格；配置缺失时回落到默认人格 */
    fun current(prefs: Prefs): Persona {
        val id = prefs.personaId
        return find(id) ?: find(defaultId) ?: all().first()
    }

    fun defaultPersona(): Persona = find(defaultId) ?: all().first()

    fun saveCustom(p: Persona): Boolean {
        val idx = customList.indexOfFirst { it.id == p.id }
        if (idx >= 0) customList[idx] = p else customList.add(p)
        return persistCustom()
    }

    fun deleteCustom(id: String): Boolean {
        val p = customList.firstOrNull { it.id == id } ?: return false
        if (p.builtin) return false
        customList.removeAll { it.id == id }
        return persistCustom()
    }

    /** 把内置人格「另存为」自定义，方便用户改两句 */
    fun duplicateAsCustom(src: Persona): Persona {
        val copy = src.copy(
            id = "custom_" + System.currentTimeMillis(),
            name = src.name + "·改",
            title = if (src.title.isEmpty()) "自定义" else src.title,
            builtin = false
        )
        saveCustom(copy)
        return copy
    }

    private fun persistCustom(): Boolean = runCatching {
        val arr = JSONArray()
        customList.forEach { arr.put(it.toJson()) }
        val root = JSONObject().apply {
            put("schema", 1)
            put("personas", arr)
        }
        customFile.writeText(root.toString(2))
        true
    }.getOrElse {
        Diag.e("Persona", "自定义人格保存失败", it)
        false
    }
}
