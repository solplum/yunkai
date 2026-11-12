package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.util.Log
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryRecord
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.OutboxStore
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

/** 送达账本持久化；Attempting 在进程重启后是结果不明，不能自动重发。 */
class FileOutboxStore(private val file: File) : OutboxStore {
    private val records = linkedMapOf<String, DeliveryRecord>()

    init {
        if (file.isFile) runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.forEach { item ->
                val o = item.jsonObject
                val id = o.getValue("id").jsonPrimitive.content
                val threadId = o.getValue("threadId").jsonPrimitive.content
                val peer = ChannelPeer(o.getValue("channelId").jsonPrimitive.content,
                    o.getValue("peerId").jsonPrimitive.content, threadId)
                val text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val delivery: Delivery = when (o.getValue("kind").jsonPrimitive.content) {
                    "ask" -> Delivery.Ask(id, threadId, o.getValue("askId").jsonPrimitive.content,
                        Delivery.Ask.Kind.valueOf(o.getValue("askKind").jsonPrimitive.content), text)
                    "result" -> Delivery.Result(id, threadId, text,
                        o["restored"]?.jsonPrimitive?.content == "true", o["title"]?.jsonPrimitive?.contentOrNull ?: tr(R.string.notif_result_title))
                    "progress" -> Delivery.Progress(id, threadId, text)
                    else -> Delivery.Notice(id, threadId, text)
                }
                records[id] = DeliveryRecord(peer, delivery, DeliveryState.valueOf(o.getValue("state").jsonPrimitive.content))
            }
        }.onFailure {
            Log.e("MikaOutbox", "outbox could not be read; preserving damaged file", it)
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            records.clear()
        }
    }

    @Synchronized override fun get(id: String): DeliveryRecord? = records[id]
    @Synchronized override fun all(): List<DeliveryRecord> = records.values.toList()

    @Synchronized override fun save(record: DeliveryRecord) {
        records[record.delivery.id] = record
        file.parentFile?.mkdirs()
        val json = JsonArray(records.values.map { r -> buildJsonObject {
            val d = r.delivery
            put("id", d.id); put("threadId", d.threadId)
            put("channelId", r.peer.channelId); put("peerId", r.peer.peerId)
            put("state", r.state.name)
            when (d) {
                is Delivery.Ask -> {
                    put("kind", "ask"); put("askId", d.askId); put("askKind", d.kind.name)
                    put("text", d.text)
                }
                is Delivery.Result -> {
                    put("kind", "result"); put("text", d.text); put("restored", d.restored); put("title", d.title)
                }
                is Delivery.Progress -> { put("kind", "progress"); put("text", d.text) }
                is Delivery.Notice -> { put("kind", "notice"); put("text", d.text) }
            }
        } }).toString()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(temp.renameTo(file)) { "outbox rename failed" }
    }
}
