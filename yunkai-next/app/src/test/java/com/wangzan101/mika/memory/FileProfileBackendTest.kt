package com.wangzan101.mika.memory

import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.memory.ProfileStatus
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProfileBackendTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun encryptedRoundTripKeepsSupersededHistory() {
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain.reversedArray()
            override fun decrypt(data: ByteArray) = data.reversedArray()
        }
        val file = File(tmp.root, "memory/profile.bin")
        val store = FileProfileBackend(file, cipher)
        val rows = listOf(ProfileEntry("1", "用户喜欢安静的酒店。", 1, ProfileStatus.superseded, 2),
            ProfileEntry("2", "用户喜欢交通便利的酒店。", 2, ProfileStatus.active))
        store.write(rows)
        assertFalse(file.readText().contains("用户喜欢"))
        assertEquals(rows, FileProfileBackend(file, cipher).read())
    }
}
