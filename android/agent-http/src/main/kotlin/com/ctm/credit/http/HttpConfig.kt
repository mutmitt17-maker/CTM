package com.ctm.credit.http

/**
 * Adresse du projet Supabase et clé publique (anon). Ces deux valeurs ne sont pas secrètes :
 * ce sont celles du fichier web/config.js.
 */
data class HttpConfig(val supabaseUrl: String, val anonKey: String) {
    private val base get() = supabaseUrl.trimEnd('/')
    val authUrl get() = "$base/auth/v1"
    val restUrl get() = "$base/rest/v1"
    val apiUrl get() = "$base/functions/v1/api"
}
