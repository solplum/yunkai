// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.about

import com.wangzan101.mika.core.adb.ConnectCandidates
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.SetupInputs
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.settings.AccessibilityState
import java.net.URI

/**
 * 「复制诊断信息」那一刻读到的状态。只放排查要用的:版本、机型、手机操作每一步的状态、模型的接口类型和地址、
 * 最近几次运行的结局;不放 Key、登录、聊天内容、任务目标和屏幕内容,也不放 IP、端口和 Wi-Fi 名。
 * 引导页的几项直接是引导页自己的输入 [setup](同一份状态机算出步骤),不另算一遍。
 */
data class DiagnosticsSnapshot(
    val generatedAtMs: Long,
    val app: App,
    val device: Device,
    val setup: SetupInputs,
    val phone: Phone,
    val recovery: Recovery,
    val models: Models,
    val runs: List<Run>,
    val update: Update,
) {
    data class App(val versionName: String, val build: String, val installer: String?, val language: String)

    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val abis: List<String>,
        /** 沙箱(运行环境)的状态:不支持时写原因。 */
        val sandbox: String,
    )

    data class Phone(
        val accessibility: AccessibilityState,
        /** 系统设置里「无线调试」那一项;这一项读不到为 null。 */
        val wirelessDebuggingSetting: Boolean?,
        val connect: ConnectCandidates?,
        /** 系统里已有的配对码通知频道(id 到重要程度);一个都没建为空。是否可用以 [SetupInputs.pairingChannel] 为准。 */
        val pairingChannels: Map<String, Int>,
        val attentionChannelImportance: Int?,
        val wifiConnected: Boolean?,
        /** 最近一次配对或连接失败的原文(渲染时脱敏)。 */
        val lastError: String?,
    )

    data class Recovery(
        val consent: RecoveryConsent,
        val writeSecureSettings: Boolean,
        /** 系统电池优化里 Mika 是不是不受限制;读不到为 null。 */
        val ignoringBatteryOptimizations: Boolean?,
        val lastRestore: String?,
    )

    data class Models(
        val providers: List<Provider>,
        val main: RoleModel?,
        val vision: RoleModel?,
        val light: RoleModel?,
        val chatGptSignedIn: Boolean,
    )

    /** 一个服务商:接口类型、地址(只取主机名)、列过几个模型(没列过为 null)。 */
    data class Provider(val kind: String, val baseUrl: String, val listedModels: Int?)

    data class RoleModel(val model: String, val baseUrl: String)

    /** 一次运行:开始时间、结局、失败的类别(不是原话)、走了几步、用的模型。 */
    data class Run(val startedAtMs: Long, val outcome: String, val failKind: String?, val judged: String?, val steps: Int, val model: String?)

    data class Update(val status: String, val latest: String?, val available: String?)
}

/** 把 [DiagnosticsSnapshot] 写成一段纯文本:分段顺序固定,英文键,发到微信或 GitHub 都好读。最后整段再过一遍脱敏。 */
object Diagnostics {
    const val HEADER = "云开诊断信息 / Yunkai diagnostics"

