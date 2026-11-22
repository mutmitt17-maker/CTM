package com.ctm.credit.http

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.model.CalculatorSpec
import com.ctm.credit.model.ExpectedOffer
import com.ctm.credit.model.Frequency
import com.ctm.credit.model.OfferInput
import com.ctm.credit.model.SpecVector
import com.ctm.credit.network.ServerResult
import com.ctm.credit.network.SyncItem
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.LocalDate

/** Conversions entre le JSON du serveur et les classes Kotlin. Les montants restent des BigDecimal (jamais des Double). */
internal object Mappers {

    // ------------------------------------------------------------------ catalogue hors ligne
    fun catalogue(root: JsonObject): AgentApi.OfflineCatalogueResult {
        val items = root.getAsJsonArray("items").map { e ->
            val o = e.asJsonObject
            CatalogItem(
                id = o.req("item_id").asString,
                brand = o.req("brand").asString,
                model = o.req("model").asString,
                retailPrice = o.req("retail_price").asBigDecimal.toDouble(),
                imageUrl = null,
                color = o.stringOrNull("color"),
                minDownPaymentPct = o.optNumber("min_down_payment_pct")?.toDouble(),
                imeiSuffix = o.stringOrNull("imei_suffix"),
            )
        }
        val t = root.getAsJsonObject("terms")
        val policy = CreditPolicy(
            processingFeeRate = t.req("processing_fee_rate").asBigDecimal.toDouble(),
            interestRatePer30Days = t.req("interest_rate_per_30_days").asBigDecimal.toDouble(),
            penaltyRatePerDay = t.req("penalty_rate_per_day").asBigDecimal.toDouble(),
            graceDays = t.req("grace_days").asInt,
            lockAfterDaysOverdue = t.req("lock_after_days_overdue").asInt,
            downPaymentMinPct = t.req("down_payment_min_pct").asBigDecimal.toDouble(),
            downPaymentMaxPct = t.req("down_payment_max_pct").asBigDecimal.toDouble(),
            allowedDurations = t.getAsJsonArray("allowed_durations").map { it.asInt },
        )
        return AgentApi.OfflineCatalogueResult(items, policy, calculatorSpec(root.get("calculator")))
    }

    /**
     * Si le calculateur du serveur ne se lit pas, on renvoie un spec vide : la vérification le refuse
     * et la simulation hors ligne est désactivée (jamais de calcul avec un calculateur non vérifié).
     */
    fun calculatorSpec(element: JsonElement?): CalculatorSpec = try {
        val o = element!!.asJsonObject
        CalculatorSpec(
            version = o.req("version").asString,
            vectors = o.getAsJsonArray("test_vectors").map { v ->
                val vo = v.asJsonObject
                val i = vo.getAsJsonObject("input")
                val x = vo.getAsJsonObject("expected")
                SpecVector(
                    input = OfferInput(
                        retailPrice = i.req("retail_price").asBigDecimal,
                        downPayment = i.req("down_payment").asBigDecimal,
                        durationDays = i.req("duration_days").asInt,
                        frequency = Frequency.fromWire(i.req("frequency").asString),
                        startDate = LocalDate.parse(i.req("start_date").asString),
                        processingFeeRate = i.req("processing_fee_rate").asBigDecimal,
                        interestRatePer30Days = i.req("interest_rate_per_30_days").asBigDecimal,
                    ),
                    expected = ExpectedOffer(
                        processingFee = x.req("processing_fee").asBigDecimal,
                        financedAmount = x.req("financed_amount").asBigDecimal,
                        interestAmount = x.req("interest_amount").asBigDecimal,
                        totalDue = x.req("total_due").asBigDecimal,
                        installmentCount = x.req("installment_count").asInt,
                        installmentAmount = x.req("installment_amount").asBigDecimal,
                        lastInstallmentAmount = x.req("last_installment_amount").asBigDecimal,
                        firstDueDate = LocalDate.parse(x.req("first_due_date").asString),
                        endDate = LocalDate.parse(x.req("end_date").asString),
                    ),
                )
            },
        )
    } catch (_: Exception) {
        CalculatorSpec(version = "unreadable", vectors = emptyList())
    }

    // ------------------------------------------------------------------ synchronisation
    /** Format attendu par `process_sync_batch` : `payload` est un objet JSON, pas un texte. */
    fun syncItems(items: List<SyncItem>): JsonArray = JsonArray().also { arr ->
        items.forEach { item ->
            arr.add(JsonObject().apply {
                addProperty("idempotency_key", item.idempotencyKey)
                addProperty("action", item.action.wire)
                add("payload", JsonParser.parseString(item.payloadJson))
                addProperty("client_created_at", item.clientCreatedAt.toString())
                addProperty("client_updated_at", item.clientUpdatedAt.toString())
            })
        }
    }

    fun syncResults(array: JsonArray): List<ServerResult> = array.map { e ->
        val o = e.asJsonObject
        ServerResult(
            idempotencyKey = o.stringOrNull("idempotency_key"),
            status = o.stringOrNull("status") ?: "failed",
            error = o.stringOrNull("error"),
            resultJson = o.get("result")?.takeUnless { it.isJsonNull }?.toString(),
            replayed = o.get("replayed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }

    // ------------------------------------------------------------------ petits outils
    private fun JsonObject.req(name: String): JsonElement {
        val e = get(name)
        require(e != null && !e.isJsonNull) { "champ manquant : $name" }
        return e
    }

    private fun JsonObject.optNumber(name: String): BigDecimal? {
        val e = get(name)
        return if (e == null || e.isJsonNull) null else e.asBigDecimal
    }
}
