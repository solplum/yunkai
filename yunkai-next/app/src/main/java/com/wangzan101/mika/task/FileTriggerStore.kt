package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.MemoryTriggerStore
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerStore
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** 触发器和时段账本同文件原子替换；认领时段落盘成功后才返回给派发器。 */
class FileTriggerStore(private val file: File) : TriggerStore {
    private val memory = MemoryTriggerStore(if (file.isFile) runCatching {
        Json.decodeFromString<TriggerSnapshot>(file.readText())
    }.getOrElse {
        file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
        TriggerSnapshot(emptyList(), emptyList())
    } else TriggerSnapshot(emptyList(), emptyList()))

    @Synchronized override fun upsert(trigger: TriggerDefinition) { memory.upsert(trigger); save() }
    @Synchronized override fun remove(id: String) { memory.remove(id); save() }
    @Synchronized override fun get(id: String) = memory.get(id)
    @Synchronized override fun all() = memory.all()
    @Synchronized override fun windows() = memory.windows()
    @Synchronized override fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean): List<TriggerWindow> =
        memory.claimDue(nowMs, eligible).also { if (it.isNotEmpty()) save() }
    @Synchronized override fun claimNow(id: String, nowMs: Long): TriggerWindow? =
        memory.claimNow(id, nowMs)?.also { save() }
    @Synchronized override fun mark(key: String, state: TriggerWindowState, reason: String?): Boolean =
        memory.mark(key, state, reason).also { if (it) save() }
    @Synchronized override fun retireDeliveredOneShots() { memory.retireDeliveredOneShots(); save() }

    private fun save() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(Json.encodeToString(memory.snapshot()).toByteArray()); out.fd.sync() }
        check(temp.renameTo(file)) { "trigger store rename failed" }
    }
}
