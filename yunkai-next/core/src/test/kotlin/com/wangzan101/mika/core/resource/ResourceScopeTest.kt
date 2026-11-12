package com.wangzan101.mika.core.resource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ResourceScopeTest {
    private val released = CopyOnWriteArrayList<String>()

    private inner class Res(val name: String) {
        fun release() { released += name }
    }

    /** 建好之后、交回调用方之前任务被取消:换线程的 withContext 交回时抛取消,资源仍归作用域,收尾时放掉。 */
    @Test
    fun cancelledRightAfterCreationStillReleased() = runBlocking {
        val scope = ResourceScope()
        val outer = launch(Dispatchers.Default) {
            val task = currentCoroutineContext().job
            scope.acquire(Dispatchers.IO, Res::release) { Res("display").also { task.cancel() } }
            fail("cancellation should surface after creation")
        }
        outer.join()
        assertTrue(outer.isCancelled)
        scope.close()
        assertEquals(listOf("display"), released)
    }

    /** 对照:旧写法(建完再记到字段)在同一时机丢掉资源。 */
    @Test
    fun assignAfterReturnLosesResource() = runBlocking {
        var field: Res? = null
        val outer = launch(Dispatchers.Default) {
            val task = currentCoroutineContext().job
            field = withContext(Dispatchers.IO) { Res("display").also { task.cancel() } }
        }
        outer.join()
        assertEquals(null, field)
    }

    @Test
    fun normalPathReleasesOnceInReverseOrder() = runBlocking {
        val scope = ResourceScope()
        val a = scope.acquire(Dispatchers.IO, Res::release) { Res("a") }
        scope.acquire(Dispatchers.Default, Res::release) { Res("b") }
        scope.acquire(Dispatchers.IO, Res::release) { Res("c") }
        assertEquals("a", a.name)
        assertEquals(emptyList<String>(), released)
        scope.close()
        scope.close()
        assertEquals(listOf("c", "b", "a"), released)
    }

    /** 放掉也在登记时给的上下文里做(如只能在主线程关的东西)。 */
    @Test
    fun releaseRunsInAcquireContext() = runBlocking {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "owner-thread") }
        try {
            val scope = ResourceScope()
            var thread: String? = null
            scope.acquire(pool.asCoroutineDispatcher(), { _: Res -> thread = Thread.currentThread().name }) { Res("web") }
            scope.close()
            assertTrue(thread.toString(), thread!!.startsWith("owner-thread"))
        } finally {
            pool.shutdown()
        }
    }

    /** 一个放不掉,其余照放;第一个错误在全部放完后抛出。 */
    @Test
    fun failingReleaseDoesNotSkipOthers() = runBlocking {
        val scope = ResourceScope()
        scope.acquire(Dispatchers.IO, Res::release) { Res("a") }
        scope.acquire(Dispatchers.IO, { _: Res -> throw IllegalStateException("boom") }) { Res("b") }
        scope.acquire(Dispatchers.IO, Res::release) { Res("c") }
        try {
            scope.close()
            fail("release failure should surface")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("c", "a"), released)
    }

    /** 作用域已关:再建出来的立即放掉,调用方拿到错误而不是一个没人管的资源。 */
    @Test
    fun acquireAfterCloseReleasesImmediately() = runBlocking {
        val scope = ResourceScope()
        scope.close()
        try {
            scope.acquire(Dispatchers.IO, Res::release) { Res("late") }
            fail("closed scope should refuse")
        } catch (e: IllegalStateException) {
            assertTrue(e !is CancellationException)
        }
        assertEquals(listOf("late"), released)
    }

    /** 多个线程同时登记、同时关:每个恰好放一次。 */
    @Test
    fun concurrentAcquireAndCloseReleaseEachOnce() = runBlocking {
        repeat(50) { round ->
            released.clear()
            val scope = ResourceScope()
            val made = CopyOnWriteArrayList<String>()
            val jobs = (0 until 8).map { i ->
                async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                    runCatching { scope.acquire(Dispatchers.IO, Res::release) { Res("$round-$i").also { made += it.name } } }
                }
            }
            val closers = (0 until 3).map { async(Dispatchers.Default) { scope.close() } }
            jobs.awaitAll()
            closers.awaitAll()
            scope.close()
            assertEquals(made.sorted(), released.sorted())
        }
    }

    @Test
    fun acquireReturnsCreatedObject() = runBlocking {
        val scope = ResourceScope()
        val r = Res("x")
        assertSame(r, scope.acquire(Dispatchers.IO, Res::release) { r })
        scope.close()
    }
}
