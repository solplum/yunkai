package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.util.concurrent.Executors

/** 页面上看出的登录状态。 */
enum class LoginState { SignedIn, SignedOut, Unknown }

/**
 * 判断登录状态用的页面迹象,只取页面顶部账号区(页头、导航条、横幅,或页面最上面一截)里看得见的东西:
 * [url] 当前地址;[passwordVisible] 页面上有看得见的密码框;[account] 账号区里看得见的链接、按钮的文字;
 * [avatars] 账号区里看得见的头像(评论、列表、文章里的不算)。
 */
data class LoginSignals(
    val url: String?,
    val passwordVisible: Boolean,
    val account: List<String>,
    val avatars: Int,
)

/**
 * 通用的登录状态判断,不认具体网站:
 * - 已登录:账号区里看得见「退出登录 / log out」一类的链接(整条文字就是它、或以它开头),或「个人中心 / my account」一类入口,
 *   且没有看得见的登录入口;
 * - 已退出:看得见密码框,或账号区里看得见「登录 / sign in」入口且没有账号入口与头像;
 * - 头像只用来否定「已退出」,自己不能把状态判成已登录;地址像登录页(单点登录、通行证跳转途中)时不下结论。
 * 其余是 [LoginState.Unknown]:登记表不动。
 */
object LoginProbe {
    private val LOGIN_PATH = Regex("""(^|[/_.-])(log-?in|sign-?in|sign_in|logon|passport|sso|oauth|authorize|auth/login|account/login|user/login)([/_.?#-]|$)""", RegexOption.IGNORE_CASE)
    /** 整条文字就是它才算(太常见,以它开头的多半是别的意思,如「退出全屏」)。 */
    private val LOGOUT_EXACT = setOf("退出", "注销", "logout", "signout")
    /** 整条文字是它、或以它开头(后面至多再跟 [LOGOUT_SLACK] 个字)。 */
    private val LOGOUT = listOf("退出登录", "退出账号", "安全退出", "注销登录", "log out", "sign out", "log off")
    /** 带这些字的不是退出链接(注销账号的说明、帮助页)。 */
    private val NOT_LOGOUT = listOf("说明", "帮助", "须知", "协议", "账号注销", "注销账号", "注销账户", "help", "how to", "delete account")
    private val SIGN_IN = listOf("登录", "登入", "请登录", "立即登录", "登录/注册", "注册/登录", "登录 / 注册", "注册 / 登录", "log in", "login", "sign in", "signin", "sign in / register", "sign up / log in")
    private val ACCOUNT = listOf("个人中心", "我的账号", "我的账户", "账号设置", "账户设置", "my account", "your account", "account settings")
    /** 「退出」类词后面最多再跟几个字还算(如「退出登录 >」「Log out of Example」不算,太长的是句子)。 */
    private const val LOGOUT_SLACK = 4

    fun classify(s: LoginSignals): LoginState {
        val texts = s.account.map(::norm)
        val logout = texts.any(::isLogout)
        val account = texts.any { t -> ACCOUNT.any { t == it || t.startsWith(it) } }
        val signIn = texts.any { it in SIGN_IN }
        // 登录、单点登录、授权页多是跳转途中:不下结论,登记表不动
        if (loginUrl(s.url)) return LoginState.Unknown
        if (s.passwordVisible && !logout) return LoginState.SignedOut
        if ((logout || account) && !signIn && !s.passwordVisible) return LoginState.SignedIn
        if (signIn && !logout && !account && s.avatars == 0) return LoginState.SignedOut
        return LoginState.Unknown
    }

    private fun isLogout(t: String) =
        NOT_LOGOUT.none { t.contains(it) } &&
            (t in LOGOUT_EXACT || LOGOUT.any { w -> t == w || (t.startsWith(w) && t.length <= w.length + LOGOUT_SLACK) })

    /** 地址是登录、单点登录或授权页:按路径认(查询串里提到 login 不算)。 */
    fun loginUrl(url: String?): Boolean {
        val path = url?.let { runCatching { URI(it.trim()).rawPath }.getOrNull() } ?: return false
        return LOGIN_PATH.containsMatchIn(path)
    }

    private fun norm(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ")

    /** 解析 [SCRIPT] 经 evaluateJavascript 回来的结果(一个 JSON 字符串字面量);读不出为 null。 */
    fun parse(raw: String?): LoginSignals? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        return LoginSignals(
            url = (o["url"] as? JsonPrimitive)?.contentOrNull,
            passwordVisible = (o["pw"] as? JsonPrimitive)?.booleanOrNull == true,
            account = (o["account"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            avatars = (o["avatars"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * 在页面主框架里收集迹象的只读脚本:只读 DOM,不改页面、不发请求。按文档顺序至多走 [SCAN_BUDGET] 个元素;
     * 账号区是页头 / 导航 / 横幅里的,或顶边在页面最上面 160 像素内的;评论、列表项、文章里的头像不算。
     */
    val SCRIPT = """(function(){try{
var out={url:location.href,pw:false,account:[],avatars:0},budget=$SCAN_BUDGET;
function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return null;var c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&+c.opacity>0.05?r:null;}
function top(e,r){return r.top+scrollY<160||!!e.closest('header,nav,[role=banner],[role=navigation]');}
var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_ELEMENT),e,n=0;
while((e=w.nextNode())&&n++<budget){var t=e.tagName,r;
if(t==='INPUT'){var y=(e.getAttribute('type')||'').toLowerCase();if(y==='password'&&!out.pw&&vis(e))out.pw=true;if(y!=='submit'&&y!=='button')continue;}
if(t==='A'||t==='BUTTON'||t==='INPUT'||e.getAttribute('role')==='button'||e.getAttribute('role')==='link'||e.getAttribute('role')==='menuitem'){
if(out.account.length>=60)continue;var s=String(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim();
if(s&&s.length<=24&&(r=vis(e))&&top(e,r))out.account.push(s);continue;}
if(t==='IMG'&&out.avatars<3){var k=(e.className&&typeof e.className==='string'?e.className:'')+' '+(e.getAttribute('alt')||'')+' '+(e.getAttribute('src')||'');
if(/avatar|头像/i.test(k)&&(r=vis(e))&&top(e,r)&&!e.closest('article,li,[class*=comment],[class*=list],[class*=feed]'))out.avatars++;}}
return JSON.stringify(out);}catch(x){return null;}})()"""

    private const val SCAN_BUDGET = 4000
    private const val TAG = "MikaLoginProbe"
    /** 解析、判断与写登记表都放在它上面,不占主线程。 */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-login-probe").apply { isDaemon = true } }

    /**
     * 主线程调:在 [tab] 当前页面上看一眼登录状态(脚本在页面里跑),解析与判断在后台线程上做,回调(站点主机名, 状态)也在后台线程。
     * 标签已不能用、还在加载、读不出、读的时候已换到别的网站时不回调。
     */
    fun probe(tab: WebTab, onResult: (String, LoginState) -> Unit) {
        if (tab.destroyed || tab.crashed || tab.loading) return
        val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
        tab.view.evaluateJavascript(SCRIPT) { raw ->
            worker.execute {
                val signals = parse(raw) ?: return@execute
                if (signals.url?.let(WebUrlPolicy::host) != host) return@execute
                val state = classify(signals)
                Log.i(TAG, "login probe $host: $state")
                onResult(host, state)
            }
        }
    }
}
