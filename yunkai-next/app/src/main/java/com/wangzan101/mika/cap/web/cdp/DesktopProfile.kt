package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** 内核自报的一个品牌及版本(UA-CH 的 brands / fullVersionList 各取一种版本)。 */
data class Brand(val brand: String, val major: String, val full: String)

/**
 * 桌面版网页的身份:Linux 桌面的 UA 与 UA-CH,内核版本号如实沿用本机 WebView 的,品牌沿用内核自己的(去掉 Android WebView)。
 * 页面能读到的都要和这个身份对得上:UA / UA-CH / navigator.platform 与视口、屏幕、像素比由仿真设([userAgentOverride]、
 * [metricsOverride]、[emulationCalls]);仿真设不了的(触摸能力、指针与悬停的媒体查询、窗口外框、WebView 特有的痕迹)
 * 由文档开始时注入的 [identityScript] 补上。语言、时区不动,沿用本机的(请求头与页面读到的本来就一致)。
 * 手机版站点一律不改,用本机真实的手机身份。
 */
object DesktopProfile {
    /** 桌面版的视口宽度(CSS 像素)。 */
    const val WIDTH = 1280
    const val FALLBACK_HEIGHT = 800
    /** 桌面版标签的宿主显示:按 1 CSS 像素对 1 像素画桌面视口。 */
    const val HOST_WIDTH = WIDTH
    const val HOST_HEIGHT = 800
    const val HOST_DPI = 160
    const val NAVIGATOR_PLATFORM = "Linux x86_64"
    const val PLATFORM = "Linux"
    /** 桌面浏览器窗口里网页之外的部分(标签栏、地址栏)的高度:窗口外框比网页视口高这么多。 */
    const val WINDOW_CHROME_PX = 85
    private const val WEBVIEW_BRAND = "Android WebView"
    private val CHROME = Regex("""Chrome/(\d+)((?:\.\d+)*)""")

    /** 从内核默认 UA 里取版本:(主版本, 完整版本)。 */
    fun version(defaultUa: String): Pair<String, String>? =
        CHROME.find(defaultUa)?.let { m -> m.groupValues[1] to (m.groupValues[1] + m.groupValues[2]) }

