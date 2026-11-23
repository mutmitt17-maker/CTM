package com.ctm.credit.http

import com.ctm.credit.network.ApiErrorClassifier
import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException
import com.ctm.credit.security.DeviceFingerprint
import com.ctm.credit.security.TokenStore
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Appels HTTP bloquants vers Supabase. À lancer hors du fil principal (voir HttpAgentApi).
 *
 * - connexion : `POST /auth/v1/token?grant_type=password` ;
 * - actions : `POST /functions/v1/api` avec `{action, params}` ;
 * - lectures : `GET /rest/v1/...` (la sécurité par lignes ne renvoie que les données de l'agent).
 *
 * Si le serveur répond 401, le jeton est renouvelé une fois avec le jeton de rafraîchissement, puis l'appel est rejoué.
 */
class SupabaseHttp private constructor(
    private val config: HttpConfig,
    private val tokens: TokenStore,
    private val device: DeviceFingerprint,
    private val client: OkHttpClient,
) {
    constructor(
        config: HttpConfig,
        tokens: TokenStore,
        device: DeviceFingerprint,
    ) : this(config, tokens, device, defaultClient())

    private val json = "application/json".toMediaType()
    private val refreshLock = Any()

    // ------------------------------------------------------------------ connexion
    /** Renvoie la réponse de Supabase (access_token, refresh_token, user…). Lève ApiException. */
    fun passwordLogin(email: String, password: String): JsonObject {
        val body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        val request = Request.Builder()
            .url("${config.authUrl}/token?grant_type=password")
            .header("apikey", config.anonKey)
            .post(body.toString().toRequestBody(json))
            .build()
        return execute(request).asJsonObjectOrFail()
    }

    /** Adresse e-mail du compte connecté (sert de nom affiché : la base ne stocke pas de nom). */
    fun userEmail(): String? {
        val obj = get("${config.authUrl}/user").asJsonObjectOrNull() ?: return null
        return obj.stringOrNull("email")
    }

    /** Envoie un e-mail de réinitialisation de mot de passe via Supabase Auth. */
    fun resetPasswordForEmail(email: String) {
        val body = JsonObject().apply { addProperty("email", email) }
        val request = Request.Builder()
            .url("${config.authUrl}/recover")
            .header("apikey", config.anonKey)
            .post(body.toString().toRequestBody(json))
            .build()
        execute(request)
    }

    // ------------------------------------------------------------------ API
    /** Appelle une action de l'Edge Function `api` et renvoie son champ `data` (peut être null). */
    fun call(action: String, params: JsonObject = JsonObject()): JsonElement? {
        val payload = JsonObject().apply { addProperty("action", action); add("params", params) }
        val response = authorized { token ->
            Request.Builder()
                .url(config.apiUrl)
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer $token")
                .header("x-device-fingerprint", device.get())
                .post(payload.toString().toRequestBody(json))
                .build()
        }
        val obj = response.asJsonObjectOrNull() ?: return null
        val data = obj.get("data")
        return if (data == null || data.isJsonNull) null else data
    }

    /** Lecture d'une table via REST, par exemple `delivery?id=eq.<id>&select=mdm_status`. */
    fun rest(pathAndQuery: String): JsonArray {
        val element = get("${config.restUrl}/$pathAndQuery")
        return if (element != null && element.isJsonArray) element.asJsonArray else JsonArray()
    }

    /**
     * Envoie une photo (JPEG ou PNG, 5 Mo max) dans un bucket privé Supabase Storage.
     * L'agent n'a que le droit d'ajout (INSERT, jamais d'écrasement).
     * Un code 409 indique que le fichier unique existe déjà côté serveur : traité comme déjà envoyé.
     */
    fun uploadStorageObject(
        bucket: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String = "image/jpeg",
    ) {
        if (bytes.size > MAX_PHOTO_BYTES) {
            throw ApiException(ApiErrorKind.REJECTED, "La photo dépasse la taille maximale de 5 Mo.")
        }
        val cleanPath = objectPath.trimStart('/')
        try {
            authorized { token ->
                Request.Builder()
                    .url("${config.storageUrl}/object/$bucket/$cleanPath")
                    .header("apikey", config.anonKey)
                    .header("Authorization", "Bearer $token")
                    .post(bytes.toRequestBody(contentType.toMediaType()))
                    .build()
            }
        } catch (e: ApiException) {
            if (e.kind == ApiErrorKind.CONFLICT) return
            throw e
        }
    }

    // ------------------------------------------------------------------ interne
    private fun get(url: String): JsonElement? = authorized { token ->
        Request.Builder().url(url).header("apikey", config.anonKey).header("Authorization", "Bearer $token").get().build()
    }

    /** Envoie la requête avec le jeton ; sur 401, renouvelle le jeton une fois et rejoue. */
    private fun authorized(build: (token: String) -> Request): JsonElement? {
        val first = tokens.accessToken() ?: throw ApiException(ApiErrorKind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.")
        try {
            return execute(build(first))
        } catch (e: ApiException) {
            if (e.kind != ApiErrorKind.UNAUTHORIZED) throw e
        }
        val fresh = refreshAccessToken(first)
        return execute(build(fresh))
    }

    /** Renouvelle le jeton. Un seul renouvellement à la fois ; si un autre appel l'a déjà fait, on reprend son jeton. */
    private fun refreshAccessToken(staleToken: String): String = synchronized(refreshLock) {
        val current = tokens.accessToken()
        if (current != null && current != staleToken) return current
        val refresh = tokens.refreshToken() ?: throw ApiException(ApiErrorKind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.")
        val body = JsonObject().apply { addProperty("refresh_token", refresh) }
        val request = Request.Builder()
            .url("${config.authUrl}/token?grant_type=refresh_token")
            .header("apikey", config.anonKey)
            .post(body.toString().toRequestBody(json))
            .build()
        val obj = try {
            execute(request).asJsonObjectOrFail()
        } catch (e: ApiException) {
            if (e.kind == ApiErrorKind.NETWORK || e.kind == ApiErrorKind.SERVER) throw e
            throw ApiException(ApiErrorKind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.", e)
        }
        val access = obj.stringOrNull("access_token")
        val newRefresh = obj.stringOrNull("refresh_token")
        if (access == null || newRefresh == null) throw ApiException(ApiErrorKind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.")
        tokens.save(access, newRefresh)
        access
    }

    /** Exécute la requête. Renvoie le JSON de la réponse ; lève ApiException si HTTP non 2xx ou réseau coupé. */
    private fun execute(request: Request): JsonElement? {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, "Pas de connexion au serveur.", e)
        }
        val raw = response.use { r ->
            val text = try { r.body?.string().orEmpty() } catch (e: IOException) {
                throw ApiException(ApiErrorKind.NETWORK, "Réponse du serveur interrompue.", e)
            }
            val parsed = if (text.isBlank()) null else try { JsonParser.parseString(text) } catch (_: Exception) { null }
            RawResponse(r.code, r.isSuccessful, parsed)
        }
        if (raw.ok) return raw.parsed
        val obj = raw.parsed.asJsonObjectOrNull()
        val code = obj?.stringOrNull("code") ?: obj?.stringOrNull("error_code")
        val message = obj?.stringOrNull("error") ?: obj?.stringOrNull("msg")
            ?: obj?.stringOrNull("error_description") ?: "Erreur ${raw.status}"
        throw ApiException(ApiErrorClassifier.classify(raw.status, code), message)
    }

    private class RawResponse(val status: Int, val ok: Boolean, val parsed: JsonElement?)

    companion object {
        const val MAX_PHOTO_BYTES = 5 * 1024 * 1024

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal fun JsonElement?.asJsonObjectOrNull(): JsonObject? = if (this != null && this.isJsonObject) this.asJsonObject else null

internal fun JsonElement?.asJsonObjectOrFail(): JsonObject =
    this.asJsonObjectOrNull() ?: throw ApiException(ApiErrorKind.SERVER, "Réponse inattendue du serveur.")

internal fun JsonObject.stringOrNull(name: String): String? {
    val e = get(name)
    return if (e == null || e.isJsonNull || !e.isJsonPrimitive) null else e.asString
}
