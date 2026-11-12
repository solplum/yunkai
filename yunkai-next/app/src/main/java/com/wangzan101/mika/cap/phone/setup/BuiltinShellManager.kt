package com.wangzan101.mika.cap.phone.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.wangzan101.mika.core.adb.AdbConnectPort
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.ConnectCandidates
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbServiceTypes
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.LocalPortScan
import com.wangzan101.mika.core.adb.NioPortScanner
import com.wangzan101.mika.core.adb.PortCache
import com.wangzan101.mika.core.shell.BuiltinShellEngine
import com.wangzan101.mika.core.shell.BuiltinShellPlatform
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.WirelessDebuggingAutoOn
import com.wangzan101.mika.core.shell.ShellCommandRunner
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.shell.adb.NsdAdbServiceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/**
 * 内置助手的真实现:配对、经本机 adbd 起助手、看门狗。由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份,
 * App 启动时由 [start] 开始看着助手;[hub] 是助手交来 Binder 的落点;[consent] 是用户对那几项能力的选择(「自动打开无线调试」看它)。
 */
class BuiltinShellManager(context: Context, private val hub: HelperHub, private val consent: () -> RecoveryConsent) {
    private val app = context.applicationContext
    private var started = false

    private val _scanningPorts = MutableStateFlow(false)
    /** 正在扫本机找无线调试端口(引导页显示「正在本机查找…」)。 */
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    private val platform: AndroidPlatform by lazy {
        AndroidPlatform(app, hub, { lastConnectCandidates = it }, { _scanningPorts.value = it })
    }

    /** 最近一次找连接端口时各来源给了几个候选(诊断信息用);这次进程里还没连过为 null。 */
    @Volatile var lastConnectCandidates: ConnectCandidates? = null
        private set

    /** 要起助手时替用户打开无线调试:只由 App 自己写系统设置(要起助手时没有别的连接可用)。 */
    val wirelessAutoOn: WirelessDebuggingAutoOn by lazy {
        WirelessDebuggingAutoOn(
            wants = { consent().wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = platform::wirelessDebugging,
            write = { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) },
        )
    }

    /** 配对客户端 + 看门狗。 */
    val engine: BuiltinShellEngine by lazy {
        BuiltinShellEngine(
            platform, hub.shell, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO, autoOn = wirelessAutoOn,
        )
    }

    /** 恢复和授权开关用:每条命令单独连一次已配对的 adbd,跑完就断。 */
    val commandRunner = ShellCommandRunner { command ->
        withContext(Dispatchers.IO) { platform.connect().use { it.exec(command).readText() } }
    }

    /** 用户在引导页填了无线调试的端口:下一次连接带上它(只用这一次,连上了就记作缓存),然后重试。 */
    suspend fun connectWithPort(port: Int) {
        platform.manualPort.set(port)
        engine.retry()
    }

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < MIN_SDK) return
        started = true
        val e = engine
        // 无线调试一打开就试着连回去;关掉时状态马上变成「无线调试已关」
        app.contentResolver.registerContentObserver(
            DeveloperSettings.wirelessDebuggingUri, false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = e.wirelessDebuggingChanged()
            },
        )
        // 卡在「没有 Wi-Fi」或无线调试关着时,一连上任意 Wi-Fi(不用能上网)就再试
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = e.wifiAvailable()
                },
            )
        }
        e.start()
    }

    private class AndroidPlatform(
        private val app: Context,
        private val hub: HelperHub,
        private val candidates: (ConnectCandidates) -> Unit,
        private val scanning: (Boolean) -> Unit,
    ) : BuiltinShellPlatform {
        /** 用户填的端口,下一次连接取走。 */
        val manualPort = AtomicReference<Int?>(null)
        private val scan = LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(portRangeText()) })
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val finder = NsdAdbServiceFinder(app)
        private val portCache = object : PortCache {
            override fun load() = prefs.getInt("port", 0).takeIf { it > 0 }
            override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
        }

        override fun paired() = Build.VERSION.SDK_INT >= MIN_SDK && prefs.getBoolean("paired", false)

        override fun setPaired(paired: Boolean) {
            prefs.edit().putBoolean("paired", paired).apply()
        }

        // 这一项没写过时读出 null:看不清就不拦着
        override fun wirelessDebugging(): Boolean? =
            Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }

        override fun wifiConnected(): Boolean = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(true)

        override suspend fun findPairingPort(timeoutMs: Long): Int? = finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs).firstOrNull()

        override fun pair(port: Int, code: String) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.identity(app))).pair(LOCALHOST, port, code)
        }

        override suspend fun launchHelper() {
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            // 助手每次交接前都核对 Mika 的签名,指纹取自本 App 自己的安装信息
            val signers = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            val pin = HelperHandoff.certPin(signers) ?: error("cannot read own signing certificate")
            val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), pin)
            val connection = connect(tls)
            // 流开着 sh 就一直等;交接到了(或超时)再关,交接结果仍由引擎从 HelperHub 判断
            val before = hub.shell.value
            connection.use {
                val stream = it.exec(command)
                stream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
            }
        }

        suspend fun connect(tls: AdbTls = AndroidAdb.tls(AndroidAdb.identity(app))): AdbConnection =
            AdbConnectPort(
                ::tlsPort, finder, portCache, report = candidates, scan = scan, manual = { manualPort.getAndSet(null) }, scanning = scanning,
            ).connect { host, port -> AdbConnection.open(host, port, tls) }

        /** 扫端口时只扫本机自己:127.0.0.1 和 Wi-Fi 上的 IPv4 地址。 */
        private fun localHosts(): List<String> = listOf(LOCALHOST) + runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        }.getOrDefault(emptyList())

        /** 系统分配临时端口的范围;有的系统不让 App 读,读不到就扫默认的一段。 */
        private fun portRangeText(): String? = runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()

        override fun stopHelper() {
            // destroy 会让助手进程当场退出,这一调用多半以 DeadObjectException 结束
            runCatching { hub.shell.value?.destroy() }
        }

        override fun forget() {
            hub.forget()
            AndroidAdb.forget(app)
            prefs.edit().remove("port").apply()
        }

        @SuppressLint("PrivateApi")
        private fun tlsPort(): Int? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, TLS_PORT_PROPERTY) as String?
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    companion object {
        /** 内置助手只在安卓 13 及以上启用(手机功能本来就要求这个版本)。 */
        const val MIN_SDK = Build.VERSION_CODES.TIRAMISU
        private const val PREFS = "builtin_shell"
        /** 安卓原版 adbd 把无线调试的连接端口写在这个系统属性里;读不到时靠缓存、mDNS、扫本机端口和用户填的端口。 */
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val LOCALHOST = AdbConnectPort.LOCALHOST
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val HANDOFF_WAIT_MS = 15_000L
    }
}
