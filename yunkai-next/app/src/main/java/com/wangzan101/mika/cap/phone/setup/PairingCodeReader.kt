package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PairingScreen
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.PairingScreenReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 配对时借 Mika 的无障碍服务读出系统配对窗口上的配对码,读到就直接配对,用户不用去通知里填。
 * 只在 [PairingCoordinator] 开着的这段时间里跑,只看系统设置的窗口;读到的码不写日志。
 * 无障碍没开就什么也不做,配对照样走通知。
 */
internal object PairingCodeReader {
    /** 窗口内容常常分几次刷出来,等它稳一下再读。 */
    private const val SETTLE_MS = 300L
    private const val MAX_NODES = 400

    suspend fun run(context: Context, control: BuiltinShellControl, coordinator: PairingCoordinator) {
        val settings = SystemPickers.settingsPackage(context.packageManager) ?: return
        val tried = mutableSetOf<String>()
        ScreenAccessibility.instance.collectLatest { service ->
            service ?: return@collectLatest
            service.eventsFor(Display.DEFAULT_DISPLAY)
                .filter { it.packageName?.toString() == settings }
                .map { }
                .onStart { emit(Unit) }
                .conflate()
                .collect {
                    delay(SETTLE_MS)
                    val portFound = control.pairingDiscovery.value is PairingDiscovery.Found
                    val attempt = readings(service, settings).firstNotNullOfOrNull {
                        PairingScreen.attempt(it, portFound, PhoneSetupFlow.effectiveStatus(control.status.value, coordinator.repairing.value), tried)
                    } ?: return@collect
                    tried += attempt.code
                    coordinator.pair(attempt.code, attempt.port)
                }
        }
    }

    /** 每个系统设置窗口各读一次:配对码和端口要在同一个窗口里(配对窗口下面那页也有一个「IP:端口」)。 */
    private fun readings(service: ScreenAccessibility, settings: String): List<PairingScreenReading> =
        service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != settings) return@mapNotNull null
            PairingScreen.read(texts(root))
        }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }
}
