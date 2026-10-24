package com.dshpet.phone

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 桌宠运行时：进程内单例，持有所有宠物窗口、气泡、长按菜单与一个全局帧循环。
 *
 * 「一个帧循环驱动所有宠物」而不是每只宠物各起一个 Choreographer：
 * 多开时开销不叠加，这是相比移植版的一个实打实的优化。
 */
object PetRuntime {

    private lateinit var appCtx: Context
    private lateinit var config: PetConfig
    private lateinit var pack: AnimationPack
    private lateinit var prefs: Prefs
    private lateinit var personaLib: PersonaLibrary
    private lateinit var lineLib: LineLibrary
    private lateinit var memStore: MemoryStore
    private lateinit var engine: ChatEngine

    private val pets = ArrayList<PetWindow>()
    private val bubbles = HashMap<PetWindow, BubbleWindow>()
    private var menu: PetMenuWindow? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var whisperJob: Job? = null

    private var lastFrameNs = 0L
    private var frameTick = 0
    private var frameScheduled = false

    var isRunning: Boolean = false
        private set

    private var _persona: Persona? = null
    val persona: Persona
        get() = _persona ?: personaLib.defaultPersona()

    // ── 初始化 ────────────────────────────────────────────────────

    fun init(ctx: Context) {
        if (::appCtx.isInitialized) return
        appCtx = ctx.applicationContext
        prefs = Prefs(appCtx)
        config = PetConfig.load(appCtx)
        pack = AnimationPack(appCtx, config)
        personaLib = PersonaLibrary(appCtx).load()
        lineLib = LineLibrary.load(appCtx)
        memStore = MemoryStore(appCtx)
        engine = ChatEngine(prefs, memStore)
        _persona = personaLib.current(prefs)
        if (prefs.personaId.isEmpty()) prefs.personaId = _persona!!.id
        Diag.i("Runtime", "init 完成，人格=${persona.name} 素材=${config.hasAnimations}")
    }

    fun prefs(): Prefs = prefs
    fun personas(): PersonaLibrary = personaLib
    fun lines(): LineLibrary = lineLib
    fun memory(): MemoryStore = memStore
    fun chat(): ChatEngine = engine
    fun pack(): AnimationPack = pack
    fun config(): PetConfig = config

    // ── 启动 / 停止 ───────────────────────────────────────────────

    fun start(count: Int = prefs.petCount) {
        if (isRunning) {
            syncCount(count)
            return
        }
        isRunning = true
        prefs.running = true
        syncCount(count)
        startFrameLoop()
        restartWhisperTimer()
        handler.postDelayed({ sayToAll(persona.greeting.ifEmpty { lineLib.pick("boot") ?: "" }) }, 900)
        Diag.i("Runtime", "启动，共 ${pets.size} 只")
    }

    fun stop() {
        isRunning = false
        prefs.running = false
        stopFrameLoop()
        whisperJob?.cancel()
        whisperJob = null
        menu?.dismiss()
        menu = null
        pets.forEach { p ->
            bubbles[p]?.hide()
            p.release()
        }
        pets.clear()
        bubbles.clear()
        Diag.i("Runtime", "已全部收起")
    }

    /** 数量变化时增删窗口，不重启整体 */
    fun syncCount(target: Int) {
        if (!isRunning) return
        val want = target.coerceIn(1, 3)
        while (pets.size < want) {
            val w = buildPet(pets.size)
            pets.add(w)
            bubbles[w] = BubbleWindow(appCtx)
            w.show()
        }
        while (pets.size > want) {
            val w = pets.removeAt(pets.size - 1)
            bubbles[w]?.hide()
            bubbles.remove(w)
            w.release()
        }
    }

    /** 改大小后重算窗口尺寸（不重建，避免动画中断） */
    fun resizeAll() {
        pets.forEach { it.resize() }
    }

    private fun buildPet(slot: Int): PetWindow = PetWindow(
        ctx = appCtx,
        slot = slot,
        config = config,
        pack = pack,
        prefs = prefs,
        callbacks = object : PetWindow.Callbacks {
            override fun onTap(pet: PetWindow) {
                say(pet, lineLib.forTap(persona))
            }

            override fun onLongPress(pet: PetWindow) {
                showMenu(pet)
            }

            override fun onFling(pet: PetWindow, speed: Float) {
                if (speed > 2200f) say(pet, lineLib.pick("fling") ?: "哇——")
            }
        }
    )

