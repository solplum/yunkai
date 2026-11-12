package com.wangzan101.mika.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {
    private class Backend : ProfileBackend {
        var rows = emptyList<ProfileEntry>()
        override fun read() = rows
        override fun write(entries: List<ProfileEntry>) { rows = entries }
    }

    @Test fun addReplaceDeleteAndFrozenSnapshot() {
        val db = Backend()
        var next = 0
        val store = ProfileStore(db, { 100L }, { (++next).toString() }, capacity = 2)
        assertTrue(store.add("用户喜欢安静的酒店。") is ProfileChange.Applied)
        val frozen = store.snapshot()
        assertTrue(store.replace("安静", "交通便利") is ProfileChange.Applied)
        assertEquals("用户喜欢安静的酒店。", frozen.single().text)
        assertEquals("用户喜欢交通便利的酒店。", store.snapshot().single().text)
        assertEquals(ProfileStatus.superseded, db.rows.first().status)
        assertTrue(store.delete("交通便利") is ProfileChange.Applied)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun fullAndAmbiguousNeverEvict() {
        val db = Backend()
        var next = 0
        val store = ProfileStore(db, { 1L }, { (++next).toString() }, capacity = 2)
        store.add("用户喜欢清淡的饭菜。")
        store.add("用户喜欢清淡的饮料。")
        val full = store.add("用户不吃辣。") as ProfileChange.Rejected
        assertEquals("profile_full", full.code)
        assertEquals(2, full.existing.size)
        assertEquals("match_not_unique", (store.replace("清淡", "咸") as ProfileChange.Rejected).code)
        assertEquals(2, store.snapshot().size)
    }
}
