# 云开 Next：以用户最终结果为准的手机验收

## 本阶段交付边界

这不是将 Mika 改名就宣布完成，也不是另写一套脱离真实执行器的演示算法。
新增 `YunkaiPhoneOutcomeTest` 将模拟设备接到 **真实** `ActionExecutor → PhoneRun → PhoneQuery / PhoneAutomationEngine → ScreenPort` 路径，验证操作结果、最终读屏和停止条件。旧引用用例直接调用上游测试专用分发入口，以隔离引用失效行为。

测试不启动 Android、不调用真实模型、不联网、不发送消息、不读取账号或凭据；包名、内容和页面全部是合成数据。`ScreenPort` 只模拟设备边界；目标选择、命令执行、守卫、结构化失败和读屏均由 Mika 的实现负责。

**编译通过不等于测试通过；模拟测试通过也不等于真机验收通过。** 本文不声称 APK 已构建、安装或在真实手机上完成任务。实际运行结果以执行日志/JUnit 报告为准。

## 三个最小可观察结果

| 用户结果 | 测试方法 | 必须成立的断言 |
| --- | --- | --- |
| 打开 App，找到指定对象并进入详情 | `openAppFindExactTargetAndReadItsDetail` | App 包名正确；相似名称与精确名称同时出现时只点精确名称；最终屏幕为该对象详情；三个步骤全部完成；日志包含真实查询工具结果 |
| 跨两个页面填写，最后读回内容 | `inputAcrossTwoPagesMustReadBackBothValuesOnReviewPage` | 两个不同资源 ID 的输入框分别收到合成值；按 Next、Review 导航；最终审核页两项内容逐项匹配；六步完成 |
| 异常弹窗或目标不存在时安全停止，提供重新规划依据 | `unexpectedDialogStopsBeforeNextInputAndReturnsEvidenceForReplan` / `zeroMatchesAndDuplicateTargetsStopWithoutTapping` | 弹窗后不输入下一项；零匹配或重复匹配时不点击；`ok=false`、具体原因、已完成步数准确；未执行步骤显式标记 `executed=false`；返回当前屏幕 |

## 必须失败，不能报完成的反例

- **输入 API 返回成功但内容丢失**：`acknowledgedTypingWithoutVisibleEffectCannotPassReadback` 模拟底层确认输入、页面没有相应值。审核断言必须失败为 `condition_unmet`，后续发送步骤不能执行。不能把输入接口成功当成用户任务成功。
- **页面变化后旧引用过期**：`expiredReferenceCannotActOnReplacementScreen` 先读取目标，再换到目标消失的页面；旧引用必须返回 `ref_expired`，设备点击次数为零。
- **发送结果不确定**：`uncertainSendStopsWithoutRetryOrSuccessReceipt` 仅模拟一次 Send 点击，设备返回 `input_uncertain`；复合操作必须停止、不能重复发送、不能计为完成，也不能凭空出现成功回执。

“安全停止”在这里的精确定义是：本批次后续动作不再执行，保留失败原因和当前屏幕，交由上层或用户重新规划。测试没有声称已经实现所有异常的自动恢复，也没有用预写的模型回复冒充恢复推理。

## 运行方式

在具备项目所需 JDK/Gradle、依赖与允许执行构建的环境中，从 `yunkai-next` 根目录运行：

```sh
./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.phone.YunkaiPhoneOutcomeTest'
```

这是 JVM 核心测试，不需要真实手机；工程配置仍可能解析 Android 插件/SDK。不要为绕过环境缺失而把未运行写成通过。完整核心测试、Android 构建及真机测试是不同验证层，分别记录状态。

## 云开适配：严格目标匹配

基线运行发现：上游在没有精确名称时，会选择唯一子串候选。例如想打开 `Demo target`，实际仅有 `Demo target archive`，仍被点击且返回成功。这不是可接受的指定对象验收结果。

本次适配保留原执行链，仅收紧 `phone_run` 的目标选择：

