package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 沙箱任务、网页任务的依据不在手机屏幕上:命令输出、用户的回答同样是证据,Grounding 照样核。 */
class VerifierSourcesTest {
    private val criteria = Criteria(listOf("回答给出文件的行数"), "read", fallback = false)
    private val pass = """{"checks": [{"criterion": "回答给出文件的行数", "status": "met", "quote": "1287 data.csv"}], "verdict": "pass", "reason": "输出里有"}"""
    private fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)

    private fun sandboxOnly(outputs: List<String>, facts: List<String> = emptyList()) =
        Evidence(freshOf(""), null, ledgerOf(outputs = outputs), listOf("第1步 run_command(wc -l data.csv) 生效"), protected = false, appOpened = false,
            userFacts = facts)

    @Test fun commandOutputGroundsTheAnswer() = runTest {
        val m = FakeModel(reply(pass))
        val v = Verifier(m, null).verify("data.csv 有几行", criteria, "共 1287 行\n依据: 「1287 data.csv」",
            sandboxOnly(listOf("run_command「wc -l data.csv」退出码 0:\n1287 data.csv")))
        assertEquals(Verdict.Pass, v.verdict)
        val body = (m.requests.single().single().parts.single() as Part.Text).text
        assertTrue(body, "\"kind\":\"output\"" in body && "run_command(wc -l data.csv)" in body)
        assertFalse(body, Verifier.NOTHING_READ in body)
    }

    @Test fun numberMissingFromTheOutputIsNotPass() = runTest {
        val v = Verifier(FakeModel(reply(pass)), null).verify("data.csv 有几行", criteria, "共 1300 行\n依据: 「1300 data.csv」",
            sandboxOnly(listOf("run_command「wc -l data.csv」退出码 0:\n1287 data.csv")))
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(Verifier.NOT_IN_READS, v.reason)
    }

    @Test fun userAnswersAreEvidenceToo() = runTest {
        val c = Criteria(listOf("回答给出预算"), "read", fallback = false)
        val ok = """{"checks": [{"criterion": "回答给出预算", "status": "met", "quote": "300"}], "verdict": "pass", "reason": "用户说的"}"""
        val v = Verifier(FakeModel(reply(ok)), null).verify("按预算找", c, "预算 300 元\n依据: 用户说「300」",
            sandboxOnly(emptyList(), listOf("问用户「预算多少」,用户答:300")))
        assertEquals(Verdict.Pass, v.verdict)
    }
}
