package com.wangzan101.mika.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.view.Surface;

// 运行在 shell 身份(uid 2000)进程里的常驻服务:经无线调试拉起的内置助手。
interface IMikaShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=已启动 1=用户正在主屏使用该 App(不启动,不打扰主屏) 2=没有可启动的入口 3=出错
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    // 冷启动:先结束该 App 的进程再启动;返回值同 startApp,用户正在主屏使用时不结束、返回 1;
    // App 在 Mika 的屏以外还有任务时不结束,改为普通启动并返回 4
    int restartApp(int displayId, String packageName) = 9;
    // 该包当前的 PLAY_AUDIO 模式(allow/ignore/default…);读不到返回 null
    String getAudioMode(String packageName) = 10;
    // 把 PLAY_AUDIO 设回指定模式;用于 Mika 异常退出后按持久化记录恢复
    boolean setAudioMode(String packageName, String mode) = 11;
    // 任务所在的屏;任务已不存在返回 -1
    int displayOfTask(int taskId) = 12;
    // 任务最上面页面所属的包;任务不存在返回 null
    String packageOfTask(int taskId) = 13;
    // 以 shell 身份把剪贴板设成这段纯文字(不弹系统的「已复制」浮层),供粘贴到只认真实输入的编辑区。
    // 内容标为敏感(API 33+)。第一次设置前记下原剪贴板;读不到原剪贴板时不动它。返回 0=已设置 1=读不到原剪贴板(未改动) 2=出错
    int setClipText(String text) = 14;
    // 还原 setClipText 之前的剪贴板(原先为空则清空);剪贴板已被用户换成别的内容、或此刻读不到时不动;被清空了就放回去
    void restoreClip() = 15;
    // 打开链接或安卓设置页(action 为空时按 VIEW 打开 uri;packageName 限定由哪个 App 打开)。
    // 返回值同 startApp,另有 5=没有 App 能打开 6=链接不允许;解析出的 App 在主屏有任务时不启动、返回 1
    int startView(int displayId, String action, String uri, String packageName) = 17;
    // 该包的任务所在的 Mika 虚拟屏(并行任务查冲突用);不在 Mika 的任何一块屏上返回 -1
    int displayOfPackage(String packageName) = 18;
    // setClipText 之前用户剪贴板的文字(原先为空或不是文字时为空串);没有待还原的返回 null。供 Mika 加密留底,进程被杀后还原
    String savedClipText() = 19;
    // 剪贴板仍是 Mika 放进去的 ours 时,换回用户原来的文字 original(空串则清空);已被用户换掉时不动
    void restoreClipText(String original, String ours) = 20;
    // 服务所在进程的 [uid, pid];Mika 收下内置助手的 Binder 前据此确认它就在来交接的那个进程里
    int[] identity() = 21;
    // 撤掉 Mika 这次绑定留下的改动(还原剪贴板、解除静音、释放所有虚拟屏),进程不退出;内置助手解绑时用
    void releaseAll() = 22;
    // 以 shell 身份连 Mika 自己 WebView 的调试套接字(抽象名 webview_devtools_remote_<pid>,pid 须是 Mika 的进程),
    // 把连好的套接字交回;名字不合规矩或连不上返回 null。Mika 自己直连被系统拦下时用
    ParcelFileDescriptor openDevtoolsSocket(String name) = 23;
    // 在本任务已建好的虚拟屏上下文中，以 shell 身份执行命令；返回 exitCode 和 output。
    Bundle runSystemCommand(int displayId, String command) = 24;
    // 当前虚拟屏最上面 Activity 的完整类名；没有任务时返回 null。
    String topActivity(int displayId) = 25;
    // 往这块屏发 Ctrl+键(只放行 A 全选、V 粘贴):树里没有输入框、只知道焦点在哪时打字用
    boolean injectCtrlKey(int displayId, int keyCode) = 26;
    // `pm list users` 的原样输出;出错为 null
    String listUsers() = 27;
    // `pm list packages --user <userId>` 的原样输出;出错为 null
    String listPackages(int userId) = 28;
    // 该包是系统自带的(含更新过的系统 App);查不到返回 false
    boolean isSystemPackage(String packageName) = 29;
    // 把一段文字分享给该包(ACTION_SEND text/plain,EXTRA_TEXT),启动到这块屏;返回值同 startApp,另有 5=该包没有收文字分享的页面;
    // 该包在主屏有任务时不启动、返回 1
    int startShare(int displayId, String packageName, String text) = 30;
    // 从现在起盯着主屏 windowMs:这段时间里主屏最上面换了的任务都记下(见 mainArrivals);返回此刻主屏最上面的任务,没有为 -1。
    // Agent 每个动作之前调:App 不从自己在 Mika 屏上的页面、而是从后台打开另一个 App 时,系统把它放到主屏,Mika 据此认出、挪回来
    int watchMainScreen(int windowMs) = 31;
    // sinceUptimeMs(SystemClock.uptimeMillis)之后主屏冒到最上面的任务,先后排;字段见 MainScreenWatch.toBundle。出错为 null
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    // 任务所属的用户(分身、工作资料是别的用户);任务不存在返回 -1
    int userOfTask(int taskId) = 33;
    // 在 Mika 自己的屏上按一根手指的一笔触摸:times[i] 毫秒(相对按下)时手指在 (xs[i], ys[i]);第一个点按下,最后一个点抬起。
    // 按时间逐个注入,注完才返回(至多约 10 秒);不是 Mika 的屏、参数不对或按下没注入成功返回 false
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    // 系统动作(设闹钟、计时器、打开闹钟列表、打开预填的新日程,见 SystemIntents;别的 intent 一律不启动),启动到这块屏。
    // 先以 shell 身份启动;接手的页面要求 Mika 有的权限(如设闹钟)而 shell 没有时,改为发出 Mika 做好的 asMika(以 Mika 的身份启动、
    // 屏仍是这块)。返回值同 startView:5=没有页面接,6=不在放行之列;接手的 App 在主屏有任务时不启动、返回 1
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMika) = 35;

    // 沿用早先定下的销毁事务号;AIDL 要求要么全部方法编号要么都不编号
    void destroy() = 16777114;
}
