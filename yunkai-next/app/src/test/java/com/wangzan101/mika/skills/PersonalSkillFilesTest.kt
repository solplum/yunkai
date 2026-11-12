package com.wangzan101.mika.skills

import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PersonalSkillFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files() = PersonalSkillFiles(tmp.root)
    private fun skill(name: String, body: String = "正文", status: SkillStatus = SkillStatus.active, replaces: String? = null) =
        PersonalSkill(name, "---\nname: $name\n---\n$body", status, "run-7", replaces, 123L, listOf("pass", "fail"))

    @Test fun roundTripActiveAndPending() {
        val a = skill("lao-ma-msg")
        val s = skill("my-trip", status = SkillStatus.suspended)
        files().apply { put(a); put(s); putPending("d1", skill("draft-one")) }
        val f = files()
        assertEquals(listOf(a, s), f.list())
        assertEquals(a, f.get("lao-ma-msg"))
        assertNull(f.get("nope"))
        assertEquals(listOf(skill("draft-one", status = SkillStatus.pending)), f.pending())
        assertTrue(File(tmp.root, "skills/personal/lao-ma-msg/SKILL.md").exists())
        assertTrue(File(tmp.root, "skills/pending/d1/meta.json").exists())
        f.remove("my-trip")
        assertEquals(listOf(a), files().list())
        assertTrue(runCatching { f.put(skill("../evil")) }.isFailure)
    }

    @Test fun approveMovesDraftAndAppliesEdit() {
        val f = files()
        f.putPending("d1", skill("draft-one"))
        val got = f.approve("d1", editedMarkdown = "---\nname: draft-one\n---\n改过的正文")!!
        assertEquals(SkillStatus.trial, got.status)
        assertEquals(listOf(got), files().list())
        assertEquals("---\nname: draft-one\n---\n改过的正文", files().get("draft-one")!!.markdown)
        assertFalse(File(tmp.root, "skills/pending/d1").exists())
        assertNull(f.approve("d1"))
        // 更新草稿覆盖已有技能,不新增一份
        f.putPending("d2", skill("draft-two", body = "新版本", replaces = "draft-one"))
        f.approve("d2")
        assertEquals(listOf("draft-one"), files().list().map { it.name })
        assertTrue(files().get("draft-one")!!.markdown.endsWith("新版本"))
        assertNull(files().get("draft-one")!!.replacesDraftOf)
    }

    @Test fun approveRewritesNameLine() {
        val f = files()
        f.put(skill("draft-one"))
        f.putPending("d2", skill("other-name", replaces = "draft-one"))
        f.approve("d2", editedMarkdown = "---\nname: typed-by-user\n---\n改过")
        assertEquals("---\nname: draft-one\n---\n改过", files().get("draft-one")!!.markdown)
    }

    @Test fun staleUpdateDraftDoesNotOverwriteChangedOriginal() {
        val f = files()
        val original = skill("draft-one", body = "原文")
        f.put(original)
        f.putPending("d2", skill("other-name", replaces = "draft-one")
            .copy(baseHash = PersonalSkills.fingerprint(original.markdown)))
        f.put(skill("draft-one", body = "别人改过"))
        assertNull(f.approve("d2"))
        assertTrue(f.get("draft-one")!!.markdown.endsWith("别人改过"))
        assertEquals(1, f.pending().size)
    }

    @Test fun rejectDeletesDraft() {
        val f = files()
        f.putPending("d1", skill("draft-one"))
        f.reject("d1")
        assertTrue(f.pending().isEmpty())
        assertFalse(File(tmp.root, "skills/pending/d1").exists())
        assertTrue(f.list().isEmpty())
    }

    @Test fun overwrittenSkillCanBeRestoredFromAppendOnlyJournal() {
        val f = files()
        f.put(skill("my-trip", body = "旧版本"))
        f.put(skill("my-trip", body = "新版本"))
        val lines = File(tmp.root, "skills/changes.jsonl").readLines()
        val revision = (Json.parseToJsonElement(lines.last()) as JsonObject)["revision"] as JsonPrimitive
        assertTrue(f.restore(revision.content))
        assertTrue(files().get("my-trip")!!.markdown.endsWith("旧版本"))
        assertTrue(File(tmp.root, "skills/changes.jsonl").readLines().size > lines.size)
        f.remove("my-trip")
        assertTrue(File(tmp.root, "skills/archive").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun missingSkillFileStillListed() {
        files().put(skill("lao-ma-msg"))
        File(tmp.root, "skills/personal/lao-ma-msg/SKILL.md").delete()
        assertEquals("", files().get("lao-ma-msg")!!.markdown) // 技能目录据此报「坏了」
    }
}