    fun render(s: DiagnosticsSnapshot): String = sanitize(buildString {
        appendLine(HEADER)
        appendLine("generated: ${java.time.Instant.ofEpochMilli(s.generatedAtMs).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)}")

        section("App")
        line("version", "${s.app.versionName} (build ${s.app.build})")
        line("installer", s.app.installer ?: "unknown")
        line("language", s.app.language)

        section("Device")
        val d = s.device
        line("model", if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}")
        line("android", "${d.androidRelease} (SDK ${d.sdk})")
        line("abi", d.abis.joinToString(", ").ifEmpty { "unknown" })
        line("arm64 sandbox", if ("arm64-v8a" in d.abis) "supported (${d.sandbox})" else "not supported (${d.sandbox})")

        section("Phone control")
        phone(s)

        section("Recovery")
        val r = s.recovery
        for (c in RecoveryCapability.entries) line(capability(c), consent(r.consent, c))
        line("write secure settings", if (r.writeSecureSettings) "granted" else "not granted")
        line("battery optimization", when (r.ignoringBatteryOptimizations) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" })
        line("last reboot restore", r.lastRestore ?: "none")

        section("Models")
        val m = s.models
        line("providers", if (m.providers.isEmpty()) "none" else "${m.providers.size}")
        m.providers.forEach { p -> appendLine("- ${p.kind} ${host(p.baseUrl)}" + (p.listedModels?.let { " ($it models listed)" } ?: "")) }
        line("main", m.main?.let(::role) ?: "not set")
        line("vision", m.vision?.let(::role) ?: "off")
        line("light", m.light?.let(::role) ?: "off")
        line("ChatGPT sign-in", yes(m.chatGptSignedIn))

        section("Recent runs")
        if (s.runs.isEmpty()) appendLine("none")
        s.runs.forEach { run ->
            appendLine(
                "- ${ago(s.generatedAtMs - run.startedAtMs)}: ${run.outcome}" +
                    (run.failKind?.let { " ($it)" } ?: "") + (run.judged?.let { ", judged $it" } ?: "") +
                    ", ${run.steps} steps" + (run.model?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""),
            )
        }

        section("Update")
        line("last check", s.update.status)
        line("latest seen", s.update.latest ?: "none")
        line("update available", s.update.available ?: "no")
    }.trimEnd() + "\n")

    private fun StringBuilder.phone(s: DiagnosticsSnapshot) {
        val i = s.setup
        val p = s.phone
        val view = PhoneSetupFlow.view(i)
        line("helper", shell(i.status))
        line("setup", "phase=${view.phase}" + (view.current?.let { ", current=${step(it)}" } ?: ""))
        line("steps", SetupStep.entries.joinToString(", ") { "${it.ordinal + 1} ${view.marks[it]}" })
        view.suspect?.let { line("suspect", step(it.step) + if (it.unreadable) " (switch unreadable)" else "") }
        line("confirmed by user", i.confirmed.sortedBy { it.ordinal }.joinToString(", ") { step(it) }.ifEmpty { "none" })
        if (i.accessibilitySkipped) line("accessibility step", "skipped")
        if (i.repair) line("repair", "yes")
        line("developer options", onOff(i.developerOptions))
        line("USB debugging", onOff(i.usbDebugging))
        line("wireless debugging", p.wirelessDebuggingSetting?.let(::onOff) ?: "unreadable")
        line("accessibility", when (p.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        })
        line("restricted settings", if (i.restrictedSettings) "may block" else "no")
        line("pairing service", when (i.discovery) {
            is PairingDiscovery.Found -> "found"
            PairingDiscovery.Searching -> "searching"
            PairingDiscovery.Idle -> "not found / not searching"
        })
        line("connect service", p.connect?.let { c ->
            "cached ${c.cache}, property ${c.property}, mDNS ${c.mdns} candidates; " +
                (if (c.scanned) "scan: ${c.scanOpen} open, ${c.scanAdb} adb; " else "scan: not run; ") +
                "manual: " + (if (c.manual) "yes" else "no") + "; " +
                if (c.connected) "connected" else "none connected"
        } ?: "not tried in this session")
        line("notifications", if (i.notificationsAllowed) "allowed" else "blocked")
        line("pairing channel", "${i.pairingChannel} (" +
            (if (p.pairingChannels.isEmpty()) "not created" else p.pairingChannels.entries.joinToString(", ") { "${it.key}=${importance(it.value)}" }) + ")")
        view.notifyIssue?.let { line("pairing notification issue", it.name) }
        line("attention channel", importance(p.attentionChannelImportance))
        line("Wi-Fi", when (p.wifiConnected) { true -> "connected"; false -> "not connected"; null -> "unknown" })
        line("last error", p.lastError?.takeIf { it.isNotBlank() }?.take(MAX_ERROR) ?: "none")
    }

    private fun StringBuilder.section(name: String) {
        appendLine()
        appendLine("[$name]")
    }

    private fun StringBuilder.line(key: String, value: String) {
        appendLine("$key: $value")
    }

    private fun step(s: SetupStep) = "${s.ordinal + 1} ${s.name}"

    private fun shell(s: BuiltinShellStatus): String = when (s) {
        is BuiltinShellStatus.Failed -> "Failed (reason=${s.reason}, fix=${s.fix})"
        else -> s.toString()
    }

    private fun capability(c: RecoveryCapability) = when (c) {
        RecoveryCapability.Accessibility -> "auto accessibility"
        RecoveryCapability.BatteryWhitelist -> "battery whitelist"
        RecoveryCapability.RestoreAfterReboot -> "restore after reboot"
        RecoveryCapability.AutoWirelessDebugging -> "auto wireless debugging"
    }

    private fun consent(r: RecoveryConsent, c: RecoveryCapability) =
        (if (r.wants(c)) "on" else "off by user") + ", " + (if (r.isApplied(c)) "applied" else "not applied")

    private fun role(r: DiagnosticsSnapshot.RoleModel) = "${r.model} @ ${host(r.baseUrl)}"

    /** 地址只留主机名(路径、查询里可能有别的东西);解析不了就不写。 */
    fun host(url: String): String = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(unparsed host)"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    /** 通知频道的重要程度,按系统的取值:0 关、1 最低、2 低、3 默认、4 高。 */
    private fun importance(i: Int?) = when (i) {
        null -> "not created"
        0 -> "off"
        1 -> "min"
        2 -> "low"
        3 -> "default"
        4, 5 -> "high"
        else -> "$i"
    }

    fun ago(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 48 * 60 -> "${m / 60}h ago"
            else -> "${m / (24 * 60)}d ago"
        }
    }

    private const val MAX_ERROR = 200

    private val IP = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b(:\d{1,5})?""")
    /** 完整写法(至少五段)或带「::」的缩写;时间里的 12:30:05 不算。 */
    private val IPV6 = Regex("""\[?(?:\b(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}\b|[0-9A-Fa-f]{0,4}::[0-9A-Fa-f:]*[0-9A-Fa-f])(?:%\w+)?\]?(:\d{1,5})?""")
    private val PORT = Regex("""(?i)\bport\s*[=:]?\s*\d{2,5}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val SECRET_PAIR = Regex("""(?i)\b(api[_-]?key|key|token|secret|password|passwd|authorization|cookie)\b(\s*[=:]\s*)\S+""")

    /** 最后一遍:IP(带端口)、IPv6、端口号、邮箱、像凭据的「名=值」,再交给 [Redactor](Key、JWT、又长又杂的串)。 */
    fun sanitize(text: String): String {
        var out = IP.replace(text, "<ip>")
        out = IPV6.replace(out, "<ip>")
        out = PORT.replace(out, "port <n>")
        out = EMAIL.replace(out, "<email>")
        out = SECRET_PAIR.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + Redactor.MASK }
        return Redactor.pageData(out)
    }
}
