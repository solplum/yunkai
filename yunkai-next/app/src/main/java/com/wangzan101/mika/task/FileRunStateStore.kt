package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import android.util.Log

/** App 私有目录里的运行账本；每次转阶段后原子替换，异常重启不会把半份 JSON 当成记录。 */
class FileRunStateStore(private val file: File) : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()

    init {
        if (file.isFile) runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.forEach { item ->
                val o = item.jsonObject
                val id = RunId(o.getValue("id").jsonPrimitive.content)
                records[id] = StoredRun(
                    id, o.getValue("threadId").jsonPrimitive.content, o.getValue("goal").jsonPrimitive.content,
                    phase(o.getValue("phase").jsonPrimitive.content),
                    o["reply"]?.jsonPrimitive?.contentOrNull, o["deliveryId"]?.jsonPrimitive?.contentOrNull,
                    o["roundInProgress"]?.jsonPrimitive?.content == "true",
                    o["triggerWindowKey"]?.jsonPrimitive?.contentOrNull,
                    o["runSucceeded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                )
            }
        }.onFailure {
            Log.e("MikaRunStore", "run store could not be read; preserving damaged file", it)
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            records.clear()
        }
    }

    @Synchronized override fun save(record: StoredRun) {
        records[record.id] = record
        file.parentFile?.mkdirs()
        val json = JsonArray(records.values.map { r -> buildJsonObject {
            put("id", r.id.value); put("threadId", r.threadId); put("goal", r.goal)
            put("phase", code(r.phase)); put("reply", r.reply)
            put("deliveryId", r.deliveryId)
            put("roundInProgress", r.roundInProgress)
            put("triggerWindowKey", r.triggerWindowKey)
            put("runSucceeded", r.runSucceeded)
        } }).toString()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(temp.renameTo(file)) { "run store rename failed" }
    }

    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()

    private fun code(phase: RunPhase): String = when (phase) {
        RunPhase.Queued -> "queued"
        RunPhase.Preparing -> "preparing"
        RunPhase.Running -> "running"
        is RunPhase.WaitingUser -> "waiting_user"
        is RunPhase.Parked -> "parked"
        is RunPhase.Paused -> "paused"
        RunPhase.Finishing -> "finishing"
        RunPhase.Aftercare -> "aftercare"
        RunPhase.Ended -> "ended"
        RunPhase.Interrupted -> "interrupted"
    }

    private fun phase(code: String): RunPhase = when (code) {
        "queued" -> RunPhase.Queued
        "preparing" -> RunPhase.Preparing
        "running" -> RunPhase.Running
        "waiting_user" -> RunPhase.WaitingUser()
        "parked" -> RunPhase.Parked("")
        "paused" -> RunPhase.Paused("")
        "finishing" -> RunPhase.Finishing
        "aftercare" -> RunPhase.Aftercare
        "ended" -> RunPhase.Ended
        else -> RunPhase.Interrupted
    }
}
