package com.dshpet.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。刻意用纯代码构建 UI：
 * 这个项目的界面元素几乎都是「动态生成的人格列表」，XML 反而更难维护，
 * 而且少一堆 layout 资源就少一类编译期/运行期的坑。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var overlayState: TextView
    private lateinit var startButton: Button
    private lateinit var personaBox: LinearLayout
    private lateinit var usageText: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var countBox: LinearLayout

    private val overlayReq = 10
    private val notifReq = 11

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        PetRuntime.init(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
            addView(root)
        })
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshDynamic()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    // ── 界面 ──────────────────────────────────────────────────────

    private fun buildUi() {
        root.removeAllViews()

        root.addView(label("DSH 桌宠", 26f, R.color.text, bold = true))
        root.addView(
            label("手机上的一只小宠物：会呼吸、会被你甩出去，还能按人格跟你说话。",
                13f, R.color.text_dim).apply { setPadding(0, dp(6), 0, dp(18)) }
        )

        buildStatusCard()
        buildPersonaCard()
        buildModelCard()
        buildLookCard()
        buildAboutCard()
    }

    private fun buildStatusCard() {
        val card = newCard("状态", "宠物住在桌面上，需要「显示在其他应用上层」权限")
        statusText = label("", 14f, R.color.text)
        overlayState = label("", 13f, R.color.text_dim)
        startButton = primaryButton("放出来") { toggleRun() }

        card.addView(statusText)
        card.addView(overlayState, marginTop(6))
        card.addView(startButton, marginTop(12))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(ghostButton("授予悬浮窗权限") { requestOverlay() }, weight(1f))
        row.addView(ghostButton("通知权限") { requestNotif() }, weight(1f))
        card.addView(row)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row2.addView(ghostButton("电池优化白名单") { requestBattery() }, weight(1f))
        row2.addView(ghostButton(if (prefs.autoStart) "自启：开" else "自启：关") { toggleAutoStart() }, weight(1f))
        card.addView(row2)
        root.addView(card)
    }

    private fun buildPersonaCard() {
        val card = newCard("人格", "内置 8 种；点一下换人，长按可另存为自定义再改")
        personaBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(personaBox)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(ghostButton("＋ 新建人格") { editCustom(null) }, weight(1f))
        row.addView(ghostButton("改当前人格") { editCurrent() }, weight(1f))
        card.addView(row)
        root.addView(card)
    }

    private fun buildModelCard() {
        val card = newCard("大模型（可选）", "填你自己的 DeepSeek Key；不填也能用，只是不会聊天")

        val keyInput = input("sk-...", prefs.apiKey, password = true)
        val modelInput = input("deepseek-flash", prefs.model)
        val baseInput = input(Prefs.DEFAULT_BASE_URL, prefs.baseUrl)

        card.addView(label("API Key", 12f, R.color.text_dim))
        card.addView(keyInput, marginTop(4))
        card.addView(label("模型", 12f, R.color.text_dim), marginTop(10))
        card.addView(modelInput, marginTop(4))
        card.addView(label("Base URL", 12f, R.color.text_dim), marginTop(10))
        card.addView(baseInput, marginTop(4))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(ghostButton("保存") {
            prefs.apiKey = keyInput.text.toString()
            prefs.model = modelInput.text.toString()
            prefs.baseUrl = baseInput.text.toString()
            toast("已保存")
            refreshDynamic()
        }, weight(1f))
        row.addView(ghostButton("测试连接") {
            prefs.apiKey = keyInput.text.toString()
            prefs.model = modelInput.text.toString()
            prefs.baseUrl = baseInput.text.toString()
            testConnection()
        }, weight(1f))
        card.addView(row)

        usageText = label("", 12f, R.color.text_dim).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(usageText)
        card.addView(
            label("提示：人格的 systemPrompt 是固定前缀，能吃到缓存命中（半价）；上下文只带 4 轮。",
                12f, R.color.text_dim).apply { setPadding(0, dp(6), 0, 0) }
        )
        root.addView(card)
    }

    private fun buildLookCard() {
        val card = newCard("外观与行为", null)

        sizeLabel = label("", 13f, R.color.text)
        card.addView(sizeLabel)
        val seek = SeekBar(this).apply {
            max = Prefs.MAX_SIZE_DP - Prefs.MIN_SIZE_DP
            progress = prefs.sizeDp - Prefs.MIN_SIZE_DP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.sizeDp = p + Prefs.MIN_SIZE_DP
                    sizeLabel.text = "大小：${prefs.sizeDp} dp（松开手指生效）"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    PetRuntime.resizeAll()
                }
            })
        }
        card.addView(seek, marginTop(4))

        card.addView(label("数量", 12f, R.color.text_dim), marginTop(12))
        countBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(countBox)

        card.addView(switchRow("台词气泡", prefs.bubbleEnabled) {
            prefs.bubbleEnabled = it
            cardRefresh()
        }, marginTop(12))

        card.addView(switchRow("AI 碎碎念（耗 token，默认关）", prefs.aiWhisper) {
            prefs.aiWhisper = it
            PetRuntime.restartWhisperTimer()
            cardRefresh()
        }, marginTop(4))

        card.addView(label("碎碎念间隔", 12f, R.color.text_dim), marginTop(12))
        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        listOf(0 to "关", 5 to "5 分", 10 to "10 分", 20 to "20 分").forEach { (min, name) ->
            intervalRow.addView(ghostButton(name) {
                prefs.whisperMinutes = min
                PetRuntime.restartWhisperTimer()
                cardRefresh()
            }, weight(1f))
        }
        card.addView(intervalRow)
        root.addView(card)
    }

    private fun buildAboutCard() {
        val card = newCard("关于", null)
        card.addView(
            label(
                "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n" +
                    "代码 MIT；动画素材版权归上游 PC2005-cloud/dsh-pet（禁止商用）。\n" +
                    "本工程是社区移植，与 DeepSeek 官方无关。",
                12f, R.color.text_dim
            )
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(ghostButton("看日志") { showLog() }, weight(1f))
        row.addView(ghostButton("重置今日用量") {
            prefs.resetQuota()
            refreshDynamic()
            toast("已重置")
        }, weight(1f))
        card.addView(row)
        root.addView(card)
    }

    // ── 动态刷新 ──────────────────────────────────────────────────

    private fun refreshDynamic() {
        val running = PetRuntime.isRunning
        statusText.text = if (running) "● 正在桌面上（${PetRuntime.attachCount()} 只）" else "○ 已收起"
        statusText.setTextColor(color(if (running) R.color.ok else R.color.text_dim))
        startButton.text = if (running) "收起来" else "放出来"

        val can = Settings.canDrawOverlays(this)
        overlayState.text = if (can) "悬浮窗权限：已授予" else "悬浮窗权限：未授予（必须先开）"
        overlayState.setTextColor(color(if (can) R.color.ok else R.color.warn))

        sizeLabel.text = "大小：${prefs.sizeDp} dp"
        usageText.text = "今日已用 ${prefs.usedToday()} / ${prefs.dailyLimit} 次 · " +
            "人格记忆 ${PetRuntime.memory().size(PetRuntime.persona.id)} 条"

        renderPersonas()
        renderCount()
    }

    private fun renderPersonas() {
        personaBox.removeAllViews()
        val current = PetRuntime.persona
        val all = PetRuntime.personas().all()
        all.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(
                    if (p.id == current.id) R.color.surface_alt else R.color.surface,
                    if (p.id == current.id) R.color.primary else R.color.surface_alt,
                    dp(12)
                )
                setOnClickListener {
                    PetRuntime.applyPersona(p.id)
                    refreshDynamic()
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("${p.emoji} ${p.name}")
                        .setItems(arrayOf("另存为自定义（可改）", "设为当前")) { _, which ->
                            if (which == 0) {
                                val copy = PetRuntime.personas().duplicateAsCustom(p)
                                editCustom(copy)
                            } else {
                                PetRuntime.applyPersona(p.id)
                                refreshDynamic()
                            }
                        }
                        .show()
                    true
                }
            }
            val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            title.addView(label("${p.emoji} ${p.name}", 15f, R.color.text, bold = true))
            if (p.title.isNotEmpty()) {
                title.addView(
                    label("  ${p.title}", 12f, R.color.text_dim).apply {
                        setPadding(dp(6), 0, 0, 0)
                    }
                )
            }
            if (!p.builtin) {
                title.addView(label("  自定义", 11f, R.color.accent).apply { setPadding(dp(6), 0, 0, 0) })
            }
            row.addView(title)
            if (p.desc.isNotEmpty()) {
                row.addView(label(p.desc, 12f, R.color.text_dim).apply { setPadding(0, dp(4), 0, 0) })
            }
            personaBox.addView(row, marginTop(6))
        }
    }

    private fun renderCount() {
        countBox.removeAllViews()
        for (i in 1..3) {
            countBox.addView(ghostButton("$i 只") {
                prefs.petCount = i
                PetRuntime.syncCount(i)
                refreshDynamic()
            }.apply {
                if (prefs.petCount == i) setTextColor(color(R.color.primary))
            }, weight(1f))
        }
    }

    private fun cardRefresh() {
        // 开关/间隔变化后只刷新对应片段；简单起见整体重建（post 一帧，避免在控件回调里销毁自己）
        root.post {
            buildUi()
            refreshDynamic()
        }
    }

    // ── 行为 ──────────────────────────────────────────────────────

    private fun toggleRun() {
        if (PetRuntime.isRunning) {
            PetService.stop(this)
            PetRuntime.stop()
            refreshDynamic()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifReq)
        }
        PetService.start(this)
        root.postDelayed({ refreshDynamic() }, 600)
    }

    private fun requestOverlay() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }.onFailure { toast("这台机器没有这个设置页，请手动到「设置 → 应用 → 特殊权限」开启") }
    }

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifReq)
        } else {
            toast("这个系统版本不需要单独授权")
        }
    }

    private fun requestBattery() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } else {
                toast("已经在白名单里了")
            }
        }.onFailure { toast("请手动到「电池 → 应用耗电管理」里放开") }
    }

    private fun toggleAutoStart() {
        prefs.autoStart = !prefs.autoStart
        toast(if (prefs.autoStart) "开机自启：开" else "开机自启：关")
        buildUi()
        refreshDynamic()
    }

    private fun testConnection() {
        toast("正在测试…")
        ui.launch {
            when (val reply = PetRuntime.chat().testConnection()) {
                is ChatEngine.Reply.Ok -> toast("通了：${reply.text.take(20)}")
                is ChatEngine.Reply.Err -> toast(humanError(reply))
            }
        }
    }

    private fun humanError(r: ChatEngine.Reply.Err): String = when (r.kind) {
        ChatEngine.Kind.NO_KEY -> "还没填 API Key"
        ChatEngine.Kind.AUTH -> "Key 无效（401）"
        ChatEngine.Kind.BALANCE -> "余额不足（402）"
        ChatEngine.Kind.RATE -> "太频繁（429）"
        ChatEngine.Kind.NETWORK -> "网络不通：${r.detail}"
        ChatEngine.Kind.SERVER -> "服务端错误：${r.detail}"
        ChatEngine.Kind.QUOTA -> "今日次数用完了"
        ChatEngine.Kind.EMPTY -> "模型返回空内容"
    }

    private fun editCurrent() {
        val p = PetRuntime.persona
        if (p.builtin) {
            AlertDialog.Builder(this)
                .setTitle("内置人格不能直接改")
                .setMessage("先「另存为自定义」再改，避免把内置模板改乱。")
                .setPositiveButton("另存一份") { _, _ ->
                    editCustom(PetRuntime.personas().duplicateAsCustom(p))
                }
                .setNegativeButton("算了", null)
                .show()
        } else {
            editCustom(p)
        }
    }

    private fun editCustom(base: Persona?) {
        val seed = base ?: Persona.template()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val nameInput = input("名字，例如：傲娇女友", seed.name)
        val emojiInput = input("一个 emoji", seed.emoji)
        val promptInput = input("系统提示词", seed.systemPrompt, multiline = true)
        val linesInput = input(
            "离线台词，一行一句（不填也能用）",
            seed.whisper.joinToString("\n"),
            multiline = true
        )
        box.addView(nameInput)
        box.addView(emojiInput, marginTop(8))
        box.addView(label("系统提示词", 12f, R.color.text_dim), marginTop(8))
        box.addView(promptInput, marginTop(4))
        box.addView(label("离线台词", 12f, R.color.text_dim), marginTop(8))
        box.addView(linesInput, marginTop(4))

        AlertDialog.Builder(this)
            .setTitle(if (base == null) "新建人格" else "编辑人格")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) {
                    toast("系统提示词不能为空")
                    return@setPositiveButton
                }
                val p = seed.copy(
                    name = nameInput.text.toString().trim().ifEmpty { "未命名" },
                    emoji = emojiInput.text.toString().trim().ifEmpty { "✨" },
                    systemPrompt = prompt,
                    whisper = linesInput.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                )
                PetRuntime.personas().saveCustom(p)
                PetRuntime.applyPersona(p.id)
                refreshDynamic()
                toast("已保存并切换")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLog() {
        val crash = runCatching {
            val f = java.io.File(filesDir, "last_crash.txt")
            if (f.isFile) f.readText() else ""
        }.getOrDefault("")
        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setMessage(Diag.report(this) + if (crash.isNotEmpty()) "\n\n─ 上次崩溃 ─\n" + crash.take(1500) else "")
            .setPositiveButton("知道了", null)
            .show()
    }

    // ── 控件工厂 ──────────────────────────────────────────────────

    private fun newCard(title: String, subtitle: String?): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(R.color.surface, R.color.surface_alt, dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        box.addView(label(title, 16f, R.color.text, bold = true))
        if (!subtitle.isNullOrEmpty()) {
            box.addView(label(subtitle, 12f, R.color.text_dim).apply { setPadding(0, dp(4), 0, dp(10)) })
        } else {
            box.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        }
        return box
    }

    private fun label(text: String, size: Float, colorRes: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color(colorRes))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun primaryButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setTextColor(color(R.color.bg))
            background = rounded(R.color.primary, R.color.primary, dp(14))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun ghostButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(color(R.color.text))
            background = rounded(R.color.surface_alt, R.color.surface_alt, dp(12))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minHeight = 0
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener { onClick() }
        }

    private fun switchRow(text: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(text, 13f, R.color.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        val sw = Switch(this).apply { isChecked = initial }
        // 先设定初始值再挂监听：否则初始化那一下就会触发回调 → 重建界面 → 递归
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(sw)
        return row
    }

    private fun input(hint: String, value: String, password: Boolean = false, multiline: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 13f
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(R.color.bg, R.color.surface_alt, dp(10))
            inputType = when {
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun rounded(fillRes: Int, strokeRes: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color(fillRes))
            setStroke(1, color(strokeRes))
            cornerRadius = radius.toFloat()
        }

    private fun marginTop(v: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(v) }

    private fun weight(w: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = w }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
