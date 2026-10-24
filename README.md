# dsh-pet-phone · 会说话的手机桌宠

把 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 这只桌宠搬到**安卓桌面**上，
并补上原版和现有安卓移植版都缺的那一块：**内置人格定位 + AI 对话**。

> 它住在桌面上（不是住在某个 App 里），会呼吸、会被你拖着甩出去、会在屏幕边缘自己散步；
> 点它会有反应，长按可以**换人格**、点播动作、跟它聊天。
> 内置 8 个人格（傲娇鲸鱼娘 / 毒舌损友 / 温柔学姐 / 赛博猫娘 / 职场导师 / 元气小狗 / 深夜树洞 / 理性助手），
> 也支持你自己写一个人格。

- 代码：MIT
- 素材：来自上游，**允许开源使用、禁止商用**（见 [NOTICE.md](NOTICE.md)，**别拿它做付费产品**）
- 与 DeepSeek 官方无关，社区作品

---

## 一、这一版比上游多了什么

### 1. 新增：人格系统（本项目的主要增量）

| | 上游 dsh-pet（电脑） | dsh-pet-android（移植版） | **本项目** |
| --- | --- | --- | --- |
| 桌宠本体（物理/动画/菜单） | ✅ | ✅ | ✅ |
| 和宠物 AI 对话 | ✅ 但要连 DSH 宿主 | ❌ 明确写了「没有 AI」 | ✅ 自带对话，只填一个 Key |
| 人格定位 | ❌ 无 | ❌ 无 | ✅ **8 个内置人格 + 自定义** |
| 碎碎念 | ✅ 走模型（要 token） | ✅ 320 句固定台词 | ✅ **离线台词库优先，默认 0 成本**；可选 AI 生成 |
| 记忆 | ✅ memory.json | ❌ | ✅ **按人格隔离**，互不串味 |

人格不是一句「你是个可爱的女孩」就完事。每个人格是一份 7 段式系统提示词 +
一套该人格专属的口头禅 + 兜底话，见 [docs/PERSONA.md](docs/PERSONA.md)。

### 2. 优化：手机端的三个真问题

| 问题 | 移植版的现状 | 本项目的做法 |
| --- | --- | --- |
| 悬浮窗是矩形，透明区会吃掉点击 | 已知限制，未解决 | **按素材 alpha 包围盒裁剪窗口**：窗口只有身体那么大，透明区根本不在窗口内，点击自然落到下面的 App |
| 多开没有 UI | 只能手改 `pet-config.json` | 设置页 1/2/3 只一键切换，增删窗口不重启服务 |
| 多只宠物各起一个帧循环 | 每只一份开销 | **单帧循环驱动所有宠物**，全部待机时物理自动降到 ~20fps（动画仍由系统解码器驱动，不卡） |
| 气泡位置 / 一次点击演两遍 | 修过多轮 | 气泡独立窗口 + 按宠物身体中心对齐；一次性动画按「真实时长」定时收尾，不依赖 `repeatCount` |

### 3. 优化：花钱这件事

- **默认不花钱**：碎碎念走离线台词库；只有你主动聊天才发请求。
- **AI 碎碎念默认关**：想开才开（间隔 ≥5 分钟）。
- **每日调用上限**（默认 80 次）在发请求前拦截，不会因为 bug 或误触跑飞。
- 上下文只带 **4 轮**；人格 systemPrompt 固定不变 → 天然吃缓存命中的半价。
- 设置页实时显示「今日已用 N / 80 次」。

按 `deepseek-flash` 的价格，一个人一天聊 30 句，成本大概几分钱。

---

## 二、快速开始

### 路径 A：只想装 APK

1. 打开仓库的 **Actions** → 选最新一次 `Build APK` → 下载 `dsh-pet-phone-apk` 压缩包
2. 里面的 `app-debug.apk` 传到手机安装（需允许「未知来源」）
3. 打开 App → 授予 **「显示在其他应用上层」** → 点 **「放出来」**

> Release 页里带 `v*` tag 的构建也会自动出包。

### 路径 B：本地构建（需要 JDK 17+ + Android SDK 34）

