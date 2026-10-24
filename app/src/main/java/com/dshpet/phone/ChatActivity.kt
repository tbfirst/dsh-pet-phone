package com.dshpet.phone

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 聊天页。极简实现：没有 RecyclerView，消息就是一串 TextView 塞进 LinearLayout。
 * 桌宠对话一天几十条，这个量级根本不需要复用机制。
 */
class ChatActivity : AppCompatActivity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var prefs: Prefs
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleText: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        PetRuntime.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(12), dp(8))
        }
        bar.addView(textButton("←") { finish() })
        titleText = TextView(this).apply {
            textSize = 16f
            setTextColor(color(R.color.text))
            setPadding(dp(6), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            setOnClickListener { switchPersona() }
        }
        bar.addView(titleText)
        bar.addView(textButton("换人") { switchPersona() })
        bar.addView(textButton("清空") { clearHistory() })
        root.addView(bar)

        // 消息区
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(10))
        }
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            addView(listBox)
        }
        root.addView(scroll)

        // 输入区
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(14))
        }
        input = EditText(this).apply {
            hint = "说点什么…"
            textSize = 14f
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(R.color.surface, R.color.surface_alt, dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                marginEnd = dp(8)
            }
        }
        sendBtn = Button(this).apply {
            text = "发送"
            isAllCaps = false
            textSize = 14f
            setTextColor(color(R.color.bg))
            background = rounded(R.color.primary, R.color.primary, dp(12))
            setOnClickListener { send() }
        }
        inputBar.addView(input)
        inputBar.addView(sendBtn)
        root.addView(inputBar)

        setContentView(root)

        renderHistory()
        titleText.text = "${PetRuntime.persona.emoji} ${PetRuntime.persona.name}"
        if (prefs.apiKey.isBlank()) {
            addBubble("还没填 API Key，我只能用离线台词陪你。去设置页「大模型」里填一下就能聊了。", false)
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        titleText.text = "${PetRuntime.persona.emoji} ${PetRuntime.persona.name}"
    }

    // ── 对话 ──────────────────────────────────────────────────────

    private fun send() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (prefs.apiKey.isBlank()) {
            addBubble("先填 Key：设置页 → 大模型 → API Key。", false)
            return
        }
        input.setText("")
        addBubble(text, true)

        busy = true
        sendBtn.isEnabled = false
        sendBtn.alpha = 0.5f
        val placeholder = addBubble("…", false)

        ui.launch {
            val reply = PetRuntime.chat().chat(PetRuntime.persona, text)
            placeholder.text = when (reply) {
                is ChatEngine.Reply.Ok -> reply.text
                is ChatEngine.Reply.Err -> fallbackText(reply)
            }
            busy = false
            sendBtn.isEnabled = true
            sendBtn.alpha = 1f
            scrollDown()
        }
    }

    private fun fallbackText(r: ChatEngine.Reply.Err): String {
        val persona = PetRuntime.persona
        val canned = persona.fallback.randomOrNull()
        val reason = when (r.kind) {
            ChatEngine.Kind.NO_KEY -> "没配置 Key"
            ChatEngine.Kind.AUTH -> "Key 无效（401）"
            ChatEngine.Kind.BALANCE -> "余额不足（402）"
            ChatEngine.Kind.RATE -> "请求太频繁（429）"
            ChatEngine.Kind.QUOTA -> "今天的次数用完了（可在设置页重置）"
            ChatEngine.Kind.NETWORK -> "网络不通"
            ChatEngine.Kind.SERVER -> "服务端错误"
            ChatEngine.Kind.EMPTY -> "模型返回空内容"
        }
        return listOfNotNull(canned, "（$reason）").joinToString("\n")
    }

    private fun renderHistory() {
        listBox.removeAllViews()
        val persona = PetRuntime.persona
        val hist = PetRuntime.memory().context(persona.id, 30)
        if (hist.isEmpty()) {
            addBubble(persona.greeting.ifEmpty { "我在。" }, false)
            return
        }
        hist.forEach { addBubble(it.content, it.role == "user") }
        scrollDown()
    }

    private fun addBubble(text: String, mine: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (mine) color(R.color.text) else color(R.color.text))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(
                if (mine) R.color.primary_dim else R.color.surface,
                if (mine) R.color.primary_dim else R.color.surface_alt,
                dp(12)
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(tv)
        listBox.addView(row)
        scrollDown()
        return tv
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空记忆")
            .setMessage("只清空「${PetRuntime.persona.name}」这个人格的对话记录，其他人格不受影响。")
            .setPositiveButton("清空") { _, _ ->
                PetRuntime.memory().clear(PetRuntime.persona.id)
                listBox.removeAllViews()
                addBubble("好，我忘了。", false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun switchPersona() {
        val all = PetRuntime.personas().all()
        val names = all.map { "${it.emoji} ${it.name}  ${it.title}" }.toTypedArray()
        val current = all.indexOfFirst { it.id == PetRuntime.persona.id }
        AlertDialog.Builder(this)
            .setTitle("换个人格")
            .setSingleChoiceItems(names, current) { dialog, which ->
                PetRuntime.applyPersona(all[which].id)
                titleText.text = "${all[which].emoji} ${all[which].name}"
                renderHistory()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 控件 ──────────────────────────────────────────────────────

    private fun textButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minHeight = 0
            setTextColor(color(R.color.primary))
            background = null
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    private fun rounded(fillRes: Int, strokeRes: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color(fillRes))
            setStroke(1, color(strokeRes))
            cornerRadius = radius.toFloat()
        }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
