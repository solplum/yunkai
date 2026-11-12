package com.wangzan101.mika.cap.phone.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.shell.HandoffNonces
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.NonceSlots
import com.wangzan101.mika.core.shell.NonceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.ParcelFileDescriptor
import java.io.IOException
import com.wangzan101.mika.shell.IMikaShell

/**
 * Mika 进程里内置助手 Binder 的落点。助手经 [HandoffProvider] 交来 Binder,在这里核对后收下;
 * 由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份(助手只认一个进程里的一个落点),
 * [BuiltinShellConnection] 和看门狗都看它的 [shell]。
 */
class HelperHub(context: Context) {
    private val app = context.applicationContext

    private val _shell = MutableStateFlow<IMikaShell?>(null)
    /** 现在手里的助手接口;助手进程死了就变回 null。 */
    val shell: StateFlow<IMikaShell?> = _shell.asStateFlow()

    /** 经助手代连本进程的 WebView 调试套接字;助手不在时抛 IOException,助手拒绝或连不上为 null。 */
    fun openDevtoolsSocket(endpoint: String): ParcelFileDescriptor? {
        val sh = shell.value ?: throw IOException("phone helper is not running")
        return sh.openDevtoolsSocket(endpoint)
    }

    /** 交给助手的本进程令牌:Mika 进程一死,助手就从它的死亡通知得知,撤掉 Mika 留下的改动。 */
    private val token = Binder()
    private val nonces: HandoffNonces by lazy { HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime) }

    /** 经 adbd 起新助手前调:这个一次性口令经 exec 流的标准输入交给助手。 */
    fun issueLaunchNonce(): String = nonces.issueLaunch()

    /** 解除配对:口令全部作废(还活着的助手下次交接会被拒绝并退出),手里的接口也放掉。 */
    fun forget() {
        nonces.clear()
        _shell.value = null
    }

    /**
     * 助手来交接。只收 shell(uid 2000)、自报 uid/pid 与调用方一致、跑的是当前这份 APK、口令对的;
     * 再调一次 Binder 确认它确实在来交接的这个进程里,最后才用掉口令并换发下一枚。
     */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        HelperHandoff.check(
            callingUid, callingPid,
            reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(HelperHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(it) }
        val gate = nonces
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        // 口令不对就不碰对方给的 Binder
        if (!gate.matches(nonce)) return reject(HelperHandoff.Reject.BadNonce)
        val shell = IMikaShell.Stub.asInterface(binder!!)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject(HelperHandoff.Reject.BinderElsewhere)
        }
        val next = gate.accept(nonce) ?: return reject(HelperHandoff.Reject.BadNonce)
        try {
            binder.linkToDeath({ _shell.compareAndSet(shell, null) }, 0)
        } catch (e: Exception) {
            return reject(HelperHandoff.Reject.NoBinder)
        }
        // 换了新的助手:旧的随后会被新的结束,它的死亡通知因为不是当前这个而被忽略
        _shell.value = shell
        Log.i(TAG, "helper pid $callingPid handed off")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    private fun reject(why: HelperHandoff.Reject): Bundle {
        Log.w(TAG, "helper handoff rejected: $why")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, false)
            putString(HelperHandoff.KEY_REASON, why.name)
        }
    }

    private companion object {
        const val TAG = "MikaHelperHub"
        const val PREFS = "builtin_helper"
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun load() = NonceSlots(prefs.getString("resume", null), prefs.getString("launch", null), prefs.getLong("launchAt", 0))
        // 同步写:助手拿到新口令后 Mika 可能马上被杀,新进程要认得它
        override fun save(slots: NonceSlots) {
            prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch).putLong("launchAt", slots.launchAt).commit()
        }
    }
}

/**
 * 内置助手交接 Binder 的入口。要对 shell 开放所以是 exported,清单里另要求 INTERACT_ACROSS_USERS_FULL
 * (shell 有,普通 App 没有),普通 App 连 provider 都拿不到;真正的把关在 [HelperHub.offer]。
 * 落点是 App 的那一份([PhoneServices.of]):App 对象在 provider 之前就构造好了,但交接调用可能早于或与 Application.onCreate 同时到达;
 * 安全靠 MikaApp.phone 与 [HelperHub] 里口令都是同步的 lazy(谁先到谁建、只建一次),[HelperHub.offer] 本身也加了锁,不依赖 onCreate 先跑完。
 */
open class HandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        return PhoneServices.of(ctx).hub.offer(extras, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
