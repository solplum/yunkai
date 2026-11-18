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
- **做** —— 按执行确认模式自动执行点击、滑动、输入等操作，命中相应规则时暂停确认，**不是每一步都询问**；
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
| 在支持的应用中执行操作 | 计划通过校验后开始执行，是否暂停确认取决于执行确认模式；旧版 Android 当前拒绝以微信/QQ 为声明目标的计划 |
| 不想重复自我介绍 | 它记住你的偏好与常用信息，新对话自动带上 |
| 长文档看不完 | 丢给它 docx / PDF / xlsx / txt / 图片，直接问内容 |
| 想让 AI 更懂某个领域 | 导入自定义"技能"说明书，或使用内置的讲解、军师技能 |

## 功能总览

| 能力 | Android | HarmonyOS NEXT |
|------|:---:|:---:|
| Agent loop（多步工具调用 / 取消 / 流式输出） | ✅ | ✅ |
| 屏幕感知：无障碍读屏 → 结构化控件清单 | ✅ | 验证骨架，未接入 |
| 写操作：提交计划 → 按模式执行/暂停确认 → 结果回报 | ✅ | 未接入执行闭环 |
| 执行护栏：按模式敏感暂停 / 步骤回显 / 条件确认 | ✅ | 未接入屏幕执行闭环 |
| 隐私黑名单（银行 / 支付类默认拒绝读屏） | ✅ | 规则逻辑，未接入实际读屏 |
| 悬浮球 + 半屏 / 全屏对话面板（底色三档） | ✅ | ✅ |
| 快捷设置磁贴入口 | ✅ | ✅ |
| 忆枢记忆引擎（摘要 / 检索 / 隐私挡位） | ✅ | ✅ |
| 技能系统（@技能 / 自动路由 / 讲解画布，双内置） | ✅ | ✅ |
| 文档附件问答（图片 / txt / docx / xlsx / PDF） | ✅ | ✅ |
| 主题三档 + 双皮肤 | ✅ | ✅ |

## 技术亮点

- **双端原生同构** —— Android（Kotlin 2.0 / Compose）与 HarmonyOS NEXT（ArkTS）分别原生实现对话、技能、记忆等能力，不是跨端框架套壳；屏幕读取与执行尚未在鸿蒙端接通。
- **屏幕操作执行模型** —— 模型提交动作计划（点击 / 滑动 / 输入 / 返回），通过校验后即执行；执行器按确认模式决定是否暂停，计划卡展示进度并在暂停时提供确认/继续与取消按钮。
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
        Write["写操作状态机\n提交计划 → 按模式执行/暂停 → 回报"]
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

旧版 Android 的计划通过校验后即开始执行，不存在整份计划的预先批准门。执行确认模式在设置中选择，下次提交计划时读取，不会动态改变已提交计划的模式。

| 模式 | 当前实现 |
|---|---|
| SMART · AI 自审（默认） | 敏感检查命中则暂停；否则对模型标记 `confirm:true` 的动作或敏感文字输入暂停。其他动作可自动执行。 |
| STRICT · 事事过问 | 保留敏感检查，所有文字输入暂停；点击、滑动等并非每步确认。当前实现不因非输入动作的模型 `confirm:true` 标记单独暂停。 |
| FULL_AUTO · 完全访问 | 跳过执行期敏感检查和分档确认，自动执行；包括跳过执行期黑名单、密码页、读不到页面等暂停。 |

**重要限制：** STRICT 不应理解为所有维度都比 SMART 更严格。敏感检查依赖可读取的页面信息、密码框和关键词，不能保证识别所有高风险操作；暂停可经用户批准继续。FULL_AUTO 并非取消一切限制：读屏/截图工具的独立黑名单检查、动作参数校验、提交次数上限和执行失败收敛仍保留。

以下说明当前实现的防线及适用范围：

1. **计划卡**：提交后展示执行进度，而非等待整份计划获准；只有触发暂停时，卡片显示确认/继续与取消按钮；
2. **敏感护栏**：SMART/STRICT 在页面不可读、命中黑名单、密码框或敏感词等情况下暂停，批准后该步可继续；FULL_AUTO 跳过该执行期检查；
3. **输入确认**：STRICT 对所有文字输入暂停；SMART 按敏感检查或模型标记暂停；FULL_AUTO 不暂停。敏感暂停获准后，同一步不会再重复进行分档确认，因此不是统一的外发二次确认；
4. **读屏黑名单**：读屏/截图工具拒绝黑名单应用，用户可增补名单；这与写操作执行期的敏感暂停不同，后者在 FULL_AUTO 下被跳过；
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
