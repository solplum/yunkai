// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika.about

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 手动检查更新这一次的结果;自动检查不改它(失败时不打扰)。 */
enum class CheckStatus { Idle, Checking, Done, Failed }

/**
 * 检查更新的状态。[latest] 是最近一次查到的版本(更新记录用),[update] 是比当前新的那个(有它才有红点)。
 * [manualRounds] 每完成一次手动检查加一,页面据此给出「已是最新」「连不上」之类的提示。
 */
data class UpdateState(
    val latest: ReleaseInfo? = null,
    val update: ReleaseInfo? = null,
    val status: CheckStatus = CheckStatus.Idle,
    val manualRounds: Int = 0,
) {
    val hasUpdate: Boolean get() = update != null
}

/**
 * 每次打开 App 都自动查一次,只挡住连着来的:离上次自动检查不到 [MIN_GAP_MS](来回切几下、旋转屏幕)不再查。
 * 上次时间比现在还晚(改过系统时间)也算到期。
 */
object UpdateSchedule {
    const val MIN_GAP_MS = 2 * 60 * 1000L

    fun due(lastAttempt: Long?, now: Long): Boolean = lastAttempt == null || now - lastAttempt >= MIN_GAP_MS || now < lastAttempt
}

/**
 * 检查更新,没有自己的服务器:只问 GitHub 发布页接口;连不上、限流、读不懂都算这次没查到。
 * 查到的版本和上次自动检查的时间存在 [file],App 重开后红点还在;装了新版后当前版本不再落后,红点自然消失。
 * 只告诉用户有新版,不下载也不安装。
 */
class UpdateChecker(
    private val file: File,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient(),
    private val releaseApi: String = MikaLinks.LATEST_RELEASE_API,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    // The Yunkai Next composition root disables this until a separately reviewed release channel exists.
    private val enabled: Boolean = true,
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastAttempt: Long? = null
    private var loaded = false

    /** 每次打开 App 时调:到期就在后台查一次,失败不吭声。 */
    fun autoCheckInBackground() {
        if (!enabled) return
        scope.launch { autoCheck() }
    }

    /** 用户点了「检查更新」。 */
    fun checkInBackground() {
        if (!enabled) return
        scope.launch { check(manual = true) }
    }

    /** 读出存着的结果(只读一次),红点不用等联网。 */
    suspend fun load() = mutex.withLock { loadLocked() }

    /**
     * 到期就查一次,返回这次有没有真的去查。开查前先记下这次的时间:同时进来的第二次(查还没完)看到的是刚记的时间,
     * 不会再查一遍。
     */
    suspend fun autoCheck(): Boolean {
        if (!enabled) return false
        val due = mutex.withLock {
            loadLocked()
            UpdateSchedule.due(lastAttempt, now()).also { if (it) lastAttempt = now() }
        }
        if (due) check(manual = false)
        return due
    }

    suspend fun check(manual: Boolean) {
        if (!enabled) return
        mutex.withLock {
            loadLocked()
            if (manual) _state.update { it.copy(status = CheckStatus.Checking) }
            val found = withContext(Dispatchers.IO) { fetchLatest() }
            if (found != null) {
                _state.update { it.withLatest(found) }
            }
            if (manual) _state.update { it.copy(status = if (found != null) CheckStatus.Done else CheckStatus.Failed, manualRounds = it.manualRounds + 1) }
            save()
        }
    }

    /** GitHub 上最新的发布;没查到为空。 */
    internal fun fetchLatest(): ReleaseInfo? = if (!enabled) null else runCatching { Releases.fromGitHub(get(releaseApi)) }.getOrNull()

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mika/$currentVersion")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("empty body")
        }
    }

    private fun UpdateState.withLatest(r: ReleaseInfo?): UpdateState =
        copy(latest = r, update = r?.takeIf { AppVersion.isNewer(it.version, currentVersion) })

    private fun loadLocked() {
        if (!enabled) return
        if (loaded) return
        loaded = true
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        lastAttempt = (o["lastAttempt"] as? JsonPrimitive)?.longOrNull
        val latest = (o["latest"] as? JsonObject)?.let(Releases::fromJson)
        _state.update { it.withLatest(latest) }
    }

    private fun save() {
        val o = buildJsonObject {
            lastAttempt?.let { put("lastAttempt", it) }
            _state.value.latest?.let { put("latest", Releases.toJson(it)) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        }
    }

    companion object {
        /** 国内连 GitHub 时快时慢:超时放短,失败就换备用来源。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
