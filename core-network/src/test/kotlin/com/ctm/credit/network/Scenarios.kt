package com.ctm.credit.network

import java.time.Instant

/*
 * Every scenario returns null when it passes, or a message when it fails.
 * The JUnit tests call them, and so does the offline runner used when no test library is available.
 */

private val T0: Instant = Instant.parse("2026-11-16T10:00:00Z")

private fun item(
    id: Long,
    action: SyncAction,
    entity: String? = "cust-1",
    key: String = "key-$id",
    status: SyncStatus = SyncStatus.PENDING,
) = SyncItem(id, key, action, """{"n":$id}""", entity, T0, T0, status)

private fun customerFlow(store: InMemorySyncStore, entity: String = "cust-1", from: Long = 1) {
    store.save(item(from, SyncAction.CUSTOMER_CREATE, entity))
    store.save(item(from + 1, SyncAction.KYC_SUBMIT, entity))
    store.save(item(from + 2, SyncAction.APPLICATION_SUBMIT, entity))
}

private fun check(condition: Boolean, message: String): String? = if (condition) null else message

fun scenarioHappyPath(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    customerFlow(store)
    val r = SyncEngine(store, server).runOnce()
    return check(r.outcome == SyncOutcome.DONE, "outcome ${r.outcome}")
        ?: check(r.applied == 3 && r.conflicts == 0 && r.failed == 0, "counts $r")
        ?: check(store.items.values.all { it.status == SyncStatus.APPLIED }, "not all applied")
        ?: check(server.requestSizes == listOf(3), "requests ${server.requestSizes}")
        ?: check(server.received == listOf("key-1", "key-2", "key-3"), "order ${server.received}")
}

fun scenarioConnectionCutBeforeSending(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    customerFlow(store)
    server.script += FakeServer.Script.LOST_BEFORE
    val engine = SyncEngine(store, server)
    val first = engine.runOnce()
    if (first.outcome != SyncOutcome.RETRY_LATER) return "first outcome ${first.outcome}"
    if (!store.items.values.all { it.status == SyncStatus.SENT }) return "items should stay SENT after a cut"
    val second = engine.runOnce()
    return check(second.outcome == SyncOutcome.DONE && second.applied == 3, "second run $second")
        ?: check(server.appliedOnce.size == 3, "applied ${server.appliedOnce.size} times")
        ?: check(store.items.values.all { it.idempotencyKey.startsWith("key-") }, "keys changed")
}

fun scenarioAnswerLostAfterServerApplied(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    customerFlow(store)
    server.script += FakeServer.Script.LOST_AFTER
    val engine = SyncEngine(store, server)
    if (engine.runOnce().outcome != SyncOutcome.RETRY_LATER) return "should ask to retry"
    // The server DID apply the 3 actions. The resend must not apply them again.
    val second = engine.runOnce()
    val sentKeys = server.received
    return check(second.outcome == SyncOutcome.DONE, "second outcome ${second.outcome}")
        ?: check(server.appliedOnce.size == 3, "no duplicate expected, applied ${server.appliedOnce.size}")
        ?: check(sentKeys == listOf("key-1", "key-2", "key-3", "key-1", "key-2", "key-3"), "same keys expected, got $sentKeys")
        ?: check(store.items.values.all { it.status == SyncStatus.APPLIED }, "all should be applied")
        ?: check(store.items.values.all { it.attempts == 2 }, "attempts ${store.items.values.map { it.attempts }}")
}

fun scenarioCrashLeavesItemsSent(): String? {
    // App killed after saving SENT and before the network call: next start resends the same keys.
    val store = InMemorySyncStore()
    val server = FakeServer()
    store.save(item(1, SyncAction.CUSTOMER_CREATE, status = SyncStatus.SENT))
    store.save(item(2, SyncAction.KYC_SUBMIT, status = SyncStatus.PENDING))
    val r = SyncEngine(store, server).runOnce()
    return check(r.outcome == SyncOutcome.DONE && r.applied == 2, "run $r")
        ?: check(server.received == listOf("key-1", "key-2"), "received ${server.received}")
}

