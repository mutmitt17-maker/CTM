package com.ctm.credit.http

import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncItem
import java.time.Instant
import kotlin.test.*

class HttpSyncApiTest {
    private fun item(key: String, action: SyncAction, payload: String) = SyncItem(
        localId = 1, idempotencyKey = key, action = action, payloadJson = payload, entityKey = "e-1",
        clientCreatedAt = Instant.parse("2026-02-01T10:00:00Z"), clientUpdatedAt = Instant.parse("2026-02-01T10:00:05Z"),
    )

    @Test fun `le lot est envoye au format attendu par le serveur`() = withEnv { env ->
        env.data("[]")
        env.sync.syncBatch(listOf(item("k1", SyncAction.CUSTOMER_CREATE, """{"id":"c-1","first_name":"Jean \"JJ\""}""")))
        val req = env.server.takeRequest()
        assertEquals("/functions/v1/api", req.path)
        assertEquals("fp-test", req.getHeader("x-device-fingerprint"))
        val body = req.json()
        assertEquals("sync_batch", body.get("action").asString)
        assertEquals(setOf("p_items"), body.getAsJsonObject("params").keySet())
        val sent = body.getAsJsonObject("params").getAsJsonArray("p_items")[0].asJsonObject
        assertEquals(setOf("idempotency_key", "action", "payload", "client_created_at", "client_updated_at"), sent.keySet())
        assertEquals("k1", sent.get("idempotency_key").asString)
        assertEquals("customer.create", sent.get("action").asString)
        assertTrue(sent.get("payload").isJsonObject) // un objet JSON, pas un texte
        assertEquals("Jean \"JJ\"", sent.getAsJsonObject("payload").get("first_name").asString)
        assertEquals("2026-02-01T10:00:00Z", sent.get("client_created_at").asString)
    }

    @Test fun `les resultats du serveur sont lus dans l ordre`() = withEnv { env ->
        env.data(
            """[{"idempotency_key":"k1","status":"applied","result":{"customer_id":"c-1"}},
                {"idempotency_key":"k2","status":"failed","error":"agent_not_allowed"},
                {"idempotency_key":"k3","status":"applied","replayed":true,"result":{"ok":true}}]""",
        )
        val r = env.sync.syncBatch(listOf(item("k1", SyncAction.CUSTOMER_CREATE, "{}"), item("k2", SyncAction.PAYMENT_CASH, "{}"), item("k3", SyncAction.APPLICATION_SUBMIT, "{}")))
        assertEquals(listOf("k1", "k2", "k3"), r.map { it.idempotencyKey })
        assertEquals(listOf("applied", "failed", "applied"), r.map { it.status })
        assertEquals("""{"customer_id":"c-1"}""", r[0].resultJson)
        assertEquals("agent_not_allowed", r[1].error)
        assertNull(r[1].resultJson)
        assertEquals(listOf(false, false, true), r.map { it.replayed })
    }

    @Test fun `reponse qui n est pas une liste est une erreur serveur`() = withEnv { env ->
        env.data("""{"oops":1}""")
        val e = assertFailsWith<ApiException> { env.sync.syncBatch(listOf(item("k1", SyncAction.PAYMENT_CASH, "{}"))) }
        assertEquals(ApiErrorKind.SERVER, e.kind)
    }

    @Test fun `coupure reseau est signalee pour reessayer plus tard`() = withEnv { env ->
        env.server.shutdown()
        val e = assertFailsWith<ApiException> { env.sync.syncBatch(listOf(item("k1", SyncAction.PAYMENT_CASH, "{}"))) }
        assertEquals(ApiErrorKind.NETWORK, e.kind)
    }

    @Test fun `session expiree est signalee pour redemander la connexion`() = withEnv { env ->
        env.reply(401, """{"error":"Invalid JWT"}""")
        env.reply(400, """{"msg":"Invalid Refresh Token"}""")
        val e = assertFailsWith<ApiException> { env.sync.syncBatch(listOf(item("k1", SyncAction.PAYMENT_CASH, "{}"))) }
        assertEquals(ApiErrorKind.UNAUTHORIZED, e.kind)
    }
}
