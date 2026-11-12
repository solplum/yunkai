package com.wangzan101.mika.memory

import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.settings.AesGcmKeyCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.KeyGenerator

class EncryptedMemoryFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = 1_761_200_000_000L
    private fun file(k: javax.crypto.SecretKey = key) = EncryptedMemoryFile(tmp.root, AesGcmKeyCipher { k }) { now }
    private val bin get() = File(tmp.root, "memory/memory.bin")
    private fun memDir() = File(tmp.root, "memory")

    private val items = listOf(
        MemoryItem(
            id = "a", kind = MemoryKind.preference, content = "外卖不要香菜", keywords = listOf("外卖"),
            source = MemorySource(type = "user_said", quote = "以后外卖都不要香菜", conversationId = "c1", atMs = 5L),
            status = MemoryStatus.active, pinned = true, createdAt = 1L, updatedAt = 2L, lastUsedAt = 3L, useCount = 4,
            replaces = "外卖少放香菜",
        ),
        MemoryItem(
            id = "b", kind = MemoryKind.procedure, content = "点外卖先比价", keywords = emptyList(),
            source = MemorySource(type = "manual", atMs = 6L), status = MemoryStatus.pending, createdAt = 7L, updatedAt = 7L,
        ),
    )

    private fun brokenFiles() = memDir().listFiles { f -> f.name.startsWith("memory.bin.broken-") }.orEmpty().toList()

    @Test fun roundTrip() {
        assertEquals(emptyList<MemoryItem>(), file().load()) // 没有文件 = 空,不算坏
        file().save(items)
        val f = file()
        assertEquals(items, f.load())
        assertNull(f.brokenNotice.value)
    }

    @Test fun fileHasNoPlaintext() {
        file().save(items)
        val bytes = bin.readBytes()
        val needle = "香菜".toByteArray(Charsets.UTF_8)
        assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
    }

    @Test fun undecryptableMovedAsideNotOverwritten() {
        file().save(items)
        val original = bin.readBytes()
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val f = file(other)
        assertEquals(emptyList<MemoryItem>(), f.load())
        val broken = brokenFiles().single()
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now)
        assertEquals("memory.bin.broken-$stamp", broken.name)
        assertArrayEquals(original, broken.readBytes())
        assertFalse(bin.exists())
        assertEquals(broken.name, f.brokenNotice.value)
        // 之后的写入不碰另存的文件
        f.save(items.take(1))
        assertArrayEquals(original, broken.readBytes())
        assertEquals(items.take(1), file(other).load())
    }

    @Test fun transientKeystoreErrorRethrownFileKept() {
        file().save(items)
        val original = bin.readBytes()
        val busy = object : com.wangzan101.mika.settings.KeyCipher {
            override fun encrypt(plain: ByteArray) = throw java.security.KeyStoreException("busy")
            override fun decrypt(data: ByteArray) = throw java.security.KeyStoreException("busy")
        }
        val f = EncryptedMemoryFile(tmp.root, busy) { now }
        assertTrue(runCatching { f.load() }.exceptionOrNull() is java.security.KeyStoreException)
        assertTrue(brokenFiles().isEmpty())
        assertArrayEquals(original, bin.readBytes())
        assertNull(f.brokenNotice.value)
        assertEquals(items, file().load())
    }

    @Test fun corruptJsonMovedAside() {
        memDir().mkdirs()
        val garbage = AesGcmKeyCipher { key }.encrypt("{not json".toByteArray())
        bin.writeBytes(garbage)
        val f = file()
        assertEquals(emptyList<MemoryItem>(), f.load())
        assertNotNull(f.brokenNotice.value)
        assertArrayEquals(garbage, brokenFiles().single().readBytes())
    }

    @Test fun atomicWriteLeavesNoTmp() {
        val f = file()
        f.save(items)
        f.save(items.take(1))
        assertEquals(listOf("memory.bin"), memDir().list()!!.toList())
        assertEquals(items.take(1), file().load())
    }

    @Test fun wipeRemovesFile() {
        file().save(items)
        file(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()).load() // 产生一份另存
        file().save(items)
        val f = file()
        f.wipe()
        assertTrue(memDir().list().orEmpty().isEmpty())
        assertNull(f.brokenNotice.value)
        assertEquals(emptyList<MemoryItem>(), file().load())
    }
}
