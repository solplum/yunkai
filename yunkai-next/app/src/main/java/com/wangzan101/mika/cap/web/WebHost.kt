package com.wangzan101.mika.cap.web

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.wangzan101.mika.cap.web.cdp.DesktopProfile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/** 网页标签挂在哪里:私有显示上的 Presentation、直接加到该显示的窗口、或不挂窗口(页面可能没完全渲染)。 */
enum class HostMode { Presentation, WindowManager, Detached }

/**
 * 网页标签的后台宿主:App 自建的私有虚拟显示(不带 PUBLIC 标志,不经 shell 身份的助手),
 * 上面放一个窗口承载当前标签的 WebView。页面因此认为自己可见,定时器、动画帧、懒加载照常运转;
 * 显示的画面进 ImageReader,只给截图和视觉定位用。
 *
 * 两块显示:手机版标签挂在与主屏一样大的那块上;桌面版标签挂在桌面视口大小的那块上。每块都只在有标签要用时才建
 * (桌面版是默认,所以建宿主时先建它;建不起来就用手机那块),不白建、白拆显示;
 * 页面按 1 CSS 像素对 1 像素画,截图清楚、坐标不被压缩。同一时间只挂一个视图,[width]、[height]、截图都按它所在的那块。
 *
 * 构造、attach/detach、close 都在主线程调用。
 */
