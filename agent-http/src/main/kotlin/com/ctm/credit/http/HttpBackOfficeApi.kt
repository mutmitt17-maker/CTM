package com.ctm.credit.http

import com.ctm.credit.agent.ApiResult
import com.ctm.credit.client.MerchantAccount
import com.ctm.credit.network.ApiException
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Implémentation HTTP des fonctionnalités Back-Office (identique à web/app.js)
 * ainsi que de la réinitialisation de mot de passe et du chargement des dossiers à livrer.
 */
class HttpBackOfficeApi(private val http: SupabaseHttp) {

    suspend fun resetPassword(email: String): ApiResult<Unit> = attempt {
        http.resetPasswordForEmail(email)
    }

    suspend fun fetchApplications(limit: Int = 50): ApiResult<List<LoanApplicationSummary>> = attempt {
        val arr = http.rest(
            "loan_application?select=id,status,total_price,down_payment,financed_amount,duration_days,installment_frequency,created_at,created_by" +
                "&order=created_at.desc&limit=$limit"
        )
        arr.mapNotNull { it.asJsonObjectOrNull()?.toLoanApplicationSummary() }
    }

    suspend fun fetchApprovedApplicationsForDelivery(): ApiResult<List<LoanApplicationSummary>> = attempt {
        val arr = http.rest(
            "loan_application?select=id,status,total_price,down_payment,financed_amount,duration_days,installment_frequency,created_at,created_by," +
                "customer(first_name,last_name,phone_number),inventory_item(brand,model,imei)" +
                "&status=eq.approved&order=created_at.asc&limit=50"
        )
        arr.mapNotNull { it.asJsonObjectOrNull()?.toLoanApplicationSummary() }
    }

    suspend fun fetchPendingReviews(): ApiResult<List<PendingReviewCase>> = attempt {
        val arr = http.rest(
            "loan_application?select=id,status,total_price,down_payment,financed_amount,duration_days,installment_frequency,created_at,created_by," +
                "customer(first_name,last_name,phone_number),inventory_item(brand,model)" +
                "&status=eq.pending_review&order=created_at.asc"
        )
        val apps = arr.mapNotNull { it.asJsonObjectOrNull()?.toLoanApplicationSummary() }
        val decisionMap = mutableMapOf<String, CreditDecisionInfo>()
        if (apps.isNotEmpty()) {
            val ids = apps.joinToString(",") { it.id }
            val decArr = try {
                http.rest(
                    "credit_decision?select=application_id,score,review_flags,hard_rule_failures,reason,created_at" +
                        "&application_id=in.($ids)&order=created_at.desc"
                )
            } catch (_: ApiException) {
                JsonArray()
            }
            for (el in decArr) {
                val o = el.asJsonObjectOrNull() ?: continue
                val appId = o.stringOrNull("application_id") ?: continue
                if (!decisionMap.containsKey(appId)) {
                    decisionMap[appId] = CreditDecisionInfo(
                        applicationId = appId,
                        score = o.intOrNull("score"),
                        reviewFlags = o.stringList("review_flags"),
                        hardRuleFailures = o.stringList("hard_rule_failures"),
                        reason = o.stringOrNull("reason"),
                    )
                }
            }
        }
        apps.map { app -> PendingReviewCase(app, decisionMap[app.id]) }
    }

