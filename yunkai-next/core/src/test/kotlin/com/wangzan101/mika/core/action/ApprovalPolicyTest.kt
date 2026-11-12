// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {
    @Test fun paymentIrreversibleAndUnknownAsk() {
        for (level in ActionRisk.entries) {
            assertEquals("$level", if (level in listOf(ActionRisk.Irreversible, ActionRisk.Unknown)) Approval.Ask else Approval.Allow, ApprovalPolicy.decide(Risk(level)))
            assertEquals("$level payment", Approval.Ask, ApprovalPolicy.decide(Risk(level, payment = true)))
        }
    }

    @Test fun everyOriginAndAttendance() {
        for (origin in RunOrigin.entries) for (attendance in Attendance.entries) for (level in ActionRisk.entries) {
            val run = RunProvenance(origin, attendance)
            // 不花钱的:谁发起、人在不在都直接做
            val guarded = level in listOf(ActionRisk.Irreversible, ActionRisk.Unknown)
            val expectedRisk = if (!guarded) Approval.Allow else if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            assertEquals("$origin $attendance $level", expectedRisk, ApprovalPolicy.decide(Risk(level), run))
            // 花钱的:只有手机上发起、人在场时问;问不到的(渠道、定时、MCP、子任务、无人值守)不做
            val expected = if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            assertEquals("$origin $attendance $level payment", expected, ApprovalPolicy.decide(Risk(level, payment = true), run))
        }
    }
}