fun scenarioConflictHoldsBackDependents(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer { if (it.action == SyncAction.CUSTOMER_CREATE) FakeServer.Decision.Conflict("phone_already_registered") else FakeServer.Decision.Applied }
    // Customer 1 conflicts, in two separate runs so the dependents are not in the same batch.
    store.save(item(1, SyncAction.CUSTOMER_CREATE, "cust-1"))
    val engine = SyncEngine(store, server)
    val first = engine.runOnce()
    if (first.conflicts != 1) return "first run $first"
    store.save(item(2, SyncAction.KYC_SUBMIT, "cust-1"))
    store.save(item(3, SyncAction.CUSTOMER_CREATE, "cust-2", key = "key-3")) // other customer: not blocked
    val second = engine.runOnce()
    return check(second.outcome == SyncOutcome.DONE, "second outcome ${second.outcome}")
        ?: check("key-2" !in server.received, "the dependent action must not be sent")
        ?: check(store.byKey("key-2").status == SyncStatus.PENDING, "dependent should wait as PENDING")
        ?: check(store.byKey("key-3").status == SyncStatus.CONFLICT, "other customer is processed (and conflicts in this fake)")
        ?: check(store.byKey("key-1").errorCode == "phone_already_registered", "error code kept")
}

fun scenarioDependentInSameBatchIsNotMarkedFailed(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer {
        when (it.action) {
            SyncAction.CUSTOMER_CREATE -> FakeServer.Decision.Conflict("phone_already_registered")
            else -> FakeServer.Decision.Failed("unknown_customer") // what the real server says without its parent
        }
    }
    customerFlow(store)
    val r = SyncEngine(store, server).runOnce()
    return check(r.conflicts == 1 && r.failed == 0, "counts $r")
        ?: check(store.byKey("key-2").status == SyncStatus.PENDING, "kyc should wait, is ${store.byKey("key-2").status}")
        ?: check(store.byKey("key-3").status == SyncStatus.PENDING, "application should wait, is ${store.byKey("key-3").status}")
}

fun scenarioRealFailureIsNotHidden(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer { if (it.action == SyncAction.PAYMENT_CASH) FakeServer.Decision.Failed("contract_not_active") else FakeServer.Decision.Applied }
    store.save(item(1, SyncAction.PAYMENT_CASH, "contract-9"))
    val r = SyncEngine(store, server).runOnce()
    return check(r.failed == 1, "counts $r")
        ?: check(store.byKey("key-1").status == SyncStatus.FAILED, "should be FAILED")
        ?: check(store.byKey("key-1").errorCode == "contract_not_active", "code lost")
}

fun scenarioOfferChangedKeepsNewOffer(): String? {
    val store = InMemorySyncStore()
    val offer = """{"offer":{"total_due":"86.14"}}"""
    val server = FakeServer { FakeServer.Decision.Conflict("offer_changed", offer) }
    store.save(item(1, SyncAction.APPLICATION_SUBMIT))
    SyncEngine(store, server).runOnce()
    val saved = store.byKey("key-1")
    return check(saved.status == SyncStatus.CONFLICT, "status ${saved.status}")
        ?: check(saved.resultJson == offer, "new offer must be kept for the screen")
        ?: check(SyncMessages.resolutionFor(saved.errorCode) == Resolution.SHOW_NEW_OFFER, "wrong resolution")
}

fun scenarioBatchesOf50(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    for (i in 1L..120L) store.save(item(i, SyncAction.PAYMENT_CASH, entity = "c$i"))
    val r = SyncEngine(store, server).runOnce()
    return check(server.requestSizes == listOf(50, 50, 20), "sizes ${server.requestSizes}")
        ?: check(r.applied == 120 && r.requests == 3, "report $r")
        ?: check(server.received == (1..120).map { "key-$it" }, "order must follow creation")
}

fun scenarioUnauthorizedStopsAndKeepsKeys(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    customerFlow(store)
    server.script += FakeServer.Script.UNAUTHORIZED
    val engine = SyncEngine(store, server)
    val r = engine.runOnce()
    if (r.outcome != SyncOutcome.NEEDS_LOGIN) return "outcome ${r.outcome}"
    val again = engine.runOnce() // after signing in again
    return check(again.outcome == SyncOutcome.DONE && again.applied == 3, "after login $again")
        ?: check(server.appliedOnce.size == 3, "applied once each")
}

