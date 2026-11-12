package com.wangzan101.mika.ui

/** 让还没输出完的行也能按 Markdown 预览；这些临时字符不会进入消息记录。 */
internal object StreamingMarkdown {
    private data class Open(val marker: String, val index: Int)
    private val unfinishedLink = Regex("""!?\[([^\]\n]*)\]\([^\)\n]*$""")
    private val unfinishedLabel = Regex("""!?\[([^\]\n]*)$""")
    private val fenceLine = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

    fun preview(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        // 代码块里的标点就是内容，不能把尚未闭合的链接或表格当排版语法改掉。
        if (insideFence(markdown)) return markdown
        // URL 尚未收完时先显示链接文字，避免 [文字](https://... 直接露出来。
        val link = unfinishedLink.find(markdown) ?: unfinishedLabel.find(markdown)
        val visible = if (link == null) markdown else markdown.replaceRange(link.range, link.groupValues[1])
        return closeInline(softenPendingTable(visible))
    }

    private fun insideFence(source: String): Boolean {
        var fence: String? = null
        for (line in source.lineSequence()) {
            val marker = fenceLine.matchEntire(line) ?: continue
            val run = marker.groupValues[1]
            if (fence == null) fence = run
            else if (run.first() == fence.first() && run.length >= fence.length && marker.groupValues[2].isBlank()) fence = null
        }
        return fence != null
    }

    private fun softenPendingTable(source: String): String {
        val lines = source.split('\n').toMutableList()
        if (lines.size >= 2 && lines.zipWithNext().any { (header, separator) ->
                header.trimStart().startsWith('|') && completeDivider(separator, cells(header).size)
            }) return source
        val last = lines.lastIndex
        val current = lines[last]
        if (last > 0 && current.trim().all { it in "|:- " } && lines[last - 1].trimStart().startsWith('|')) {
            lines[last - 1] = cells(lines[last - 1]).joinToString(" · ")
            lines[last] = ""
        } else if (current.trimStart().startsWith('|')) {
            lines[last] = cells(current).joinToString(" · ")
        }
        return lines.joinToString("\n")
    }

    private fun cells(line: String): List<String> = line.trim().trim('|').split('|').map(String::trim)

    private fun completeDivider(line: String, columns: Int): Boolean = columns > 0 &&
        cells(line).let { parts -> parts.size == columns && parts.all { it.matches(Regex(""":?-+:?""")) } }

    private fun runLength(source: String, start: Int, c: Char): Int {
        var end = start
        while (end < source.length && source[end] == c) end++
        return end - start
    }

    private fun closeInline(source: String): String {
        val opened = mutableListOf<Open>()
        var fence: String? = null
        var code: String? = null
        var i = 0
        var lineStart = true
        while (i < source.length) {
            if (lineStart) {
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                val marker = fenceLine.matchEntire(source.substring(i, end))
                if (marker != null) {
                    val run = marker.groupValues[1]
                    if (fence == null) fence = run
                    else if (run.first() == fence.first() && run.length >= fence.length && marker.groupValues[2].isBlank()) fence = null
                    i = end
                    lineStart = true
                    continue
                }
            }
            val c = source[i]
            if (c == '\n') { lineStart = true; i++; continue }
            lineStart = false
            if (fence != null) { i++; continue }
            if (c == '\\') { i = (i + 2).coerceAtMost(source.length); continue }
            if (c == ']' && source.getOrNull(i + 1) == '(') {
                // 已闭合链接的地址不是可见正文，里面的强调符号不用处理。
                val end = source.indexOf(')', i + 2)
                if (end >= 0) { i = end + 1; continue }
            }
            if (c == '`') {
                val count = runLength(source, i, '`')
                val marker = "`".repeat(count)
                if (code == marker) { opened.removeAt(opened.lastIndex); code = null }
                else if (code == null) { opened += Open(marker, i); code = marker }
                i += count
                continue
            }
            if (code != null || c !in "*_~") { i++; continue }
            val count = runLength(source, i, c)
            val previous = source.getOrNull(i - 1)
            val next = source.getOrNull(i + count)
            val canClose = previous != null && !previous.isWhitespace()
            val canOpen = (next == null || !next.isWhitespace()) &&
                (previous == null || previous.isWhitespace() || previous in "([{>.,:;!?")
            var remaining = count
            var offset = 0
            while (remaining > 0) {
                val top = opened.lastOrNull()
                val width = if (canClose && top != null && top.marker.first() == c && top.marker.length <= remaining) {
                    opened.removeAt(opened.lastIndex)
                    top.marker.length
                } else {
                    val n = if (remaining >= 2) 2 else 1
                    if (canOpen && !(c == '~' && n == 1)) opened += Open(c.toString().repeat(n), i + offset)
                    n
                }
                remaining -= width
                offset += width
            }
            i += count
        }
        var result = source
        for (open in opened.asReversed()) {
            result = if (open.index + open.marker.length == result.length) result.dropLast(open.marker.length)
            else result + open.marker
        }
        return result
    }
}
