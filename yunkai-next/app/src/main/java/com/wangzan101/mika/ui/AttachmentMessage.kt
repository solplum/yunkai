package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.ui.viewer.ViewerKind
import com.wangzan101.mika.ui.viewer.ViewerKinds

/**
 * 用户的话里带的附件:消息末尾每个附件一行「附件:/workspace/inbox/<名字>(<大小>)」,这几行原样存进对话、交给任务;
 * 对话页据此把附件画成照片格子和文件卡片,路径那几行不给用户看。旧对话里的消息也是这个写法。
 */
object AttachmentMessage {
    private const val PREFIX = "附件:"

    /** 名字里可以有括号(「报表 (1).csv」):大小只认最后那对括号里的「数字 + B/KB/MB」;路径在沙箱的 /workspace 下。 */
    private val LINE = Regex("^附件:(/workspace/.+)\\((\\d+(?:\\.\\d+)? (?:B|KB|MB))\\)$")

    /** 一个附件:工作区里的路径和写在消息里的大小(「2 KB」)。 */
    data class Item(val path: String, val size: String) {
        val name: String get() = path.substringAfterLast('/')
        /** 按扩展名认照片;照片排成格子,其余是文件卡片。 */
        val photo: Boolean get() = isPhoto(name)
    }

    /** 拆开的一句话:[photos]、[files] 按出现先后,[words] 是附件行以外的话(去掉首尾空白)。 */
    data class Parsed(val photos: List<Item>, val files: List<Item>, val words: String) {
        val hasAttachments: Boolean get() = photos.isNotEmpty() || files.isNotEmpty()
    }

    fun line(path: String, size: String): String = "$PREFIX$path($size)"

    /** 话在前,每个附件一行在后;话为空时只有附件行。 */
    fun compose(text: String, files: List<Pair<String, String>>): String =
        (listOf(text).filter { it.isNotEmpty() } + files.map { (p, s) -> line(p, s) }).joinToString("\n")

    fun parse(text: String): Parsed {
        val items = mutableListOf<Item>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) items += Item(m.groupValues[1], m.groupValues[2]) else rest += l
        }
        if (items.isEmpty()) return Parsed(emptyList(), emptyList(), text.trim())
        return Parsed(items.filter { it.photo }, items.filterNot { it.photo }, rest.joinToString("\n").trim())
    }

    /** 去掉附件行后的话;只发了附件时是一句称呼([label])。标题、侧栏用它。 */
    fun plain(text: String): String {
        val p = parse(text)
        return if (!p.hasAttachments) text else p.words.ifEmpty { label(p) }
    }

    /** 只发了附件时怎么称呼:「照片」「3 张照片」「合同.pdf」「合同.pdf 等 2 个文件」「照片和文件」。 */
    fun label(p: Parsed): String = when {
        p.photos.isNotEmpty() && p.files.isNotEmpty() -> tr(R.string.attach_photos_and_files)
        p.photos.size == 1 -> tr(R.string.attach_photo)
        p.photos.isNotEmpty() -> trPlural(R.plurals.attach_n_photos, p.photos.size)
        p.files.size == 1 -> p.files[0].name
        p.files.isNotEmpty() -> tr(R.string.attach_file_and_more, p.files[0].name, p.files.size)
        else -> ""
    }

    fun isPhoto(name: String): Boolean = ViewerKinds.of(null, name) == ViewerKind.Image
}

/** 照片格子怎么排:1 张一大张(保持比例),2 张并排,3 张起三列、多了换行。 */
object PhotoGrid {
    fun columns(count: Int): Int = when {
        count <= 1 -> 1
        count == 2 -> 2
        else -> 3
    }

    fun rows(count: Int): Int = if (count <= 0) 0 else (count + columns(count) - 1) / columns(count)
}

/** 文件卡片上的类型角标:扩展名(最多 4 个字母)和颜色分组。 */
object FileBadge {
    enum class Kind { Pdf, Sheet, Doc, Slides, Archive, Other }

    fun text(name: String): String = ext(name).uppercase().take(4).ifEmpty { tr(R.string.attach_files) }

    fun kind(name: String): Kind = when (ext(name)) {
        "pdf" -> Kind.Pdf
        "xls", "xlsx", "csv", "tsv", "numbers", "ods" -> Kind.Sheet
        "doc", "docx", "txt", "md", "rtf", "pages", "odt" -> Kind.Doc
        "ppt", "pptx", "key", "odp" -> Kind.Slides
        "zip", "rar", "7z", "gz", "tar", "tgz" -> Kind.Archive
        else -> Kind.Other
    }

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
}

/** 输入框上方待发的附件:最多 [LIMIT] 个(照片和文件一起算),同一个地址只算一次。 */
object AttachTray {
    const val LIMIT = 9

    /** 加进来:已有的跳过,超过上限的不收;返回新的列表和没收下的个数。 */
    fun add(current: List<PendingAttachment>, items: List<PendingAttachment>): Pair<List<PendingAttachment>, Int> {
        val out = current.toMutableList()
        var dropped = 0
        for (i in items) {
            if (out.any { it.uri == i.uri }) continue
            if (out.size >= LIMIT) dropped++ else out += i
        }
        return out to dropped
    }

    /** 点一下:选着的去掉,没选的加上(满了不加,[Toggle.full] 为真)。 */
    fun toggle(current: List<PendingAttachment>, item: PendingAttachment): Toggle =
        if (current.any { it.uri == item.uri }) Toggle(current.filterNot { it.uri == item.uri }, full = false)
        else if (current.size >= LIMIT) Toggle(current, full = true)
        else Toggle(current + item, full = false)

    data class Toggle(val items: List<PendingAttachment>, val full: Boolean)

    /** 照片在待发里是第几张(从 1 起,只数照片);没选为 null。照片格子右上角的序号。 */
    fun order(current: List<PendingAttachment>, uri: String): Int? =
        current.filter { it.photo }.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }?.plus(1)

    /** 有字或有附件、没在复制时能发。 */
    fun canSend(text: String, items: List<PendingAttachment>, importing: Boolean): Boolean =
        (text.isNotBlank() || items.isNotEmpty()) && !importing
}