fun scenarioServerErrorRetriesLater(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    customerFlow(store)
    server.script += FakeServer.Script.SERVER_ERROR
    val r = SyncEngine(store, server).runOnce()
    return check(r.outcome == SyncOutcome.RETRY_LATER, "outcome ${r.outcome}")
        ?: check(store.items.values.none { it.status == SyncStatus.FAILED }, "a server error must not fail the items")
}

fun scenarioEmptyAnswerDoesNotLoopForever(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    customerFlow(store)
    server.script += FakeServer.Script.EMPTY_ANSWER
    val r = SyncEngine(store, server).runOnce()
    return check(r.outcome == SyncOutcome.INCOMPLETE, "outcome ${r.outcome}")
        ?: check(r.requests == 1, "requests ${r.requests}")
        ?: check(store.items.values.all { it.status == SyncStatus.SENT }, "items stay SENT")
}

fun scenarioCorrectionUsesNewKeyAndUnblocks(): String? {
    val store = InMemorySyncStore()
    var allowed = false
    val server = FakeServer {
        if (it.action == SyncAction.CUSTOMER_CREATE && !allowed) FakeServer.Decision.Failed("forbidden") else FakeServer.Decision.Applied
    }
    store.save(item(1, SyncAction.CUSTOMER_CREATE))
    store.save(item(2, SyncAction.KYC_SUBMIT))
    val engine = SyncEngine(store, server)
    val first = engine.runOnce()
    if (first.failed != 1) return "first $first"

    val old = store.byKey("key-1")
    val (replacement, replaced) = SyncEngine.correct(old, """{"n":99}""", newLocalId = 3, now = T0.plusSeconds(60), newKey = "key-new")
    store.save(replaced)
    store.save(replacement)
    allowed = true

    val second = engine.runOnce()
    return check(replacement.idempotencyKey != old.idempotencyKey, "a correction needs a new key")
        ?: check(second.outcome == SyncOutcome.DONE, "second $second")
        ?: check(store.byKey("key-new").status == SyncStatus.APPLIED, "replacement not applied")
        ?: check(store.byKey("key-1").status == SyncStatus.FAILED && store.byKey("key-1").supersededBy == "key-new", "old item kept for history")
        ?: check(server.received.count { it == "key-1" } == 1, "the old key must never be resent")
}

fun scenarioCorrectionRefusedForAppliedItem(): String? {
    val applied = item(1, SyncAction.CUSTOMER_CREATE, status = SyncStatus.APPLIED)
    return try {
        SyncEngine.correct(applied, "{}", 2, T0)
        "an applied action must not be correctable"
    } catch (e: IllegalArgumentException) {
        null
    }
}

fun scenarioClassifier(): String? {
    val table = listOf(
        Triple(null, null, ApiErrorKind.NETWORK),
        Triple(401, null, ApiErrorKind.UNAUTHORIZED),
        Triple(403, "42501", ApiErrorKind.FORBIDDEN),
        Triple(403, null, ApiErrorKind.FORBIDDEN),
        Triple(409, "P0409", ApiErrorKind.CONFLICT),
        Triple(400, null, ApiErrorKind.REJECTED),
        Triple(422, null, ApiErrorKind.REJECTED),
        Triple(500, null, ApiErrorKind.SERVER),
        Triple(503, null, ApiErrorKind.SERVER),
    )
    for ((status, code, expected) in table) {
        val got = ApiErrorClassifier.classify(status, code)
        if (got != expected) return "classify($status, $code) = $got, expected $expected"
    }
    return null
}

