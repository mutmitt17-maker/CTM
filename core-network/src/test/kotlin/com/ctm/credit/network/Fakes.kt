package com.ctm.credit.network

import java.time.Instant

/** In-memory queue used by the tests. The app uses Room instead. */
class InMemorySyncStore : SyncStore {
    val items = linkedMapOf<Long, SyncItem>()

    override fun sendable(limit: Int, excludeEntities: Set<String>): List<SyncItem> =
        items.values
            .filter { (it.status == SyncStatus.PENDING || it.status == SyncStatus.SENT) && it.entityKey !in excludeEntities }
            .sortedBy { it.localId }
            .take(limit)

    override fun blockedEntities(): Set<String> =
        items.values
            .filter { (it.status == SyncStatus.FAILED || it.status == SyncStatus.CONFLICT) && it.supersededBy == null }
            .mapNotNull { it.entityKey }
            .toSet()

    override fun save(item: SyncItem) {
        items[item.localId] = item
    }

    fun byKey(key: String): SyncItem = items.values.first { it.idempotencyKey == key }
}

/**
 * A fake server that behaves like sync_batch(): it remembers the keys it has already applied and
 * answers a repeated key with the recorded result and replayed = true, without applying it twice.
 */
class FakeServer(
    /** Decides what happens to an item the first time the server sees it: "applied", or a conflict/failure code. */
    private val decide: (SyncItem) -> Decision = { Decision.Applied },
) : SyncApi {
    sealed interface Decision {
        data object Applied : Decision
        data class Conflict(val code: String, val resultJson: String? = null) : Decision
        data class Failed(val code: String) : Decision
    }

    /** Keys received, in order, one entry per received item (repeats included). */
    val received = mutableListOf<String>()
    val appliedOnce = linkedMapOf<String, ServerResult>()
    val requestSizes = mutableListOf<Int>()

    /** Failures to simulate, consumed one per request. */
    val script = ArrayDeque<Script>()

    enum class Script {
        /** The request never reaches the server. */
        LOST_BEFORE,

        /** The server processes the whole batch, then the answer is lost. */
        LOST_AFTER,
        UNAUTHORIZED,
        SERVER_ERROR,
        EMPTY_ANSWER,
    }

    private val seen = linkedMapOf<String, ServerResult>()

    override fun syncBatch(items: List<SyncItem>): List<ServerResult> {
        requestSizes += items.size
        val step = script.removeFirstOrNull()
        if (step == Script.LOST_BEFORE) throw ApiException(ApiErrorKind.NETWORK, "connection cut")
        if (step == Script.UNAUTHORIZED) throw ApiException(ApiErrorKind.UNAUTHORIZED)
        if (step == Script.SERVER_ERROR) throw ApiException(ApiErrorKind.SERVER)
        if (step == Script.EMPTY_ANSWER) return emptyList()

        val results = items.map { item ->
            received += item.idempotencyKey
            val previous = seen[item.idempotencyKey]
            if (previous != null) {
                previous.copy(replayed = true)
            } else {
                val r = when (val d = decide(item)) {
                    FakeServer.Decision.Applied -> ServerResult(item.idempotencyKey, "applied", null, """{"ok":true}""")
                    is FakeServer.Decision.Conflict -> ServerResult(item.idempotencyKey, "conflict", d.code, d.resultJson)
                    is FakeServer.Decision.Failed -> ServerResult(item.idempotencyKey, "failed", d.code, null)
                }
                seen[item.idempotencyKey] = r
                if (r.status == "applied") appliedOnce[item.idempotencyKey] = r
                r
            }
        }
        if (step == Script.LOST_AFTER) throw ApiException(ApiErrorKind.NETWORK, "answer lost")
        return results
    }
}
