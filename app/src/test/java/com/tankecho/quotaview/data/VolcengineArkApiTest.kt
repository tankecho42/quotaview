package com.tankecho.quotaview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VolcengineArkApiTest {
    @Test
    fun parsesCodingPlanPercentWindowsInCanonicalOrder() {
        val windows = VolcengineArkApi.parseCodingPlan(
            """
            {
              "Result": {
                "Status": "Running",
                "UpdateTimestamp": 1782226444,
                "QuotaUsage": [
                  {"Level":"monthly","Percent":40,"ResetTimestamp":1785000000},
                  {"Level":"session","Percent":12.5,"ResetTimestamp":1782226478},
                  {"Level":"weekly","Percent":"25","ResetTimestamp":1782400000}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("coding_session", "coding_week", "coding_month"), windows.map { it.selectionKey })
        assertEquals(listOf(13, 25, 40), windows.map { it.usedPercent })
        assertEquals(1782226478L, windows.first().resetAt)
        assertEquals(5 * 3600L, windows.first().windowSeconds)
    }

    @Test
    fun parsesAgentPlanAbsoluteAfpWindows() {
        val windows = VolcengineArkApi.parseAgentPlan(
            """
            {
              "Result": {
                "PlanType":"Large",
                "AFPFiveHour":{"Quota":50,"Used":12.5,"SubscribeTime":1778788800000,"ResetTime":1778806800000},
                "AFPDaily":{"Quota":100,"Used":22.5,"SubscribeTime":1778716800000,"ResetTime":1778803200000},
                "AFPWeekly":{"Quota":"200","Used":"50","SubscribeTime":1778198400000,"ResetTime":1778803200000},
                "AFPMonthly":{"Quota":500,"Used":150,"SubscribeTime":1776211200000,"ResetTime":1778803200000}
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("agent_5h", "agent_daily", "agent_week", "agent_month"), windows.map { it.selectionKey })
        assertEquals(listOf(25, 23, 25, 30), windows.map { it.usedPercent })
        assertEquals(18_000L, windows.first().windowSeconds)
        assertTrue(windows.first().label.contains("12.5/50 AFP"))
    }

    @Test
    fun parsesVolcengineAccountBalance() {
        val balances = VolcengineArkApi.parseBalance(
            """
            {"Result":{"AvailableBalance":"77.01","CashBalance":"83.01","CreditLimit":"0.01","FreezeAmount":"5.01","ArrearsBalance":"1.01"}}
            """.trimIndent(),
        )

        assertEquals(1, balances.size)
        assertEquals(77.01, balances.single().amount, 0.0001)
        assertEquals("CNY", balances.single().currency)
        assertTrue(balances.single().detail.orEmpty().contains("冻结 5.01"))
    }

    @Test
    fun partialModeFailureDoesNotHideSuccessfulModes() {
        val coding = listOf(QuotaWindow("Coding · 5h 窗口", 10, 100, 18_000, selectionKey = "coding_session"))
        val status = VolcengineArkApi.merge(coding, emptyList(), emptyList(), listOf("账户余额：AccessDenied"))

        assertTrue(status.hasData)
        assertNull(status.error)
        assertEquals("CODING", status.plan)
        assertTrue(status.detailMessage.orEmpty().contains("AccessDenied"))
        assertTrue(status.detailMessageIsError)
    }

    @Test
    fun signatureV4MatchesDeterministicVector() {
        val headers = VolcengineSigner.headers(
            accessKeyId = "AKLTTEST",
            secretAccessKey = "test-secret",
            region = "cn-beijing",
            service = "ark",
            host = "open.volcengineapi.com",
            method = "POST",
            canonicalQuery = "Action=GetCodingPlanUsage&Version=2024-01-01",
            body = "{}",
            timestamp = Instant.parse("2026-08-21T01:02:03Z"),
        )

        assertEquals("20260821T010203Z", headers["X-Date"])
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", headers["X-Content-Sha256"])
        assertEquals(
            "HMAC-SHA256 Credential=AKLTTEST/20260821/cn-beijing/ark/request, " +
                "SignedHeaders=host;x-content-sha256;x-date, " +
                "Signature=df2dfab4b64ab050e203e85b4f98633a60fa3c971d52faed9c394a4e3b19d5c3",
            headers["Authorization"],
        )
        assertFalse(headers.values.any { it.contains("test-secret") })
    }
}
