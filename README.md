# 云开 Yunkai · 本地手机 AI Agent

<p align="center">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-green">
  <img alt="android" src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white">
  <img alt="harmonyos" src="https://img.shields.io/badge/HarmonyOS%20NEXT-API%2026-000000">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white">
  <img alt="arkts" src="https://img.shields.io/badge/ArkTS-DevEco-0EA5E9">
</p>

<p align="center">
  简体中文 &nbsp;|&nbsp; <a href="README_EN.md">English</a>
</p>

---

## 这是什么？

> **云开是一个装在你手机里的 AI 助手：能看懂屏幕、替你动手操作、长期记住你的偏好——对话与记忆保存在本地，模型推理所需内容会发送至你配置的模型服务。**

和网页版 AI 聊天不同，云开不是一个聊天窗口，而是一个能"看"和"做"的助手：

- **看** —— 读取当前屏幕上正在显示的内容，知道你在哪个页面；
- **做** —— 替你执行点击、滑动、输入等操作，**每一步都要你确认后才会执行**；
- **记** —— 把你的偏好和重要信息存成本地记忆，越用越懂你；
- **本地** —— API Key 配置、对话记录和记忆保存在设备本地；调用模型时，API Key 用于向所配置端点鉴权，相关上下文会发送至该端点。

包含 **Android 11+** 与 **HarmonyOS NEXT** 原生项目，但两端能力进度不同。以下屏幕读取、计划卡和手势执行说明适用于旧版 Android；鸿蒙屏幕扩展仍是验证骨架，尚未接入可用的读屏与操作闭环。

## 效果演示

![云开演示](docs/screenshots/demo.gif)

## 界面截图

| 主对话 | 写操作确认卡 |
|---|---|
| ![主对话](docs/screenshots/harmony_dark.png) | ![计划卡](docs/screenshots/android_plan_card.png) |

| 悬浮球对话面板 | 屏蔽应用管理 |
|---|---|
| ![闪问面板](docs/screenshots/android_flash_answer.png) | ![黑名单](docs/screenshots/android_blacklist.png) |

## 它能帮你做什么？

| 场景 | 云开的行为 |
|------|-----------|
| 在任何 App 里随手提问 | 点开悬浮球，半屏对话面板直接问，不用切出当前应用 |
| "帮我把这条消息发给张三" | 云开列出将要执行的操作步骤，你逐条确认后它才动手 |
| 不想重复自我介绍 | 它记住你的偏好与常用信息，新对话自动带上 |
| 长文档看不完 | 丢给它 docx / PDF / xlsx / txt / 图片，直接问内容 |
| 想让 AI 更懂某个领域 | 导入自定义"技能"说明书，或使用内置的讲解、军师技能 |

## 功能总览

| 能力 | Android | HarmonyOS NEXT |
|------|:---:|:---:|
| Agent loop（多步工具调用 / 取消 / 流式输出） | ✅ | ✅ |
| 屏幕感知：无障碍读屏 → 结构化控件清单 | ✅ | 验证骨架，未接入 |
| 写操作：计划卡预览 → 逐条批准 → 手势执行 | ✅ | 未接入执行闭环 |
| 三层安全：敏感页护栏 / 每步回显 / 外发二次确认 | ✅ | 未接入屏幕执行闭环 |
| 隐私黑名单（银行 / 支付类默认拒绝读屏） | ✅ | 规则逻辑，未接入实际读屏 |
| 悬浮球 + 半屏 / 全屏对话面板（底色三档） | ✅ | ✅ |
| 快捷设置磁贴入口 | ✅ | ✅ |
| 忆枢记忆引擎（摘要 / 检索 / 隐私挡位） | ✅ | ✅ |
| 技能系统（@技能 / 自动路由 / 讲解画布，双内置） | ✅ | ✅ |
| 文档附件问答（图片 / txt / docx / xlsx / PDF） | ✅ | ✅ |
| 主题三档 + 双皮肤 | ✅ | ✅ |

## 技术亮点

- **双端原生同构** —— Android（Kotlin 2.0 / Compose）与 HarmonyOS NEXT（ArkTS）分别原生实现对话、技能、记忆等能力，不是跨端框架套壳；屏幕读取与执行尚未在鸿蒙端接通。
- **屏幕感知安全模型** —— 模型永远不能"直接操作手机"：它只能提交动作计划（点击 / 滑动 / 输入 / 返回），用户在计划卡中逐条批准，执行过程逐步回显。
- **忆枢记忆引擎** —— 本地 BM25 检索 + 会话摘要 + 三级隐私挡位；严格挡位下，说出口的敏感信息不会进入可检索的记录。
- **提示注入防御** —— 工具返回的一切外部内容（网页 / 搜索结果 / 文档）统一标注为"不可信数据"，其中出现的指令性文字不会被执行，阻断"网页里藏指令操纵助手"一类攻击链。
- **零依赖文档解析** —— docx / xlsx 用 zip + XML 自研抽取，PDF 本地直读，无需专门的云端文档解析服务；用于 AI 问答的提取正文和图片仍会进入所配置模型服务的请求。本地解析不等于内容不出设备。
- **全链路可测** —— Android 200+ JVM 单测、HarmonyOS 81 用例，外加模拟器上 AI 驱动的端到端验收路径。

