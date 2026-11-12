package com.wangzan101.mika.memory

import com.wangzan101.mika.core.memory.ProfileBackend
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 与普通记忆分开的用户画像文件；全量原子替换，保留被替代条目。 */
class FileProfileBackend(private val file: File, private val cipher: KeyCipher) : ProfileBackend {
    private val codec = ListSerializer(ProfileEntry.serializer())

    @Synchronized override fun read(): List<ProfileEntry> =
        if (!file.exists()) emptyList() else Json.decodeFromString(codec, cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8))

    @Synchronized override fun write(entries: List<ProfileEntry>) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(cipher.encrypt(Json.encodeToString(codec, entries).toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
