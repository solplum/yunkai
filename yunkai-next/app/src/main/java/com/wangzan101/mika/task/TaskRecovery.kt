package com.wangzan101.mika.task

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.SharedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** 持久化的一组「原值」;值为 null 表示原本没有这一项。没有记录时 [load] 返回 null。 */
interface MarkerStore {
    fun load(): Map<String, String?>?
    fun save(values: Map<String, String?>)
    fun clear()
}

/** 存在 SharedPreferences 里,同步提交:必须先落盘再去改系统状态。 */
class PrefsMarker(private val prefs: SharedPreferences, private val name: String) : MarkerStore {
    override fun load(): Map<String, String?>? {
        val keys = prefs.getStringSet("$name.keys", null) ?: return null
        return keys.associateWith { prefs.getString("$name.v.$it", null) }
    }

    override fun save(values: Map<String, String?>) {
        val e = prefs.edit()
        clearInto(e)
        e.putStringSet("$name.keys", values.keys.toSet())
        values.forEach { (k, v) -> e.putString("$name.v.$k", v) }
        e.commit()
    }

    override fun clear() {
        prefs.edit().also { clearInto(it) }.commit()
    }

    private fun clearInto(e: SharedPreferences.Editor) {
        prefs.all.keys.filter { it.startsWith("$name.") }.forEach { e.remove(it) }
    }
}

/** 静音所需的 shell 能力。 */
interface AudioPort {
    /** 当前 PLAY_AUDIO 模式;读不到返回 null。 */
    fun mode(packageName: String): String?
    fun mute(packageName: String): Boolean
    fun setMode(packageName: String, mode: String): Boolean
}

class ShellAudio(private val shell: IMikaShell) : AudioPort {
    override fun mode(packageName: String): String? = runCatching { shell.getAudioMode(packageName) }.getOrNull()
    override fun mute(packageName: String): Boolean = runCatching { shell.setAudioMuted(packageName, true) }.getOrDefault(false)
    override fun setMode(packageName: String, mode: String): Boolean = runCatching { shell.setAudioMode(packageName, mode) }.getOrDefault(false)
}

/**
 * 任务期间关掉目标 App 的声音。静音前把原模式写进持久化记录:shell 服务随 Mika 进程一起结束,
 * 它内存里的原值会丢,只有这份记录能让下次启动时恢复。
 */
class AudioMute(private val port: AudioPort, private val store: MarkerStore) {
    @Synchronized
    fun mute(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) {
            val prior = port.mode(packageName) ?: return false
            store.save(saved + (packageName to prior))
        }
        return port.mute(packageName)
    }

    /** 只恢复一个 App(用户接管时让它能出声);恢复不了就留在记录里。没静音过的视为已恢复。 */
    @Synchronized
    fun restore(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) return true
        if (!port.setMode(packageName, saved[packageName] ?: "default")) return false
        val left = saved - packageName
        if (left.isEmpty()) store.clear() else store.save(left)
        return true
    }

    /** 恢复记录里的全部 App;恢复不了的留在记录里下次再试。 */
    @Synchronized
    fun restoreAll(): Boolean {
        val saved = store.load() ?: return true
        val left = saved.filterNot { (pkg, mode) -> port.setMode(pkg, mode ?: "default") }
        if (left.isEmpty()) store.clear() else store.save(left)
        return left.isEmpty()
    }
}

/**
 * Mika 借剪贴板粘贴期间用户原来的剪贴板文字(和 Mika 放进去的文字)的加密留底:外壳进程随 Mika 一起被杀时,
 * 它内存里的原剪贴板就没了,下次启动只能靠这份把用户的内容还回去。用 [cipher] 加密后存成一段文字,还原后删掉。
 */
class ClipBackup(private val read: () -> String?, private val write: (String?) -> Unit, private val cipher: KeyCipher) {
    fun save(original: String, ours: String) {
        val plain = buildJsonObject { put("original", original); put("ours", ours) }.toString().toByteArray()
        write(Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
    }

    /** (原来的文字, Mika 放进去的文字);没有留底、或解不开(密钥已换)时为 null。 */
    fun load(): Pair<String, String>? = runCatching {
        val data = read() ?: return null
        val o = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(data)))).jsonObject
        o.getValue("original").jsonPrimitive.content to o.getValue("ours").jsonPrimitive.content
    }.getOrNull()

    fun clear() = write(null)
}

/**
 * Mika 异常退出后留下的系统改动(App 静音、粘贴期间暂时换掉的剪贴板),在下次启动时恢复。
 * 静音记录每个任务一份(按 [TaskRun.key]),几个任务同时跑时各记各的包。
 * 任务期间的改动由任务自己收尾,恢复只处理没人收尾的那些:还在跑的任务([live])的记录不动,
 * 有任务在跑时也不还原剪贴板(那时它可能正借着剪贴板粘贴)。
 * 由 App 建一份、经 [Owner] 给出([live] 要在各任务间共用);等助手时用进程里共用的那条 [sharedShell]。
 */
class TaskRecovery(context: Context, private val sharedShell: SharedShell) {
    private val app = context.applicationContext
    private val clipCipher by lazy { KeystoreKeyCipher(CLIP_KEY_ALIAS) }

