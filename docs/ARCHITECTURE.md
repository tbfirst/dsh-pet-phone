# 架构说明

## 1. 为什么是「一只宠物一个窗口」

安卓悬浮窗的命中判定是**矩形**，没有逐像素穿透。两条路：

| 方案 | 问题 |
| --- | --- |
| 一个大全屏透明窗口，里面排版所有宠物 | 整块屏幕的触摸都被这个窗口吃掉，桌面就废了 |
| **每只宠物一个小窗口，位置随宠物移动** | 窗口之外完全不响应 → 天然"穿透"；窗口内用 alpha 蒙版判定命中 |

本项目选后者，并多做一步：**用待机动画的 alpha 包围盒裁剪窗口**。
一只 480×270 的宠物，真实身体可能只占中间 300×230，
把窗口收到这个范围后，两侧的透明区连窗口都不在，点击自然落到下层 App。

```
整帧 480×270                 窗口 = alpha 包围盒
┌──────────────────┐         ┌────────────┐
│                  │         │   (身体)   │  ← 只有这块会拦截触摸
│      ▄██▄        │   →     │    ████    │
│     ██████       │         └────────────┘
│                  │
└──────────────────┘
```

残留问题（写在 README 已知限制里）：身体凹角处仍在窗口内，会吃掉点击。
彻底解决要靠 `FLAG_NOT_TOUCHABLE` 动态切换或无障碍手势转发，延迟与耗电都不划算。

## 2. 进程、线程与帧循环

```
PetService（前台服务，specialUse 类型）
   └─ PetRuntime（object 单例，跑在主线程）
        ├─ PetWindow × N   ← 每只一个悬浮窗 + PetView
        ├─ BubbleWindow    ← 每人一个（台词气泡，NOT_TOUCHABLE）
        ├─ PetMenuWindow   ← 全局一个，长按时才创建
        └─ Choreographer 单帧循环
```

- **只有一个帧循环**：`Choreographer.postFrameCallback` 每帧调用所有宠物的 `step(dt)`。
  多开时开销不叠加（对比：每只宠物各起一个循环）。
- **省电降频**：所有宠物都处于 `IDLE` / `SLEEP` 时，物理步进按 1/3 频率执行
  （dt 相应放大）。动画本身由系统解码器驱动并回调 `invalidate`，不受影响。
- **网络在主线程之外**：`ChatEngine` 内部 `withContext(Dispatchers.IO)`；
  UI 层用 Activity 自己的 `CoroutineScope(SupervisorJob() + Main.immediate)`，`onDestroy` 里 cancel。

## 3. 动画管线

上游是 VP9-alpha 的 `.webm`。安卓 `MediaCodec` 不暴露 alpha 平面，原生播不了透明视频。
所以：

```
上游 .webm (VP9-alpha)
   │  ffmpeg -c:v libvpx-vp9 -i in.webm        ← 必须显式指定解码器，否则 alpha 变 rgb24
   │         -vf fps=15,scale=480:-2
   │         -c:v libwebp_anim -pix_fmt yuva420p -loop 0
   ▼
动画 WebP（带 alpha）
   │  ImageDecoder.decodeDrawable()
   ▼
AnimatedImageDrawable  →  PetView.onDraw 里 drawable.draw(canvas)
```

细节：

- **`AnimatedImageDrawable` 的 `repeatCount`**：
  循环动画设 `REPEAT_INFINITE`；一次性动画设 `0`（= 只播一次），
  并且**不依赖 `repeatCount` 收尾**，而是按 `pet-config.json` 里的 `durations` 定时回待机
  （`repeatCount = 1` 在部分机型上会演两遍，这是已知坑）。
- **每次切换动画都重新 decode**：一次性动画必须从头播，复用实例会被停在中途。
  代价是每次状态切换约 5–15ms 的解码，发生在状态切换而非每帧，可以接受。
- **alpha 蒙版**：把首帧 decode 成 64px 宽的软位图，采样 alpha>24 得到布尔网格，
  同时算出包围盒。蒙版只用于命中判定与窗口裁剪，不参与渲染。
- **绘制时坐标映射**：窗口被裁剪后，`PetView` 先 `translate(-crop.left, -crop.top)` 再 `scale(1/crop.w, 1/crop.h)`，
  把整帧画进窗口；命中判定反向映射回整帧坐标再采样蒙版。

## 4. 物理：常量与上游对齐

`Physics.kt` 是纯函数 + 无 Android 依赖，`PhysicsTest` 覆盖 10 个用例。

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `SPRING_K` / `SPRING_C` | 200 / 30 | 拖拽跟手，接近临界阻尼，不来回摆 |
| `GRAVITY` | 1400 px/s² | 甩出后的下坠 |
| `RESTITUTION` | 0.78 | 撞边 / 落地反弹 |
| `GROUND_FRICTION` | 2.5 /s | 落地后的水平衰减 |
| `DEADZONE` | 500 px/s | 低于此速度视为停稳 |
| `MAX_SPEED` | 3600 px/s | 甩掷初速度上限 |
| `SQUASH_MS` / `SQUASH_DEPTH` | 220ms / 0.55 | Q 弹挤压 |
| `BOUNCE_STOP` | 150 px/s | 垂直速度低于此值不再弹 |
| `FLING_MIN` | 600 px/s | 低于此值算"轻放"而不是"甩" |

**Q 弹以脚底为锚点**（`canvas.scale(sx, sy, width/2, height)`），不是以中心，
否则看起来像气球原地膨胀而不是被压扁。

## 5. 状态机

```
                 tap / fling 动画播完
   ┌──────────────┐ ───────────────► ┌──────┐
   │     IDLE     │                   │ TAP  │
   └──────┬───────┘ ◄─────────────────└──────┘
          │ 拖动超过 touchSlop
          ▼
   ┌──────────────┐   松手，速度 ≥ 600
   │     DRAG     │ ─────────────────► FLING ──► 停稳 ──► IDLE
   └──────────────┘
          │ 闲置 25s 后随机
          ▼
   ┌──────────────┐
   │     WALK     │ ── 到边缘掉头 / 3~5.5s 后回 IDLE
   └──────────────┘

   闲置 3 分钟 → SLEEP（任意触摸唤醒 → IDLE）
```

## 6. 成本闸门（三级）

1. **入口闸**：只有"聊天"和"AI 碎碎念"会走网络；单击、甩出、散步全是离线台词。
2. **配额闸**：`Prefs.consumeQuota()` 在发请求**之前**扣次数，超过 `dailyLimit` 直接返回 `QUOTA`，不发包。
3. **请求闸**：`CONTEXT_TURNS = 4`（只带 8 条消息）、`MAX_USER_CHARS = 1200`、
   `max_tokens = max(120, persona.maxChars * 3)`、`stream = false`。

失败自动降级：AI 碎碎念失败 → 回落离线台词；对话失败 → 用该人格的 `fallback` + 中文原因。

## 7. 诊断

- `Diag`：Logcat + 200 条内存环形缓冲，设置页「看日志」直接看。
- `App`：`UncaughtExceptionHandler` 把堆栈写到 `filesDir/last_crash.txt`，
  再交给系统默认处理。悬浮窗/服务类问题最怕「无声消失」，这一层是刚需。
- `tools/verify-assets.mjs`：校验 alpha 通道、尺寸、配置引用一致性，
  防止出现"配置指了一个不存在的文件 → 某段动画永远播不出来"。