- `tap` / `type` / `clear` 的 `targetText` 默认 `textMatch="exact"`，完整名称匹配、不区分大小写。
- 精确目标不存在即 `target_not_found`；同时提供 x/y 也不能绕过文字目标失败去盲点。
- `textMatch="contains"` 是显式子串匹配选择，仅供完整文字会变化的非身份 UI 标签；例如 `Show details (2)`。不要用它模糊选择人、会话或指定对象；匹配不唯一仍停止，存在完整匹配时仍优先完整匹配。
- 不支持的模式，以及没有 `targetText` 或非 tap/type/clear 操作使用此参数，返回参数错误；本批所有命令的新参数在第一次设备动作前统一校验，后续命令的错误不会留下部分执行。
- 未提供 `targetText` 的 resourceId / role 选择保持上游行为。
- `wait` / `assert` / `whenText` 仍是文字存在检查，不是身份精确匹配；该差异在工具提示中明确说明。

新增/替换回归包括：`missingExactTargetStopsEvenWhenLoneSubstringAndCoordinatesExist`、`substringSelectionRequiresExplicitOptIn`、`invalidTextMatchNeverExecutesTheCommand`、`roleOnlySelectionRetainsExistingSemantics`。原来证明错误行为存在的绿色刻画测试已替换为阻止该错误行为的回归测试。

## 验证证据（2026-10-30）

**改动前基线**：外置 SDK-free JVM 工程编译实际 core 代码，原 8 项执行、0 失败/错误/跳过。其中 7 项为结果/停止契约、1 项暴露上述错误目标行为；不能将这份改动前结果当成本次修复已通过。基线报告：`mika-core-validation/evidence/focused/TEST-com.wangzan101.mika.core.cap.phone.YunkaiPhoneOutcomeTest.xml`（时间 `2026-10-30T16:28:02`）。

**改动后验证**：最终本类 **13 项执行，0 失败，0 错误，0 跳过**（`2026-10-30T16:35:41`），报告位于 `mika-core-validation/evidence/postpatch-before-goldens/TEST-com.wangzan101.mika.core.cap.phone.YunkaiPhoneOutcomeTest.xml`。包括 tap/type/clear 对唯一相似目标的拒绝、坐标不可绕过、跨页读回，以及后续命令参数错误时整批零设备写入。第一次更广核心范围执行 2239 项时，另出现 13 项提示词/工具 schema 派生快照比较失败，随后由验证流程审阅和更新；这是历史中间结果，不是行为测试失败。后续项目身份、技能和审批适配另有验证范围。本文的通过声明仅限最终 13 项手机结果测试；整个迁移的最终套件状态以汇总验证报告为准，不能从本类通过推断。

验证使用 Gradle 8.11.1、Kotlin 2.1.20、Java 21 执行并面向 JVM 17；没有修改生产 Gradle 配置，也没有配置 Android 插件或运行手机工具。上述路径相对本次工作区，并非仓库发布附件。这不是标准全工程 Gradle 命令、Android 构建或真机通过的声明。

## 已知限制与下一步门槛

1. `PhoneRun` 的 `ok=true` 代表该批指令执行成功，不自动代表任意自然语言目标达成。任务必须带可观察的后置条件；上述跨页用例明确加入读回断言。
2. 精确匹配修复仅限定 `phone_run` 的 `targetText`；不代表所有工具、资源 ID、视觉定位或自然语言规划都获得严格身份保证。显式 contains 的风险由调用方承担，不能把它当成精确对象验收。
3. `assert` 目前查询可见文字子串；跨页测试另对最终元素列表做完整相等断言，但生产任意任务不会自动得到这份测试断言。
4. 未验证真实模型规划质量、视觉/OCR、系统权限、IME、网络超时、厂商后台策略或真实 App 可访问性树。模拟 ScreenPort 不是这些条件的替代品。
5. 未验证最终自然语言回复的真实性；这里验证机器可读的工具失败、停止和证据。真实模型的最终回复还须与这些证据一致，特别不能将不确定发送说成已发送。
6. 真机前先使用测试 App 和合成内容，再以明确授权的非敏感任务验证这三个结果。记录目标、必要权限、动作轨迹、最终页面、停止原因；失败时保留证据而非无限重试。外发、付款、删除等风险另按产品授权策略验收。

最终交付门槛是“用户指定的目标有可观察证据，或明确说明无法完成及原因”，不是工具调用次数、改名文件数量或未经验证的成功文案。
