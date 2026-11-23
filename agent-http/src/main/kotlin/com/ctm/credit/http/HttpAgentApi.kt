package com.ctm.credit.http

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.ImeiCheck
import com.ctm.credit.agent.MdmStatus
import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

data class ApprovedDeliveryApplication(
    val id: String,
    val status: String,
    val financedAmount: BigDecimal,
    val customerName: String?,
    val deviceLabel: String?,
    val deviceImei: String?,
)

data class AgentCustomerSummary(
    val id: String,
    val fullName: String,
    val phoneNumber: String?,
    val createdAt: String? = null,
)

data class AgentApplicationSummary(
    val id: String,
    val status: String,
    val financedAmount: BigDecimal,
    val decisionReason: String?,
    val createdAt: String?,
    val customerName: String?,
    val customerPhone: String?,
    val deviceLabel: String?,
)

data class AgentContractSummary(
    val id: String,
    val contractNumber: String,
    val status: String,
    val outstandingBalance: BigDecimal,
    val createdAt: String?,
    val customerName: String?,
)

/**
 * Implémentation réelle de [AgentApi] : parle à Supabase.
 * Les appels réseau tournent sur le fil d'entrée-sortie ; les ViewModels n'ont rien à gérer.
 */
class HttpAgentApi(private val http: SupabaseHttp) : AgentApi {

    // ------------------------------------------------------------------ session
    override suspend fun login(email: String, password: String): AgentApi.LoginResult = try {
        val o = io { http.passwordLogin(email, password) }
        val access = o.stringOrNull("access_token")
        val refresh = o.stringOrNull("refresh_token")
        if (access == null || refresh == null) AgentApi.LoginResult.Failure("Réponse de connexion inattendue.")
        else AgentApi.LoginResult.Success(access, refresh)
    } catch (e: ApiException) {
        AgentApi.LoginResult.Failure(ErrorMessages.forLogin(e))
    }

    suspend fun resetPassword(email: String): ApiResult<Unit> = attempt {
        http.resetPasswordForEmail(email)
    }

    override suspend fun me(): AgentSession = friendly {
        val data = http.call("me").asJsonObjectOrNull()
            ?: throw ApiException(ApiErrorKind.REJECTED, "Compte non configuré : aucune fiche utilisateur.")
        val agencyId = data.stringOrNull("agency_id").orEmpty()
        val agencyName = if (agencyId.isEmpty()) "" else
            http.rest("agency?id=eq.$agencyId&select=name").firstOrNull().asJsonObjectOrNull()?.stringOrNull("name").orEmpty()
        AgentSession(
            userId = data.stringOrNull("id").orEmpty(),
            agencyId = agencyId,
            agencyName = agencyName,
            agentName = http.userEmail().orEmpty(),
            role = data.stringOrNull("role").orEmpty(),
            status = data.stringOrNull("status").orEmpty(),
        )
    }

    override suspend fun offlineCatalogue(): AgentApi.OfflineCatalogueResult = friendly {
        val data = http.call("offline_catalogue").asJsonObjectOrNull()
            ?: throw ApiException(ApiErrorKind.SERVER, "Catalogue vide ou illisible.")
        Mappers.catalogue(data)
    }

    suspend fun uploadStoragePhoto(
        bucket: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String = "image/jpeg",
    ): ApiResult<Unit> = attempt {
        http.uploadStorageObject(bucket, objectPath, bytes, contentType)
    }

    override suspend fun uploadHandoverObject(
        objectPath: String,
        bytes: ByteArray,
        contentType: String,
    ): ApiResult<Unit> = uploadStoragePhoto("handover", objectPath, bytes, contentType)

    suspend fun fetchRecentCustomers(limit: Int = 50): ApiResult<List<AgentCustomerSummary>> = attempt {
        val arr = http.rest(
            "customer?select=id,first_name,last_name,phone_number,created_at&order=created_at.desc&limit=$limit",
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val id = o.stringOrNull("id") ?: return@mapNotNull null
            val fullName = "${o.stringOrNull("first_name").orEmpty()} ${o.stringOrNull("last_name").orEmpty()}".trim()
                .ifBlank { "Client" }
            AgentCustomerSummary(
                id = id,
                fullName = fullName,
                phoneNumber = o.stringOrNull("phone_number"),
                createdAt = o.stringOrNull("created_at"),
            )
        }
    }