    suspend fun resolveReview(applicationId: String, approve: Boolean, reason: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_application", applicationId)
            addProperty("p_approve", approve)
            addProperty("p_reason", reason)
        }
        http.call("resolve_review", params)
        Unit
    }

    suspend fun fetchCreditPolicy(): ApiResult<FullCreditPolicy> = attempt {
        val o = http.rest("credit_policy?select=*&limit=1").firstOrNull().asJsonObjectOrNull()
            ?: error("Aucune politique de crédit trouvée.")
        FullCreditPolicy(
            processingFeeRate = o.decimal("processing_fee_rate"),
            interestRatePer30Days = o.decimal("interest_rate_per_30_days"),
            penaltyRatePerDay = o.decimal("penalty_rate_per_day"),
            penaltyCapRate = o.decimal("penalty_cap_rate"),
            graceDays = o.intOrZero("grace_days"),
            lockAfterDaysOverdue = o.intOrZero("lock_after_days_overdue"),
            autoApproveMinScore = o.intOrZero("auto_approve_min_score"),
            reviewMinScore = o.intOrZero("review_min_score"),
            downPaymentMinPct = o.decimal("down_payment_min_pct"),
            downPaymentMaxPct = o.decimal("down_payment_max_pct"),
            allowedDurations = o.intList("allowed_durations"),
            faceMatchMin = o.decimal("face_match_min"),
            ocrConfidenceMin = o.decimal("ocr_confidence_min"),
            burdenGood = o.decimal("burden_good"),
            burdenOk = o.decimal("burden_ok"),
            burdenMax = o.decimal("burden_max"),
        )
    }

    suspend fun updateCreditPolicy(changes: Map<String, Any>, reason: String): ApiResult<Unit> = attempt {
        val pChanges = JsonObject().apply {
            changes.forEach { (k, v) ->
                when (v) {
                    is Number -> addProperty(k, v)
                    is Boolean -> addProperty(k, v)
                    is List<*> -> add(k, JsonArray().apply { v.forEach { item -> if (item is Number) add(item) } })
                    else -> addProperty(k, v.toString())
                }
            }
        }
        val params = JsonObject().apply {
            add("p_changes", pChanges)
            addProperty("p_reason", reason)
        }
        http.call("update_credit_policy", params)
        Unit
    }

    suspend fun recordManualMobileMoneyPayment(
        externalId: String,
        contractNumber: String,
        amount: BigDecimal,
        method: String,
        note: String?,
    ): ApiResult<Map<String, String>> = attempt {
        val params = JsonObject().apply {
            addProperty("p_external_id", externalId)
            addProperty("p_contract_number", contractNumber)
            addProperty("p_amount", amount)
            addProperty("p_method", method)
            if (note.isNullOrBlank()) add("p_note", com.google.gson.JsonNull.INSTANCE)
            else addProperty("p_note", note)
        }
        val res = http.call("record_manual_mobile_money_payment", params)
        val obj = res.asJsonObjectOrNull() ?: return@attempt emptyMap()
        buildMap {
            obj.entrySet().forEach { (k, v) ->
                if (v.isJsonPrimitive) put(k, v.asString)
            }
        }
    }

    suspend fun fetchPendingCashPayments(): ApiResult<List<PendingCashPayment>> = attempt {
        val arr = http.rest(
            "payment?select=id,amount,created_at,collected_by,loan_contract(contract_number)" +
                "&payment_method=eq.cash&status=eq.pending&order=created_at.asc"
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            PendingCashPayment(
                id = o.stringOrNull("id").orEmpty(),
                amount = o.decimal("amount"),
                createdAt = o.stringOrNull("created_at").orEmpty(),
                collectedBy = o.stringOrNull("collected_by"),
                contractNumber = o.get("loan_contract").asJsonObjectOrNull()?.stringOrNull("contract_number") ?: "—",
            )
        }
    }

    suspend fun confirmCashPayment(paymentId: String): ApiResult<Unit> = attempt {
        http.call("confirm_cash_payment", JsonObject().apply { addProperty("p_payment", paymentId) })
        Unit
    }

    suspend fun fetchMerchantAccounts(): ApiResult<List<MerchantAccount>> = attempt {
        val arr = http.rest("merchant_account?select=provider,merchant_number,display_name,active")
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            MerchantAccount(
                provider = o.stringOrNull("provider").orEmpty(),
                merchantNumber = o.stringOrNull("merchant_number").orEmpty(),
                displayName = o.stringOrNull("display_name").orEmpty(),
                active = o.boolOrFalse("active"),
            )
        }
    }

    suspend fun setMerchantAccount(
        provider: String,
        number: String,
        name: String,
        active: Boolean,
    ): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_provider", provider)
            addProperty("p_number", number)
            addProperty("p_name", name)
            addProperty("p_active", active)
        }
        http.call("set_merchant_account", params)
        Unit
    }

    suspend fun fetchManagedDevices(): ApiResult<List<ManagedDeviceRecord>> = attempt {
        val arr = http.rest(
            "managed_device?select=id,imei,lock_state,lock_reason,lock_provider,security_hold,last_seen_at" +
                "&order=created_at.desc&limit=50"
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            ManagedDeviceRecord(
                id = o.stringOrNull("id").orEmpty(),
                imei = o.stringOrNull("imei").orEmpty(),
                lockState = o.stringOrNull("lock_state").orEmpty(),
                lockReason = o.stringOrNull("lock_reason"),
                lockProvider = o.stringOrNull("lock_provider"),
                securityHold = o.boolOrFalse("security_hold"),
                lastSeenAt = o.stringOrNull("last_seen_at"),
            )
        }
    }

    suspend fun manualLockDevice(deviceId: String, reason: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_device", deviceId)
            addProperty("p_reason", reason)
        }
        http.call("manual_lock_device", params)
        Unit
    }

    suspend fun manualUnlockDevice(deviceId: String, hours: Int, reason: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_device", deviceId)
            addProperty("p_hours", hours)
            addProperty("p_reason", reason)
        }
        http.call("manual_unlock_device", params)
        Unit
    }

    suspend fun clearSecurityHold(deviceId: String, reason: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_device", deviceId)
            addProperty("p_reason", reason)
        }
        http.call("clear_security_hold", params)
        Unit
    }

    suspend fun fetchPendingSims(): ApiResult<List<PendingSimRecord>> = attempt {
        val arr = http.rest(
            "device_sim?select=managed_device_id,sim_hash,first_seen_at,managed_device(imei)" +
                "&status=eq.pending&order=first_seen_at.asc"
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            PendingSimRecord(
                deviceId = o.stringOrNull("managed_device_id").orEmpty(),
                imei = o.get("managed_device").asJsonObjectOrNull()?.stringOrNull("imei") ?: "—",
                simHash = o.stringOrNull("sim_hash").orEmpty(),
                firstSeenAt = o.stringOrNull("first_seen_at"),
            )
        }
    }

    suspend fun resolveSimChange(
        deviceId: String,
        simHash: String,
        approve: Boolean,
        reason: String,
    ): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_device", deviceId)
            addProperty("p_sim_hash", simHash)
            addProperty("p_approve", approve)
            addProperty("p_reason", reason)
        }
        http.call("resolve_sim_change", params)
        Unit
    }

    suspend fun fetchDevicePolicyAndProviders(): ApiResult<Pair<DeviceRestrictionPolicyRecord, List<BrandLockProviderRecord>>> = attempt {
        val o = http.rest("device_restriction_policy?select=*&limit=1").firstOrNull().asJsonObjectOrNull()
            ?: error("Politique des appareils introuvable.")
        val policy = DeviceRestrictionPolicyRecord(
            blockUsbDebugging = o.boolOrFalse("block_usb_debugging"),
            blockDeveloperMode = o.boolOrFalse("block_developer_mode"),
            blockFactoryReset = o.boolOrFalse("block_factory_reset"),
            blockUninstall = o.boolOrFalse("block_uninstall"),
            blockUnknownSources = o.boolOrFalse("block_unknown_sources"),
            blockRoot = o.boolOrFalse("block_root"),
            requireLockedBootloader = o.boolOrFalse("require_locked_bootloader"),
            requireVerifiedBoot = o.boolOrFalse("require_verified_boot"),
            requireOemLock = o.boolOrFalse("require_oem_lock"),
            attestationIntervalHours = o.intOrZero("attestation_interval_hours"),
            maxAttestationFailures = o.intOrZero("max_attestation_failures"),
            heartbeatMaxSilenceDays = o.intOrZero("heartbeat_max_silence_days"),
            simGraceHours = o.intOrZero("sim_grace_hours"),
            maxManualUnlockHours = o.intOrZero("max_manual_unlock_hours"),
        )
        val brandsArr = http.rest("brand_lock_provider?select=brand,provider&order=brand")
        val brands = brandsArr.mapNotNull { el ->
            val b = el.asJsonObjectOrNull() ?: return@mapNotNull null
            BrandLockProviderRecord(
                brand = b.stringOrNull("brand").orEmpty(),
                provider = b.stringOrNull("provider").orEmpty(),
            )
        }
        policy to brands
    }

    suspend fun updateDevicePolicy(changes: Map<String, Any>, reason: String): ApiResult<Unit> = attempt {
        val pChanges = JsonObject().apply {
            changes.forEach { (k, v) ->
                when (v) {
                    is Boolean -> addProperty(k, v)
                    is Number -> addProperty(k, v)
                    else -> addProperty(k, v.toString())
                }
            }
        }
        val params = JsonObject().apply {
            add("p_changes", pChanges)
            addProperty("p_reason", reason)
        }
        http.call("update_device_policy", params)
        Unit
    }

    suspend fun setBrandLockProvider(brand: String, provider: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_brand", brand)
            addProperty("p_provider", provider)
        }
        http.call("set_brand_lock_provider", params)
        Unit
    }

    suspend fun fetchStockAndAgencies(): ApiResult<Pair<List<StockItemRecord>, List<AgencyRecord>>> = attempt {
        val stockArr = http.rest(
            "inventory_item?select=id,imei,brand,model,retail_price,agency(name)&status=eq.in_stock&order=created_at.desc&limit=200"
        )
        val stock = stockArr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            StockItemRecord(
                id = o.stringOrNull("id").orEmpty(),
                imei = o.stringOrNull("imei").orEmpty(),
                brand = o.stringOrNull("brand").orEmpty(),
                model = o.stringOrNull("model").orEmpty(),
                retailPrice = o.decimal("retail_price"),
                agencyName = o.get("agency").asJsonObjectOrNull()?.stringOrNull("name"),
            )
        }
        val agArr = http.rest("agency?select=id,name,region&order=name")
        val agencies = agArr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            AgencyRecord(
                id = o.stringOrNull("id").orEmpty(),
                name = o.stringOrNull("name").orEmpty(),
                region = o.stringOrNull("region"),
            )
        }
        stock to agencies
    }

    suspend fun assignStockToAgency(itemIds: List<String>, agencyId: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            add("p_items", JsonArray().apply { itemIds.forEach { add(it) } })
            addProperty("p_agency", agencyId)
        }
        http.call("assign_stock_to_agency", params)
        Unit
    }

    suspend fun fetchCollections(): ApiResult<Pair<List<CollectionTaskRecord>, List<AssignableAgentRecord>>> = attempt {
        val arr = http.rest(
            "collection_task?select=id,task_type,priority,reason,status,due_date,assigned_to," +
                "loan_contract(contract_number),customer(first_name,last_name,phone_number)" +
                "&status=in.(open,in_progress)&order=due_date.asc"
        )
        val tasks = arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val cust = o.get("customer").asJsonObjectOrNull()
            val custName = cust?.let { "${it.stringOrNull("first_name").orEmpty()} ${it.stringOrNull("last_name").orEmpty()}".trim() }
            CollectionTaskRecord(
                id = o.stringOrNull("id").orEmpty(),
                taskType = o.stringOrNull("task_type") ?: "followup",
                priority = o.stringOrNull("priority") ?: "normal",
                reason = o.stringOrNull("reason"),
                status = o.stringOrNull("status") ?: "open",
                dueDate = o.stringOrNull("due_date"),
                assignedTo = o.stringOrNull("assigned_to"),
                contractNumber = o.get("loan_contract").asJsonObjectOrNull()?.stringOrNull("contract_number"),
                customerName = custName?.ifBlank { null },
                customerPhone = cust?.stringOrNull("phone_number"),
            )
        }.sortedByDescending { it.priority == "high" }

        val agentsArr = try {
            http.rest("app_user?select=id,role,zone&role=in.(field_agent,collections)&status=eq.active")
        } catch (_: ApiException) {
            JsonArray()
        }
        val agents = agentsArr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            AssignableAgentRecord(
                id = o.stringOrNull("id").orEmpty(),
                role = o.stringOrNull("role").orEmpty(),
                zone = o.stringOrNull("zone"),
            )
        }
        tasks to agents
    }

    suspend fun assignCollectionTask(taskId: String, assigneeId: String, taskType: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_task", taskId)
            addProperty("p_assignee", assigneeId)
            addProperty("p_task_type", taskType)
        }
        http.call("assign_collection_task", params)
        Unit
    }

    suspend fun completeCollectionTask(taskId: String, outcome: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_task", taskId)
            addProperty("p_outcome", outcome)
        }
        http.call("complete_collection_task", params)
        Unit
    }

    suspend fun lookupContract(contractNumber: String): ApiResult<ContractLookupResult> = attempt {
        val c = http.rest(
            "loan_contract?select=id,contract_number,status,loan_application(customer(first_name,last_name))" +
                "&contract_number=eq.$contractNumber&limit=1"
        ).firstOrNull().asJsonObjectOrNull()
            ?: error("Contrat introuvable (ou non visible avec votre rôle).")
        val id = c.stringOrNull("id").orEmpty()
        val cust = c.get("loan_application").asJsonObjectOrNull()?.get("customer").asJsonObjectOrNull()
        val custName = cust?.let { "${it.stringOrNull("first_name").orEmpty()} ${it.stringOrNull("last_name").orEmpty()}".trim() }?.ifBlank { null }
        val st = http.call("contract_status", JsonObject().apply { addProperty("p_contract", id) }).asJsonObjectOrNull()
        ContractLookupResult(
            id = id,
            contractNumber = c.stringOrNull("contract_number") ?: contractNumber,
            status = c.stringOrNull("status").orEmpty(),
            customerName = custName,
            overdueDays = st?.intOrZero("overdue_days") ?: 0,
            penaltyOutstandingUsd = st?.decimal("penalty_outstanding_usd") ?: BigDecimal.ZERO,
            totalOutstandingUsd = st?.decimal("total_outstanding_usd") ?: BigDecimal.ZERO,
        )
    }

    suspend fun requestPenaltyWaiver(contractId: String, amount: BigDecimal, reason: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_contract", contractId)
            addProperty("p_amount", amount)
            addProperty("p_reason", reason)
        }
        http.call("request_penalty_waiver", params)
        Unit
    }

    suspend fun requestRestructuring(
        contractId: String,
        durationDays: Int,
        frequency: String,
        waivePenalties: Boolean,
        reason: String,
    ): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_contract", contractId)
            addProperty("p_duration_days", durationDays)
            addProperty("p_frequency", frequency)
            addProperty("p_waive_penalties", waivePenalties)
            addProperty("p_reason", reason)
        }
        http.call("request_restructuring", params)
        Unit
    }

    suspend fun fetchPendingApprovals(): ApiResult<List<ApprovalRequestRecord>> = attempt {
        val arr = http.rest(
            "approval_request?select=id,kind,payload,reason,status,created_at&status=eq.pending&order=created_at.desc"
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            ApprovalRequestRecord(
                id = o.stringOrNull("id").orEmpty(),
                kind = o.stringOrNull("kind").orEmpty(),
                payloadJson = o.get("payload")?.toString() ?: "{}",
                reason = o.stringOrNull("reason"),
                status = o.stringOrNull("status").orEmpty(),
                createdAt = o.stringOrNull("created_at").orEmpty(),
            )
        }
    }

    suspend fun decideApproval(requestId: String, approve: Boolean, reason: String): ApiResult<Unit> = attempt {
        val params = JsonObject().apply {
            addProperty("p_request", requestId)
            addProperty("p_approve", approve)
            addProperty("p_reason", reason)
        }
        http.call("decide_approval", params)
        Unit
    }

    // ------------------------------------------------------------------ Helpers

    private suspend fun <T> attempt(block: () -> T): ApiResult<T> = try {
        ApiResult.Ok(withContext(Dispatchers.IO) { block() })
    } catch (e: ApiException) {
        ApiResult.Failure(ErrorMessages.forException(e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val msg = e.message
        ApiResult.Failure(ErrorMessages.translate(msg) ?: msg ?: "Erreur inattendue.")
    }

    private fun JsonObject.toLoanApplicationSummary(): LoanApplicationSummary {
        val cust = get("customer").asJsonObjectOrNull()
        val item = get("inventory_item").asJsonObjectOrNull()
        val custName = cust?.let { "${it.stringOrNull("first_name").orEmpty()} ${it.stringOrNull("last_name").orEmpty()}".trim() }?.ifBlank { null }
        val deviceLabel = item?.let { "${it.stringOrNull("brand").orEmpty()} ${it.stringOrNull("model").orEmpty()}".trim() }?.ifBlank { null }
        return LoanApplicationSummary(
            id = stringOrNull("id").orEmpty(),
            status = stringOrNull("status").orEmpty(),
            totalPrice = decimal("total_price"),
            downPayment = decimal("down_payment"),
            financedAmount = decimal("financed_amount"),
            durationDays = intOrZero("duration_days"),
            installmentFrequency = stringOrNull("installment_frequency"),
            createdAt = stringOrNull("created_at").orEmpty(),
            createdBy = stringOrNull("created_by"),
            customerName = custName,
            customerPhone = cust?.stringOrNull("phone_number"),
            deviceLabel = deviceLabel,
            deviceImei = item?.stringOrNull("imei"),
        )
    }

    private fun JsonObject.decimal(name: String): BigDecimal {
        val e = get(name)
        return if (e == null || e.isJsonNull) BigDecimal.ZERO else e.asBigDecimal
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        val e = get(name)
        return if (e == null || e.isJsonNull) null else e.asInt
    }

    private fun JsonObject.intOrZero(name: String): Int = intOrNull(name) ?: 0

    private fun JsonObject.boolOrFalse(name: String): Boolean {
        val e = get(name)
        return e != null && !e.isJsonNull && e.asBoolean
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val e = get(name)
        if (e == null || !e.isJsonArray) return emptyList()
        return e.asJsonArray.mapNotNull { it.asStringOrNull() }
    }

    private fun JsonObject.intList(name: String): List<Int> {
        val e = get(name)
        if (e == null || !e.isJsonArray) return emptyList()
        return e.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asInt else null }
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive) asString else null
}
