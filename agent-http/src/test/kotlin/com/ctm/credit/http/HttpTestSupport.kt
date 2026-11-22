package com.ctm.credit.http

import com.ctm.credit.model.Frequency
import com.ctm.credit.model.LoanCalculator
import com.ctm.credit.model.OfferInput
import com.ctm.credit.security.DeviceFingerprint
import com.ctm.credit.security.TokenStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.math.BigDecimal
import java.time.LocalDate

class InMemoryTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null
    override fun accessToken() = access
    override fun refreshToken() = refresh
    override fun save(accessToken: String, refreshToken: String) { access = accessToken; refresh = refreshToken }
    override fun clear() { access = null; refresh = null }
}

class ConstantFingerprint(private val value: String) : DeviceFingerprint {
    override fun get() = value
}

/** Un faux serveur Supabase et les objets à tester, branchés dessus. */
class Env {
    val tokens = InMemoryTokenStore().apply { save("tok", "ref") }
    val server = MockWebServer().apply { start() }
    val http = SupabaseHttp(HttpConfig(server.url("/").toString().trimEnd('/'), "anon-key"), tokens, ConstantFingerprint("fp-test"))
    val agent = HttpAgentApi(http)
    val sync = HttpSyncApi(http)

    fun reply(code: Int, body: String) { server.enqueue(MockResponse().setResponseCode(code).setBody(body)) }
    fun ok(body: String) = reply(200, body)
    fun data(json: String) = reply(200, """{"data":$json}""")

    fun close() { try { server.shutdown() } catch (_: Exception) { } }
}

fun <T> withEnv(block: (Env) -> T): T {
    val env = Env()
    try { return block(env) } finally { env.close() }
}

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

fun RecordedRequest.json(): JsonObject = JsonParser.parseString(body.readUtf8()).asJsonObject

/** Réponse `offline_catalogue` au format du serveur ; le calculateur est calculé avec le vrai LoanCalculator. */
fun catalogueJson(withCalculator: Boolean = true): String {
    val input = OfferInput(
        retailPrice = BigDecimal("150.00"), downPayment = BigDecimal("30.00"), durationDays = 60,
        frequency = Frequency.WEEKLY, startDate = LocalDate.of(2026, 1, 1),
        processingFeeRate = BigDecimal("0.03"), interestRatePer30Days = BigDecimal("0.06"),
    )
    val o = LoanCalculator.compute(input)
    val root = JsonObject()
    root.addProperty("currency", "USD")
    if (withCalculator) {
        val calc = JsonObject()
        calc.addProperty("version", LoanCalculator.VERSION)
        calc.add("test_vectors", JsonArray().apply {
            add(JsonObject().apply {
                add("input", JsonObject().apply {
                    add("retail_price", JsonPrimitive(input.retailPrice)); add("down_payment", JsonPrimitive(input.downPayment))
                    addProperty("duration_days", input.durationDays); addProperty("frequency", input.frequency.wire)
                    addProperty("start_date", input.startDate.toString())
                    add("processing_fee_rate", JsonPrimitive(input.processingFeeRate))
                    add("interest_rate_per_30_days", JsonPrimitive(input.interestRatePer30Days))
                })
                add("expected", JsonObject().apply {
                    add("processing_fee", JsonPrimitive(o.processingFee)); add("financed_amount", JsonPrimitive(o.financedAmount))
                    add("interest_amount", JsonPrimitive(o.interestAmount)); add("total_due", JsonPrimitive(o.totalDue))
                    addProperty("installment_count", o.installmentCount)
                    add("installment_amount", JsonPrimitive(o.installmentAmount))
                    add("last_installment_amount", JsonPrimitive(o.lastInstallmentAmount))
                    addProperty("first_due_date", o.firstDueDate.toString()); addProperty("end_date", o.endDate.toString())
                })
            })
        })
        root.add("calculator", calc)
    }
    root.add("terms", JsonParser.parseString(
        """{"down_payment_min_pct":20,"down_payment_max_pct":60,"allowed_durations":[30,60,90],
            "processing_fee_rate":0.03,"interest_rate_per_30_days":0.06,"penalty_rate_per_day":0.005,
            "grace_days":3,"lock_after_days_overdue":15}"""))
    root.add("items", JsonParser.parseString(
        """[{"item_id":"item-1","brand":"Samsung","model":"A15","color":"noir","retail_price":149.99,
             "min_down_payment_pct":25,"imei_suffix":"1234"},
            {"item_id":"item-2","brand":"Tecno","model":"Spark 20","color":null,"retail_price":120.00,
             "min_down_payment_pct":null,"imei_suffix":"9876"}]"""))
    return root.toString()
}
