package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentMessageTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun photosAndFilesAreSplitOutOfTheWords() {
        val text = "帮我看看这两张图和合同\n附件:/workspace/inbox/IMG_1.jpg(1.2 MB)\n附件:/workspace/inbox/租房合同（草稿）.pdf(48 KB)\n附件:/workspace/inbox/b.HEIC(900 KB)"
        val p = AttachmentMessage.parse(text)
        assertEquals("帮我看看这两张图和合同", p.words)
        assertEquals(listOf("IMG_1.jpg", "b.HEIC"), p.photos.map { it.name })
        assertEquals(listOf("1.2 MB", "900 KB"), p.photos.map { it.size })
        assertEquals(listOf(AttachmentMessage.Item("/workspace/inbox/租房合同（草稿）.pdf", "48 KB")), p.files)
        assertTrue(p.hasAttachments)
    }

    /** 旧对话里存下的写法:名字里带括号、只有附件没有话、大小是字节。 */
    @Test fun oldMessagesParseTheSameWay() {
        val p = AttachmentMessage.parse("附件:/workspace/inbox/报表 (1).csv(2 KB)\n附件:/workspace/inbox/x(512 B)")
        assertEquals("", p.words)
        assertEquals(listOf("/workspace/inbox/报表 (1).csv", "/workspace/inbox/x"), p.files.map { it.path })
        assertEquals(listOf("2 KB", "512 B"), p.files.map { it.size })
        assertTrue(p.photos.isEmpty())
    }

    @Test fun linesThatOnlyLookLikeAttachmentsStayInTheWords() {
        for (t in listOf("附件:/etc/passwd(1 KB)", "附件:/workspace/inbox/a.pdf", "附件:/workspace/inbox/a.pdf(很大)", "看看附件:/workspace/inbox/a.pdf(1 KB)")) {
            val p = AttachmentMessage.parse(t)
            assertFalse(t, p.hasAttachments)
            assertEquals(t, p.words)
        }
        assertEquals("普通的一句话", AttachmentMessage.parse("  普通的一句话 ").words)
    }

    @Test fun composeWritesWhatParseReads() {
        val text = AttachmentMessage.compose("查一下", listOf("/workspace/inbox/a.png" to "3 KB", "/workspace/inbox/b.docx" to "1.0 MB"))
        assertEquals("查一下\n附件:/workspace/inbox/a.png(3 KB)\n附件:/workspace/inbox/b.docx(1.0 MB)", text)
        val p = AttachmentMessage.parse(text)
        assertEquals("查一下", p.words)
        assertEquals(1, p.photos.size)
        assertEquals(1, p.files.size)
        assertEquals("附件:/workspace/inbox/a.png(3 KB)", AttachmentMessage.compose("", listOf("/workspace/inbox/a.png" to "3 KB")))
        // 交给任务的写法和以前一样
        assertEquals(text, ChatViewModel.withAttachments("查一下", listOf("/workspace/inbox/a.png" to 3072L, "/workspace/inbox/b.docx" to 1024L * 1024)))
    }

    @Test fun plainDropsThePathLines() {
        assertEquals("查车次", AttachmentMessage.plain("查车次\n附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals("照片", AttachmentMessage.plain("附件:/workspace/inbox/a.jpg(1 KB)"))
        assertEquals("2 张照片", AttachmentMessage.plain("附件:/workspace/inbox/a.jpg(1 KB)\n附件:/workspace/inbox/b.png(1 KB)"))
        assertEquals("a.pdf", AttachmentMessage.plain("附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals("a.pdf 等 2 个文件", AttachmentMessage.plain("附件:/workspace/inbox/a.pdf(1 KB)\n附件:/workspace/inbox/b.zip(1 KB)"))
        assertEquals("照片和文件", AttachmentMessage.plain("附件:/workspace/inbox/a.jpg(1 KB)\n附件:/workspace/inbox/b.zip(1 KB)"))
        assertEquals("没有附件\n第二行", AttachmentMessage.plain("没有附件\n第二行"))
    }

    @Test fun gridColumnsByCount() {
        assertEquals(1, PhotoGrid.columns(1))
        assertEquals(2, PhotoGrid.columns(2))
        assertEquals(3, PhotoGrid.columns(3))
        assertEquals(3, PhotoGrid.columns(4))
        assertEquals(3, PhotoGrid.columns(9))
        assertEquals(listOf(0, 1, 1, 1, 2, 2, 2, 3), listOf(0, 1, 2, 3, 4, 6, 5, 9).map(PhotoGrid::rows))
    }

    @Test fun badgeByExtension() {
        assertEquals("PDF", FileBadge.text("合同.pdf"))
        assertEquals("XLSX", FileBadge.text("表.xlsx"))
        assertEquals("文件", FileBadge.text("README"))
        assertEquals(FileBadge.Kind.Pdf, FileBadge.kind("a.PDF"))
        assertEquals(FileBadge.Kind.Sheet, FileBadge.kind("a.csv"))
        assertEquals(FileBadge.Kind.Doc, FileBadge.kind("a.docx"))
        assertEquals(FileBadge.Kind.Slides, FileBadge.kind("a.pptx"))
        assertEquals(FileBadge.Kind.Archive, FileBadge.kind("a.zip"))
        assertEquals(FileBadge.Kind.Other, FileBadge.kind("a.apk"))
    }

    private fun photo(n: Int) = PendingAttachment("content://m/$n", "p$n.jpg", 1L)
    private val file = PendingAttachment("content://f/1", "a.pdf", 1L)

    @Test fun traySelectsDeselectsAndStopsAtNine() {
        var items = emptyList<PendingAttachment>()
        for (i in 0 until AttachTray.LIMIT) {
            val t = AttachTray.toggle(items, photo(i))
            assertFalse(t.full)
            items = t.items
        }
        val full = AttachTray.toggle(items, photo(99))
        assertTrue(full.full)
        assertEquals(items, full.items)
        // 取消一张后又能选
        items = AttachTray.toggle(items, photo(3)).items
        assertEquals(AttachTray.LIMIT - 1, items.size)
        assertNull(AttachTray.order(items, "content://m/3"))
        assertEquals(4, AttachTray.order(items, "content://m/4"))
        assertFalse(AttachTray.toggle(items, photo(99)).full)
    }

    @Test fun trayAddSkipsDuplicatesAndCountsWhatDidNotFit() {
        val (items, dropped) = AttachTray.add(listOf(photo(0)), listOf(photo(0), file) + (1..9).map(::photo))
        assertEquals(AttachTray.LIMIT, items.size)
        assertEquals(listOf("content://m/0", "content://f/1"), items.take(2).map { it.uri })
        assertEquals(2, dropped)
        // 照片的序号只数照片
        assertEquals(2, AttachTray.order(items, "content://m/1"))
        assertNull(AttachTray.order(items, "content://f/1"))
        assertFalse(file.photo)
        assertTrue(photo(1).photo)
    }

    @Test fun sendNeedsWordsOrAttachmentsAndNotWhileCopying() {
        assertFalse(AttachTray.canSend("", emptyList(), importing = false))
        assertFalse(AttachTray.canSend("  ", emptyList(), importing = false))
        assertTrue(AttachTray.canSend("hi", emptyList(), importing = false))
        assertTrue(AttachTray.canSend("", listOf(file), importing = false))
        assertFalse(AttachTray.canSend("hi", listOf(file), importing = true))
    }
}
