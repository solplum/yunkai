package com.wangzan101.mika.core

import com.wangzan101.mika.core.assembly.knownToolNames
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文档和代码不脱节:文档里反引号包着的文件路径、Kotlin 类名、工具名,必须在仓库里真的有。
 * 范围:README.md、README.en.md、docs/ 下全部 .md、eval/README.md、tools/sandbox/README.md。
 * 另外,同样这些文档里不写死会变的个数(内置知识、工具、服务商、评测题有多少),见 [docsDoNotHardCodeCounts]。
 *
 * 只检查「整段反引号内容就是一个标识符」的写法;下面这些一律不查(规则,不是白名单):
 *  - 围栏代码块(```)整块不看;行内反引号里含空白的是命令或代码片段;
 *  - 含 < > * … { } ( ) = : " ' $ , ; | # 的是占位、通配、调用、键值、示例值;
 *  - 以 / ~ 开头的是设备或系统路径,含 :// 的是网址;
 *  - 路径:带 / 的,只有第一段是仓库根下真有的目录或文件才查(`files/sandbox/rootfs`、`read/system` 这类是运行时产物或分类名);
 *    不带 / 的,只有代码类扩展名(kt、py、xml 等)才查,json、md、txt 这类常是运行时产物;
 *    查的时候:根目录下直接存在,或「模块/包路径/文件」的简写(`app/task/X.kt`),或中间用 ... 省略(`core/.../X.kt`),或短文件名在仓库任意位置;
 *  - 类名:大驼峰且至少两个大写字母(`Mika` 这样的单词不当类名);在 core、app 的源码(含测试)里有 class/object/interface/typealias
 *    声明或同名文件;源码里从本仓库以外的包导入过的(安卓、JDK、库的类)不归本仓库管;
 *  - 工具名:只查「第一段是现有工具名的第一段」的 snake_case(`max_seconds` 这样的参数名不查),对照 [knownToolNames],
 *    那是工具注册处的名单,不是金样快照。
 */
class DocsConsistencyTest {
    private val root = File("..").canonicalFile
    private val skipDirs = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "reference", "dist", ".claude", ".omc", "node_modules")

    private val repoFiles: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    private val repoDirs: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    private val sourceFiles: List<File> by lazy {
        listOf("core/src", "app/src").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
    }
    private val sourceText: String by lazy { sourceFiles.joinToString("\n") { it.readText() } }
    private val sourceNames: Set<String> by lazy { sourceFiles.map { it.nameWithoutExtension }.toSet() }

    private fun docs(): List<File> {
        val list = mutableListOf(File(root, "README.md"), File(root, "README.en.md"), File(root, "eval/README.md"), File(root, "tools/sandbox/README.md"))
        File(root, "docs").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }?.let { list += it }
        return list.filter { it.isFile }
    }

    /** 去掉围栏代码块后,取所有行内反引号里的内容。 */
    private fun spans(text: String): List<String> {
        var fenced = false
        val prose = text.lines().filter { line ->
            if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
        }.joinToString("\n")
        return Regex("`([^`\n]+)`").findAll(prose).map { it.groupValues[1].trim() }.toList()
    }

    private val notIdentifier = Regex("""[\s<>*…{}()=:"'$,;|#]""")
    private val pathExtensions = setOf("kt", "kts", "java", "md", "json", "xml", "txt", "yml", "yaml", "gradle", "toml", "properties", "py", "sh", "js", "html", "pro", "aidl", "cpp", "h")
    private val tool = Regex("""[a-z][a-z0-9]*(_[a-z0-9]+)+""")
    private val classLike = Regex("""[A-Z][A-Za-z0-9]*""")

    private val toolNames: Set<String> by lazy { knownToolNames() }
    private val toolPrefixes: Set<String> by lazy { toolNames.map { it.substringBefore('_') }.toSet() }

    private fun isPath(s: String): Boolean =
        !s.startsWith("/") && !s.startsWith("~") && "://" !in s &&
            ('/' in s || s.substringAfterLast('.', "").let { it in pathExtensions && s.length > it.length + 1 })

    /** 文档里写的是 App 运行时才有的东西(设备上的目录和文件),不在仓库里;逐个写明为什么。 */
    private val runtimeOnly = mapOf(
        "eval/logged-in.txt" to "评测的人工登录标记,放在设备上 App 的外部文件目录,不进仓库",
    )

    /** 只有仓库根下确有的一级目录或文件开头的路径、以及代码类的短文件名,才是在指仓库里的东西;其余是运行时产物或相对写法。 */
    private val topLevel: Set<String> by lazy { File(root, ".").list().orEmpty().filter { it !in skipDirs }.toSet() }
    private val codeExtensions = setOf("kt", "kts", "java", "py", "sh", "gradle", "xml", "aidl", "cpp", "h")

    private fun pathExists(rel: String): Boolean {
        val clean = rel.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean in runtimeOnly) return true
        val dir = rel.endsWith("/")
        // 中间用 ... 省略的写法:开头是真目录前缀,结尾是某个文件路径的结尾(可不带扩展名)
        if ("..." in clean) {
            val head = clean.substringBefore("...").trimEnd('/')
            val tail = clean.substringAfterLast("...").trimStart('/')
            return repoFiles.any { f -> f.startsWith(head) && (f.endsWith("/$tail") || f.substringBeforeLast('.').endsWith("/$tail")) }
        }
        if ('/' !in clean) {
            return if (clean.substringAfterLast('.', "") in codeExtensions) repoFiles.any { it == clean || it.endsWith("/$clean") } else true
        }
        val first = clean.substringBefore('/')
        if (first !in topLevel) return true
        if (File(root, clean).exists()) return true
        // 「模块/包路径/文件」的简写(如 app/task/X.kt 指 app/src/main/java/.../task/X.kt):模块下任一路径以后面部分结尾即可
        val rest = clean.substringAfter('/')
        return if (dir) repoDirs.any { it.startsWith("$first/") && (it.endsWith("/$rest")) }
        else repoFiles.any { it.startsWith("$first/") && it.endsWith("/$rest") }
    }

    private fun classExists(name: String): Boolean =
        name in sourceNames || Regex("""\b(class|object|interface|typealias)\s+$name\b""").containsMatchIn(sourceText)

    private fun importedFromOutside(name: String): Boolean =
        Regex("""(?m)^import\s+(?!com\.wangzan101\.mika\.)[\w.]+\.$name\b""").containsMatchIn(sourceText)

    private fun problems(doc: File): List<String> {
        val rel = doc.relativeTo(root).invariantSeparatorsPath
        return spans(doc.readText()).distinct().mapNotNull { s ->
            if (notIdentifier.containsMatchIn(s)) return@mapNotNull null
            when {
                isPath(s) -> if (pathExists(s)) null else "$rel: 路径 `$s` 在仓库里找不到"
                tool.matches(s) -> if (s.substringBefore('_') !in toolPrefixes || s in toolNames) null else "$rel: 工具名 `$s` 不在现有工具里"
                classLike.matches(s) && s.count { it.isUpperCase() } >= 2 && s.any { it.isLowerCase() } ->
                    if (classExists(s) || importedFromOutside(s)) null else "$rel: 类名 `$s` 在 core/app 的源码里找不到"
                else -> null
            }
        }
    }

    /**
     * 会变的东西不写死个数:「18 份 App」「60 多个工具」「128 道评测题」(英文的「18 apps」「60+ tools」)这类写法一加内容就过期。
     * 要列清单就用 README 里生成的段落(`./gradlew updateReadme`);确实要写个数的那一行末尾加 [countOk] 并说明为什么。
     */
    private val hardCount = Regex(
        """\d+\s*多?\s*(份|个|道|家)\s*(内置)?\s*(App|app|网站|站点|技能|工具|服务商|预设|(真机)?评测题)""" +
            """|\b\d+\+?\s+(built-in\s+)?(apps?|websites?|sites?|skills?|tools?|providers?|presets?|(on-device\s+)?eval\s+tasks?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val countOk = "<!-- count-ok -->"

    @Test fun docsDoNotHardCodeCounts() {
        val bad = docs().flatMap { doc ->
            val rel = doc.relativeTo(root).invariantSeparatorsPath
            doc.readLines().withIndex().filter { (_, line) -> countOk !in line && hardCount.containsMatchIn(line) }
                .map { (i, line) -> "$rel:${i + 1}: 「${hardCount.find(line)!!.value}」" }
        }
        assertTrue("文档里写死了会变的个数,改成不带个数的说法(清单用 README 的生成段落):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun countPatternCatchesTypicalWording() {
        listOf("18 份 App", "12 份网站", "4 份技能", "60 多个工具", "128 道真机评测题", "7 家服务商",
            "18 apps", "12 websites", "4 skills", "60 tools", "60+ tools", "7 providers", "18 built-in apps", "128 eval tasks").forEach {
            assertTrue(it, hardCount.containsMatchIn(it))
        }
        listOf("最多 3 条知识", "一次最多 9 个", "每个任务最多 3 份,关联的技能",
            "Android 13 and later", "python3 tools/x.py", "up to 3 notes", "arm64 devices").forEach {
            assertTrue(it, !hardCount.containsMatchIn(it))
        }
    }

    @Test fun inputsAreFound() {
        assertTrue("找不到仓库根目录(工作目录应为 core/)", File(root, "settings.gradle.kts").isFile)
        assertTrue("没找到文档", docs().size >= 5)
        assertTrue(toolNames.contains("knowledge_read"))
        assertTrue(classExists("ToolDef"))
        assertTrue(spans(docs().joinToString("\n") { it.readText() }).size > 50)
    }

    @Test fun docsMentionOnlyThingsThatExist() {
        val bad = docs().flatMap(::problems)
        assertTrue("文档里提到了仓库里没有的东西:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
