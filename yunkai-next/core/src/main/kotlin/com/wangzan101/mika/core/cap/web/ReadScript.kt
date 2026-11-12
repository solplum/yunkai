package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

const val READ_SCRIPT_DEFAULT_TIMEOUT_MS = 10_000
const val READ_SCRIPT_MAX_TIMEOUT_MS = 30_000
/** 一次 sleep 最多等多久。 */
const val READ_SLEEP_MAX_MS = 5_000

/**
 * 读脚本(web_evaluate_js、站点命令)的做法:在当前标签主框架的一个隔离世界里跑,看得到真实的 DOM,但碰不到原件。
 * - 模型代码按 web_script 的办法编译:这个世界全局对象上的名字都当参数遮住,只留纯 ECMAScript 内置和下面给的几个名字
 *   (document、window、location、fetch、state、args、sleep、getComputedStyle、URL、URLSearchParams)。
 * - 给的页面对象都是包装:读属性照读(读到的对象再包一层),写、删、定义属性、改原型一律报 read_only;
 *   函数只有登记过的只读方法(查找、读属性、量位置、迭代)能调,别的(点、聚焦、提交、改 DOM、开窗、跳转)都报 read_only。
 *   原生函数只拿到原件,模型拿到的永远是包装;模型能碰到的内置对象先冻住,改不了它们来截原件。
 * - 密码、隐藏字段、验证码、银行卡一类输入框的值读出来是 `***`,按 value 属性选元素不行(能逐位猜出值)。
 * - 读脚本不碰网络:fetch 调用就报 blocked_network([NETWORK_MESSAGE]);XMLHttpRequest、sendBeacon、WebSocket、EventSource、
 *   new Image() 一类经包装调用、构造也报 blocked_network;建元素、改 src/href 本来就是改页面(read_only)。只能读页面已经有的东西。
 * - state 是这个标签的读取世界里一直留着的对象:同一页面上前后几次读脚本共用;页面换了文档就没了。
 * 兜底(在 DevTools 事件上):脚本发出了任何网络请求、跑着时主框架要跳转,都让这次读脚本以错误结束。
 */
object ReadScriptProtocol {
    /** 读脚本想用网络时的说明。 */
    const val NETWORK_MESSAGE = "network access is not allowed in read scripts; use web_capture to read what the page loaded"

    /** 能发请求的页面函数和构造器的名字:经包装调用、构造时报 blocked_network(而不只是 read_only),说清楚为什么。 */
    val NETWORK_NAMES = listOf(
        "fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource", "Image", "Audio", "Request",
        "Worker", "SharedWorker", "RTCPeerConnection", "importScripts",
    )

    const val WORLD_PREFIX = "mika-read-"
    const val SOURCE_URL = "mika-read.js"
    const val PRELUDE_URL = "mika-read-prelude.js"
    const val MASK = PageScriptProtocol.SECRET_MASK

    /** 模型代码里除了纯 ECMAScript 内置之外能用的名字,也是传参的顺序。 */
    val PROVIDED = listOf("document", "window", "location", "fetch", "state", "args", "sleep", "getComputedStyle", "URL", "URLSearchParams")

    /**
     * 能经包装调用的只读方法,按接口。原型上找不到的跳过(不同系统版本的 WebView 不一样)。
     * 带 Symbol.iterator 的接口另外放行它的迭代器。
     */
    val READ_METHODS: Map<String, List<String>> = linkedMapOf(
        "Node" to listOf("contains", "hasChildNodes", "getRootNode", "compareDocumentPosition", "isEqualNode", "isSameNode", "lookupPrefix", "lookupNamespaceURI", "isDefaultNamespace"),
        "Element" to listOf(
            "querySelector", "querySelectorAll", "closest", "matches", "webkitMatchesSelector", "getAttribute", "getAttributeNS", "getAttributeNames",
            "hasAttribute", "hasAttributeNS", "hasAttributes", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getBoundingClientRect", "getClientRects", "checkVisibility",
        ),
        "Document" to listOf(
            "querySelector", "querySelectorAll", "getElementById", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getElementsByName", "elementFromPoint", "elementsFromPoint", "hasFocus",
        ),
        "DocumentFragment" to listOf("querySelector", "querySelectorAll", "getElementById"),
        "CharacterData" to listOf("substringData"),
        "NodeList" to listOf("item", "forEach", "entries", "keys", "values"),
        "HTMLCollection" to listOf("item", "namedItem"),
        "HTMLFormControlsCollection" to listOf("namedItem"),
        "HTMLOptionsCollection" to listOf("item", "namedItem"),
        "HTMLSelectElement" to listOf("item", "namedItem"),
        "HTMLAnchorElement" to listOf("toString"),
        "HTMLAreaElement" to listOf("toString"),
        "NamedNodeMap" to listOf("item", "getNamedItem", "getNamedItemNS"),
        "DOMTokenList" to listOf("item", "contains", "forEach", "entries", "keys", "values"),
        "CSSStyleDeclaration" to listOf("getPropertyValue", "getPropertyPriority", "item"),
        "DOMRectReadOnly" to listOf("toJSON"),
        "DOMRectList" to listOf("item"),
        "MediaQueryList" to listOf(),
    )

