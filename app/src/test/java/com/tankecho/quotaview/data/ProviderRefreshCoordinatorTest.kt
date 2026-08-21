package com.tankecho.quotaview.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRefreshCoordinatorTest {
    @Test
    fun `emits in completion order and isolates provider failures`() = runBlocking {
        val emitted = mutableListOf<ProviderStatus>()
        val requests = listOf(
            request("slow", "Slow", delayMs = 80),
            ProviderRefreshRequest("broken", "Broken") {
                delay(5)
                error("provider unavailable")
            },
            request("fast", "Fast", delayMs = 20),
        )

        ProviderRefreshCoordinator.collect(requests) { emitted += it }

        assertEquals(listOf("broken", "fast", "slow"), emitted.map { it.id })
        assertNotNull(emitted[0].error)
        assertNull(emitted[1].error)
        assertNull(emitted[2].error)
    }

    private fun request(id: String, name: String, delayMs: Long) =
        ProviderRefreshRequest(id, name) {
            delay(delayMs)
            ProviderStatus(id, name, "Plan", emptyList(), now())
        }
}