    // ── 帧循环 ────────────────────────────────────────────────────

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) {
                frameScheduled = false
                return
            }
            val dt = if (lastFrameNs == 0L) 0.016f
            else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos

            // 省电：全部待机/睡觉时把物理步进降到约 20fps（动画仍由系统解码器驱动，不受影响）
            val allCalm = pets.all { it.state == PetState.IDLE || it.state == PetState.SLEEP }
            frameTick++
            if (!allCalm || frameTick % 3 == 0) {
                for (p in pets) p.step(dt * if (allCalm) 3f else 1f)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (frameScheduled) return
        frameScheduled = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (!frameScheduled) return
        frameScheduled = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ── 说话 ──────────────────────────────────────────────────────

    fun say(pet: PetWindow, text: String) {
        if (!prefs.bubbleEnabled || text.isBlank()) return
        val bubble = bubbles[pet] ?: return
        bubble.show(text, pet.centerX(), pet.topY(), screenWidth())
    }

    fun sayToAll(text: String) {
        if (text.isBlank()) return
        val target = pets.firstOrNull() ?: return
        say(target, text)
    }

    private fun screenWidth(): Int {
        val dm = appCtx.resources.displayMetrics
        return dm.widthPixels
    }

    // ── 碎碎念 ────────────────────────────────────────────────────

    fun restartWhisperTimer() {
        whisperJob?.cancel()
        whisperJob = null
        val minutes = prefs.whisperMinutes
        if (minutes <= 0) return
        whisperJob = scope.launch {
            while (isActive) {
                delay(minutes * 60_000L)
                if (!isRunning) continue
                if (prefs.aiWhisper) aiWhisper() else sayToAll(lineLib.forWhisper(persona))
            }
        }
        Diag.i("Runtime", "碎碎念间隔 ${minutes} 分钟，AI=${prefs.aiWhisper}")
    }

    fun whisperNow() {
        if (prefs.aiWhisper) {
            scope.launch { aiWhisper() }
        } else {
            sayToAll(lineLib.forWhisper(persona))
        }
    }

    private suspend fun aiWhisper() {
        when (val r = engine.whisper(persona)) {
            is ChatEngine.Reply.Ok -> sayToAll(r.text)
            is ChatEngine.Reply.Err -> {
                // AI 失败就退回离线台词，不让用户面对沉默
                Diag.w("Runtime", "AI 碎碎念失败(${r.kind})：${r.detail}")
                sayToAll(lineLib.forWhisper(persona))
            }
        }
    }

    // ── 人格 ──────────────────────────────────────────────────────

    fun applyPersona(id: String) {
        val p = personaLib.find(id) ?: return
        prefs.personaId = p.id
        _persona = p
        sayToAll(p.greeting.ifEmpty { "换人了。" })
        Diag.i("Runtime", "切换人格 → ${p.name}")
    }

    fun cyclePersona() {
        val all = personaLib.all()
        if (all.isEmpty()) return
        val idx = all.indexOfFirst { it.id == persona.id }
        applyPersona(all[(idx + 1) % all.size].id)
    }

    fun reloadPersonas() {
        personaLib.load()
        _persona = personaLib.current(prefs)
    }

    // ── 菜单 ──────────────────────────────────────────────────────

    private fun showMenu(pet: PetWindow) {
        menu?.dismiss()
        val m = PetMenuWindow(appCtx)
        menu = m
        val dm = appCtx.resources.displayMetrics
        m.show(
            anchorCenterX = pet.centerX(),
            anchorTop = pet.topY(),
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            items = listOf<Pair<String, () -> Unit>>(
                "💬 聊天" to { openChat() },
                "🎭 换个人格" to { cyclePersona() },
                "🎬 换个动作" to {
                    val name = pet.playRandomAction()
                    if (name != null) say(pet, "看这个：$name") else say(pet, "还没装动画素材。")
                },
                "🔄 翻个面" to { pet.flip() },
                "🏠 回家" to { pet.jumpHome() },
                "🗨️ 说句话" to { whisperNow() }
            )
        )
    }

    fun dismissMenu() {
        menu?.dismiss()
        menu = null
    }

    fun openChat() {
        runCatching {
            appCtx.startActivity(
                Intent(appCtx, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Diag.e("Runtime", "打开聊天失败", it) }
    }

    fun attachCount(): Int = pets.size

    fun stopFromNotification() {
        stop()
        appCtx.stopService(Intent(appCtx, PetService::class.java))
    }
}
