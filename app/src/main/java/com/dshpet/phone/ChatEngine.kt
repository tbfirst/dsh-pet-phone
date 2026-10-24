package com.dshpet.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 最小的 DeepSeek 对话客户端（OpenAI 兼容协议，非流式）。
 *
 * 刻意不引入 OkHttp / Retrofit：
 *  1) 少一个依赖就少一处 CI 失败点；
 *  2) 手机端桌宠的对话都是短句，非流式的延迟可以接受；
 *  3) 所有成本控制都在这一层，好审。
 *
 * 成本控制三件事：
 *  - 只带最近 8 条上下文（CONTEXT_TURNS）
 *  - 人格 systemPrompt 固定不变，天然吃「缓存命中」的半价
 *  - 每日调用次数上限（Prefs.dailyLimit），超了直接拒绝，不发请求
 */
class ChatEngine(private val prefs: Prefs, private val memory: MemoryStore) {

    sealed class Reply {
        data class Ok(val text: String) : Reply()
        data class Err(val kind: Kind, val detail: String) : Reply()
    }

    enum class Kind { NO_KEY, QUOTA, NETWORK, AUTH, BALANCE, RATE, SERVER, EMPTY }

    suspend fun chat(persona: Persona, userText: String): Reply = withContext(Dispatchers.IO) {
        if (prefs.apiKey.isBlank()) return@withContext Reply.Err(Kind.NO_KEY, "未配置 API Key")
        val text = userText.trim()
        if (text.isEmpty()) return@withContext Reply.Err(Kind.EMPTY, "空消息")
        if (!prefs.consumeQuota()) return@withContext Reply.Err(Kind.QUOTA, "已达今日上限")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", persona.systemPrompt))
        memory.context(persona.id, CONTEXT_TURNS).forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", text.take(MAX_USER_CHARS)))

        val body = JSONObject().apply {
            put("model", prefs.model)
            put("messages", messages)
            put("temperature", persona.temperature.toDouble())
            put("max_tokens", maxOf(120, persona.maxChars * 3))
            put("stream", false)
        }

        val reply = request(body)
        if (reply is Reply.Ok) {
            memory.append(persona.id, "user", text)
            memory.append(persona.id, "assistant", reply.text)
        }
        reply
    }

    /** 让模型即兴说一句碎碎念（仅在用户手动开启「AI 碎碎念」时调用） */
    suspend fun whisper(persona: Persona): Reply = withContext(Dispatchers.IO) {
        if (prefs.apiKey.isBlank()) return@withContext Reply.Err(Kind.NO_KEY, "未配置 API Key")
        if (!prefs.consumeQuota()) return@withContext Reply.Err(Kind.QUOTA, "已达今日上限")

        val prompt = persona.whisperPrompt.ifEmpty {
            "用这个角色的口吻，对用户说一句不超过 25 字的碎碎念，只输出这句话。"
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", persona.systemPrompt))
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        request(
            JSONObject().apply {
                put("model", prefs.model)
                put("messages", messages)
                put("temperature", 1.2)
                put("max_tokens", 80)
                put("stream", false)
            }
        )
    }

    suspend fun testConnection(): Reply = withContext(Dispatchers.IO) {
        if (prefs.apiKey.isBlank()) return@withContext Reply.Err(Kind.NO_KEY, "未配置 API Key")
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "user").put("content", "只回复两个字：通了"))
        request(
            JSONObject().apply {
                put("model", prefs.model)
                put("messages", messages)
                put("max_tokens", 16)
                put("stream", false)
            }
        )
    }

    // ── 网络层 ────────────────────────────────────────────────────

    private fun endpoint(): String {
        val base = prefs.baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun request(body: JSONObject): Reply {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(endpoint())
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${prefs.apiKey}")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                Diag.w("Chat", "HTTP $code · ${payload.take(300)}")
                return when (code) {
                    401 -> Reply.Err(Kind.AUTH, "Key 无效或已失效")
                    402 -> Reply.Err(Kind.BALANCE, "账户余额不足")
                    429 -> Reply.Err(Kind.RATE, "请求过于频繁")
                    in 500..599 -> Reply.Err(Kind.SERVER, "服务端错误 HTTP $code")
                    else -> Reply.Err(Kind.SERVER, "HTTP $code")
                }
            }

            val content = JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

            return if (content.isEmpty()) Reply.Err(Kind.EMPTY, "模型返回空内容") else Reply.Ok(content)
        } catch (t: Throwable) {
            Diag.w("Chat", "请求失败：${t.javaClass.simpleName} ${t.message}")
            return Reply.Err(Kind.NETWORK, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    companion object {
        const val CONTEXT_TURNS = 4      // 4 轮 = 8 条消息
        const val MAX_USER_CHARS = 1200  // 单条输入上限，防止误粘贴长文烧钱
    }
}
