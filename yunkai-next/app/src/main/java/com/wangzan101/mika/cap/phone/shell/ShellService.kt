package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.wangzan101.mika.core.shell.DevtoolsSocket
import com.wangzan101.mika.core.uistate.PressKeys
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import com.wangzan101.mika.shell.IMikaShell

/**
 * 以 shell 身份(uid 2000)常驻,持有 Agent 的虚拟屏,跑在内置助手进程([HelperMain])里。
 * 进程结束时虚拟屏及其上的 App 一并销毁,所以必须常驻而不是一次性命令。
 *
 * 隐藏接口一律反射调用,反射失败时退回到同等效果的 shell 命令。
 */
class ShellService(
    /** 只接受这个 uid(Mika)的调用。 */
    private val allowedUid: Int,
    /** 静音记录放的目录;shell 身份能写的位置。 */
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    /** 被静音的包 → 静音前的 PLAY_AUDIO 模式("default" 表示原先未设置),落在 [stateDir] 里,进程被杀后下次启动接着恢复。 */
    private val muted = AudioMuteLedger(File(stateDir, "mika_audio_mute_$allowedUid"))
    /** appops 作用的用户:Mika 所在的用户。 */
    private val userArg = (allowedUid / PER_USER_RANGE).toString()

    // 不能用 getSystemService:那样拿到的 DisplayManager 绑的是系统上下文,包名与 shell uid 对不上
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // 同上,剪贴板要以 shell 包名访问:系统只对来自 shell 的剪贴板认「不弹已复制浮层」的标记
    private val clipboard: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val clipLock = Any()
    /** 查占用和启动之间不能插进另一个任务的启动:两个任务同时开同一个 App 时只有一个能成。 */
    private val launchLock = Any()
    /** setClipText 之前用户的剪贴板(null 表示原先确实是空的,读不到的不会换出);[clipSaved] 为 false 表示没有待还原的。 */
    private var savedClip: ClipData? = null
    private var clipSaved = false
    /** Mika 最后放进去的文字;还原前据此判断剪贴板还是不是 Mika 的(用户中途复制了别的就不动)。 */
    private var ourText: String? = null

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        val vd = displayManager.createVirtualDisplay("mika-agent", width, height, dpi, surface, displayFlags())
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setImePolicy(id)
        id
    } catch (t: Throwable) {
        Log.e(TAG, "createDisplay failed", t)
        -1
    }

    /** 只往这个服务自己建的虚拟屏上启动,调用方传错屏号(比如主屏)也不会打扰用户。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    /**
     * 先确认用户没在主屏用这个 App:若它在主屏有可见的任务就不启动(返回 [START_IN_USE_ON_MAIN]),
     * 否则系统会把用户正在看的界面挪走或打断。后台任务可以被拉到虚拟屏上。
     * 再解析启动入口(包里有些 Activity 是跳板,直接启动会立刻跳走、只在虚拟屏留下空任务),
     * 以 shell 包名调用 IActivityTaskManager.startActivityAsUser,带 ActivityOptions.setLaunchDisplayId。
     */
    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            // 看不清主屏状态时宁可不启动,也不冒险打扰用户
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
            //  requestCode, flags, profilerInfo, options, userId)
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($packageName) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            exec("am", "start", "--display", "$displayId", "-n", component) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /** 这些包里有在 Mika 另一块屏(不是 [displayId])上的任务:那是别的任务正在用的 App。 */
    private fun onOtherOwnDisplay(packages: Collection<String>, displayId: Int): Boolean {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) return false
        val displayField = TASK_INFO.getField("displayId")
        return allTasks().any { t -> displayField.getInt(t) in others && packagesOf(t).any { it in packages } }
    }

    /**
     * 只有这个 App 的任务全在 Mika 的屏上时才结束进程再启动(回到初始页面)。
     * 它在别的屏上还有任务(用户的状态)就绝不结束,改为普通启动并返回 [START_OK_COLD_IGNORED];
     * 用户正在主屏用它时不启动。
     */
    override fun restartApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        // 不是自己建的屏(含主屏)一律不动,更不能先结束进程
        if (!ownDisplay(displayId)) return START_ERROR
        val decision = try {
            // 别的任务正在 Mika 另一块屏上用它:绝不结束它的进程
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
            val displayField = TASK_INFO.getField("displayId")
            val taskDisplays = allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }
            ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect tasks", t)
            return START_ERROR
        }
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) return START_NOT_FOUND
        when (decision) {
            ColdStart.IN_USE -> START_IN_USE_ON_MAIN
            ColdStart.WARM -> startApp(displayId, packageName).let { if (it == START_OK) START_OK_COLD_IGNORED else it }
            ColdStart.RESTART -> {
                if (exec("am", "force-stop", "--user", "current", packageName) != 0) return START_ERROR
                startApp(displayId, packageName)
            }
        }
    }

    /**
     * 打开链接或设置页。外壳这边再按 [ShellLinks] 查一次链接;解析不到返回 [START_UNRESOLVED]。
     * 能打开它的 App 在主屏有任务就不启动:带 NEW_TASK 的链接可能落进那个任务,出现在用户眼前。
     * 只有一个 App 能打开时直接指定到那个页面,免得系统另选。
     */
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        ShellLinks.refuse(action, uri, packageName)?.let { return it }
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        packageName?.let { intent.setPackage(it) }
        // 网页式链接只交给声明了可从链接打开的页面,不碰 App 的内部页面
        if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = activitiesFor(intent, null) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(link) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
                ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                    (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
            exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * 把文字分享给 [packageName]:ACTION_SEND text/plain 带 EXTRA_TEXT,只交给这个包。它没有收文字分享的页面返回 [START_UNRESOLVED];
     * 和 [startApp] 一样,用户在主屏用着它时不启动。只有一个收分享的页面时直接指定,有几个时由系统问用哪一个。
     */
    override fun startShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val intent = ShareIntent.of(packageName, text)
        val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, ShareIntent.TYPE, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * 系统动作(见 [SystemIntents]):只放行那几种 intent。没有页面接返回 [START_UNRESOLVED];和 [startView] 一样,
     * 接手的 App 在主屏有任务就不启动。先以 shell 身份启动;不成(接手的页面要求 shell 没有的权限,如设闹钟)时发出 Mika 做好的
     * [asMika]:以 Mika 的身份启动(权限按 Mika 查),屏和后台启动按发出的这一方(shell)查,仍落在这块屏上。
     */
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: android.app.PendingIntent?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val asShell = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, intent.type, null, null,
                0, 0, null, options.toBundle(), USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(${intent.action}) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
            false
        }
        if (asShell) return START_OK
        if (asMika == null) return START_ERROR
        // 安卓 14 起 PendingIntent 的发出方要明说允许它从后台启动页面
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return try {
            asMika.send(context, 0, null, null, null, null, options.toBundle())
            START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "sending ${intent.action} as Mika failed", t)
            START_ERROR
        }
    }

    /**
     * 能接 [intent] 的页面,(包名, 完整类名)。系统接口在这一侧查不了时改问系统服务(见 [ActivityQuery]);都不行时为 null。
     * [type] 是 intent 的数据类型(命令里要单独给)。
     */
    private fun activitiesFor(intent: Intent, type: String?): List<Pair<String, String>>? = try {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName to it.activityInfo.name }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentActivities failed, asking the package service", t)
        val args = ActivityQuery.args(intent.action!!, type, intent.dataString, intent.categories?.firstOrNull(), intent.`package`)
        sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
            .also { if (it == null) Log.e(TAG, "cannot resolve ${intent.action}") }
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    /** 该包在主屏有可见任务,或者就是主屏最上面的任务。 */
    private fun inUseOnMainScreen(packageName: String): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            packageName in packagesOf(task) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    /**
     * IActivityTaskManager.getTasks:按最近使用排序,shell 持有 REAL_GET_TASKS,能看到所有 App 的任务。
     * API 33+ 有带 displayId 的重载,优先用它;更早的版本取全部再按 TaskInfo.displayId 过滤。
     */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    /** 所有屏上的任务:带屏号的重载传 INVALID_DISPLAY 即不按屏过滤。 */
    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun task(taskId: Int): Any? {
        val idField = TASK_INFO.getField("taskId")
        return allTasks().firstOrNull { idField.getInt(it) == taskId }
    }

    override fun displayOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    override fun displayOfPackage(packageName: String): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackage failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        task(taskId)?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        null
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId),即 `am display move-stack` 背后的调用。
     * 真机(安卓 14)手动验证:任务在虚拟屏与主屏之间双向都能挪。Mika 启动的任务都是根任务。
     */
    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    /** 主屏任务的记录:Agent 动作前开始盯,见 [MainScreenWatch]。第一次用到才建(线程、任务栈监听)。 */
    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    override fun userOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("userId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "userOfTask failed", t)
        -1
    }

    /** 所有屏上的任务,换成 [MainScreenLog.Task];页面种类读不到时当作普通 App 的页面。 */
    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
            )
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // 与 press_key 同一张白名单:即便调用方出错,也不从这里发出音量、电源、Home 之类的整机按键
        if (!PressKeys.isAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    /** Ctrl+A / Ctrl+V:修饰键写在按键事件的 metaState 里(App 按它认组合键),只放行这两个,只发到 Mika 自己的屏(粘贴不能落到用户的屏上)。 */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
            Log.w(TAG, "refused ctrl key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { false }
    }

    override fun listUsers(): String? = sh("pm", "list", "users").takeIf { it.first == 0 }?.second

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * 一根手指的一笔:按下、按 [times] 逐点移动、在最后一点抬起;每个事件到了它的时刻才注入(事件时间就是那一刻),
     * App 看到的速度就是 Mika 排好的快慢。只发到 Mika 自己的屏。按下没注入成功返回 false(调用方改走别的路);
     * 按下之后中途哪个点没注入成功也照样往下走,最后一定补发抬起,不让手指一直按着。
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    /**
     * 只有 DOWN 就没注入成功时才退回 shell 命令;DOWN 已送达后再退回会造成一次多余的完整点击/按键,
     * 这时只补发 UP(失败重试一次)。
     */
    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean {
        if (!inject(down, displayId)) return fallback()
        return inject(up, displayId) || inject(up, displayId)
    }

    /**
     * `appops set <pkg> PLAY_AUDIO ignore`;首次静音时记下原模式(同时落文件),取消静音时恢复。
     * 原来没设置过的恢复成 allow,不写 default(见 [AudioMuteLedger.restoreMode])。
     * 命令失败返回 false,且不记录原模式(读不到原值就不改)。
     */
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = synchronized(this.muted) {
        if (muted) {
            if (!this.muted.contains(packageName)) {
                val (code, out) = appops("get", packageName)
                if (code != 0) return false
                this.muted.put(packageName, parsePlayAudioMode(out))
            }
            appops("set", packageName, "ignore").first == 0
        } else {
            val ok = appops("set", packageName, AudioMuteLedger.restoreMode(this.muted[packageName])).first == 0
            if (ok) this.muted.remove(packageName)
            ok
        }
    }

    /** 启动时把上次没来得及恢复的包还原(只动记录里的)。 */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll { pkg, mode -> appops("set", pkg, mode).first == 0 } }
    }

    private fun appops(verb: String, packageName: String, mode: String? = null): Pair<Int, String> =
        if (mode == null) sh("appops", verb, "--user", userArg, packageName, "PLAY_AUDIO")
        else sh("appops", verb, "--user", userArg, packageName, "PLAY_AUDIO", mode)

    override fun getAudioMode(packageName: String): String? {
        val (code, out) = appops("get", packageName)
        return if (code == 0) parsePlayAudioMode(out) else null
    }

    /** "default"(没设置过)按 allow 写,理由同 [AudioMuteLedger.restoreMode]。 */
    override fun setAudioMode(packageName: String, mode: String): Boolean {
        if (!MODE_RE.matches(mode)) return false
        val ok = appops("set", packageName, AudioMuteLedger.restoreMode(mode)).first == 0
        if (ok) synchronized(muted) { muted.remove(packageName) }
        return ok
    }

    /**
     * 只有读得到用户原来的剪贴板才动它:读不到就分不清「原来是空的」和「有内容但看不到」,
     * 覆盖了就还原不回去,这时返回 [CLIP_UNREADABLE],剪贴板保持原样。
     */
    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            if (!clipSaved) {
                if (!canReadClipboard()) return CLIP_UNREADABLE
                val prior = clipboard.primaryClip
                // 读到空不一定是空:系统可能悄悄拦下了读取,这时有无、描述还会说有内容
                when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                    ShellClip.Read.Unreadable -> {
                        Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                        return CLIP_UNREADABLE
                    }
                    ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                    ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
                }
                savedClip = prior
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            clipboard.setPrimaryClip(clip)
            ourText = text
            CLIP_SET
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip() = synchronized(clipLock) {
        if (!clipSaved) return
        runCatching {
            val prior = savedClip
            val now = nowState(ourText)
            val action = ShellClip.restore(prior == null, now)
            Log.i(TAG, "restoreClip: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(prior!!))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }
        savedClip = null
        clipSaved = false
        ourText = null
    }

    override fun savedClipText(): String? = synchronized(clipLock) {
        if (!clipSaved) return null
        runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
    }

    override fun restoreClipText(original: String, ours: String) = synchronized(clipLock) {
        runCatching {
            val now = nowState(ours)
            val action = ShellClip.restore(original.isEmpty(), now)
            Log.i(TAG, "restoreClipText: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(ClipData.newPlainText("", original)))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
        }.onFailure { Log.w(TAG, "restoreClipText failed", it) }
        Unit
    }

    /** 剪贴板此刻是不是 Mika 放进去的 [ours];读不到(有内容但取不到)时不当成空的。 */
    private fun nowState(ours: String?): ShellClip.Now {
        val now = clipboard.primaryClip
        return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
            ShellClip.Read.Empty -> ShellClip.Now.Empty
            ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
            ShellClip.Read.Has -> {
                val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                    now.getItemAt(0).text?.toString() == ours
                if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
            }
        }
    }

    /** shell 要有后台读剪贴板的权限、且读剪贴板的权限操作没被关掉,才算读得到。 */
    private fun canReadClipboard(): Boolean = runCatching {
        val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
        val ops = context.getSystemService(AppOpsManager::class.java)
        granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun suppressOverlay(clip: ClipData): ClipData {
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    /**
     * Mika 粘贴的内容(可能是地址、手机号等)标成敏感:系统和输入法的剪贴板预览、历史不显示原文。
     * 只标 Mika 自己放进去的,还原用户的剪贴板时保持原样。
     */
    private fun markSensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        return clip
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // 内置助手的进程不跟着 Mika 结束,Binder 也可能被别人拿到:只有 Mika 能调用接口方法
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        fun result(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }
        if (!ownDisplay(displayId)) return result(-1, "No task display is active.")
        if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                .apply { environment()["MIKA_DISPLAY_ID"] = displayId.toString() }.start()
        } catch (t: Throwable) {
            return result(-1, t.message ?: "Command could not start.")
        }
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < COMMAND_OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, COMMAND_OUTPUT_MAX - bytes.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val output = bytes.toString(Charsets.UTF_8.name()) + if (bytes.size() >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
        return result(if (finished) process.exitValue() else -1, output)
    }

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? {
        val pid = DevtoolsSocket.pidOf(name) ?: return null
        // 只连 Mika 自己进程的调试套接字:别的 App 的网页不经这里
        val uid = runCatching { DevtoolsSocket.procUid(File("/proc/$pid/status").readText()) }.getOrNull()
        if (uid != allowedUid) {
            Log.w(TAG, "devtools socket refused: pid $pid is not Mika's (uid $uid)")
            return null
        }
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        } catch (e: Exception) {
            Log.w(TAG, "devtools socket $name: ${e.message}")
            null
        } finally {
            // 交出去的是复制的 fd,这一份关掉
            runCatching { socket.close() }
        }
    }

    override fun releaseAll() {
        restoreClip()
        synchronized(muted) { muted.packages() }.forEach { setAudioMuted(it, false) }
        synchronized(displays) { displays.values.toList().also { displays.clear() } }.forEach { it.release() }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    private fun displayFlags(): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        // 只有 OWN_FOCUS 时,虚拟屏上启动 App 或 App 自己开新页面都会让它成为「最上层焦点屏」,
        // 主屏的焦点窗口随之清空;STEAL_TOP_FOCUS_DISABLED(需同时有 OWN_FOCUS、TRUSTED)让它不抢主屏的焦点
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        return flags
    }

    /** IWindowManager.setDisplayImePolicy:输入法不弹到用户的主屏。选哪种策略见 IME_POLICY。 */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        // API 34 起注入入口在 InputManagerGlobal,更早在 InputManager
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        manager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /**
     * 跑一条 shell 命令,返回 (退出码, 输出);超时返回 -1。
     * 输出在单独线程里读,卡住的命令不会把 binder 线程永远堵住。
     */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    companion object {
        private const val TAG = "MikaShell"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        // injectStroke 一笔的上限:点数、从按下到抬起的毫秒数
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 10L
        private const val COMMAND_OUTPUT_MAX = 64 * 1024
        private const val READER_JOIN_MS = 1_000L
        private val MODE_RE = Regex("[a-z_]+")
        private const val PER_USER_RANGE = 100_000

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        /** 冷启动被改成了普通启动:App 在 Mika 的屏以外还有任务。 */
        const val START_OK_COLD_IGNORED = 4
        /** startView:没有 App 能打开这个链接。 */
        const val START_UNRESOLVED = 5
        /** startView:链接不允许打开(见 [ShellLinks])。 */
        const val START_INVALID_LINK = 6
        /** 这个 App 正在 Mika 的另一块屏上给别的任务用。 */
        const val START_IN_USE_BY_OTHER = 7

        // setClipText 的结果
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        private const val CLIP_LABEL = "Mika"
        // AppOpsManager 里这个名字是隐藏的
        private const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        private const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        private const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        // WindowConfiguration.ACTIVITY_TYPE_*:桌面、最近任务、助理等是别的种类
        private const val ACTIVITY_TYPE_UNDEFINED = 0
        private const val ACTIVITY_TYPE_STANDARD = 1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*(部分为隐藏常量)
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, _FALLBACK_DISPLAY = 1, _HIDE = 2。
        // 真机(安卓 14)实测 LOCAL:在虚拟屏聚焦输入框后输入法窗口出现在虚拟屏上,主屏焦点不变,无需 HIDE。
        private const val IME_POLICY = 0

        // SystemUI 的约定:来自 shell 的剪贴板带上它就不显示「已复制」浮层(否则会弹在用户的主屏上)
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        /** 解析 `appops get <pkg> PLAY_AUDIO`:"PLAY_AUDIO: ignore; time=..." → "ignore";未设置 → "default"。 */
        internal fun parsePlayAudioMode(output: String): String = AudioMuteLedger.parseSetMode(output) ?: "default"
    }
}