fun scenarioEveryKnownCodeHasAFrenchMessageAndResolution(): String? {
    for (code in SyncMessages.knownCodes) {
        val m = SyncMessages.describe(code)
        if (m.isBlank() || m == code || m.startsWith("Erreur inattendue")) return "no message for $code"
    }
    val required = listOf(
        "phone_already_registered", "device_unavailable", "stale_update", "offer_changed", "kyc_already_verified",
        "duplicate_record", "unknown_customer", "unknown_device", "unknown_contract", "contract_not_active",
        "forbidden", "agent_not_allowed", "missing_key_agent_or_action", "malformed_item",
        "idempotency_key_reused_with_different_payload", "unknown_action",
    )
    for (code in required) if (code !in SyncMessages.knownCodes) return "missing code $code"
    return check(SyncMessages.describe("something_new").startsWith("Erreur inattendue"), "unknown code needs a fallback")
}

fun scenarioActionNames(): String? {
    val expected = listOf(
        "customer.create", "customer.update", "kyc.submit", "emergency_contact.add",
        "consent.record", "application.submit", "payment.cash",
    )
    return check(SyncAction.entries.map { it.wire } == expected, "wire names ${SyncAction.entries.map { it.wire }}")
        ?: check(SyncAction.fromWire("kyc.submit") == SyncAction.KYC_SUBMIT, "fromWire")
        ?: check(IdempotencyKeys.new() != IdempotencyKeys.new(), "keys must be unique")
}

fun scenarioCanSendGateHoldsEntityUntilReady(): String? {
    val store = InMemorySyncStore()
    val server = FakeServer()
    store.save(item(1, SyncAction.CUSTOMER_CREATE, "cust-1", key = "c1"))
    store.save(item(2, SyncAction.KYC_SUBMIT, "cust-1", key = "k1"))
    store.save(item(3, SyncAction.EMERGENCY_CONTACT_ADD, "cust-1", key = "e1"))
    store.save(item(4, SyncAction.CUSTOMER_CREATE, "cust-2", key = "c2"))

    var photosReady = false
    val engine = SyncEngine(store, server, canSend = { it.action != SyncAction.KYC_SUBMIT || photosReady })

    val first = engine.runOnce()
    if (first.outcome != SyncOutcome.DONE || first.applied != 2) return "first $first"
    if (server.received != listOf("c1", "c2")) return "expected c1 and c2 only, got ${server.received}"
    if (store.byKey("k1").status != SyncStatus.PENDING || store.byKey("e1").status != SyncStatus.PENDING) {
        return "kyc and contact should remain PENDING"
    }

    photosReady = true
    val second = engine.runOnce()
    return check(second.outcome == SyncOutcome.DONE && second.applied == 2, "second $second")
        ?: check(server.received == listOf("c1", "c2", "k1", "e1"), "order ${server.received}")
}

val allScenarios: List<Pair<String, () -> String?>> = listOf(
    "happy path" to ::scenarioHappyPath,
    "connection cut before sending" to ::scenarioConnectionCutBeforeSending,
    "answer lost after the server applied" to ::scenarioAnswerLostAfterServerApplied,
    "crash leaves items SENT" to ::scenarioCrashLeavesItemsSent,
    "conflict holds back dependents" to ::scenarioConflictHoldsBackDependents,
    "dependent in same batch not failed" to ::scenarioDependentInSameBatchIsNotMarkedFailed,
    "real failure not hidden" to ::scenarioRealFailureIsNotHidden,
    "offer_changed keeps new offer" to ::scenarioOfferChangedKeepsNewOffer,
    "batches of 50" to ::scenarioBatchesOf50,
    "unauthorized stops and keeps keys" to ::scenarioUnauthorizedStopsAndKeepsKeys,
    "server error retries later" to ::scenarioServerErrorRetriesLater,
    "empty answer does not loop" to ::scenarioEmptyAnswerDoesNotLoopForever,
    "correction uses new key and unblocks" to ::scenarioCorrectionUsesNewKeyAndUnblocks,
    "correction refused for applied item" to ::scenarioCorrectionRefusedForAppliedItem,
    "error classifier" to ::scenarioClassifier,
    "french messages for every code" to ::scenarioEveryKnownCodeHasAFrenchMessageAndResolution,
    "action names" to ::scenarioActionNames,
    "canSend gate holds entity until ready" to ::scenarioCanSendGateHoldsEntityUntilReady,
)