    suspend fun fetchAgentApplications(limit: Int = 50): ApiResult<List<AgentApplicationSummary>> = attempt {
        val arr = http.rest(
            "loan_application?select=id,status,financed_amount,decision_reason,created_at," +
                "customer(first_name,last_name,phone_number),inventory_item(brand,model)" +
                "&order=created_at.desc&limit=$limit",
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val cust = o.get("customer").asJsonObjectOrNull()
            val item = o.get("inventory_item").asJsonObjectOrNull()
            val custName = cust?.let {
                "${it.stringOrNull("first_name").orEmpty()} ${it.stringOrNull("last_name").orEmpty()}".trim()
            }?.ifBlank { null }
            val deviceLabel = item?.let {
                "${it.stringOrNull("brand").orEmpty()} ${it.stringOrNull("model").orEmpty()}".trim()
            }?.ifBlank { null }
            val financedEl = o.get("financed_amount")
            val financed = if (financedEl == null || financedEl.isJsonNull) BigDecimal.ZERO else financedEl.asBigDecimal
            AgentApplicationSummary(
                id = o.stringOrNull("id").orEmpty(),
                status = o.stringOrNull("status").orEmpty(),
                financedAmount = financed,
                decisionReason = o.stringOrNull("decision_reason"),
                createdAt = o.stringOrNull("created_at"),
                customerName = custName,
                customerPhone = cust?.stringOrNull("phone_number"),
                deviceLabel = deviceLabel,
            )
        }
    }

    suspend fun fetchAgentContracts(limit: Int = 50): ApiResult<List<AgentContractSummary>> = attempt {
        val arr = http.rest(
            "contract?select=id,contract_number,status,outstanding_balance,created_at," +
                "customer(first_name,last_name)" +
                "&order=created_at.desc&limit=$limit",
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val cust = o.get("customer").asJsonObjectOrNull()
            val custName = cust?.let {
                "${it.stringOrNull("first_name").orEmpty()} ${it.stringOrNull("last_name").orEmpty()}".trim()
            }?.ifBlank { null }
            val balEl = o.get("outstanding_balance")
            val balance = if (balEl == null || balEl.isJsonNull) BigDecimal.ZERO else balEl.asBigDecimal
            AgentContractSummary(
                id = o.stringOrNull("id").orEmpty(),
                contractNumber = o.stringOrNull("contract_number").orEmpty(),
                status = o.stringOrNull("status").orEmpty(),
                outstandingBalance = balance,
                createdAt = o.stringOrNull("created_at"),
                customerName = custName,
            )
        }
    }

