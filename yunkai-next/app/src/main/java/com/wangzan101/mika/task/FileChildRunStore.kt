package com.wangzan101.mika.task

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildRecord
import com.wangzan101.mika.core.child.ChildRunStore
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** 子 Run 的独立账本；重启时进行中的子 Run 变成未知，绝不自动重跑或给用户单独投递。 */
class FileChildRunStore(private val file: File) : ChildRunStore {
    private val records = linkedMapOf<String, ChildRecord>()

    init {
        if (file.isFile) runCatching { Json.decodeFromString<List<ChildRecord>>(file.readText()) }.onSuccess { loaded ->
            loaded.forEach { records[it.id] = if (it.phase == ChildPhase.Running) it.copy(phase = ChildPhase.Unknown) else it }
            if (loaded.any { it.phase == ChildPhase.Running }) persist()
        }.onFailure { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
    }

    @Synchronized override fun save(record: ChildRecord) { records[record.id] = record; persist() }
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun all() = records.values.toList()

    private fun persist() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(Json.encodeToString(records.values.toList()).toByteArray()); out.fd.sync() }
        check(temp.renameTo(file)) { "child run store rename failed" }
    }
}