    /** 恢复与任务开工时的登记共用,保证二者不交错。 */
    val lock = Mutex()

    /** 还在跑的任务;进程被杀后为空,所有记录都归恢复处理。 */
    private val live = ConcurrentHashMap.newKeySet<String>()

    fun audioMute(shell: IMikaShell, runKey: String) = AudioMute(ShellAudio(shell), marker(runKey))

    private fun marker(runKey: String) = PrefsMarker(prefs(), "${MUTED}_$runKey")

    /** 现有的全部静音记录名(含旧版的那一份)。 */
    private fun markerNames(): List<String> =
        prefs().all.keys.filter { it.startsWith(MUTED) && it.endsWith(".keys") }.map { it.removeSuffix(".keys") }

    private fun liveNames() = live.mapTo(HashSet()) { "${MUTED}_$it" }

    fun pending(): Boolean =
        markerNames().any { it !in liveNames() } || (live.isEmpty() && prefs().getBoolean(CLIP_HELD, false))

    /** 任务开工:先恢复没人收尾的遗留,再登记为在跑。 */
    suspend fun beginRun(shell: IMikaShell, runKey: String) = lock.withLock {
        withContext(Dispatchers.IO) { recoverWith(shell) }
        live += runKey
    }

    /** 任务收尾(自己的静音已恢复)之后调用;没恢复成的留在记录里,下次由恢复处理。 */
    fun endRun(runKey: String) {
        live -= runKey
    }

    /** 粘贴期间剪贴板是 Mika 的;进程在这期间结束,下次启动让 shell 把用户原来的剪贴板还回去。 */
    fun markClipHeld(held: Boolean) {
        prefs().edit().putBoolean(CLIP_HELD, held).apply { if (!held) remove(CLIP_BACKUP) }.commit()
    }

    private fun clipBackup() = ClipBackup(
        { prefs().getString(CLIP_BACKUP, null) },
        { v -> prefs().edit().apply { if (v == null) remove(CLIP_BACKUP) else putString(CLIP_BACKUP, v) }.commit() },
        clipCipher,
    )

    /** 换剪贴板成功后留底;加密失败就不留(只是少了进程被杀后的还原)。 */
    fun saveClip(original: String, ours: String) {
        runCatching { clipBackup().save(original, ours) }.onFailure { Log.w(TAG, "clipboard backup not saved", it) }
    }

    /** 已持有 [lock] 时调用。 */
    fun recoverWith(shell: IMikaShell) {
        val records = markerNames().associateWith { PrefsMarker(prefs(), it) }
        if (!restoreRuns(records, ShellAudio(shell), liveNames())) Log.w(TAG, "muted apps not restored yet")
        if (live.isNotEmpty()) return
        // shell 进程还在就还原它记着的原剪贴板(剪贴板已被用户换掉时它不会动);
        // shell 也被杀过就按加密留底还原,两步都只在剪贴板仍是 Mika 的时才换
        val inShell = runCatching { shell.restoreClip() }.isSuccess
        val fromBackup = restoreClip(clipBackup()) { o, u -> shell.restoreClipText(o, u) }
        if (inShell && fromBackup) markClipHeld(false)
    }

    /**
     * 启动时调用:有遗留改动就等内置助手连上后恢复;等不到就留到下次。
     * 等助手时不占锁,免得新任务跟着等;拿到锁后再确认一次还有没有遗留。
     */
    suspend fun recover() {
        if (!pending()) return
        val connection = sharedShell.acquire()
        try {
            val shell = withTimeoutOrNull(SHELL_WAIT_MS) { connection.shell.filterNotNull().first() } ?: return
            lock.withLock { if (pending()) recoverWith(shell) }
        } finally {
            sharedShell.release()
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val taskRecovery: TaskRecovery
    }

    companion object {
        private const val TAG = "MikaTask"
        private const val PREFS = "task_recovery"
        private const val SHELL_WAIT_MS = 60_000L
        private const val CLIP_HELD = "clip_held"
        private const val CLIP_BACKUP = "clip_backup"
        private const val CLIP_KEY_ALIAS = "mika-clip-backup"
        /** 每个任务的静音记录名是 muted_<任务 key>;旧版只有一份,名为 muted,照样恢复。 */
        private const val MUTED = "muted"

        fun of(context: Context): TaskRecovery = (context.applicationContext as? Owner)?.taskRecovery
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement TaskRecovery.Owner to give the task recovery")

        /** 恢复 [records](记录名 → 记录)里不属于 [live] 的全部静音;都恢复了返回 true。 */
        fun restoreRuns(records: Map<String, MarkerStore>, port: AudioPort, live: Set<String>): Boolean =
            records.filterKeys { it !in live }.values.map { AudioMute(port, it).restoreAll() }.all { it }

        /** 按留底把用户原来的剪贴板还回去([restore] 只在剪贴板仍是 Mika 的时才换);还原了或没有留底返回 true,并删掉留底。 */
        fun restoreClip(backup: ClipBackup, restore: (original: String, ours: String) -> Unit): Boolean {
            val (original, ours) = backup.load() ?: return true.also { backup.clear() }
            return runCatching { restore(original, ours) }.onSuccess { backup.clear() }.isSuccess
        }
    }
}
