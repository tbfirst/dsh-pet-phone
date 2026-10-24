package com.dshpet.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 人格 = 系统提示词 + 离线口头禅 + 兜底话。
 *
 * 这是本项目相对上游 dsh-pet / 安卓移植版最大的增量：桌宠不再是「只会动」，
 * 它有一套可切换、可自定义的"人格定位"，并且**离线台词优先**，不点对话就不花一分钱。
 */
data class Persona(
    val id: String,
    val name: String,
    val emoji: String = "🐳",
    val title: String = "",
    val tags: List<String> = emptyList(),
    val desc: String = "",
    val greeting: String = "",
    val temperature: Float = 1.1f,
    val maxChars: Int = 80,
    val systemPrompt: String,
    /** 只在开启「AI 碎碎念」时使用；离线碎碎念走 whisper */
    val whisperPrompt: String = "",
    val whisper: List<String> = emptyList(),
    val tap: List<String> = emptyList(),
    val fallback: List<String> = emptyList(),
    val builtin: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("emoji", emoji)
        put("title", title)
        put("tags", JSONArray(tags))
        put("desc", desc)
        put("greeting", greeting)
        put("temperature", temperature.toDouble())
        put("maxChars", maxChars)
        put("systemPrompt", systemPrompt)
        put("whisperPrompt", whisperPrompt)
        put("whisper", JSONArray(whisper))
        put("tap", JSONArray(tap))
        put("fallback", JSONArray(fallback))
    }

    companion object {
        private fun JSONObject.strArray(key: String): List<String> {
            val arr = optJSONArray(key) ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }

        fun fromJson(o: JSONObject, builtin: Boolean): Persona? {
            val id = o.optString("id").ifEmpty { return null }
            val prompt = o.optString("systemPrompt").ifEmpty { return null }
            return Persona(
                id = id,
                name = o.optString("name", id),
                emoji = o.optString("emoji", "🐳"),
                title = o.optString("title"),
                tags = o.strArray("tags"),
                desc = o.optString("desc"),
                greeting = o.optString("greeting"),
                temperature = o.optDouble("temperature", 1.1).toFloat(),
                maxChars = o.optInt("maxChars", 80),
                systemPrompt = prompt,
                whisperPrompt = o.optString("whisperPrompt"),
                whisper = o.strArray("whisper"),
                tap = o.strArray("tap"),
                fallback = o.strArray("fallback"),
                builtin = builtin
            )
        }

        /** 自定义人格的骨架，用户填两栏就能用 */
        fun template(): Persona = Persona(
            id = "custom_" + System.currentTimeMillis(),
            name = "新人格",
            emoji = "✨",
            title = "自定义",
            desc = "",
            greeting = "你好呀。",
            systemPrompt = DEFAULT_TEMPLATE,
            whisperPrompt = "用这个角色的口吻说一句不超过 25 字的碎碎念，只输出这句话。",
            fallback = listOf("（还没接入大模型）"),
            builtin = false
        )

        val DEFAULT_TEMPLATE = """
            【身份】你是___，住在用户手机桌面上的小宠物，用户随时会戳你、拖你。
            【性格】用 3 个关键词 + 1 个反差，例如"冷淡但会记得你说过的话"。
            【说话风格】句子多长、有什么口癖、用不用语气词；不要用 Markdown。
            【记忆】怎么称呼用户、记住什么、记不清就直接问。
            【边界】不聊政治、色情、违法内容；不知道就说不知道，不要编。
            【输出格式】只输出台词，不超过 80 字，不要旁白、不要分点。
        """.trimIndent()
    }
}
