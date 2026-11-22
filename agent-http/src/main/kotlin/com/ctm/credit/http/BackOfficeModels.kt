package com.ctm.credit.http

import java.math.BigDecimal

data class LoanApplicationSummary(
    val id: String,
    val status: String,
    val totalPrice: BigDecimal,
    val downPayment: BigDecimal,
    val financedAmount: BigDecimal,
    val durationDays: Int,
    val installmentFrequency: String?,
    val createdAt: String,
    val createdBy: String?,
    val customerName: String?,
    val customerPhone: String?,
    val deviceLabel: String?,
    val deviceImei: String?,
)

data class CreditDecisionInfo(
    val applicationId: String,
    val score: Int?,
    val reviewFlags: List<String>,
    val hardRuleFailures: List<String>,
    val reason: String?,
)

data class PendingReviewCase(
    val application: LoanApplicationSummary,
    val decision: CreditDecisionInfo?,
)

data class FullCreditPolicy(
    val processingFeeRate: BigDecimal,
    val interestRatePer30Days: BigDecimal,
    val penaltyRatePerDay: BigDecimal,
    val penaltyCapRate: BigDecimal,
    val graceDays: Int,
    val lockAfterDaysOverdue: Int,
    val autoApproveMinScore: Int,
    val reviewMinScore: Int,
    val downPaymentMinPct: BigDecimal,
    val downPaymentMaxPct: BigDecimal,
    val allowedDurations: List<Int>,
    val faceMatchMin: BigDecimal,
    val ocrConfidenceMin: BigDecimal,
    val burdenGood: BigDecimal,
    val burdenOk: BigDecimal,
    val burdenMax: BigDecimal,
)

data class PendingCashPayment(
    val id: String,
    val amount: BigDecimal,
    val createdAt: String,
    val collectedBy: String?,
    val contractNumber: String,
)

data class ManagedDeviceRecord(
    val id: String,
    val imei: String,
    val lockState: String,
    val lockReason: String?,
    val lockProvider: String?,
    val securityHold: Boolean,
    val lastSeenAt: String?,
)

data class PendingSimRecord(
    val deviceId: String,
    val imei: String,
    val simHash: String,
    val firstSeenAt: String?,
)

data class DeviceRestrictionPolicyRecord(
    val blockUsbDebugging: Boolean,
    val blockDeveloperMode: Boolean,
    val blockFactoryReset: Boolean,
    val blockUninstall: Boolean,
    val blockUnknownSources: Boolean,
    val blockRoot: Boolean,
    val requireLockedBootloader: Boolean,
    val requireVerifiedBoot: Boolean,
    val requireOemLock: Boolean,
    val attestationIntervalHours: Int,
    val maxAttestationFailures: Int,
    val heartbeatMaxSilenceDays: Int,
    val simGraceHours: Int,
    val maxManualUnlockHours: Int,
)

data class BrandLockProviderRecord(
    val brand: String,
    val provider: String,
)

data class StockItemRecord(
    val id: String,
    val imei: String,
    val brand: String,
    val model: String,
    val retailPrice: BigDecimal,
    val agencyName: String?,
)

data class AgencyRecord(
    val id: String,
    val name: String,
    val region: String?,
)

data class CollectionTaskRecord(
    val id: String,
    val taskType: String,
    val priority: String,
    val reason: String?,
    val status: String,
    val dueDate: String?,
    val assignedTo: String?,
    val contractNumber: String?,
    val customerName: String?,
    val customerPhone: String?,
)

data class AssignableAgentRecord(
    val id: String,
    val role: String,
    val zone: String?,
)

data class ContractLookupResult(
    val id: String,
    val contractNumber: String,
    val status: String,
    val customerName: String?,
    val overdueDays: Int,
    val penaltyOutstandingUsd: BigDecimal,
    val totalOutstandingUsd: BigDecimal,
)

data class ApprovalRequestRecord(
    val id: String,
    val kind: String,
    val payloadJson: String,
    val reason: String?,
    val status: String,
    val createdAt: String,
)
