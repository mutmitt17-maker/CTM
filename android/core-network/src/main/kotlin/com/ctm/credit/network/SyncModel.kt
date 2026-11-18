package com.ctm.credit.network

import java.time.Instant
import java.util.UUID

/** Local state of one queued action. Mirrors the Room table sync_queue. */
enum class SyncStatus { PENDING, SENT, APPLIED, CONFLICT, FAILED }

/** Actions accepted by the server function sync_batch(). [wire] is the name sent to the server. */
enum class SyncAction(val wire: String) {
    CUSTOMER_CREATE("customer.create"),
    CUSTOMER_UPDATE("customer.update"),
    KYC_SUBMIT("kyc.submit"),
    EMERGENCY_CONTACT_ADD("emergency_contact.add"),
    CONSENT_RECORD("consent.record"),
    APPLICATION_SUBMIT("application.submit"),
    PAYMENT_CASH("payment.cash"),
    // Livraison
    START_DELIVERY("start_delivery"),
    VERIFY_DELIVERY_IMEI("verify_delivery_imei"),
    RECORD_HANDOVER_PROOF("record_handover_proof"),
    COMPLETE_DELIVERY("complete_delivery"),
    REQUEST_MDM_ENROLLMENT("request_mdm_enrollment"),
    COMPLETE_COLLECTION_TASK("complete_collection_task"),
    ;

    companion object {
        fun fromWire(value: String): SyncAction =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown action: $value")
    }
}

data class SyncItem(
    /** Local order of creation: items are always sent in this order. */
    val localId: Long,
    /** Generated once at creation, never changed, never reused for other content. */
    val idempotencyKey: String,
    val action: SyncAction,
    val payloadJson: String,
    /**
     * The thing the action is about (usually the customer id). Actions of one entity depend on each other:
     * when one of them fails or conflicts, the following ones are held back until the agent resolves it.
     */
    val entityKey: String?,
    val clientCreatedAt: Instant,
    val clientUpdatedAt: Instant,
    val status: SyncStatus = SyncStatus.PENDING,
    val resultJson: String? = null,
    /** Stable server code (see [SyncMessages]) when status is CONFLICT or FAILED. */
    val errorCode: String? = null,
    val attempts: Int = 0,
    /** Key of the corrected action that replaces this failed one; a replaced item no longer blocks its entity. */
    val supersededBy: String? = null,
)

object IdempotencyKeys {
    fun new(): String = UUID.randomUUID().toString()
}

/** One element of the server answer to sync_batch(). */
data class ServerResult(
    val idempotencyKey: String?,
    val status: String,
    val error: String?,
    val resultJson: String?,
    /** True when the server recognised the key and returned the result recorded the first time. */
    val replayed: Boolean = false,
)
