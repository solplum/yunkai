package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面脚本 digest 的输出(格式同 page.js)→ 收起、拼正文、判定、时间。 */
class PageDigestTest {
    private fun digest(json: String) = PageDigest.parse(Json.parseToJsonElement(json).jsonObject)

    private fun read(json: String, unfold: Boolean = false, httpStatus: Int? = null): Pair<PageDigest.Rendered, ReadQuality> {
        val d = digest(json)
        val r = PageDigest.render(d, unfold)
        val check = WebBarrier.classify(PageDigest.barrierFacts(d, httpStatus, "news.example.com"))
        return r to PageDigest.quality(d, r, check, httpStatus)
    }

    private val prose = "This paragraph is the article body and carries the actual information the reader came for. ".repeat(6).trim()

    /** 一篇文章:顶部导航、正文、推荐卡片、页脚。 */
    private val article = """
        {"url":"https://news.example.com/a","title":"Rain expected","parts":[
          {"g":"navigation","label":"Main","lines":["Home","World","Sport","Weather","Money"],"chars":30,"linkChars":30,"repeat":5,"links":5},
          "# Rain expected tomorrow",
          "$prose",
          {"g":"links","label":"Recommended","lines":["Recommended","Story one","Story two","Story three","Story four","Story five"],"chars":60,"linkChars":49,"repeat":5,"links":5},
          {"g":"footer","label":"","lines":["About","Contact","Privacy"],"chars":20,"linkChars":20,"repeat":0,"links":3}
        ],
        "links":[{"text":"Home","url":"https://news.example.com/","p":0},{"text":"source","url":"https://agency.example.org/report","p":-1},
                 {"text":"Story one","url":"https://news.example.com/1","p":1},{"text":"About","url":"https://news.example.com/about","p":2},
                 {"text":"read the first story","url":"https://news.example.com/1","p":-1}],
        "images":[],"dialogs":[],
        "signals":{"password":0,"prominent":["Rain expected","Rain expected tomorrow","Subscribe"],"frames":[],
          "textChars":700,"linkChars":110,
          "dates":[{"k":"time","v":"2026-10-20"},{"k":"meta:article:published_time","v":"2026-10-20T08:30:00+0800"},{"k":"ld:dateModified","v":"2026-10-21T09:00:00Z"}]}}
    """

    @Test fun navigationRecommendationsAndFooterCollapseToOneLineEach() {
        val (r, q) = read(article)
        val lines = r.text.lines()
        assertTrue(lines[0], lines[0].startsWith("<<collapsed navigation \"Main\" · 5 links · Home | World"))
        assertEquals("# Rain expected tomorrow", lines[1])
        assertEquals(prose, lines[2])
        assertTrue(lines[3], lines[3].startsWith("<<collapsed links \"Recommended\" · 5 links ·"))
        assertTrue(lines[4], lines[4].startsWith("<<collapsed footer · 3 links · About | Contact | Privacy"))
        assertEquals(5, lines.size)
        assertEquals(3, q.folded)
        assertEquals(ReadVerdict.OK, q.verdict)
        assertEquals(0.84, q.textDensity, 0.001)
        assertEquals(r.text.length, q.chars)
    }

    /** [2] 先在收起的块里出现、正文里又链到的地址留在链接表里;只在块里的去掉。 */
    @Test fun aCollapsedLinkThatMainContentRepeatsStays() {
        val (r, _) = read(article)
        assertEquals(listOf("https://agency.example.org/report", "https://news.example.com/1"), r.links.map { it.second })
        assertEquals("read the first story", r.links[1].first)
    }

    @Test fun unfoldGivesEveryBlockInFullAndLinksOnce() {
        val (r, q) = read(article, unfold = true)
        assertTrue(r.text.contains("Weather\nMoney"))
        assertTrue(r.text.contains("Story five"))
        assertFalse(r.text.contains("<<collapsed"))
        assertEquals(0, q.folded)
        assertEquals(4, r.links.size)
    }

    /** [3] 以文字为主的侧栏、页脚(链接占得少)照读。 */
    @Test fun aTextualLandmarkIsNotCollapsed() {
        val aside = "The author has covered weather for twenty years and lives on the coast."
        val (r, q) = read("""
            {"url":"https://news.example.com/a","title":"A","parts":["$prose",
              {"g":"complementary","label":"About the author","lines":["$aside","Profile"],"chars":80,"linkChars":7,"repeat":0,"links":1}
            ],"links":[],"images":[],"dialogs":[],"signals":{"textChars":640,"prominent":["A"]}}
        """)
        assertTrue(r.text.contains(aside))
        assertEquals(0, q.folded)
    }