## 架构

下图展示旧版 Android 的完整工具层及双端共用的设计思路，不表示鸿蒙已实现全部模块。

```mermaid
flowchart LR
    UI["UI 层\n主界面 / 悬浮球面板 / 计划卡 / 设置"] --> VM["ChatViewModel\n共享大脑"]
    VM --> Loop["AgentLoop\n多步工具调用 / 取消 / 事件流"]
    Loop --> Tools
    subgraph Tools [工具层]
        Builtin["内置工具\n搜索 / 网页 / 附件抽取"]
        Screen["屏幕感知\n读屏 / 截图 / 应用列表"]
        Write["写操作状态机\n计划卡 → 逐条批准 → 执行"]
        Memory["忆枢记忆\n摘要 / 检索 / 归档"]
        Skill["技能系统\n@技能 / 自动路由 / 画布"]
    end
    Loop --> LLM["LlmClient\nOpenAI 兼容协议 / 流式输出"]
    Tools --> Store[("本地存储\nRoom (Android)\nRDB + Preferences (HarmonyOS)")]
    Memory --> Store
```

- **Android**：Kotlin 2.0 / Compose / Room / DataStore / AccessibilityService（`takeScreenshot` 需 API 30+，故 minSdk 30）
- **HarmonyOS**：ArkTS / API 26 / RDB / Preferences / 无障碍扩展验证骨架（未接入读屏/手势执行）

## 安全与隐私

下列屏幕操作防线描述旧版 Android；鸿蒙尚未接入屏幕执行闭环。

云开的设计原则是"**只看不动，除非逐条批准**"，围绕这条原则有七道防线：

1. **计划卡**：写操作只能以"计划"形式提交，聊天界面内嵌计划卡逐步展示，用户点「执行」后状态机才推进；
2. **敏感护栏**：屏幕内容命中敏感关键词（支付 / 转账 / 验证码等）即进入暂停态，写操作止步；
3. **外发确认**：包含文字输入的动作单独走二次确认；
4. **黑名单默认不信**：银行 / 支付类应用直接拒绝读屏，用户可自行增补；
5. **隐私挡位**：读屏与对话内容按挡位脱敏，严格挡位下转录不入库；
6. **注入防御**：外部内容一律视为数据而非指令（见技术亮点）；
7. **附件防线**：文档解析设大小上限与内容截断，防资源耗尽。

### 模型请求与数据边界

本地存储与模型推理传输是两回事。请求可能包含问题、相关对话或摘要、按条件注入的记忆与技能、工具结果、文档提取正文，以及相关图片或屏幕内容。具体内容取决于功能、模型能力和上下文裁剪，不代表每次发送全部历史或原始附件。

请求发送至你配置的模型端点，该端点可能是云服务、本机服务或局域网服务。若使用远程服务，相关内容会离开设备；服务端如何保存和使用数据取决于其政策。搜索和网页访问也会连接相应外部服务，不能把“本地优先”理解为完全离线。

以上依据公开代码路径说明，不是全量网络出口审计或真实流量验证。

## 构建与运行

两端的模型接入均为 **OpenAI 兼容协议**：自备 base URL 与 API key，配置保存在设备本地；推理请求直接发往所配置端点。

### Android

```bash
cd yunkai-android
# Android Studio 打开，或命令行：
./gradlew assembleDebug          # 产物 app/build/outputs/apk/
./gradlew testDebugUnitTest      # JVM 单测
```

minSdk 30（无障碍截图 `takeScreenshot` 需 API 30+）。已在 MIUI 真机完成验证。

### HarmonyOS NEXT

```bash
cd yunkai-harmony
# DevEco Studio 打开（API 26），或命令行：
hvigorw --mode module -p product=default assembleHap
```

首次使用：在设置页填入模型端点与 API key，即可使用已接入的对话等功能。当前鸿蒙屏幕扩展仍为验证骨架，不能通过开启开关或授权就获得完整读屏与操作能力。

## 测试

| 层 | 内容 |
|---|---|
| 代码审查 | 提交前按 checklist 逐项走查 |
| 单元测试 | Android JVM **200+** 用例 / HarmonyOS **81** 用例 |
| 端到端验收 | 模拟器上 AI 驱动的全链路路径，ADB / HDC + UI 自动化执行，截图留证 |

双端共享同一组忆枢测试向量（检索 / 迁移 / 隐私闸门），逐字节一致。

## License

[MIT](LICENSE)
