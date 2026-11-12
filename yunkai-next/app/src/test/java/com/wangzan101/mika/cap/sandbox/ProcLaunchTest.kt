package com.wangzan101.mika.cap.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 起进程、收输出、兜底时限:用宿主的 /bin/sh 跑(不经启动器与 PRoot)。 */
class ProcLaunchTest {
    private val env = mapOf("PATH" to "/usr/bin:/bin")

    @Test fun exitCodeAndMergedOutput() = runBlocking {
        val o = AndroidSandbox.launch(listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 3"), env, 10_000)
        assertEquals(3, o.exitCode)
        assertFalse(o.timedOut)
        assertEquals("out\nerr\n", String(o.output))
    }

    @Test fun environmentIsOnlyWhatWasGiven() = runBlocking {
        val o = AndroidSandbox.launch(listOf("/bin/sh", "-c", "env"), env + ("MIKA_CONVERSATION" to "c1"), 10_000)
        val keys = String(o.output).lines().filter { it.isNotBlank() }.map { it.substringBefore('=') }.toSet()
        assertTrue(keys.toString(), keys.containsAll(setOf("PATH", "MIKA_CONVERSATION")))
        assertFalse(keys.toString(), "HOME" in keys || "LD_PRELOAD" in keys)
    }

    @Test fun outputBoundedAndDrained() = runBlocking {
        val o = AndroidSandbox.launch(listOf("/bin/sh", "-c", "head -c 3000000 /dev/zero"), env, 30_000)
        assertEquals(0, o.exitCode)
        assertEquals(ProcRunner.OUTPUT_LIMIT, o.output.size)
        assertEquals(3_000_000L - ProcRunner.OUTPUT_LIMIT, o.droppedBytes)
    }

    @Test fun fallbackLimitKillsHungProcess() = runBlocking {
        val begin = System.nanoTime()
        val o = AndroidSandbox.launch(listOf("/bin/sh", "-c", "echo started; exec sleep 30"), env, 500)
        assertTrue(o.timedOut)
        assertTrue("took ${(System.nanoTime() - begin) / 1_000_000} ms", (System.nanoTime() - begin) / 1_000_000 < 5_000)
        assertEquals("started\n", String(o.output))
    }

    private fun alive(pid: Long) = ProcessBuilder("/bin/kill", "-0", pid.toString()).start().waitFor() == 0

    private fun awaitGone(pid: Long, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (!alive(pid)) return true; Thread.sleep(50) }
        return !alive(pid)
    }

    @Test fun cancelKillsProcess() = runBlocking {
        val pidFile = File.createTempFile("pid", ".txt").apply { delete() }
        val job = launch(Dispatchers.Default) {
            AndroidSandbox.launch(listOf("/bin/sh", "-c", "echo \$\$ > ${pidFile.path}; exec sleep 30"), env, 60_000)
        }
        val end = System.currentTimeMillis() + 5_000
        while (pidFile.length() == 0L && System.currentTimeMillis() < end) delay(20)
        val pid = pidFile.readText().trim().toLong()
        assertTrue(alive(pid))
        job.cancelAndJoin()
        assertTrue("process $pid survived cancellation", awaitGone(pid, 5_000))
    }

    @Test fun cancelDuringStartLeavesNoOrphan() = runBlocking {
        // 取消恰好落在 start() 返回之前:进程已经起来了,也必须被杀掉
        val pidFile = File.createTempFile("pid", ".txt").apply { delete() }
        lateinit var job: Job
        job = launch(Dispatchers.Default) {
            AndroidSandbox.launch(listOf("/bin/sh", "-c", "echo \$\$ > ${pidFile.path}; exec sleep 30"), env, 60_000) { pb ->
                pb.start().also {
                    val end = System.currentTimeMillis() + 5_000
                    while (pidFile.length() == 0L && System.currentTimeMillis() < end) Thread.sleep(20)
                    job.cancel()
                }
            }
        }
        job.join()
        val pid = pidFile.readText().trim().toLong()
        assertTrue("process $pid survived", awaitGone(pid, 5_000))
    }
}