    /** UA 里的版本按惯例只写主版本(其余为 0)。 */
    fun userAgent(major: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** 内核给的品牌去掉 Android WebView;拿不到时用 Chromium 加一个占位品牌。 */
    fun brands(engine: List<Brand>?, major: String, full: String): List<Brand> =
        engine?.filter { it.brand != WEBVIEW_BRAND }?.takeIf { l -> l.any { it.brand == "Chromium" } }
            ?: listOf(Brand("Chromium", major, full), Brand("Not_A Brand", "24", "24.0.0.0"))

    /** `Emulation.setUserAgentOverride` 的参数。不带 acceptLanguage:语言保持用户的。 */
    fun userAgentOverride(major: String, full: String, brands: List<Brand>): JsonObject = buildJsonObject {
        put("userAgent", userAgent(major))
        put("platform", NAVIGATOR_PLATFORM)
        put("userAgentMetadata", buildJsonObject {
            put("brands", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.major) } }))
            put("fullVersionList", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.full) } }))
            put("fullVersion", full)
            put("platform", PLATFORM)
            put("platformVersion", "")
            put("architecture", "x86")
            put("model", "")
            put("mobile", false)
            put("bitness", "64")
            put("wow64", false)
        })
    }

    /** 视口高度按宿主的宽高比放大到桌面宽度,整页正好铺满宿主;宿主尺寸未知时用 800。 */
    fun height(viewW: Int, viewH: Int): Int =
        if (viewW > 0 && viewH > 0) (WIDTH.toDouble() * viewH / viewW).roundToInt() else FALLBACK_HEIGHT

    /**
     * 仿真画面的缩放:不缩时 1 CSS 像素占 [density](不加仿真时页面的 devicePixelRatio)个视图像素,
     * 桌面宽度的页面可能只露出左边一截;缩到整页 [WIDTH] 正好铺满视图宽度,视图像素与 CSS 像素之比就是 viewW / WIDTH
     * (读屏换算按它)。桌面宿主上视图宽就是 [WIDTH],一比一。
     */
    fun fitScale(viewW: Int, density: Float): Double? =
        if (viewW > 0 && density > 0f) viewW / (WIDTH * density.toDouble()) else null

    /**
     * `Emulation.setDeviceMetricsOverride` 的参数:桌面宽度、像素比 1、非移动端;[scale] 把整页缩到宿主宽度里显示。
     * 屏幕按「窗口铺满整个屏幕」算:宽同视口,高是视口加上浏览器窗口的外框([WINDOW_CHROME_PX]),和脚本给的 outerHeight 一致。
     */
    fun metricsOverride(viewW: Int, viewH: Int, scale: Double?): JsonObject = buildJsonObject {
        val h = height(viewW, viewH)
        put("width", WIDTH)
        put("height", h)
        put("deviceScaleFactor", 1)
        put("mobile", false)
        put("screenWidth", WIDTH)
        put("screenHeight", h + WINDOW_CHROME_PX)
        put("screenOrientation", buildJsonObject {
            val landscape = WIDTH >= h + WINDOW_CHROME_PX
            put("type", if (landscape) "landscapePrimary" else "portraitPrimary")
            put("angle", 0)
        })
        scale?.let { put("scale", it) }
    }

    /**
     * 桌面版在 UA 与视口之外还要发的仿真命令(方法, 参数):不开触摸仿真、鼠标不派生触摸事件、
     * 指针与悬停按鼠标报(内核不认这几项媒体特性时这一条不起作用,由 [identityScript] 兜底)。
     * 每条单独发,哪条被拒都不影响别的。
     */
    fun emulationCalls(): List<Pair<String, JsonObject>> = listOf(
        "Emulation.setTouchEmulationEnabled" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmitTouchEventsForMouse" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmulatedMedia" to buildJsonObject {
            put("features", JsonArray(DESKTOP_MEDIA.map { (n, v) -> buildJsonObject { put("name", n); put("value", v) } }))
        },
    )

    /**
     * 桌面版的视口用哪一条:Agent 的标签用仿真把整页 [WIDTH] 缩进视图([metricsOverride],读屏和输入坐标按 [scale] 换算);
     * 用户亲手看、亲手点的标签([handOperated])不加视口仿真(撤销那一条),交给 WebView 自己的宽视口与总览模式——
     * 打开时整页缩进屏幕,双指能放大;仿真定死了缩放,放大不了。UA、UA-CH、身份补丁两种标签一样。
     */
    fun viewportCall(handOperated: Boolean, viewW: Int, viewH: Int, scale: Double?): Pair<String, JsonObject> =
        if (handOperated) "Emulation.clearDeviceMetricsOverride" to JsonObject(emptyMap())
        else "Emulation.setDeviceMetricsOverride" to metricsOverride(viewW, viewH, scale)

    /** 手机版换回时撤销 [emulationCalls] 里改过的媒体特性(空列表即撤销)。 */
    fun clearMediaCall(): Pair<String, JsonObject> =
        "Emulation.setEmulatedMedia" to buildJsonObject { put("features", JsonArray(emptyList())) }

    private val DESKTOP_MEDIA = listOf("pointer" to "fine", "any-pointer" to "fine", "hover" to "hover", "any-hover" to "hover")

    /**
     * 桌面版在每个文档(含框架)开始时注入的补丁,只补仿真设不了的:
     * - 触摸:navigator.maxTouchPoints 为 0,去掉 ontouch* 处理器属性,`document.createEvent('TouchEvent')` 像桌面一样报不支持;
     * - 指针与悬停:matchMedia 里的 pointer / any-pointer / hover / any-hover 按鼠标回答(其余照原样交给内核),
     *   返回对象的 media 仍是页面给的原文(改在原型的 getter 上,对象上不多出自有属性);
     * - 窗口外框:outerWidth = innerWidth,outerHeight = innerHeight + [WINDOW_CHROME_PX];
     * - navigator.platform 为 [NAVIGATOR_PLATFORM](没有 DevTools 时也对得上 UA);navigator.webdriver 为假;
     *   navigator.connection 去掉只有手机才有的 type;没有 window.chrome 时补一个空对象(桌面浏览器上它总在)。
     * 换过的 getter 先调原来的(接收者不对时照样报 Illegal invocation)再给新值;换过的方法保留原来的 length;
     * 它们的源码按内置函数的样子显示,别的窗口里的 toString 看也一样(各窗口的补丁源码相同,按源码认)。
     * 页面脚本新建的同源框架(about:blank、srcdoc)不一定经过文档开始注入:第一次经 contentWindow / contentDocument
     * 取到时在框架自己的环境里补上同一份(框架禁止 eval 时退回在本环境里补)。已经报 0 个触点的窗口不再补。
     * 不碰 UA、语言、时区、硬件参数。
     */
    fun identityScript(): String = IDENTITY_SCRIPT
        .replace("__PLATFORM__", NAVIGATOR_PLATFORM)
        .replace("__CHROME_PX__", WINDOW_CHROME_PX.toString())

    private val IDENTITY_SCRIPT = """
(function desktopIdentity(W) {
  'use strict';
  try { if (!W.navigator || W.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
  var FP = W.Function.prototype, readSource = FP.toString, ours = new Set(), lists = new WeakMap();
  function ownFn(fn) { ours.add(readSource.call(fn)); return fn; }
  function accessor(target, prop, answer) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.get !== 'function' || !old.configurable) return;
    var realGet = old.get;
    var get = ownFn(Object.getOwnPropertyDescriptor({ get [prop]() { return answer(realGet.call(this), this); } }, prop).get);
    Object.defineProperty(target, prop, { get: get, set: old.set, enumerable: old.enumerable, configurable: true });
  }
  function method(target, prop, body) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.value !== 'function' || !old.configurable) return;
    var real = old.value;
    var fn = ownFn({ [prop]() { return body.call(this, real, arguments); } }[prop]);
    Object.defineProperty(fn, 'length', { value: real.length, configurable: true });
    Object.defineProperty(target, prop, { value: fn, writable: old.writable, enumerable: old.enumerable, configurable: true });
  }
  function constant(v) { return function () { return v; }; }

  method(FP, 'toString', function (real, args) {
    var text = real.apply(this, args);
    return ours.has(text) ? 'function ' + this.name + '() { [native code] }' : text;
  });

  var nav = W.Navigator && W.Navigator.prototype;
  accessor(nav, 'maxTouchPoints', constant(0));
  accessor(nav, 'platform', constant('__PLATFORM__'));
  accessor(nav, 'webdriver', constant(false));

  [W, W.Document && W.Document.prototype, W.Element && W.Element.prototype,
   W.HTMLElement && W.HTMLElement.prototype, W.SVGElement && W.SVGElement.prototype].forEach(function (o) {
    if (!o) return;
    ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (p) {
      var d = Object.getOwnPropertyDescriptor(o, p);
      if (d && d.configurable) delete o[p];
    });
  });
  method(W.Document && W.Document.prototype, 'createEvent', function (real, args) {
    if (args.length && /^\s*touchevents?\s*${'$'}/i.test(String(args[0]))) {
      throw new W.DOMException("Failed to execute 'createEvent' on 'Document': The provided event type ('" + args[0] + "') is invalid.", 'NotSupportedError');
    }
    return real.apply(this, args);
  });

  var FEATURE = /\(\s*(any-)?(pointer|hover)\s*(?::\s*([a-z-]+)\s*)?\)/gi;
  var TRUE_Q = '(min-width: 0px)', FALSE_Q = '(max-width: 0px) and (min-width: 1px)';
  function asMouse(all, any, feature, value) {
    if (!value) return TRUE_Q;
    value = value.toLowerCase();
    return (feature.toLowerCase() === 'pointer' ? value === 'fine' : value === 'hover') ? TRUE_Q : FALSE_Q;
  }
  method(W, 'matchMedia', function (real, args) {
    if (!args.length) return real.apply(this, args);
    var query = String(args[0]);
    FEATURE.lastIndex = 0;
    if (!FEATURE.test(query)) return real.apply(this, args);
    var list = real.call(this, query.replace(FEATURE, asMouse));
    lists.set(list, real.call(this, query).media);
    return list;
  });
  accessor(W.MediaQueryList && W.MediaQueryList.prototype, 'media', function (v, self) { return lists.has(self) ? lists.get(self) : v; });

  accessor(W, 'outerWidth', function () { return W.innerWidth; });
  accessor(W, 'outerHeight', function () { return W.innerHeight + __CHROME_PX__; });

  var conn = W.NetworkInformation && W.NetworkInformation.prototype;
  ['type', 'ontypechange'].forEach(function (p) {
    var d = conn && Object.getOwnPropertyDescriptor(conn, p);
    if (d && d.configurable) delete conn[p];
  });

  if (!('chrome' in W)) Object.defineProperty(W, 'chrome', { value: {}, writable: true, enumerable: true, configurable: true });

  // 脚本里新建的 about:blank / srcdoc 框架不一定经过文档开始注入:第一次被取到时补上同一份
  var self = desktopIdentity, source = readSource.call(self);
  function adopt(child) {
    try { if (!child || child === W || child.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
    try { child.Function('W', 'return (' + source + ')(W);')(child); } catch (e) { try { self(child); } catch (e2) {} }
  }
  var frame = W.HTMLIFrameElement && W.HTMLIFrameElement.prototype;
  accessor(frame, 'contentWindow', function (v) { adopt(v); return v; });
  accessor(frame, 'contentDocument', function (v) { if (v) adopt(v.defaultView); return v; });
})(window);
"""
}
