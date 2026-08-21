package com.tankecho.quotaview.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderOrderingTest {
    @Test
    fun `sort follows saved order instead of completion order`() {
        val completionOrder = listOf(status("deepseek"), status("codex"), status("zai"))

        val sorted = ProviderOrdering.sort(completionOrder, "zai,deepseek,codex")

        assertEquals(listOf("zai", "deepseek", "codex"), sorted.map { it.id })
    }

    @Test
    fun `move persists before and after positions while retaining hidden providers`() {
        val movedBefore = ProviderOrdering.move(null, "deepseek", "glm", placeAfter = false)
        assertEquals("deepseek", movedBefore[movedBefore.indexOf("glm") - 1])
        assertEquals(ProviderOrdering.defaultIds.toSet(), movedBefore.toSet())

        val movedAfter = ProviderOrdering.move(
            ProviderOrdering.encode(movedBefore),
            "codex",
            "zai",
            placeAfter = true,
        )
        assertEquals("codex", movedAfter[movedAfter.indexOf("zai") + 1])
    }

    @Test
    fun `resolve removes duplicates and appends newly supported providers`() {
        val resolved = ProviderOrdering.resolve("deepseek,deepseek,unknown,codex")

        assertEquals(listOf("deepseek", "codex"), resolved.take(2))
        assertEquals(ProviderOrdering.defaultIds.toSet(), resolved.toSet())
    }

    private fun status(id: String) = ProviderStatus(id, id, "?", emptyList(), 0)
}
