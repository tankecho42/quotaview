package com.tankecho.quotaview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class ProviderRefreshRequest(
    val id: String,
    val name: String,
    val fetch: suspend () -> ProviderStatus,
)

/**
 * Runs every provider independently and emits each result as soon as it finishes.
 * A provider exception becomes that provider's error state and never cancels siblings.
 */
object ProviderRefreshCoordinator {
    suspend fun collect(
        requests: List<ProviderRefreshRequest>,
        onResult: (ProviderStatus) -> Unit,
    ) = supervisorScope {
        requests.forEach { request ->
            launch {
                val status = try {
                    request.fetch()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    ProviderStatus(
                        id = request.id,
                        name = request.name,
                        plan = "?",
                        windows = emptyList(),
                        updatedAt = now(),
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: error.javaClass.simpleName.ifBlank { "请求失败" },
                    )
                }
                onResult(status)
            }
        }
    }
}
