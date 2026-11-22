package com.ctm.credit.security

/**
 * Stockage sécurisé des jetons de session.
 *
 * Sur Android, l'implémentation utilise EncryptedSharedPreferences (Jetpack Security).
 * En test, FakeTokenStore garde les valeurs en mémoire.
 *
 * Les jetons ne transitent jamais dans les logs ni dans le SyncStore.
 */
interface TokenStore {
    /** Jeton d'accès Supabase (courte durée). Null si l'agent n'est pas connecté. */
    fun accessToken(): String?

    /** Jeton de rafraîchissement Supabase (longue durée). Null si l'agent n'est pas connecté. */
    fun refreshToken(): String?

    /** Enregistre les deux jetons après une connexion ou un rafraîchissement. */
    fun save(accessToken: String, refreshToken: String)

    /** Efface les deux jetons (déconnexion ou révocation). */
    fun clear()

    /** Vrai si un jeton d'accès est présent (ne vérifie pas l'expiration). */
    fun isLoggedIn(): Boolean = accessToken() != null
}
