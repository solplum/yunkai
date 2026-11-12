package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.PageScriptProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** web_script 的「不读值的输入框」和读屏脚本 page.js 是同一条规则:两边的 autocomplete 清单要一致。 */
class PageScriptSecretRuleTest {
    private val pageJs = File("src/main/assets/web/page.js").readText()

    private fun tokens(name: String): Set<String> {
        val body = Regex("var $name = \\{([^}]*)\\}").find(pageJs)!!.groupValues[1]
        return Regex("'([^']+)'").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun autocompleteListMatchesPageScript() {
        assertEquals(tokens("PASSWORD_AUTOCOMPLETE") + tokens("SECRET_AUTOCOMPLETE"), PageScriptProtocol.SECRET_AUTOCOMPLETE.toSet())
    }
}
