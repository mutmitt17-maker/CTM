package com.ctm.credit.security

/**
 * Empreinte stable de l'appareil.
 *
 * Utilisée pour détecter un changement d'appareil entre deux sessions
 * et comme facteur supplémentaire lors de l'authentification (BACKEND_GAPS #5).
 *
 * Sur Android, l'implémentation calcule un hash SHA-256 de :
 *   ANDROID_ID + modèle de l'appareil + identifiant d'installation (UUID persistant).
 *
 * La valeur est mise en cache en mémoire ; elle ne change pas pendant la durée de vie
 * de l'application. Elle est stockée dans EncryptedSharedPreferences au premier calcul
 * pour rester stable même si ANDROID_ID change (réinitialisation usine exclue).
 *
 * En test, FakeDeviceFingerprint renvoie une constante.
 */
interface DeviceFingerprint {
    /**
     * Renvoie l'empreinte sous forme de chaîne hexadécimale de 64 caractères (SHA-256).
     * Ne lève jamais d'exception : en cas d'erreur, renvoie un UUID aléatoire et enregistre
     * l'erreur dans les logs de l'application (non remontée au serveur).
     */
    fun get(): String
}
