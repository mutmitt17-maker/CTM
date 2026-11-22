package com.ctm.credit.security

/**
 * Fournit la clé de chiffrement de la base de données locale (SQLCipher via Room).
 *
 * La clé est un tableau de 32 octets aléatoires générés une seule fois, puis protégés
 * par le KeyStore Android (AES-256-GCM, matériel si disponible).
 * Elle n'est jamais écrite en clair sur le disque ni transmise au serveur.
 *
 * Cycle de vie :
 *   - Premier lancement : génère la clé, la chiffre avec le KeyStore, stocke le blob chiffré.
 *   - Lancements suivants : charge le blob, déchiffre avec le KeyStore, renvoie la clé.
 *   - Réinitialisation (déconnexion définitive) : détruit la clé KeyStore et le blob.
 *
 * Sur Android < 23 (API level < 23) il n'y a pas de KeyStore matériel ; la clé est alors
 * protégée par le keystore logiciel — suffisant pour ce produit (DRC, Android 8+).
 *
 * DatabaseKey.Fake renvoie une clé fixe de 32 zéros pour les tests unitaires.
 */
interface DatabaseKey {
    /**
     * Renvoie la clé prête à passer à SQLiteDatabase.openOrCreateDatabase() ou
     * à SupportFactory de SQLCipher.
     * Lève [SecurityException] si le KeyStore est corrompu ou si l'appareil a été rooté
     * et que l'intégrité ne peut pas être vérifiée (Play Integrity API).
     */
    fun get(): ByteArray

    /** Efface définitivement la clé (réinitialisation ou changement d'appareil). */
    fun destroy()

    /** Implémentation factice pour les tests unitaires (ne touche pas le KeyStore). */
    object Fake : DatabaseKey {
        private val KEY = ByteArray(32) { 0 }
        override fun get(): ByteArray = KEY.copyOf()
        override fun destroy() = Unit
    }
}
