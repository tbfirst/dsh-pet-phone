# NOTICE · 署名与授权

本项目是社区移植 + 二次开发，**与 DeepSeek 官方无关**。

## 一、上游（必须署名）

- **dsh-pet** — <https://github.com/PC2005-cloud/dsh-pet>
  - 作者：PC2005-cloud
  - 本项目沿用其：动画素材、物理常量与手感参数（弹簧 200/30、重力 1400、恢复系数 0.78、地面摩擦 2.5、死区 500、钳速 3600、Q 弹 220ms / 压深 0.55）、配置字段的组织方式
  - 授权：**代码 MIT；素材（动画 / 提示词 / 源视频）允许开源使用，禁止商用**
  - 上游的二创约定要求：基于本项目衍生的作品，**在任何介绍、展示、分发的地方都要附上原作者地址**。本文件与 README 均遵守此约定。

## 二、参考实现

- **dsh-pet-android** — <https://github.com/Tony-Chen-Stark/dsh-pet-android>
  - 作者：Tony-Chen-Stark
  - 本项目参考其已验证的技术结论：安卓原生播不了带 alpha 的 webm（MediaCodec 不暴露 alpha 平面）→ 改用**动画 WebP + AnimatedImageDrawable**；以及 `ffmpeg -c:v libvpx-vp9` 显式指定解码器才能保住 alpha 的转码姿势。
  - 物理与状态机为本项目重新实现（纯函数 + 单测覆盖），不是逐行复制。

## 三、素材分发约束（重要）

1. 本仓库**不包含**任何 `.webp` 动画素材（`.gitignore` 已排除 `app/src/main/assets/pet/`）。
   素材由 `tools/fetch-and-transcode.mjs` 在构建时从上游 npm 包取回并转码。
2. 因此：**任何包含这些素材的 APK 都不能用于商业用途**（不能卖、不能放进付费课程、不能作为商业产品分发）。
3. 想商用，请自行替换为自有素材：把你拥有版权的动画按 `pet-config.json` 的文件名放进 `app/src/main/assets/pet/`，即可完全脱离上游素材。

## 四、第三方组件

| 组件 | 授权 |
| --- | --- |
| AndroidX Core / AppCompat | Apache-2.0 |
| Kotlin / kotlinx-coroutines | Apache-2.0 |
| DeepSeek API（可选，用户自带 Key） | 按 DeepSeek 开放平台条款 |

## 五、人格提示词

`app/src/main/assets/personas.json` 里的 8 个人格提示词为本项目原创撰写，
随代码以 MIT 分发；引用到文档/课程时请注明来源。

---

如你是权利人且认为本项目某处使用不当，请开 issue，会尽快处理。
