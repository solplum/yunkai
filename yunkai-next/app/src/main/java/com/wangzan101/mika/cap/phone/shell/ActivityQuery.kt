package com.wangzan101.mika.cap.phone.shell

/**
 * 系统接口查不了哪些页面能接一个 Intent 时(有的系统在应用这一侧的查询里另读设置,外壳身份读不了就抛错),
 * 改问 `cmd package query-activities`:查询在系统服务里做,只认外壳身份。不依赖安卓类,单元测试直接调。
 */
internal object ActivityQuery {
    /** 命令参数:[action]、可选 [type]、[uri]、[category],限定 [packageName] 时只查这个包。 */
    fun args(action: String, type: String? = null, uri: String? = null, category: String? = null, packageName: String? = null): List<String> =
        listOf("cmd", "package", "query-activities", "--brief", "--user", "current", "-a", action) +
            listOfNotNull(type?.let { "-t" }, type) + listOfNotNull(uri?.let { "-d" }, uri) +
            listOfNotNull(category?.let { "-c" }, category) + listOfNotNull(packageName?.let { "-p" }, packageName)

    /** 从输出里取出每个页面的 (包名, 完整类名);以点开头的短类名补上包名。 */
    fun parse(output: String): List<Pair<String, String>> = output.lineSequence().mapNotNull { line ->
        val m = COMPONENT.matchEntire(line.trim()) ?: return@mapNotNull null
        val (pkg, cls) = m.destructured
        pkg to if (cls.startsWith(".")) pkg + cls else cls
    }.distinct().toList()

    private val COMPONENT = Regex("""([A-Za-z][\w.]*)/([\w.$]+)""")
}