    /** 全局对象自己身上的只读方法。 */
    val GLOBAL_READ_METHODS = listOf("getComputedStyle", "matchMedia")

    /** 读不得的属性:Cookie、本地存储一类(登录凭据常在里面)。 */
    val DENIED_PROPS = listOf("cookie", "localStorage", "sessionStorage", "indexedDB", "caches", "cookieStore", "credentials", "serviceWorker", "clipboard", "openDatabase")

    /** 先冻住再交给模型的内置对象(连同原型):模型改不了它们,也就截不到包装里传的原件。 */
    private val FROZEN = PageScriptProtocol.ALLOWED_GLOBALS.filter { it !in setOf("undefined", "NaN", "Infinity") } + listOf("URL", "URLSearchParams")

    /** 模型代码第 1 行前面,编译后的源码还有几行(`(async function anonymous(…`、`) {`、`'use strict';`、`return (` 或空行)。 */
    private const val LINE_OFFSET = 4

    private fun js(v: Collection<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    private fun jsMap(m: Map<String, List<String>>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> JsonPrimitive(k).toString() + ":" + js(v) }

    /** 装进读取世界的前置脚本:冻住内置对象、登记能调的方法、定义包装、`__mikaReadRun` 与 `__mikaReadStop`。装过再装不做事。 */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object, R = Reflect;
  if (O.prototype.hasOwnProperty.call(G, '__mikaReadRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const fnBind = G.Function.prototype.bind;
  const apply = R.apply, rget = R.get, ownKeys = R.ownKeys, getDesc = O.getOwnPropertyDescriptor, getProto = O.getPrototypeOf;
  const freeze = O.freeze, isArray = Array.isArray, keysOf = O.keys, define = O.defineProperty, names = O.getOwnPropertyNames;
  const stringify = JSON.stringify, parseJson = JSON.parse, timer = G.setTimeout;
  const URL_ = G.URL, loc = G.location, doc = G.document;
  const Elem = G.Element, Input = G.HTMLInputElement, Area = G.HTMLTextAreaElement, AttrNode = G.Attr;
  const MASK = '$MASK';
  const fixed = (o, k, v) => { try { define(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const fail = (code, message) => { const e = new Error(code + ': ' + message); e.code = code; return e; };
  const deny = (what) => { throw fail('read_only', what + ' is not allowed in a read script; it can only read the page'); };
  const NETWORK = new Set(${js(NETWORK_NAMES)});
  const offline = () => { throw fail('blocked_network', ${JsonPrimitive(NETWORK_MESSAGE)}); };

  // 把字符串当代码跑的路(会在全局作用域里跑)、调用栈 API 封掉
  const unavailable = function () { throw fail('read_only', 'running code from strings is not available'); };
  const fnKinds = [function () {}, async function () {}, function* () {}, async function* () {}];
  for (const f of fnKinds) fixed(getProto(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  // 冻住模型碰得到的内置对象
  const ice = (x) => { if (x && (typeof x === 'object' || typeof x === 'function')) { try { freeze(x); } catch (e) {} } };
  for (const n of ${js(FROZEN)}) { const v = G[n]; ice(v); if (v && typeof v === 'function') ice(v.prototype); }
  for (const f of fnKinds) ice(getProto(f));
  const arrayIter = getProto([][Symbol.iterator]());
  for (const p of [arrayIter, getProto(arrayIter), getProto(new Map().entries()), getProto(new Set().values()), getProto(''[Symbol.iterator]())]) ice(p);

  // 能经包装调用的原生函数
  const SAFE = new WeakSet();
  const READ = ${jsMap(READ_METHODS)};
  for (const iface of keysOf(READ)) {
    const C = G[iface];
    const p = C && C.prototype;
    if (!p) continue;
    for (const k of READ[iface]) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
    const it = getDesc(p, Symbol.iterator);
    if (it && typeof it.value === 'function') SAFE.add(it.value);
  }
  for (const k of ${js(GLOBAL_READ_METHODS)}) { const d = getDesc(G, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const k of ['toString', 'valueOf']) { const d = getDesc(loc, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const f of [Array.prototype.values, Array.prototype.keys, Array.prototype.entries, Array.prototype.forEach, arrayIter.next,
    O.prototype.toString, O.prototype.toLocaleString, O.prototype.valueOf, O.prototype.hasOwnProperty, O.prototype.propertyIsEnumerable]) SAFE.add(f);
  // 第一个参数是选择器的;第几个参数是属性名的
  const SELECTORS = new WeakSet();
  for (const iface of ['Element', 'Document', 'DocumentFragment']) {
    const p = G[iface] && G[iface].prototype;
    if (p) for (const k of ['querySelector', 'querySelectorAll', 'closest', 'matches', 'webkitMatchesSelector']) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SELECTORS.add(d.value); }
  }
  const ATTRS = new WeakMap();
  for (const [k, i] of [['getAttribute', 0], ['getAttributeNS', 1]]) { const d = Elem && getDesc(Elem.prototype, k); if (d && typeof d.value === 'function') ATTRS.set(d.value, i); }
  const BY_VALUE = /\[\s*value\b/i;
  const DENIED = new Set(${js(DENIED_PROPS)});

  // 不读值的输入框:密码框、隐藏字段,autocomplete 标着密码、银行卡、一次性验证码的
  const SECRET_AC = new Set(${js(PageScriptProtocol.SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(PageScriptProtocol.SECRET_INPUT_TYPES)});
  const secretNow = (el) => {
    if (Input && el instanceof Input) {
      if (SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    } else if (!(Area && el instanceof Area)) return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  // 曾经是秘密输入框的(如「显示密码」把 type 改成 text)也一直按秘密算:从读取世界建起时起盯着
  const wasSecret = new WeakSet();
  const markIn = (root) => { try { if (secretNow(root)) wasSecret.add(root); for (const x of root.querySelectorAll('input, textarea')) if (secretNow(x)) wasSecret.add(x); } catch (e) {} };
  try {
    new G.MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'attributes') { if (secretNow(r.target)) wasSecret.add(r.target); } else for (const n of r.addedNodes) if (n.nodeType === 1) markIn(n);
      }
    }).observe(doc, { subtree: true, childList: true, attributes: true, attributeFilter: ['type', 'autocomplete'] });
  } catch (e) {}
  markIn(doc);
  const secret = (el) => secretNow(el) || (!!el && typeof el === 'object' && wasSecret.has(el));
  const SECRET_TEXT = new Set(['value', 'defaultValue', 'textContent', 'innerText', 'innerHTML', 'nodeValue']);
  // HTML 里带着秘密输入框的 value 时,改在一份脱离页面的副本上打码再取
  const html = (el, k) => {
    let hit = secret(el);
    if (!hit) for (const x of el.querySelectorAll('input, textarea')) if (secret(x)) { hit = true; break; }
    if (!hit) return rget(el, k, el);
    const c = el.cloneNode(true);
    const all = [c];
    for (const x of c.querySelectorAll('input, textarea')) all.push(x);
    for (const x of all) if (secret(x)) { if (x.hasAttribute('value')) x.setAttribute('value', MASK); if (Area && x instanceof Area && x.textContent) x.textContent = MASK; }
    return rget(c, k, c);
  };
  const readProp = (v, k) => {
    if (typeof k === 'string') {
      if (DENIED.has(k)) deny('reading ' + k);
      if (Elem && v instanceof Elem && (k === 'outerHTML' || k === 'innerHTML')) return html(v, k);
      if (SECRET_TEXT.has(k) && secret(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (AttrNode && v instanceof AttrNode && SECRET_TEXT.has(k) && String(v.name).toLowerCase() === 'value' && v.ownerElement && secret(v.ownerElement)) {
        const x = rget(v, k, v); return x ? MASK : x;
      }
    }
    return rget(v, k, v);
  };

  // 包装:模型手里只有包装,原件只交给原生函数
  const toProxy = new WeakMap(), toReal = new WeakMap();
  const wrap = (v, depth) => {
    if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return v;
    if (toReal.has(v)) return v;
    const d = (depth || 0) + 1;
    if (d <= 4 && isArray(v)) { const out = []; for (let i = 0; i < v.length; i++) out.push(wrap(v[i], d)); return out; }
    if (d <= 4 && typeof v === 'object') {
      const proto = getProto(v);
      if (proto === O.prototype || proto === null) {
        const out = {};
        for (const k of keysOf(v)) define(out, k, { value: wrap(v[k], d), enumerable: true, writable: true, configurable: true });
        return out;
      }
    }
    let p = toProxy.get(v);
    if (!p) { p = makeProxy(v); toProxy.set(v, p); toReal.set(p, v); }
    return p;
  };
  const toNative = (a) => {
    if (a === null || (typeof a !== 'object' && typeof a !== 'function')) return a;
    const r = toReal.get(a);
    // 包装过的函数原样交出去:谁调它都还要过检查
    if (r !== undefined) return typeof r === 'function' ? a : r;
    // 模型自己的回调:原生函数传给它的东西先包装
    if (typeof a === 'function') return function () { const xs = []; for (let i = 0; i < arguments.length; i++) xs.push(wrap(arguments[i])); return toNative(apply(a, undefined, xs)); };
    return a;
  };
  const nameOf = (f) => { try { return String(f.name || ''); } catch (e) { return ''; } };
  const call = (f, self, xs) => {
    if (!SAFE.has(f)) { if (NETWORK.has(nameOf(f))) offline(); deny('calling ' + (nameOf(f) || 'this function')); }
    const t = toNative(self);
    const args = [];
    for (const x of xs) args.push(toNative(x));
    if (SELECTORS.has(f) && typeof args[0] === 'string' && BY_VALUE.test(args[0])) throw fail('invalid_selector', 'selecting by the value attribute is not allowed; read the value property instead');
    const at = ATTRS.get(f);
    if (at !== undefined && secret(t) && String(args[at]).toLowerCase() === 'value') { const x = apply(f, t, args); return x ? MASK : x; }
    return wrap(apply(f, t, args));
  };
  const changing = () => deny('changing the page');
  // 函数的包装用一个绑定过的普通函数当底子:能被 new(才走得到 construct,报清楚为什么不行),又没有自己的 prototype(不碍代理的约束)
  const makeProxy = (v) => new Proxy(typeof v === 'function' ? apply(fnBind, function () {}, [undefined]) : {}, {
    get: (_, k) => wrap(readProp(v, k)),
    set: changing, defineProperty: changing, deleteProperty: changing, setPrototypeOf: changing, preventExtensions: changing,
    getPrototypeOf: () => null,
    has: (_, k) => k in v,
    ownKeys: () => { const out = []; for (const k of ownKeys(v)) if (!(typeof k === 'string' && DENIED.has(k))) out.push(k); return out; },
    getOwnPropertyDescriptor: (_, k) => {
      if (typeof k === 'string' && DENIED.has(k)) return undefined;
      const d = getDesc(v, k);
      return d ? { value: wrap(readProp(v, k)), writable: false, enumerable: d.enumerable, configurable: true } : undefined;
    },
    apply: (_, self, xs) => call(v, self, xs),
    construct: () => { if (typeof v === 'function' && NETWORK.has(nameOf(v))) offline(); deny('creating page objects'); },
  });

  // 模型代码能用的名字:纯 ECMAScript 内置 + PROVIDED,其余全局名都遮住
  const PROVIDED = ${js(PROVIDED)};
  const ALLOWED = new Set(${js(PageScriptProtocol.ALLOWED_GLOBALS)}.concat(PROVIDED));
  const RESERVED = new Set(${js(PageScriptProtocol.RESERVED)});
  const hidden = new Set(['globalThis', 'self', 'top', 'parent', 'frames', 'opener', '__mikaReadRun', '__mikaReadStop']);
  for (let o = G; o; o = getProto(o)) for (const k of names(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) hidden.add(k);
  }
  const shadow = [...hidden];
  const blanks = shadow.map(() => undefined);
  const params = shadow.concat(PROVIDED);
  const state = {};
  const pageWindow = wrap(G), pageDocument = wrap(doc), pageLocation = wrap(loc), computedStyle = wrap(G.getComputedStyle);
  let generation = 0;
  const STACK_LINE = /mika-read\.js:(\d+):\d+/;
  const KNOWN = new Set(['read_only', 'invalid_selector', 'blocked_network', 'limit_exceeded', 'network_error', 'too_large', 'invalid_arguments', 'script_ended']);
  const failure = (e) => {
    const m = STACK_LINE.exec(String((e && e.stack) || ''));
    const line = m ? Number(m[1]) - $LINE_OFFSET : 0;
    const code = e && typeof e.code === 'string' && KNOWN.has(e.code) ? e.code : 'script_error';
    return { ok: false, error: code, message: String((e && e.message) || e), line: line > 0 ? line : null };
  };

  fixed(G, '__mikaReadStop', () => { generation++; });
  fixed(G, '__mikaReadRun', (code, args, lim) => {
    const gen = ++generation;
    const live = () => { if (gen !== generation) throw fail('script_ended', 'the script was stopped'); };
    // 不碰网络:要页面背后的数据,读页面自己收到的响应(web_capture)
    const pageFetch = () => { live(); offline(); };
    const sleep = (ms) => { live(); return new Promise((done) => timer(done, Math.max(0, Math.min(Number(ms) || 0, lim.sleepMs)))); };
    let fn;
    try {
      fn = new AsyncFunction(...params, "'use strict';\nreturn (\n" + code + '\n);\n//# sourceURL=$SOURCE_URL');
    } catch (e) {
      try {
        fn = new AsyncFunction(...params, "'use strict';\n\n" + code + '\n//# sourceURL=$SOURCE_URL');
      } catch (e2) {
        return Promise.resolve(failure(e2));
      }
    }
    const input = args == null ? undefined : parseJson(stringify(args));
    const ice2 = (x) => { if (x && typeof x === 'object') { for (const k of keysOf(x)) ice2(x[k]); freeze(x); } return x; };
    let running;
    try {
      running = apply(fn, undefined, blanks.concat([pageDocument, pageWindow, pageLocation, pageFetch, state, ice2(input), sleep, computedStyle, URL_, G.URLSearchParams]));
    } catch (e) {
      return Promise.resolve(failure(e));
    }
    return running.then((v) => {
      let s;
      try { s = v === undefined ? 'null' : stringify(v); } catch (e) { return { ok: false, error: 'script_error', message: 'the result is not JSON-serializable: ' + String((e && e.message) || e), line: null }; }
      if (s === undefined) s = 'null';
      const max = lim.resultChars;
      return { ok: true, json: s.length > max ? s.slice(0, max) : s, truncated: s.length > max };
    }, failure);
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** 跑一次读脚本的表达式(在读取世界里求值,等 Promise,按值返回)。[args] 是站点命令的参数。 */
    fun run(code: String, args: JsonObject?, resultChars: Int): String =
        "globalThis.__mikaReadRun(${JsonPrimitive(code)}, ${args ?: "null"}, " +
            "{resultChars: $resultChars, sleepMs: $READ_SLEEP_MAX_MS})"

    /** 到时限后让还在跑的脚本之后的 sleep 都失败。 */
    const val STOP = "globalThis.__mikaReadStop && globalThis.__mikaReadStop()"

    /** 读取世界装好了时返回页面的源(location.origin),没装为 null。 */
    const val PROBE = "typeof globalThis.__mikaReadRun === 'function' ? String(location.origin) : null"

    /** 发起调用栈(含异步的 parent 链)里有读脚本或它的前置脚本。 */
    fun fromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /**
     * 兜底:`Network.requestWillBeSent` 是读脚本(或它的前置脚本)发出的请求时返回说明:读脚本不碰网络,哪种请求都不行。
     * 不是读脚本发的为 null。
     */
    fun requestViolation(params: JsonObject): String? {
        if (!fromScript(params)) return null
        val request = params["request"] as? JsonObject
        val url = (request?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (request?.get("method") as? JsonPrimitive)?.contentOrNull ?: "GET"
        return "the script sent a $method request (${url.take(200)}); $NETWORK_MESSAGE"
    }

    /** `scheme://host[:port]`,默认端口不写;读不懂为 null。 */
    fun originOf(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = u.port.takeIf { it >= 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /**
     * `__mikaReadRun` 按值返回的对象 → 结果。结果先按网页数据的规则脱敏(凭据名字的字段、JWT、又长又杂的串、Key),再截到 [EVAL_RESULT_MAX_CHARS]。
     * [fresh]:这次新建了读取世界(之前 state 里存的都没了)。
     */
    fun result(value: JsonObject?, fresh: Boolean): EvalJs {
        value ?: return EvalJs.Failed("script_error", "the script produced no readable result")
        fun str(k: String) = (value[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((value["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val line = (value["line"] as? JsonPrimitive)?.intOrNull
            return EvalJs.Failed(str("error") ?: "script_error", (line?.let { "line $it: " } ?: "") + str("message").orEmpty())
        }
        val json = Redactor.pageData(str("json") ?: "null")
        val truncated = (value["truncated"] as? JsonPrimitive)?.booleanOrNull == true || json.length > EVAL_RESULT_MAX_CHARS
        return EvalJs.Ok(json.take(EVAL_RESULT_MAX_CHARS), truncated, fresh)
    }
}