```bash
# 1) 可选：从上游拉取并转码动画（需要 node + ffmpeg）
node tools/fetch-and-transcode.mjs
node tools/verify-assets.mjs

# 2) 指定 SDK 路径
#    Windows 注意写成转义形式：sdk.dir=E\:\\android-sdk
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 3) 构建
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

没有 Gradle Wrapper 的 jar 时（本仓库刻意不入库二进制），先跑一次：

```bash
gradle wrapper --gradle-version 8.7
```

> **实测过的版本组合**（能编出可安装的包，不是猜的）：
> Gradle 8.7 · AGP 8.5.2 · Kotlin 1.9.24 · JDK 17 与 JDK 21 均可 · compileSdk 34 / build-tools 34.0.0
 
### 构建排错：四个真踩过的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| CI 报 `Warning: Failed to find package 'tools'` + `sdkmanager failed with exit code 1` | 老版 SDK Tools 包已从 Google 仓库下架，而 `android-actions/setup-android@v3` 内部仍执行 `sdkmanager tools` | 已改用「复用 runner 预装 SDK + 手动装 `platforms;android-34`」的步骤，见 `.github/workflows/android.yml` |
| `Your project path contains non-ASCII characters` | AGP 默认拒绝中文路径 | `gradle.properties` 里已加 `android.overridePathCheck=true`；但**仍建议把工程放在纯英文路径**（本工程就是这么放到 `operation/dsh-pet-phone` 的） |
| 单测报 `ClassNotFoundException: com.dshpet.phone.PhysicsTest`（中文路径下） | Windows 上 Gradle 测试工作进程读不到非 ASCII 路径下的 class | 换到 ASCII 路径即消失；这是路径问题，不是代码问题 |
| `Unresolved reference: util`（指向 `app/build.gradle.kts`） | Kotlin DSL 里 `java` 被 Gradle 的 `java {}` 扩展占用，`java.util.Properties` 解析不了 | 文件顶部 `import java.util.Properties`，正文写 `Properties()` |
| `Could not connect to Kotlin compile daemon` / `CreateProcess error=1920` | Windows 下 Kotlin 编译守护进程起不来（常见于受限账户、杀软、沙箱） | 加参数 `-Pkotlin.compiler.execution.strategy=in-process` 让编译在 Gradle 进程内跑 |

### 路径 C：完全不取素材也能跑

`app/src/main/assets/pet/` 为空时，App 会自动使用**内置矢量占位宠物**
（一只会呼吸、会眨眼的小鲸鱼，Canvas 画的），功能一个不少。
适合先看效果、或你想换成自己的美术素材。

转码参数可调（体积随宽度涨得很快）：

```bash
WIDTH=640 FPS=24 QUALITY=60 node tools/fetch-and-transcode.mjs
```

---

## 三、怎么用

| 操作 | 效果 |
| --- | --- |
| 单击 | 点击回应动画 + Q 弹 + 该人格的一句台词 |
| **长按** | 菜单：聊天 / 换个人格 / 换个动作 / 翻个面 / 回家 / 说句话 |
| 按住拖动 | 过阻尼弹簧跟手 |
| 快速甩出 | 抛物线 → 撞边反弹 → 落地摩擦 → Q 弹停稳 |
| 闲置 3 分钟 | 睡着（碰一下醒） |
| 闲置 25 秒后 | 偶尔自己散步，走到边缘自动掉头 |
| 通知栏 | 聊天 / 说句话 / 收起 |

### 接上大模型（可选，5 分钟）

1. <https://platform.deepseek.com/> 注册 → **API keys** → 创建 → 复制 `sk-...`
2. 充值 10 元（够用很久）
3. App 设置页 →「大模型」→ 填 Key → 模型写 `deepseek-flash` → **测试连接**
4. 回到桌面，长按宠物 →「聊天」

> 模型必须用现役名字：`deepseek-flash` 或 `deepseek-v4-pro`。
> 网上 2025 年教程里的 `deepseek-chat` / `deepseek-reasoner` 已经停用，填了会报错。

---

## 四、人格怎么改

**不写代码也能改**：设置页 → 人格 → 点一个人格即切换；长按人格 → 「另存为自定义」→ 改提示词和台词 → 保存。

**想改内置的**：直接编辑 `app/src/main/assets/personas.json`，字段说明见 [docs/PERSONA.md](docs/PERSONA.md)。

一个能用的提示词至少要有这 6 段（照着填就行）：

```
【身份】叫什么、住在桌面上的什么角色、用户随时会戳你拖你
【性格】3 个关键词 + 1 个反差（例：毒舌但心软）
【说话风格】句子长短、口癖、能不能用 Markdown（建议不能）
【记忆】怎么称呼用户、记住什么、记不清就承认
【边界】不聊什么、不冒充真人、不编造信息
【输出格式】只输出台词，不超过 N 字，不要旁白、不要分点
```

**关键提醒**：只写「你是一个可爱的女孩」是没用的，模型会退回默认客服腔。
性格要具体到「会怎么说话」，最好附 3-5 句典型台词当语气锚点。

---

## 五、目录结构

```
app/src/main/java/com/dshpet/phone/
├─ MainActivity.kt      设置页（纯代码构建 UI）
├─ ChatActivity.kt      聊天页
├─ PetService.kt        前台服务（保活 + 通知栏按钮）
├─ PetRuntime.kt        进程内单例：窗口管理 + 单帧循环 + 碎碎念调度
├─ PetWindow.kt         单只宠物：悬浮窗/触摸/状态机/物理推进
├─ PetView.kt           渲染层：动画 WebP 或矢量占位宠物，Q 弹挤压
├─ Physics.kt           上游 physics.ts 的移植（常量同值，纯函数，带单测）
├─ AnimationPack.kt     素材查找 + alpha 蒙版 + 时长
├─ PetConfig.kt         pet-config.json 解析
├─ Persona.kt           人格数据模型
├─ PersonaLibrary.kt    内置人格 + 自定义人格读写
├─ LineLibrary.kt       离线台词库（按情境）
├─ MemoryStore.kt       按人格隔离的对话记忆
├─ ChatEngine.kt        DeepSeek 客户端（非流式 + 成本闸门）
├─ BubbleWindow.kt      台词气泡（独立窗口、不挡点击）
├─ PetMenuWindow.kt     长按菜单
├─ Diag.kt / App.kt     诊断与崩溃落盘
└─ BootReceiver.kt      开机自启

