package com.wangzan101.mika.core.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingNumbersTest {
    // 实测记录(豆瓣 Top250 前五):依据里「5 千与千寻」的 5 是引文开头的序号,后面的「千」是片名,不是数量级
    private val douban = listOf(
        "1",
        "肖申克的救赎 / The Shawshank Redemption / 月黑高飞(港) / 刺激1995(台) [可播放]",
        "导演: 弗兰克·德拉邦特 Frank Darabont 主演: 蒂姆·罗宾斯 Tim Robbins /...",
        "1994 / 美国 / 犯罪 剧情",
        "9.7 3353028人评价",
        "希望让人自由。",
        "2",
        "霸王别姬 / 再见，我的妾 / Farewell My Concubine [可播放]",
        "导演: 陈凯歌 Kaige Chen 主演: 张国荣 Leslie Cheung / 张丰毅 Fengyi Zha...",
        "1993 / 中国大陆 中国香港 / 剧情 爱情 同性",
        "9.6 2461563人评价",
        "风华绝代。",
        "3",
        "泰坦尼克号 / Titanic / 铁达尼号(港 / 台) [可播放]",
        "导演: 詹姆斯·卡梅隆 James Cameron 主演: 莱昂纳多·迪卡普里奥 Leonardo...",
        "1997 / 美国 / 剧情 爱情 灾难",
        "9.5 2539108人评价",
        "失去的才是永恒的。",
        "4",
        "阿甘正传 / Forrest Gump / 福雷斯特·冈普 [可播放]",
        "导演: 罗伯特·泽米吉斯 Robert Zemeckis 主演: 汤姆·汉克斯 Tom Hanks / ...",
        "1994 / 美国 / 剧情 爱情",
        "9.5 2465323人评价",
        "一部美国近现代史。",
        "5",
        "千与千寻 / 千と千尋の神隠し / 神隐少女(台) / 千与千寻的神隐",
        "导演: 宫崎骏 Hayao Miyazaki 主演: 柊瑠美 Rumi Hîragi / 入野自由 Miy...",
        "2001 / 日本 / 剧情 动画 奇幻",
        "9.4 2577745人评价",
    ).joinToString("\n")
    private val doubanGoal = "用网页打开豆瓣电影 Top250(https://movie.douban.com/top250),告诉我排名前五的电影名和评分。只看,不要登录、不要标记想看"
    private val doubanAnswer = "豆瓣电影 Top250 前五名如下(来自页面原文,只读未登录、未做任何标记):\n\n1. 肖申克的救赎 — 9.7 分(3353028人评价)\n2. 霸王别姬 — 9.6 分(2461563人评价)\n3. 泰坦尼克号 — 9.5 分(2539108人评价)\n4. 阿甘正传 — 9.5 分(2465323人评价)\n5. 千与千寻 — 9.4 分(2577745人评价)\n\n依据:网页 https://movie.douban.com/top250 页面文字显示「1 肖申克的救赎…9.7」「2 霸王别姬…9.6」「3 泰坦尼克号…9.5」「4 阿甘正传…9.5」「5 千与千寻…9.4」。"

    @Test fun listIndexBeforeANameIsNotAMagnitude() {
        val g = Grounding.check(doubanGoal, doubanAnswer, listOf(douban))
        assertFalse(g.claims.toString(), g.claims.any { it.kind == Grounding.Kind.Number && "千" in it.text })
        assertEquals(g.unmatched.toString(), true, g.found)
        // 真的数量级照样算:屏上 46 亿,回答 46 万对不上;隔空格写的「1561 万热度」照样是数量级
        assertEquals(false, Grounding.check("", "下载 46万 次", listOf("46亿 次安装")).found)
        assertEquals(true, Grounding.check("", "下载 46亿 次\n依据:「46亿 次安装」", listOf("46亿 次安装")).found)
    }

    private val rivers = listOf("长江 干流长6397公里", "黄河全长约5464千米")

    // 标准单位的同义写法按一回事算:公里和千米
    @Test fun computationAcceptsUnitSynonyms() {
        val a = "长江比黄河长 933 公里:6397 公里 − 5464 千米 = 933 公里\n依据:「干流长6397公里」「黄河全长约5464千米」"
        assertEquals(Grounding.check("", a, rivers).unmatched.toString(), true, Grounding.check("", a, rivers).found)
        // 写出处的算式(提示词里的写法)也认
        val b = "长江比黄河长 933 公里:6397(长江词条)− 5464(黄河词条)= 933\n依据:「干流长6397公里」「黄河全长约5464千米」"
        assertEquals(true, Grounding.check("", b, rivers).found)
    }

    // 排版标记(加粗、代码)不影响核对:真实回答「相差约 6363 − 5464 = **899** 公里」
    @Test fun markupDoesNotHideTheComputation() {
        val screens = listOf("长江 全长6363千米,是中国第一大河", "黄河 干流全长5464公里")
        val a = "- **长江**:全长 **6363 千米**\n- 相差约 6363 − 5464 = **899** 公里\n依据:「全长6363千米」「干流全长5464公里」"
        assertEquals(Grounding.check("", a, screens).unmatched.toString(), true, Grounding.check("", a, screens).found)
        // 加粗不能把编造的数变成有依据
        val invented = "- 相差约 **899** 公里\n依据:「全长6363千米」「干流全长5464公里」"
        assertFalse(Grounding.check("", invented, screens).found == true)
    }

    // 单位换算不对、量纲不同的算式不算数
    @Test fun computationRejectsWrongUnits() {
        val metres = "长江比黄河长 933 公里:6397 公里 − 5464 米 = 933 公里\n依据:「干流长6397公里」「黄河全长约5464千米」"
        assertFalse(Grounding.check("", metres, listOf("长江 干流长6397公里", "黄河 5464 米")).found == true)
        val mixed = "差 933 元:6397 公里 − 5464 千米 = 933 元\n依据:「干流长6397公里」「黄河全长约5464千米」"
        assertFalse(Grounding.check("", mixed, rivers).found == true)
        // 换算对的照样过:1 公里 = 1000 米
        val ok = "差 5 米:6 公里 − 5995 米 = 5 米\n依据:「全程 6 公里」「已走 5995 米」"
        assertTrue(Grounding.check("", ok, listOf("全程 6 公里", "已走 5995 米")).found == true)
    }
}
