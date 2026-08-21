package com.tankecho.quotaview.data

/** Stable, user-editable ordering shared by both home screen layouts. */
object ProviderOrdering {
    val defaultIds = listOf(
        "codex",
        "glm",
        "zai",
        "kimi",
        "claude",
        "minimax",
        "volcengine",
        "deepseek",
    )

    fun resolve(saved: String?): List<String> {
        val savedIds = saved.orEmpty()
            .split(',')
            .map(String::trim)
            .filter { it in defaultIds }
            .distinct()
        return savedIds + defaultIds.filterNot(savedIds::contains)
    }

    fun sort(statuses: List<ProviderStatus>, saved: String?): List<ProviderStatus> {
        val rank = resolve(saved).withIndex().associate { it.value to it.index }
        return statuses.withIndex()
            .sortedWith(compareBy<IndexedValue<ProviderStatus>>(
                { rank[it.value.id] ?: Int.MAX_VALUE },
                { it.index },
            ))
            .map(IndexedValue<ProviderStatus>::value)
    }

    fun move(saved: String?, draggedId: String, targetId: String, placeAfter: Boolean): List<String> {
        if (draggedId == targetId) return resolve(saved)
        val order = resolve(saved).toMutableList()
        if (draggedId !in order || targetId !in order) return order
        order.remove(draggedId)
        val targetIndex = order.indexOf(targetId)
        val insertionIndex = targetIndex + if (placeAfter) 1 else 0
        order.add(insertionIndex.coerceIn(0, order.size), draggedId)
        return order
    }

    fun visibleAfterMove(
        saved: String?,
        visibleIds: List<String>,
        draggedId: String,
        targetId: String,
        placeAfter: Boolean,
    ): List<String> {
        val visible = visibleIds.toSet()
        return move(saved, draggedId, targetId, placeAfter).filter(visible::contains)
    }

    /** Replaces only visible slots, retaining disabled providers at their existing positions. */
    fun applyVisibleOrder(saved: String?, visibleOrder: List<String>): List<String> {
        val order = resolve(saved).toMutableList()
        val visibleIds = visibleOrder.filter(order::contains).distinct()
        val visibleSet = visibleIds.toSet()
        val replacements = visibleIds.iterator()
        order.indices.forEach { index ->
            if (order[index] in visibleSet && replacements.hasNext()) {
                order[index] = replacements.next()
            }
        }
        return order
    }

    fun insertVisible(visibleIds: List<String>, draggedId: String, insertionIndex: Int): List<String> {
        val withoutDragged = visibleIds.filterNot { it == draggedId }.toMutableList()
        if (draggedId !in visibleIds) return visibleIds
        withoutDragged.add(insertionIndex.coerceIn(0, withoutDragged.size), draggedId)
        return withoutDragged
    }

    fun encode(order: List<String>): String = order.joinToString(",")
}
