package com.wangzan101.mika.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceSchedulerTest {
    @Test fun simultaneousAppClaimsHaveExactlyOneOwnerAndReleaseFreesIt() {
        val resources = ResourceScheduler()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val wins = AtomicInteger()
        for (owner in listOf("a", "b")) Thread {
            start.await()
            if (resources.reserveApp("com.example", owner, { true }, { true })) wins.incrementAndGet()
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
        val first = resources.appHolder("com.example")!!
        resources.releaseOwner(first)
        assertTrue(resources.reserveApp("com.example", if (first == "a") "b" else "a", { true }, { true }))
    }

    @Test fun clipboardLeaseSerializesTwoTasks() {
        val resources = ResourceScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val first = Thread {
            resources.clipboard.lock()
            try { entered.countDown(); release.await() } finally { resources.clipboard.unlock() }
        }
        val second = Thread {
            resources.clipboard.lock()
            try { secondEntered.set(true) } finally { resources.clipboard.unlock(); secondFinished.countDown() }
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        second.start()
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(secondEntered.get())
        release.countDown()
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        first.join(5_000)
    }
}
