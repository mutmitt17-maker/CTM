package com.ctm.credit.http

import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException
import com.ctm.credit.network.ServerResult
import com.ctm.credit.network.SyncApi
import com.ctm.credit.network.SyncItem
import com.google.gson.JsonObject

/**
 * Envoi des actions en file au serveur (`sync_batch`). Bloquant : le SyncEngine l'appelle depuis un fil de fond.
 * Lève [ApiException] quand la requête n'a pas donné de réponse utilisable (réseau, 401, 5xx) ; le SyncEngine
 * décide alors de réessayer plus tard ou de redemander la connexion.
 */
class HttpSyncApi(private val http: SupabaseHttp) : SyncApi {
    override fun syncBatch(items: List<SyncItem>): List<ServerResult> {
        val params = JsonObject().apply { add("p_items", Mappers.syncItems(items)) }
        val data = http.call("sync_batch", params)
        if (data == null || !data.isJsonArray) {
            throw ApiException(ApiErrorKind.SERVER, "Réponse de synchronisation inattendue.")
        }
        return Mappers.syncResults(data.asJsonArray)
    }
}
