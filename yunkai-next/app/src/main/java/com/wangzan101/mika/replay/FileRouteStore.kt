package com.wangzan101.mika.replay

import android.content.Context
import com.wangzan101.mika.core.replay.MAX_ROUTE_BYTES
import com.wangzan101.mika.core.replay.MAX_ROUTE_STEPS
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteCodec
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.replay.replacing
import com.wangzan101.mika.core.replay.withCurrentToolNames
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.LegacyNames
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 路线存成 `<dir>/<首个包名>/<id>.json`;超过 [max] 条时删最久没用的。
 * 读不了的文件直接删掉(开发期格式变了不做兼容)。产品路径用 [shared],全进程一个实例、一把锁。
 */
class FileRouteStore(
    private val dir: File, private val max: Int = 200,
    /** 改名前记下的路线读出时把旧工具名换成现在的。 */
    private val legacy: LegacyNames = legacyNames(), private val tools: ToolIndex = knownTools(),
) : RouteStore {
    init { require(max > 0) { "max must be positive" } }

    private val lock = Any()

    override fun all(): List<Route> = synchronized(lock) { load().map { it.second } }

    override fun put(r: Route) = synchronized(lock) { putLocked(r, load()) }

    override fun putReplacing(r: Route) = synchronized(lock) {
        val existing = load()
        val old = existing.filter { it.second.template == r.template }
        putLocked(replacing(r, old.map { it.second }), existing)
        old.filter { it.second.id != r.id }.forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    override fun update(id: String, change: (Route) -> Route?) = synchronized(lock) {
        val existing = load()
        existing.firstOrNull { it.second.id == id }?.second?.let(change)?.let { putLocked(it, existing) }
        Unit
    }

    override fun remove(id: String) = synchronized(lock) {
        load().filter { it.second.id == id }.forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    override fun clear() = synchronized(lock) { dir.deleteRecursively(); Unit }

    override fun count(): Int = synchronized(lock) { load().size }

    private fun putLocked(r: Route, existing: List<Pair<File, Route>>) {
        val bytes = RouteCodec.encode(r).toByteArray()
        require(r.steps.size <= MAX_ROUTE_STEPS && bytes.size <= MAX_ROUTE_BYTES) { "route ${r.id} too large" }
        val f = fileOf(r)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        // 原子替换:旧文件在新文件就位前一直完好
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // 同 id 换了首个包名:旧位置的那份删掉
        existing.filter { it.second.id == r.id && it.first.absoluteFile != f.absoluteFile }.forEach { it.first.delete() }
        val kept = existing.filter { it.second.id != r.id } + (f to r)
        kept.sortedBy { it.second.lastUsedMs }.take((kept.size - max).coerceAtLeast(0)).forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    private fun fileOf(r: Route) = File(File(dir, safe(r.packages.firstOrNull() ?: NO_PACKAGE)), "${safe(r.id)}.json")

    private fun load(): List<Pair<File, Route>> =
        (dir.listFiles()?.filter { it.isDirectory } ?: emptyList()).flatMap { d ->
            (d.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()).mapNotNull { f ->
                try {
                    f to RouteCodec.decode(f.readText()).withCurrentToolNames(legacy, tools)
                } catch (e: Exception) {
                    f.delete()
                    null
                }
            }
        }

    private fun pruneEmptyDirs() {
        dir.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
    }

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val routes: FileRouteStore
    }

    companion object {
        const val DIR = "routes"
        private const val NO_PACKAGE = "_"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        private fun safe(s: String) = UNSAFE.replace(s, "_").ifEmpty { "_" }.let { if (it == "." || it == "..") "_" else it }

        /** 产品里的那份(App 建):files/routes 下。 */
        fun create(context: Context): FileRouteStore = FileRouteStore(File(context.applicationContext.filesDir, DIR))

        /** App 内共用的那份(经 [Owner] 给出;任务结束记录、设置页清除都用它):全进程一个实例、一把锁。 */
        fun shared(context: Context): FileRouteStore = (context.applicationContext as? Owner)?.routes
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement FileRouteStore.Owner to give the route store")
    }
}