app/src/main/assets/
├─ personas.json        8 个人格（核心资产）
├─ lines.json           通用情境台词
└─ pet-config.json      动画清单（无素材时留空，走占位宠物）

tools/
├─ fetch-and-transcode.mjs   npm 取素材 + ffmpeg 转动画 WebP + 生成配置
└─ verify-assets.mjs         校验 alpha / 尺寸 / 配置引用一致性
```

---

## 六、已知限制（诚实版）

1. **悬浮窗仍是矩形**：已经按 alpha 包围盒收到最小，但宠物身体的凹角处（比如尾巴和身体之间那块）还是会吃掉点击。要彻底解决需要 `FLAG_NOT_TOUCHABLE` 动态切换或无障碍手势转发，代价是延迟与耗电，暂不做。
2. **无法感知"你在用什么 App"**：没有 Accessibility 权限，所以不会像电脑版那样联动「工作状态」。
3. **没有主动通知**：手机端不像电脑，桌宠不会弹系统通知打扰你。
4. **只支持 Android 9+**：动画 WebP 依赖 `AnimatedImageDrawable`（API 28）。
5. **素材是转码的**：640px 全量约 400MB+，480px 约 150MB。App 内不做二次压缩，包大是必然代价。
6. **国产 ROM 保活**：小米/荣耀需要手动给「无限制 + 自启动」，否则锁屏久了会被清。App 里有一键跳转电池白名单。
7. **本地没有编译验证**：本仓库的代码是按 JDK 17 + AGP 8.5.2 + Gradle 8.7 写的，首次 CI 构建可能要修一两处版本细节。`gradle :app:testDebugUnitTest` 可以先跑物理层单测。

---

## 七、路线图

- [ ] 用无障碍手势把「矩形窗口」彻底变成逐像素穿透
- [ ] 动作分类二级菜单（对接 `pet-config.json` 的 `categories`）
- [ ] 语音说话（TTS）+ 语音输入
- [ ] 人格市场：一键导入导出 `personas.json` 片段
- [ ] 桌面小组件（Widget）版本的静态宠物
- [ ] 与电脑端 dsh-pet 同步记忆（同一份 memory 格式）

---

## 八、上传 GitHub 并拿到 APK（5 步）

1. **新建仓库**（建议 Public），把本目录整个上传：
   ```bash
   cd dsh-pet-phone
   git init && git add . && git commit -m "feat: dsh-pet 安卓版 + 人格系统"
   git remote add origin git@github.com:<你的账号>/dsh-pet-phone.git
   git push -u origin main
   ```
   > `.gitignore` 已经排除了 `app/src/main/assets/pet/`（素材不入库）和 `local.properties`，直接 push 不会带二进制。
2. **确认 Actions 有权限**：仓库 → Settings → Actions → General → Workflow permissions 选 **Read and write**（Release 发布需要）。
   如果你是用带 `workflow` scope 的 token 推的，`.github/workflows/android.yml` 会正常上传；否则 GitHub 会拒绝推送该文件，需要在网页端手动新建一次。
3. **等构建**：Actions 页会自动跑 `Build APK`。第一次大约 5–8 分钟（要装 Android SDK、拉素材、转码）。
4. **下载 APK**：进入这次运行 → 页面底部 **Artifacts** → `dsh-pet-phone-apk` → 解压得到 `app-debug.apk`，
   传到手机安装（需允许「安装未知来源应用」）。
5. **想要"正式下载页"**：打个 tag 就会自动发 Release：
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```
   Release 里会挂 `dsh-pet-phone-v1.0.0-debug.apk`。

**想签名 release 版**：在仓库根目录建 `keystore.properties`（已在 .gitignore 里）：

```properties
storeFile=release.jks
storePassword=你的密码
keyAlias=dshpet
keyPassword=你的密码
```

生成 keystore：

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias dshpet
```

> CI 里想签名，把 `release.jks` 做成 base64 存进 Secrets，构建前还原成文件即可（工作流里已留好 `if (keystoreProps != null)` 的判断）。

**注意**：Debug 包和 Release 包签名不同，**不能互相覆盖安装**，换包前先卸载旧版。

---

## 九、许可与署名

- 代码：**MIT**
- 动画素材：来自 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，**允许开源使用，禁止商用**
- 技术结论参考：[Tony-Chen-Stark/dsh-pet-android](https://github.com/Tony-Chen-Stark/dsh-pet-android)
- 交付与分发时请保留 [NOTICE.md](NOTICE.md)

> 上游二创约定：基于该项目的衍生作品，在任何介绍、展示、分发处都需附上
> <https://github.com/PC2005-cloud/dsh-pet>。本项目遵守。
