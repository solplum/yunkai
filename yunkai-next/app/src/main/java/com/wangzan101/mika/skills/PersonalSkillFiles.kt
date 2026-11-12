package com.wangzan101.mika.skills

import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.core.skills.SkillRevision
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 个人技能 `skills/personal/<name>/`、待收下草稿 `skills/pending/<id>/`,各含 `SKILL.md` + `meta.json`,明文
 * (沉淀时已去掉个人信息,便于查看与调试)。SKILL.md 读不出或缺失时照样列出,由技能目录报「坏了」,不静默消失。
 */
class PersonalSkillFiles(dir: File) : PersonalSkills {
    private val personalDir = File(dir, "skills/personal")
    private val pendingDir = File(dir, "skills/pending")
    private val archiveDir = File(dir, "skills/archive")
    private val journal = File(dir, "skills/changes.jsonl")

    @Synchronized override fun list(): List<PersonalSkill> = readAll(personalDir)

    @Synchronized override fun get(name: String): PersonalSkill? =
        if (PersonalSkills.NAME.matches(name)) read(File(personalDir, name), name) else null

    @Synchronized override fun put(s: PersonalSkill) {
        require(PersonalSkills.NAME.matches(s.name)) { "invalid skill name: ${s.name}" }
        archive(s.name, "put")
        write(File(personalDir, s.name), s)
    }

    @Synchronized override fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill? {
        val next = get(name)?.let(transform) ?: return null
        require(next.name == name) { "update must keep the name" }
        archive(name, "update")
        write(File(personalDir, name), next)
        return next
    }

    @Synchronized override fun remove(name: String) {
        if (PersonalSkills.NAME.matches(name)) {
            archive(name, "remove")
            File(personalDir, name).deleteRecursively()
        }
    }

    @Synchronized override fun pending(): List<PersonalSkill> = drafts().values.toList()

    @Synchronized override fun drafts(): Map<String, PersonalSkill> =
        pendingDir.listFiles { f -> f.isDirectory && ID.matches(f.name) }.orEmpty().sortedBy { it.name }
            .mapNotNull { d -> read(d, null)?.let { d.name to it.copy(status = SkillStatus.pending) } }.toMap()

    @Synchronized override fun putPending(id: String, s: PersonalSkill) {
        require(ID.matches(id)) { "invalid draft id: $id" }
        require(PersonalSkills.NAME.matches(s.name)) { "invalid skill name: ${s.name}" }
        write(File(pendingDir, id), s.copy(status = SkillStatus.pending))
    }

    @Synchronized override fun approve(id: String, editedMarkdown: String?): PersonalSkill? {
        if (!ID.matches(id)) return null
        val draftDir = File(pendingDir, id)
        val draft = read(draftDir, null) ?: return null
        // 更新草稿收下后覆盖它指向的那份已有技能
        val name = draft.replacesDraftOf?.takeIf { PersonalSkills.NAME.matches(it) } ?: draft.name
        if (draft.replacesDraftOf != null && draft.baseHash != null &&
            get(name)?.markdown?.let { PersonalSkills.fingerprint(it) } != draft.baseHash) return null
        // 头部名字跟存放的名字走:更新草稿、用户改过的全文都可能对不上
        val markdown = PersonalSkills.withName(editedMarkdown ?: draft.markdown, name)
        val skill = draft.copy(name = name, markdown = markdown, status = SkillStatus.trial, replacesDraftOf = null, baseHash = null)
        archive(name, "approve")
        write(File(personalDir, name), skill)
        draftDir.deleteRecursively()
        return skill
    }

    @Synchronized override fun reject(id: String) {
        if (ID.matches(id)) {
            val d = File(pendingDir, id)
            if (d.exists()) {
                val revision = UUID.randomUUID().toString()
                d.copyRecursively(File(archiveDir, revision), overwrite = true)
                log(revision, "reject", id, false)
                d.deleteRecursively()
            }
        }
    }

