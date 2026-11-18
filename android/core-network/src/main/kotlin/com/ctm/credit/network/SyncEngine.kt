package com.ctm.credit.network

import java.time.Instant

/** Where the queue is kept. The Android app implements it with Room (SQLCipher). */
interface SyncStore {
    /** Items to send, oldest first: PENDING, and SENT (a send that was cut: resent with the SAME key). */
    fun sendable(limit: Int, excludeEntities: Set<String>): List<SyncItem>

    /** Entities that have a FAILED or CONFLICT item not yet replaced by a correction. */
    fun blockedEntities(): Set<String>

    fun save(item: SyncItem)

    /** Nombre d'items en attente d'envoi (PENDING + SENT non encore confirmés). */
    fun pendingCount(): Int = sendable(Int.MAX_VALUE, emptySet()).size
}

/** The network call. Throws [ApiException] when the request did not give a usable answer. */
interface SyncApi {
    fun syncBatch(items: List<SyncItem>): List<ServerResult>
}

enum class SyncOutcome {
    /** Nothing left to send (items held back by a blocked entity do not count). */
    DONE,

    /** The network or the server failed: try again later. Cut items keep their key. */
    RETRY_LATER,

    /** The session ended: sign in again, then run again. */
    NEEDS_LOGIN,

    /** The server refused the account or the whole batch: stop and show an error. */
    REFUSED,

    /** A pass made no progress (unexpected answer): stopped to avoid an endless loop. */
    INCOMPLETE,
}

data class SyncReport(
    val outcome: SyncOutcome,
    val applied: Int,
    val conflicts: Int,
    val failed: Int,
    val requests: Int,
)

class SyncEngine(
    private val store: SyncStore,
    private val api: SyncApi,
    private val batchSize: Int = 50,
) {
    init {
        require(batchSize in 1..200) { "the server accepts 1 to 200 items per batch" }
    }

    private val dependencyErrors = setOf("unknown_customer", "unknown_device", "unknown_contract")

    /** Ajoute un item à la file (raccourci vers store.save). */
    fun enqueue(item: SyncItem) = store.save(item)

    /** Nombre d'items en attente d'envoi (délègue à SyncStore). */
    fun pendingCount(): Int = store.pendingCount()

    fun runOnce(): SyncReport {
        var applied = 0
        var conflicts = 0
        var failed = 0
        var requests = 0
        val blocked = store.blockedEntities().toMutableSet()

        fun report(outcome: SyncOutcome) = SyncReport(outcome, applied, conflicts, failed, requests)

        while (true) {
            val batch = store.sendable(batchSize, blocked)
            if (batch.isEmpty()) return report(SyncOutcome.DONE)

            // Written BEFORE the network call: after a crash, SENT items are resent with the same key.
            val sent = batch.map { it.copy(status = SyncStatus.SENT, attempts = it.attempts + 1) }
            sent.forEach(store::save)

            requests++
            val results = try {
                api.syncBatch(sent)
            } catch (e: ApiException) {
                return report(
                    when (e.kind) {
                        ApiErrorKind.NETWORK, ApiErrorKind.SERVER -> SyncOutcome.RETRY_LATER
                        ApiErrorKind.UNAUTHORIZED -> SyncOutcome.NEEDS_LOGIN
                        ApiErrorKind.FORBIDDEN, ApiErrorKind.CONFLICT, ApiErrorKind.REJECTED -> SyncOutcome.REFUSED
                    },
                )
            }

            val byKey = results.filter { it.idempotencyKey != null }.associateBy { it.idempotencyKey!! }
            var progress = 0

            sent.forEachIndexed { index, item ->
                val result = byKey[item.idempotencyKey] ?: results.getOrNull(index)?.takeIf { it.idempotencyKey == null }
                    ?: return@forEachIndexed // no answer for this item: stays SENT, resent later with the same key

                val entity = item.entityKey
                when (result.status) {
                    "applied" -> {
                        store.save(item.copy(status = SyncStatus.APPLIED, resultJson = result.resultJson, errorCode = null))
                        applied++
                        progress++
                    }

                    "conflict", "failed" -> {
                        val isConflict = result.status == "conflict"
                        val heldBack = !isConflict && entity != null && entity in blocked && result.error in dependencyErrors
                        if (heldBack) {
                            // It only failed because its parent action did not go through: wait for the parent.
                            store.save(item.copy(status = SyncStatus.PENDING, attempts = item.attempts - 1))
                            progress++
                        } else {
                            store.save(
                                item.copy(
                                    status = if (isConflict) SyncStatus.CONFLICT else SyncStatus.FAILED,
                                    resultJson = result.resultJson,
                                    errorCode = result.error,
                                ),
                            )
                            if (entity != null) blocked += entity
                            if (isConflict) conflicts++ else failed++
                            progress++
                        }
                    }
                    // Unknown status: leave the item SENT.
                }
            }

            if (progress == 0) return report(SyncOutcome.INCOMPLETE)
        }
    }

    companion object {
        /**
         * Builds the corrected action for a FAILED or CONFLICT item: a NEW key and the corrected content.
         * Returns the new item and the old one, marked as replaced so that it stops blocking its entity.
         * Never reuse a key with changed content: the server refuses it.
         */
        fun correct(
            old: SyncItem,
            newPayloadJson: String,
            newLocalId: Long,
            now: Instant,
            newKey: String = IdempotencyKeys.new(),
        ): Pair<SyncItem, SyncItem> {
            require(old.status == SyncStatus.FAILED || old.status == SyncStatus.CONFLICT) {
                "only a failed or conflicting action can be corrected"
            }
            val replacement = old.copy(
                localId = newLocalId,
                idempotencyKey = newKey,
                payloadJson = newPayloadJson,
                clientCreatedAt = now,
                clientUpdatedAt = now,
                status = SyncStatus.PENDING,
                resultJson = null,
                errorCode = null,
                attempts = 0,
                supersededBy = null,
            )
            return replacement to old.copy(supersededBy = newKey)
        }
    }
}
