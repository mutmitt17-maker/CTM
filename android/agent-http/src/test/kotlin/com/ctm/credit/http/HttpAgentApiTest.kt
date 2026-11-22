package com.ctm.credit.http

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.ImeiCheck
import com.ctm.credit.agent.MdmStatus
import com.ctm.credit.model.CalculatorVerifier
import com.ctm.credit.model.VerificationResult
import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException
import com.google.gson.JsonParser
import kotlin.test.*

class HttpAgentApiTest {

    // ------------------------------------------------------------------ connexion
    @Test fun `connexion reussie envoie email et mot de passe a Supabase`() = withEnv { env ->
        env.ok("""{"access_token":"acc","refresh_token":"ref2","user":{"email":"a@b.cd"}}""")
        val result = blocking { env.agent.login("a@b.cd", "secret") }
        assertEquals(AgentApi.LoginResult.Success("acc", "ref2"), result)
        val req = env.server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("anon-key", req.getHeader("apikey"))
        assertEquals(JsonParser.parseString("""{"email":"a@b.cd","password":"secret"}"""), req.json())
    }

    @Test fun `mauvais identifiants donnent un message clair`() = withEnv { env ->
        env.reply(400, """{"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        val result = blocking { env.agent.login("a@b.cd", "faux") }
        assertEquals(AgentApi.LoginResult.Failure("Email ou mot de passe incorrect."), result)
    }

    @Test fun `serveur injoignable a la connexion`() = withEnv { env ->
        env.server.shutdown()
        val result = blocking { env.agent.login("a@b.cd", "secret") }
        assertTrue(result is AgentApi.LoginResult.Failure && result.message.startsWith("Pas de connexion"))
    }

    // ------------------------------------------------------------------ session et catalogue
    @Test fun `me assemble la session avec agence et email`() = withEnv { env ->
        env.data("""{"id":"u-1","role":"field_agent","status":"active","agency_id":"ag-1"}""")
        env.ok("""[{"name":"Agence Test"}]""")
        env.ok("""{"email":"agent@ctm.cd"}""")
        val s = blocking { env.agent.me() }
        assertEquals("u-1", s.userId); assertEquals("ag-1", s.agencyId); assertEquals("Agence Test", s.agencyName)
        assertEquals("agent@ctm.cd", s.agentName); assertEquals("field_agent", s.role); assertEquals("active", s.status)
        assertEquals("/functions/v1/api", env.server.takeRequest().path)
        assertEquals("/rest/v1/agency?id=eq.ag-1&select=name", env.server.takeRequest().path)
        assertEquals("/auth/v1/user", env.server.takeRequest().path)
    }

    @Test fun `catalogue hors ligne lu en entier et calculateur verifie`() = withEnv { env ->
        env.data(catalogueJson())
        val c = blocking { env.agent.offlineCatalogue() }

        val req = env.server.takeRequest()
        assertEquals("/functions/v1/api", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("anon-key", req.getHeader("apikey"))
        assertEquals("fp-test", req.getHeader("x-device-fingerprint"))
        assertEquals(JsonParser.parseString("""{"action":"offline_catalogue","params":{}}"""), req.json())

        assertEquals(2, c.items.size)
        val first = c.items[0]
        assertEquals("item-1", first.id); assertEquals(149.99, first.retailPrice)
        assertEquals("noir", first.color); assertEquals(25.0, first.minDownPaymentPct); assertEquals("1234", first.imeiSuffix)
        assertNull(c.items[1].minDownPaymentPct); assertNull(c.items[1].color)

        assertEquals(0.03, c.policy.processingFeeRate); assertEquals(0.06, c.policy.interestRatePer30Days)
        assertEquals(20.0, c.policy.downPaymentMinPct); assertEquals(60.0, c.policy.downPaymentMaxPct)
        assertEquals(listOf(30, 60, 90), c.policy.allowedDurations); assertEquals(15, c.policy.lockAfterDaysOverdue)
        assertTrue(CalculatorVerifier.verify(c.calculatorSpec) is VerificationResult.Ok)
    }

    @Test fun `calculateur illisible desactive la simulation hors ligne`() = withEnv { env ->
        env.data(catalogueJson(withCalculator = false))
        val c = blocking { env.agent.offlineCatalogue() }
        assertEquals(2, c.items.size)
        assertTrue(CalculatorVerifier.verify(c.calculatorSpec) is VerificationResult.Disabled)
    }

    @Test fun `refus du catalogue affiche un message en francais`() = withEnv { env ->
        env.reply(403, """{"error":"not allowed to read the catalogue","code":"42501"}""")
        val e = assertFailsWith<ApiException> { blocking { env.agent.offlineCatalogue() } }
        assertEquals(ApiErrorKind.FORBIDDEN, e.kind)
        assertEquals("Vous n'avez pas accès au catalogue.", e.message)
    }

    // ------------------------------------------------------------------ jeton expiré
    @Test fun `jeton expire est renouvele puis l appel est rejoue`() = withEnv { env ->
        env.reply(401, """{"error":"Invalid JWT"}""")
        env.ok("""{"access_token":"new","refresh_token":"newref"}""")
        env.data(catalogueJson())
        val c = blocking { env.agent.offlineCatalogue() }
        assertEquals(2, c.items.size)

        assertEquals("/functions/v1/api", env.server.takeRequest().path)
        val refresh = env.server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", refresh.path)
        assertEquals(JsonParser.parseString("""{"refresh_token":"ref"}"""), refresh.json())
        val retry = env.server.takeRequest()
        assertEquals("Bearer new", retry.getHeader("Authorization"))
        assertEquals("new", env.tokens.accessToken()); assertEquals("newref", env.tokens.refreshToken())
    }

    @Test fun `renouvellement refuse demande de se reconnecter`() = withEnv { env ->
        env.reply(401, """{"error":"Invalid JWT"}""")
        env.reply(400, """{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""")
        val e = assertFailsWith<ApiException> { blocking { env.agent.offlineCatalogue() } }
        assertEquals(ApiErrorKind.UNAUTHORIZED, e.kind)
        assertEquals("Session expirée. Reconnectez-vous.", e.message)
    }

    @Test fun `sans jeton aucun appel n est envoye`() = withEnv { env ->
        env.tokens.clear()
        val e = assertFailsWith<ApiException> { blocking { env.agent.offlineCatalogue() } }
        assertEquals(ApiErrorKind.UNAUTHORIZED, e.kind)
        assertEquals(0, env.server.requestCount)
    }

    // ------------------------------------------------------------------ livraison
    @Test fun `demarrer une livraison renvoie l identifiant du serveur`() = withEnv { env ->
        env.data("\"d-123\"")
        assertEquals(ApiResult.Ok("d-123"), blocking { env.agent.startDelivery("app-1") })
        assertEquals(
            JsonParser.parseString("""{"action":"start_delivery","params":{"p_application":"app-1"}}"""),
            env.server.takeRequest().json(),
        )
    }

    @Test fun `dossier non approuve donne un message en francais`() = withEnv { env ->
        env.reply(400, """{"error":"only an approved application can be delivered"}""")
        assertEquals(ApiResult.Failure("Seul un dossier approuvé peut être livré."), blocking { env.agent.startDelivery("app-1") })
    }

    @Test fun `verification IMEI conforme et non conforme`() = withEnv { env ->
        env.data("""{"matched":true}""")
        env.data("""{"matched":false,"reason":"mismatch","attempts":1}""")
        assertEquals(ApiResult.Ok(ImeiCheck(true, null)), blocking { env.agent.verifyDeliveryImei("d-1", "490154203237518") })
        assertEquals(ApiResult.Ok(ImeiCheck(false, "mismatch")), blocking { env.agent.verifyDeliveryImei("d-1", "490154203237518") })
        assertEquals(
            JsonParser.parseString("""{"action":"verify_delivery_imei","params":{"p_delivery":"d-1","p_scanned_imei":"490154203237518"}}"""),
            env.server.takeRequest().json(),
        )
    }

    @Test fun `preuve de remise envoie les quatre champs du serveur`() = withEnv { env ->
        env.data("null")
        assertEquals(ApiResult.Ok(Unit), blocking { env.agent.recordHandoverProof("d-1", "d-1/p.jpg", "abc", "fp") })
        assertEquals(
            JsonParser.parseString(
                """{"action":"record_handover_proof","params":{"p_delivery":"d-1","p_photo_path":"d-1/p.jpg","p_signature_hash":"abc","p_signer_device":"fp"}}""",
            ),
            env.server.takeRequest().json(),
        )
    }

    @Test fun `rejouer une etape deja faite ne bloque pas l agent`() = withEnv { env ->
        env.reply(400, """{"error":"proof already recorded"}""")
        env.reply(400, """{"error":"device already enrolled"}""")
        assertEquals(ApiResult.Ok(Unit), blocking { env.agent.recordHandoverProof("d-1", "p", "h", "fp") })
        assertEquals(ApiResult.Ok(Unit), blocking { env.agent.requestMdmEnrollment("d-1") })
    }

    @Test fun `statut d enrolement lu dans la table delivery`() = withEnv { env ->
        env.ok("""[{"mdm_status":"token_requested"}]""")
        env.ok("""[{"mdm_status":"enrolled"}]""")
        env.ok("[]")
        assertEquals(ApiResult.Ok(MdmStatus.TOKEN_REQUESTED), blocking { env.agent.mdmStatus("d-1") })
        assertEquals("/rest/v1/delivery?id=eq.d-1&select=mdm_status", env.server.takeRequest().path)
        assertEquals(ApiResult.Ok(MdmStatus.ENROLLED), blocking { env.agent.mdmStatus("d-1") })
        assertEquals(ApiResult.Failure("Livraison introuvable."), blocking { env.agent.mdmStatus("d-1") })
    }

    @Test fun `cloture refusee tant que l enrolement n est pas confirme`() = withEnv { env ->
        env.reply(400, """{"error":"MDM enrolment is not confirmed by the server yet (status: token_requested)"}""")
        val r = blocking { env.agent.completeDelivery("d-1") }
        assertEquals(ApiResult.Failure("L'enrôlement du téléphone n'est pas encore confirmé. Réessayez dans un instant."), r)
    }

    @Test fun `panne du serveur et coupure reseau sont des messages et non des plantages`() = withEnv { env ->
        env.reply(500, """{"error":"internal error"}""")
        assertEquals(
            ApiResult.Failure("Le serveur a rencontré un problème. Réessayez dans un instant."),
            blocking { env.agent.completeDelivery("d-1") },
        )
        env.server.shutdown()
        val r = blocking { env.agent.completeDelivery("d-1") }
        assertTrue(r is ApiResult.Failure && r.message.startsWith("Pas de connexion"))
    }
}
