// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika.cap.phone.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList
import com.wangzan101.mika.shell.ShellProbeActivity

/** 探针页发来的一条记录。 */
data class ProbeEvent(
    val kind: String,
    val display: Int,
    val task: Int,
    val pid: Int,
    val action: Int,
    val x: Int,
    val y: Int,
    val keyCode: Int,
)

/** 在 Mika 进程里收 [ShellProbeActivity] 的广播。用完 close。 */
class ShellProbe(private val context: Context) : AutoCloseable {
    private val events = CopyOnWriteArrayList<ProbeEvent>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            events += ProbeEvent(
                kind = i.getStringExtra(KIND).orEmpty(),
                display = i.getIntExtra(DISPLAY, -1),
                task = i.getIntExtra(TASK, -1),
                pid = i.getIntExtra(PID, -1),
                action = i.getIntExtra(ACTION, -1),
                x = i.getIntExtra(X, -1),
                y = i.getIntExtra(Y, -1),
                keyCode = i.getIntExtra(KEY_CODE, -1),
            )
        }
    }

    init {
        val filter = IntentFilter(BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun all(): List<ProbeEvent> = events.toList()

    /** 等到最近一条满足条件的记录;超时返回 null。 */
    fun await(timeoutMs: Long = 10_000, match: (ProbeEvent) -> Boolean): ProbeEvent? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            events.lastOrNull(match)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            Thread.sleep(50)
        }
    }

    fun clear() = events.clear()

    override fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object {
        const val BROADCAST = ShellProbeActivity.BROADCAST
        val COMPONENT get() = "${com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE}/${ShellProbeActivity::class.java.name}"
        const val LINK = "mika-parity://probe"

        const val RESUMED = ShellProbeActivity.RESUMED
        const val FOCUSED = ShellProbeActivity.FOCUSED
        const val TOUCH = ShellProbeActivity.TOUCH
        const val KEY = ShellProbeActivity.KEY

        private const val KIND = ShellProbeActivity.KIND
        private const val DISPLAY = ShellProbeActivity.DISPLAY
        private const val TASK = ShellProbeActivity.TASK
        private const val PID = ShellProbeActivity.PID
        private const val ACTION = ShellProbeActivity.ACTION
        private const val X = ShellProbeActivity.X
        private const val Y = ShellProbeActivity.Y
        private const val KEY_CODE = ShellProbeActivity.KEY_CODE
    }
}
