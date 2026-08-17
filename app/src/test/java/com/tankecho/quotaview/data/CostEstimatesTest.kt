package com.tankecho.quotaview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimatesTest {

    @Test
    fun parsesProviderBreakdownAndIgnoresInvalidInput() {
        val parsed = CostEstimates.providerBreakdown(
            """{"codex":{"input":100,"cacheRead":200,"output":300}}"""
        )

        assertEquals(TokenBreakdown(100, 200, 300), parsed["codex"])
        assertTrue(CostEstimates.providerBreakdown("not-json").isEmpty())
    }

    @Test
    fun calculatesTieredApiCost() {
        val tokens = TokenBreakdown(
            input = 1_000_000,
            cacheRead = 2_000_000,
            output = 3_000_000,
        )

        assertEquals(31.5, CostSimulator.costUSD(tokens, CostSimulator.DEFAULT_RATES.getValue("codex")), 0.0001)
    }
}
