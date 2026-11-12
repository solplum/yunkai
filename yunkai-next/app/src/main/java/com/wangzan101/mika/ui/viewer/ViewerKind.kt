package com.wangzan101.mika.ui.viewer

/** 内置查看器能显示的几类文件;不在这里的交给别的 App。 */
enum class ViewerKind { Html, Markdown, Pdf, Image, Text }

object ViewerKinds {
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    private val texts = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "properties", "env", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "sh", "bash", "zsh", "c", "h", "cpp",
        "hpp", "cc", "go", "rs", "rb", "php", "swift", "sql", "css", "scss", "gradle", "diff", "patch", "srt", "vtt",
    )
    private val imageMimes = images.map { if (it == "jpg") "image/jpeg" else "image/$it" }.toSet() + "image/x-ms-bmp"
    private val textMimes = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript", "application/x-yaml",
        "application/yaml", "application/x-sh", "application/toml", "application/sql",
    )

    /**
     * 按内容提供方给的 MIME 类型认,认不出(空、`application/octet-stream`、泛泛的 `text/plain`)时看扩展名。
     * 明确的 HTML / Markdown / PDF / 图片类型直接定;`text/plain` 的 .md 仍按 Markdown 显示。都不是返回 null。
     */
    fun of(mime: String?, name: String): ViewerKind? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            m == "text/html" || m == "application/xhtml+xml" -> return ViewerKind.Html
            m == "text/markdown" || m == "text/x-markdown" -> return ViewerKind.Markdown
            m == "application/pdf" -> return ViewerKind.Pdf
            m in imageMimes -> return ViewerKind.Image
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "html" || ext == "htm" || ext == "xhtml" -> ViewerKind.Html
            ext == "md" || ext == "markdown" -> ViewerKind.Markdown
            ext == "pdf" -> ViewerKind.Pdf
            ext in images -> ViewerKind.Image
            ext in texts -> ViewerKind.Text
            m.startsWith("image/") -> null
            m.startsWith("text/") || m in textMimes || m.endsWith("+json") || m.endsWith("+xml") -> ViewerKind.Text
            else -> null
        }
    }
}