    /** [4] 目录页、搜索结果页:那串链接就是正文,不收起(按地标以外的文字量)。 */
    @Test fun aListThatIsThePageItselfIsNotCollapsed() {
        val items = (1..20).joinToString(",") { "\"Result $it title\"" }
        val nav = (1..30).joinToString(",") { "\"Section $it\"" }
        val (r, q) = read("""
            {"url":"https://s.example.com/?q=x","title":"Results","parts":[
              {"g":"navigation","label":"","lines":[$nav],"chars":330,"linkChars":330,"repeat":30,"links":30},
              "# Results",
              {"g":"links","label":"","lines":[$items],"chars":300,"linkChars":300,"repeat":20,"links":20}
            ],"links":[],"images":[],"dialogs":[],"signals":{"textChars":640,"linkChars":630,"prominent":["Results"]}}
        """)
        assertTrue(r.text.contains("Result 20 title"))
        // 导航很长,但它是地标:照收
        assertFalse(r.text.contains("Section 30"))
        assertEquals(1, q.folded)
    }

    /** [4] 收完剩下的字太少时,从最大的块起放回。 */
    @Test fun collapsingNeverLeavesAlmostNothing() {
        val nav = (1..30).joinToString(",") { "\"Section $it\"" }
        val foot = (1..10).joinToString(",") { "\"Link $it\"" }
        val parts = PageDigest.parse(Json.parseToJsonElement("""
            {"parts":["Hi",
              {"g":"navigation","label":"","lines":[$nav],"chars":330,"linkChars":330,"repeat":30,"links":30},
              {"g":"footer","label":"","lines":[$foot],"chars":70,"linkChars":70,"repeat":10,"links":10}]}
        """).jsonObject).parts
        assertEquals(setOf(1), PageDigest.foldSet(parts))
    }

    /** [4] 最大的块比块外的文字还多:它就是正文,不收。 */
    @Test fun theLargestBlockOutweighingFreeTextStays() {
        val cards = (1..12).joinToString(",") { "\"Card $it with a short line\"" }
        val parts = PageDigest.parse(Json.parseToJsonElement("""
            {"parts":["# Shop","Spring picks",
              {"g":"links","label":"","lines":[$cards],"chars":300,"linkChars":160,"repeat":12,"links":12}]}
        """).jsonObject).parts
        assertEquals(emptySet<Int>(), PageDigest.foldSet(parts))
    }

    @Test fun declaredTimesPreferStructuredSources() {
        val (_, q) = read(article)
        assertEquals("2026-10-20T08:30:00+08:00", q.published)
        assertEquals("2026-10-21T09:00:00Z", q.updated)
        val s = PageDigest.Signals(dates = listOf("time" to "2025-01-01", "itemprop:datePublished" to "2026-02-03", "ld:datePublished" to "not a date"))
        assertEquals("2026-02-03", PageDigest.dates(s).first)
    }

    @Test fun declaredTimeFormats() {
        assertEquals("2026-03-04", DeclaredTime.read("2026-03-04"))
        assertEquals("2026-03-04T10:20:00", DeclaredTime.read("2026-03-04 10:20:00"))
        assertEquals("2026-03-04", DeclaredTime.read("2026年3月4日"))
        assertEquals("2026-03-04T10:20:00+01:00", DeclaredTime.read("2026-03-04T10:20+01:00"))
        assertEquals("2026-03-04T10:20:05-05:00", DeclaredTime.read("2026/03/04T10:20:05.123-05"))
        assertEquals("2026-03-04T10:20:00Z", DeclaredTime.read("2026-03-04T10:20:00+00:00"))
        assertNull(DeclaredTime.read("yesterday"))
        assertNull(DeclaredTime.read("2026-13-45"))
        assertNull(DeclaredTime.read("Oct 20, 2026"))
    }

    @Test fun openDialogComesFirst() {
        val (r, q) = read("""
            {"url":"https://news.example.com/a","title":"A","parts":["$prose"],"links":[],"images":[],
             "dialogs":[["We use cookies","Accept all","Reject"]],"signals":{"textChars":600,"prominent":["A"]}}
        """)
        assertEquals(listOf("<<dialog>>", "We use cookies", "Accept all", "Reject", "<<end of dialog>>", prose), r.text.lines())
        assertEquals(1, q.dialogs)
    }

    @Test fun signInPageNeedsSignIn() {
        val (_, q) = read("""
            {"url":"https://app.example.com/login","title":"Sign in","parts":["# Sign in to continue","Email","Password","Forgot password?"],
             "links":[],"images":[],"dialogs":[],
             "signals":{"password":1,"signInShare":0.9,"signInText":["Sign in to continue Email Password","Sign in"],"prominent":["Sign in"],"textChars":60}}
        """)
        assertEquals(ReadVerdict.NEEDS_SIGN_IN, q.verdict)
    }