    /** 撤销一次删除或覆盖：只接受本地改动日志中的旧版本，不从外部路径读。 */
    @Synchronized override fun revisions(name: String): List<SkillRevision> {
        if (!PersonalSkills.NAME.matches(name)) return emptyList()
        return journal.takeIf { it.exists() }?.useLines { lines -> lines.mapNotNull { line ->
            val row = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            if ((row["name"] as? JsonPrimitive)?.contentOrNull != name || row["hadPrevious"] != JsonPrimitive(true)) return@mapNotNull null
            SkillRevision((row["revision"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (row["operation"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (row["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }.toList() }?.asReversed() ?: emptyList()
    }

    @Synchronized override fun restore(revision: String): Boolean {
        if (!ID.matches(revision)) return false
        val row = journal.takeIf { it.exists() }?.useLines { lines -> lines.mapNotNull {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }.firstOrNull { (it["revision"] as? JsonPrimitive)?.contentOrNull == revision } } ?: return false
        if (row["hadPrevious"] != JsonPrimitive(true)) return false
        val name = (row["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(PersonalSkills.NAME::matches) ?: return false
        val before = File(archiveDir, revision)
        if (!before.isDirectory || read(before, name) == null) return false
        archive(name, "restore")
        before.copyRecursively(File(personalDir, name), overwrite = true)
        log(UUID.randomUUID().toString(), "restored", name, true)
        return true
    }

    private fun archive(name: String, operation: String) {
        val previous = File(personalDir, name)
        val revision = UUID.randomUUID().toString()
        val existed = previous.isDirectory
        if (existed) previous.copyRecursively(File(archiveDir, revision), overwrite = true)
        log(revision, operation, name, existed)
    }

    private fun log(revision: String, operation: String, name: String, existed: Boolean) {
        journal.parentFile.mkdirs()
        journal.appendText(buildJsonObject {
            put("revision", revision); put("at", System.currentTimeMillis()); put("operation", operation)
            put("name", name); put("hadPrevious", existed)
        }.toString() + "\n")
    }

    private fun readAll(root: File): List<PersonalSkill> =
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { read(it, null) }

    /** [expectedName] 为 null 时名字取 meta.json(草稿目录名是编号),缺了就用目录名。 */
    private fun read(d: File, expectedName: String?): PersonalSkill? {
        if (!d.isDirectory) return null
        val meta = try {
            File(d, META).takeIf { it.exists() }?.readText()?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (e: SerializationException) {
            null
        }
        fun str(k: String) = (meta?.get(k) as? JsonPrimitive)?.contentOrNull
        val md = File(d, SKILL_FILE).takeIf { it.exists() }?.readText() ?: ""
        return PersonalSkill(
            name = expectedName ?: str("name") ?: d.name,
            markdown = md,
            status = str("status")?.let { s -> SkillStatus.entries.find { it.name == s } } ?: SkillStatus.active,
            learnedFrom = str("learnedFrom"),
            replacesDraftOf = str("replacesDraftOf"),
            createdAt = (meta?.get("createdAt") as? JsonPrimitive)?.longOrNull ?: 0L,
            outcomes = (meta?.get("outcomes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            baseHash = str("baseHash"),
        )
    }

    private fun write(d: File, s: PersonalSkill) {
        d.mkdirs()
        val meta = buildJsonObject {
            put("name", s.name)
            put("status", s.status.name)
            s.learnedFrom?.let { put("learnedFrom", it) }
            s.replacesDraftOf?.let { put("replacesDraftOf", it) }
            put("createdAt", s.createdAt)
            put("outcomes", JsonArray(s.outcomes.map(::JsonPrimitive)))
            s.baseHash?.let { put("baseHash", it) }
        }
        atomicWrite(File(d, SKILL_FILE), s.markdown)
        atomicWrite(File(d, META), meta.toString())
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val SKILL_FILE = "SKILL.md"
        const val META = "meta.json"
        val ID = Regex("[A-Za-z0-9_-]+")
    }
}
