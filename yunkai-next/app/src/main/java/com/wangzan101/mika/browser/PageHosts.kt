package com.wangzan101.mika.browser

/**
 * 浏览器页的网页视图挂在哪一处。网页标签只有一个(放在进程里),浏览器页却可能同时有几处:App 的界面不止一个实例时
 * (从通知、桌面、别的 App 又打开了一个,盖在原来那个上面),每个实例都可能停在浏览器页,都会给网页留一个容器。
 * 一个视图同一时间只能挂在一个容器里,挂错了(挂进被盖住的那个)用户看到的就是一片空白。
 *
 * 规则:挂在有容器的几处里最近一次回到前台([started])的那一处,同样新的取后来的。那一处走了换到剩下里最近回到前台的。
 * [close] 告诉调用方走的是不是最后一处:只有最后一处走了(且不是转屏)才关网页,别处离开浏览器页不关别人正看着的网页。
 *
 * [C] 是承载视图的容器,[A] 是这一处所在的界面(挂上去时视图的环境换成它)。只在主线程调用。
 */
class PageHosts<C : Any, A : Any> {
    /** 一处浏览器页。 */
    class Host<C : Any, A : Any>(val activity: A) {
        /** 这一处此刻给网页留的容器;没有打开的网页时为 null。 */
        var container: C? = null
            internal set
        internal var startedAt = 0L
    }

    private val hosts = mutableListOf<Host<C, A>>()
    private var clock = 0L

    /** 这一处进了浏览器页。 */
    fun open(host: Host<C, A>) {
        if (host !in hosts) hosts += host
    }

    /** 这一处离开了浏览器页(或界面没了);返回 true 表示已经没有别的浏览器页了。 */
    fun close(host: Host<C, A>): Boolean {
        hosts.remove(host)
        host.container = null
        return hosts.isEmpty()
    }

    /** 这一处回到了前台(看得见了)。 */
    fun started(host: Host<C, A>) {
        host.startedAt = ++clock
    }

    /** 这一处的容器有了或没了。 */
    fun setContainer(host: Host<C, A>, container: C?) {
        host.container = container
    }

    /** 视图该挂的那一处:还在浏览器页上、有容器的几处里最近回到前台的;没有为 null。 */
    fun target(): Host<C, A>? =
        hosts.withIndex().filter { it.value.container != null }
            .maxWithOrNull(compareBy<IndexedValue<Host<C, A>>>({ it.value.startedAt }, { it.index }))?.value
}