class WebHost(private val context: Context, widthPx: Int, heightPx: Int, densityDpi: Int) : Closeable {
    /** 一块私有显示及其上的窗口、帧。 */
    private inner class Layer(val width: Int, val height: Int, dpi: Int, name: String) {
        val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val virtualDisplay: VirtualDisplay
        val container: FrameLayout
        val mode: HostMode
        var presentation: Presentation? = null
        var windowManager: WindowManager? = null

        // pendingImage/copyScheduled/lastCopyAt/scratch 只在帧线程上动;frame 由 frameLock 保护,截图从任意线程读
        val frameLock = Any()
        var frame: Bitmap? = null
        var pendingImage: Image? = null
        var copyScheduled = false
        var lastCopyAt = 0L
        var scratch: ByteBuffer? = null

        init {
            var vd: VirtualDisplay? = null
            var addedWindow: View? = null
            try {
                reader.setOnImageAvailableListener({ r -> onFrame(r) }, frameHandler)
                val dm = context.getSystemService(DisplayManager::class.java)
                // flags=0:私有显示,只有本 App 能往上放内容
                vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, 0)
                    ?: error("createVirtualDisplay returned null")
                val display = vd.display
                var chosen = HostMode.Detached
                var box = showPresentation(display)?.also { chosen = HostMode.Presentation }
                if (box == null) box = addWindow(display)?.also {
                    chosen = HostMode.WindowManager
                    addedWindow = it
                }
                if (box == null) {
                    Log.w(TAG, "web tabs run detached: pages may not fully render")
                    box = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(width, height) }
                }
                virtualDisplay = vd
                container = box
                mode = chosen
                // 视图挂在哪个宿主上,从它的父容器就能找回来(换版本时据此换显示)
                box.tag = this@WebHost
            } catch (e: Throwable) {
                reader.setOnImageAvailableListener(null, null)
                presentation?.let { p -> runCatching { p.dismiss() } }
                addedWindow?.let { w -> runCatching { windowManager?.removeViewImmediate(w) } }
                vd?.release()
                reader.close()
                throw e
            }
            Log.i(TAG, "web host $name on display ${virtualDisplay.display.displayId} mode=$mode ${width}x$height@$dpi")
        }

        val displayId: Int get() = virtualDisplay.display.displayId

        private fun showPresentation(display: Display): FrameLayout? {
            var p: Presentation? = null
            return try {
                p = Presentation(context, display)
                val c = FrameLayout(p.context)
                p.setContentView(c)
                p.show()
                presentation = p
                c
            } catch (e: Exception) {
                Log.w(TAG, "Presentation on private display failed, trying WindowManager", e)
                runCatching { p?.dismiss() }
                null
            }
        }

        private fun addWindow(display: Display): FrameLayout? = try {
            val wctx = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null)
            val wm = wctx.getSystemService(WindowManager::class.java)
            val c = FrameLayout(wctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
                0,
                PixelFormat.OPAQUE,
            )
            wm.addView(c, lp)
            windowManager = wm
            c
        } catch (e: Exception) {
            Log.w(TAG, "WindowManager on private display failed, falling back to detached WebView", e)
            null
        }

        fun copy(): Bitmap? = synchronized(frameLock) { frame?.let { Bitmap.createBitmap(it, 0, 0, width, height) } }

        /** 帧线程已停之后调。 */
        fun release() {
            reader.setOnImageAvailableListener(null, null)
            pendingImage?.close()
            pendingImage = null
            presentation?.let { p -> runCatching { p.dismiss() } }
            container.removeAllViews()
            windowManager?.let { wm -> runCatching { wm.removeViewImmediate(container) } }
            virtualDisplay.release()
            reader.close()
            synchronized(frameLock) {
                frame?.recycle()
                frame = null
            }
        }

        // 每来一帧只留最新一张,至多每 100 毫秒拷一次;静态页面不再出新帧,压着的最后一帧也会被拷下
        private fun onFrame(r: ImageReader) {
            try {
                val image = r.acquireLatestImage() ?: return
                pendingImage?.close()
                pendingImage = image
                if (!copyScheduled) {
                    copyScheduled = true
                    val wait = (FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - lastCopyAt)).coerceAtLeast(0)
                    frameHandler.postDelayed(::copyPending, wait)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "frame acquire failed", e)
            }
        }

        private fun copyPending() {
            copyScheduled = false
            val image = pendingImage ?: return
            pendingImage = null
            try {
                val plane = image.planes[0]
                // 行尾可能有对齐填充:位图按行跨度分配一次反复用,截图时再裁到显示宽度
                val stridePx = plane.rowStride / plane.pixelStride
                synchronized(frameLock) {
                    val cur = frame
                    val target = if (cur != null && cur.width == stridePx && cur.height == image.height) cur
                    else Bitmap.createBitmap(stridePx, image.height, Bitmap.Config.ARGB_8888).also { cur?.recycle() }
                    val src = plane.buffer.apply { rewind() }
                    val needed = target.byteCount
                    if (src.remaining() >= needed) target.copyPixelsFromBuffer(src)
                    else {
                        // 最后一行可能不带填充,缓冲比整块位图短:先垫进一块等长的缓冲
                        val s = scratch?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed).also { scratch = it }
                        s.clear()
                        s.put(src)
                        s.rewind()
                        target.copyPixelsFromBuffer(s)
                    }
                    frame = target
                }
                lastCopyAt = SystemClock.uptimeMillis()
            } catch (e: Throwable) {
                Log.w(TAG, "frame copy failed", e)
            } finally {
                image.close()
            }
        }
    }

    private val phoneWidth = widthPx
    private val phoneHeight = heightPx
    private val phoneDpi = densityDpi
    private val frameThread: HandlerThread
    private val frameHandler: Handler
    private var phone: Layer? = null
    private var desktop: Layer? = null
    private var phoneFailed = false
    private var desktopFailed = false
    @Volatile private var active: Layer

    /** 当前挂着的那块显示(也就是网页视口)的像素尺寸。 */
    val width: Int get() = active.width
    val height: Int get() = active.height
    /** 当前挂着的那块显示的 id 与挂法。 */
    val displayId: Int get() = active.displayId
    val mode: HostMode get() = active.mode

    /** [close] 之后为 true:不能再往上挂视图。 */
    @Volatile var closed: Boolean = false
        private set

    init {
        checkMain()
        frameThread = HandlerThread("mika-web-frames").apply { start() }
        frameHandler = Handler(frameThread.looper)
        // 新标签默认桌面版:先建桌面那块;建不起来再建手机那块,两块都不行就抛出(调用方只给手机界面)
        active = try {
            desktopLayer() ?: Layer(widthPx, heightPx, densityDpi, PHONE_NAME).also { phone = it }
        } catch (e: Throwable) {
            frameThread.quitSafely()
            frameThread.join()
            throw e
        }
    }

    /**
     * 把标签的视图放进宿主窗口,替换已挂的那个。[desktop] 为 true 时挂到桌面视口那块显示上(建不起来就挂手机那块);
     * 默认按这个标签现在用的版本。
     */
    fun attach(tab: WebTab, desktop: Boolean = tab.devtools.wantsDesktopHost(tab.view)) {
        val view = tab.view
        checkMain()
        if (closed) return
        val target = (if (desktop) desktopLayer() ?: phoneLayer() else phoneLayer() ?: desktopLayer()) ?: active
        if (view.parent === target.container) {
            active = target
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        for (l in listOfNotNull(phone, this.desktop)) if (l !== target) l.container.removeAllViews()
        target.container.removeAllViews()
        target.container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        active = target
        if (target.mode == HostMode.Detached) layoutDetached(target, view)
    }

    fun detach(view: WebView) {
        checkMain()
        for (l in listOfNotNull(phone, desktop)) if (view.parent === l.container) l.container.removeView(view)
    }

    private fun phoneLayer(): Layer? {
        phone?.let { return it }
        if (phoneFailed) return null
        return try {
            Layer(phoneWidth, phoneHeight, phoneDpi, PHONE_NAME).also { phone = it }
        } catch (e: Throwable) {
            Log.w(TAG, "phone-size web display failed; mobile pages use the desktop-size display", e)
            phoneFailed = true
            null
        }
    }

    private fun desktopLayer(): Layer? {
        desktop?.let { return it }
        if (desktopFailed) return null
        return try {
            Layer(DesktopProfile.HOST_WIDTH, DesktopProfile.HOST_HEIGHT, DesktopProfile.HOST_DPI, "mika-web-desktop").also { desktop = it }
        } catch (e: Throwable) {
            Log.w(TAG, "desktop-size web display failed; desktop pages use the phone-size display", e)
            desktopFailed = true
            null
        }
    }

    /** 显示上的最新一帧(裁到显示宽度的副本),给画中画小窗用;还没有画面时为 null。 */
    fun latestFrame(): Bitmap? = active.copy()

    /**
     * 显示上的最新一帧(至多晚约 100 毫秒);还没有画出过任何一帧时为 null。
     * PNG 编码要几十毫秒,别在主线程调。
     */
    fun latestFramePng(): ByteArray? {
        val bmp = active.copy() ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        // 先停帧线程,之后才能安全关掉它手里的图和 reader
        phone?.reader?.setOnImageAvailableListener(null, null)
        desktop?.reader?.setOnImageAvailableListener(null, null)
        frameThread.quitSafely()
        frameThread.join()
        phone?.release()
        desktop?.release()
    }

    // 不挂窗口时没人给视图排版,手动按显示尺寸量一次
    private fun layoutDetached(l: Layer, view: View) {
        val w = View.MeasureSpec.makeMeasureSpec(l.width, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(l.height, View.MeasureSpec.EXACTLY)
        l.container.measure(w, h)
        l.container.layout(0, 0, l.width, l.height)
        view.requestLayout()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebHost must be used on the main thread" }

    companion object {
        private const val TAG = "MikaWebHost"
        private const val FRAME_INTERVAL_MS = 100L
        private const val PHONE_NAME = "mika-web"

        /**
         * 视图所在标签换了手机版/桌面版:它若正挂在某个宿主上,就换到对应大小的那块显示上。不论当初是谁挂上去的。
         * 不在宿主上(接管中在用户手上、在后台)的不动,下次挂上时按版本挂。主线程调用。
         */
        fun followMode(tab: WebTab) {
            val host = (tab.view.parent as? View)?.tag as? WebHost ?: return
            host.attach(tab)
        }
    }
}