    /** [1] 登录字样只看登录框、标题、对话框:页面别处的「Log in」按钮不算。 */
    @Test fun signInWordsCountOnlyInTheBoxTitleOrDialogs() {
        val (_, q) = read("""
            {"url":"https://app.example.com/x","title":"Settings","parts":["Change PIN","Old","New"],"links":[],"images":[],"dialogs":[],
             "signals":{"password":1,"signInShare":0.8,"signInText":["Change PIN Old New","Settings"],"prominent":["Settings","Log in"],"textChars":20}}
        """)
        assertEquals(ReadVerdict.THIN, q.verdict)
    }

    /** [1] 文章页旁边一个小登录框:正文照读,不算要先登录。 */
    @Test fun aSmallSignInBoxBesideALongArticleIsFine() {
        val long = "$prose ".repeat(4).trim()
        val (_, q) = read("""
            {"url":"https://news.example.com/a","title":"Story","parts":["$long"],"links":[],"images":[],"dialogs":[],
             "signals":{"password":1,"signInShare":0.05,"signInText":["Log in Password"],"prominent":["Story"],"textChars":2200}}
        """)
        assertEquals(ReadVerdict.OK, q.verdict)
    }

    @Test fun verificationFrameOrHttpErrorIsRefused() {
        val (_, captcha) = read("""
            {"url":"https://news.example.com/a","title":"Just a moment","parts":["Checking your browser"],"links":[],"images":[],"dialogs":[],
             "signals":{"prominent":["Just a moment"],"frames":["https://challenges.cloudflare.com/x"],"textChars":21}}
        """)
        assertEquals(ReadVerdict.REFUSED, captcha.verdict)
        val (_, forbidden) = read("""
            {"url":"https://news.example.com/a","title":"403","parts":["Forbidden"],"links":[],"images":[],"dialogs":[],
             "signals":{"prominent":["403"],"textChars":9}}
        """, httpStatus = 403)
        assertEquals(ReadVerdict.REFUSED, forbidden.verdict)
        assertEquals("HTTP 403", forbidden.cause)
    }

    /** 文章正文里提到「captcha」不算被拒:只看醒目处。 */
    @Test fun anArticleAboutCaptchasIsNotRefused() {
        val body = "How a captcha works and why sites use one. $prose"
        val (_, q) = read("""
            {"url":"https://news.example.com/a","title":"Bots","parts":["$body"],"links":[],"images":[],"dialogs":[],
             "signals":{"prominent":["Bots"],"textChars":600}}
        """)
        assertEquals(ReadVerdict.OK, q.verdict)
    }

    @Test fun picturePageIsImageTextAndAnEmptyPageThin() {
        val (_, pic) = read("""
            {"url":"https://maps.example.com/","title":"Map","parts":["Zoom in","Zoom out"],"links":[],"images":[],"dialogs":[],
             "signals":{"region":[1000,800],"pictures":[[0,0,1000,700]],"prominent":["Map"],"textChars":15}}
        """)
        assertEquals(ReadVerdict.IMAGE_TEXT, pic.verdict)
        val (_, short) = read("""
            {"url":"https://app.example.com/","title":"App","parts":["Loading"],"links":[],"images":[],"dialogs":[],"signals":{"textChars":7}}
        """)
        assertEquals(ReadVerdict.THIN, short.verdict)
    }

    /** [9] 小图(图标)不算;重叠的图只算一次。 */
    @Test fun pictureCoverIgnoresIconsAndCountsOverlapOnce() {
        fun share(vararg b: PageDigest.Box) = PageDigest.pictureShare(PageDigest.Signals(regionW = 1000, regionH = 1000, pictures = b.toList()))
        val icons = (0 until 100).map { PageDigest.Box((it % 10) * 100, (it / 10) * 100, 40, 40) }.toTypedArray()
        assertEquals(0.0, share(*icons), 0.0)
        val half = PageDigest.Box(0, 0, 1000, 500)
        assertEquals(0.5, share(half), 0.02)
        assertEquals(0.5, share(half, half, PageDigest.Box(0, 0, 500, 500)), 0.02)
        assertEquals(1.0, share(half, PageDigest.Box(0, 500, 1000, 500)), 0.02)
    }

    @Test fun missingFieldsParseToAnEmptyPage() {
        val d = PageDigest.parse(JsonObject(emptyMap()))
        val r = PageDigest.render(d, false)
        assertEquals("", r.text)
        assertEquals(ReadVerdict.THIN, PageDigest.quality(d, r, null, null).verdict)
    }
}
