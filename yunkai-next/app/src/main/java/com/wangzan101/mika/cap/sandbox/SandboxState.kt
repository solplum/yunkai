package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

enum class InstallStep { Check, Extract, Configure, Skills, Done }

/**
 * 沙箱状态。安装进度记在 `files/sandbox/state.json`(重置时随整个目录删掉);
 * 联网开关是用户设置,记在 `files/sandbox-settings.json`,重置后保留。
 *
 * 只有记录里「完成到 Done」且根文件系统里有 /bin/sh 才算就绪;装到一半(包括进程在安装中被杀)一律是 Broken,
 * 下次从记录的那一步之后继续。
 */
class SandboxState internal constructor(
    private val dirs: SandboxDirs,
    private val settingsFile: File,
    private val abis: () -> List<String>,
) {
    constructor(context: Context) : this(
        SandboxDirs(context), File(context.filesDir, "sandbox-settings.json"), { Build.SUPPORTED_ABIS.toList() },
    )

    private val recordFile get() = File(dirs.root, "state.json")
    private var installing: SandboxStatus.Installing? = null
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** 「允许沙箱联网」,默认关。 */
    var networkAllowed: Boolean
        get() = settings()["networkAllowed"]?.jsonPrimitive?.booleanOrNull ?: false
        set(value) { putSetting("networkAllowed", JsonPrimitive(value)); refresh() }

    /** 断网过滤器装不上时的原因(启动器自检得出);不挡安装,但断网时命令会被拒绝。 */
    val isolationIssue: String? get() = record()["isolationIssue"]?.jsonPrimitive?.contentOrNull

    val installedVersion: String? get() = record()["version"]?.jsonPrimitive?.contentOrNull

    @Synchronized
    fun completedStep(): InstallStep? =
        record()["completedStep"]?.jsonPrimitive?.contentOrNull?.let { n -> InstallStep.entries.firstOrNull { it.name == n } }

    /** 记下「完成到 [s]」并清掉上次的失败原因;Done 时写版本与完成时间。 */
    @Synchronized
    fun markStep(s: InstallStep) = markStep(s, null)

    @Synchronized
    internal fun markStep(s: InstallStep, version: String?) {
        val r = record().toMutableMap()
        r["completedStep"] = JsonPrimitive(s.name)
        r.remove("broken")
        if (s == InstallStep.Done) {
            version?.let { r["version"] = JsonPrimitive(it) }
            r["doneAt"] = JsonPrimitive(System.currentTimeMillis())
            installing = null
        }
        writeJson(recordFile, JsonObject(r))
        refresh()
    }

    @Synchronized
    internal fun installing(step: String, percent: Int) {
        installing = SandboxStatus.Installing(percent, step)
        refresh()
    }

    @Synchronized
    internal fun markBroken(reason: String) {
        installing = null
        writeJson(recordFile, JsonObject(record() + ("broken" to JsonPrimitive(reason))))
        refresh()
    }

    @Synchronized
    internal fun recordIsolation(issue: String?) {
        val r = record().toMutableMap()
        if (issue == null) r.remove("isolationIssue") else r["isolationIssue"] = JsonPrimitive(issue)
        writeJson(recordFile, JsonObject(r))
    }

    /** 重置开始:先删安装记录、显示「重置」,删到一半被杀也不会再显示就绪。 */
    @Synchronized
    internal fun resetting() {
        Files.deleteIfExists(recordFile.toPath())
        installing(tr(R.string.sandbox_resetting), 0)
    }

    /** 安装或重置结束(不论成败):撤掉进行中的状态,按记录重新算。 */
    @Synchronized
    internal fun cleared() {
        installing = null
        refresh()
    }

    @Synchronized
    fun refresh() { _status.value = read() }

    private fun read(): SandboxStatus {
        if ("arm64-v8a" !in abis()) return SandboxStatus.Unsupported(tr(R.string.install_unsupported))
        installing?.let { return it }
        val r = record()
        val step = r["completedStep"]?.jsonPrimitive?.contentOrNull
        val broken = r["broken"]?.jsonPrimitive?.contentOrNull
        // Alpine 的 /bin/sh 是指向 /bin/busybox 的绝对链接,在宿主上解析不到:只看链接本身
        val hasShell = Files.exists(File(dirs.rootfs, "bin/sh").toPath(), LinkOption.NOFOLLOW_LINKS)
        return when {
            step == InstallStep.Done.name && hasShell -> SandboxStatus.Ready(network = networkAllowed)
            step == InstallStep.Done.name -> SandboxStatus.Broken(tr(R.string.sandbox_incomplete))
            broken != null -> SandboxStatus.Broken(broken)
            step != null -> SandboxStatus.Broken(tr(R.string.sandbox_not_finished))
            else -> SandboxStatus.NotInstalled
        }
    }

    private fun record(): Map<String, JsonElement> = readJson(recordFile)
    private fun settings(): Map<String, JsonElement> = readJson(settingsFile)

    @Synchronized
    private fun putSetting(key: String, value: JsonPrimitive) = writeJson(settingsFile, JsonObject(settings() + (key to value)))

    companion object {
        /** 进程里那一份(App 给出):安装服务更新的状态,设置页与 Agent 看到的是同一份。 */
        fun get(context: Context): SandboxState = SandboxOwner.of(context).sandboxState

        private fun readJson(f: File): Map<String, JsonElement> =
            runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: emptyMap()

        /** 先写临时文件再改名,写到一半被杀不会留下坏文件。 */
        private fun writeJson(f: File, o: JsonObject) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            // 落盘后再改名:断电或被杀时要么是旧记录、要么是新记录
            java.io.FileOutputStream(tmp).use { out -> out.write(o.toString().toByteArray()); out.fd.sync() }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