    // ------------------------------------------------------------------ livraison (appels directs, en ligne)
    suspend fun fetchApprovedApplicationsForDelivery(): ApiResult<List<ApprovedDeliveryApplication>> = attempt {
        val arr = http.rest(
            "loan_application?select=id,status,financed_amount," +
                "customer(first_name,last_name),inventory_item(brand,model,imei)" +
                "&status=eq.approved&order=created_at.asc&limit=50"
        )
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val cust = o.get("customer").asJsonObjectOrNull()
            val item = o.get("inventory_item").asJsonObjectOrNull()
            val custName = cust?.let {
                "${it.stringOrNull("first_name").orEmpty()} ${it.stringOrNull("last_name").orEmpty()}".trim()
            }?.ifBlank { null }
            val deviceLabel = item?.let {
                "${it.stringOrNull("brand").orEmpty()} ${it.stringOrNull("model").orEmpty()}".trim()
            }?.ifBlank { null }
            val financedEl = o.get("financed_amount")
            val financed = if (financedEl == null || financedEl.isJsonNull) BigDecimal.ZERO else financedEl.asBigDecimal
            ApprovedDeliveryApplication(
                id = o.stringOrNull("id").orEmpty(),
                status = o.stringOrNull("status").orEmpty(),
                financedAmount = financed,
                customerName = custName,
                deviceLabel = deviceLabel,
                deviceImei = item?.stringOrNull("imei"),
            )
        }
    }

    override suspend fun startDelivery(applicationId: String): ApiResult<String> = attempt {
        val data = http.call("start_delivery", params("p_application" to applicationId))
        when {
            data == null -> throw ApiException(ApiErrorKind.SERVER, "Réponse de livraison vide.")
            data.isJsonPrimitive -> data.asString
            else -> data.asJsonObjectOrNull()?.stringOrNull("id") ?: throw ApiException(ApiErrorKind.SERVER, "Réponse de livraison illisible.")
        }
    }

    override suspend fun verifyDeliveryImei(deliveryId: String, scannedImei: String): ApiResult<ImeiCheck> = attempt {
        val o = http.call("verify_delivery_imei", params("p_delivery" to deliveryId, "p_scanned_imei" to scannedImei)).asJsonObjectOrNull()
            ?: throw ApiException(ApiErrorKind.SERVER, "Réponse de vérification illisible.")
        ImeiCheck(matched = o.get("matched")?.asBoolean ?: false, reason = o.stringOrNull("reason"))
    }

    override suspend fun recordHandoverProof(
        deliveryId: String, photoPath: String, signatureHash: String, signerDevice: String,
    ): ApiResult<Unit> = attempt(alreadyDone = ALREADY_PROOF) {
        http.call(
            "record_handover_proof",
            params("p_delivery" to deliveryId, "p_photo_path" to photoPath, "p_signature_hash" to signatureHash, "p_signer_device" to signerDevice),
        )
        Unit
    }

    override suspend fun requestMdmEnrollment(deliveryId: String): ApiResult<Unit> = attempt(alreadyDone = ALREADY_ENROLLED) {
        http.call("request_mdm_enrollment", params("p_delivery" to deliveryId))
        Unit
    }

    override suspend fun mdmStatus(deliveryId: String): ApiResult<MdmStatus> = attempt {
        val row = http.rest("delivery?id=eq.$deliveryId&select=mdm_status").firstOrNull().asJsonObjectOrNull()
            ?: throw ApiException(ApiErrorKind.REJECTED, "Livraison introuvable.")
        MdmStatus.fromWire(row.stringOrNull("mdm_status").orEmpty())
    }

    override suspend fun completeDelivery(deliveryId: String): ApiResult<Unit> = attempt {
        http.call("complete_delivery", params("p_delivery" to deliveryId))
        Unit
    }

    // ------------------------------------------------------------------ outils
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Pour les fonctions qui lèvent une exception : le message de l'exception est déjà celui à afficher. */
    private suspend fun <T> friendly(block: () -> T): T = try {
        io(block)
    } catch (e: ApiException) {
        throw ApiException(e.kind, ErrorMessages.forException(e), e)
    }

    /**
     * Transforme un appel en [ApiResult]. [alreadyDone] : message du serveur qui signifie « déjà fait » ;
     * après une coupure réseau, rejouer l'étape ne doit pas bloquer l'agent.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> attempt(alreadyDone: Regex? = null, block: () -> T): ApiResult<T> = try {
        ApiResult.Ok(io(block))
    } catch (e: ApiException) {
        if (alreadyDone != null && alreadyDone.containsMatchIn(e.message.orEmpty())) ApiResult.Ok(Unit as T)
        else ApiResult.Failure(ErrorMessages.forException(e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Failure("Réponse inattendue du serveur.")
    }

    private fun params(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().also { o ->
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> o.addProperty(k, v)
                is Boolean -> o.addProperty(k, v)
                is Number -> o.addProperty(k, v)
                else -> o.addProperty(k, v.toString())
            }
        }
    }

    private companion object {
        val ALREADY_PROOF = Regex("proof already recorded", RegexOption.IGNORE_CASE)
        val ALREADY_ENROLLED = Regex("device already enrolled", RegexOption.IGNORE_CASE)
    }
}
