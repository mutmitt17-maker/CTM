package com.ctm.credit.security

import java.io.File

/**
 * Stockage temporaire chiffré des photos prises pour le KYC et les preuves de livraison.
 *
 * Les photos ne vivent sur le téléphone que le temps de l'envoi vers Supabase Storage.
 * Dès l'envoi confirmé, [delete] est appelé et le fichier est effacé.
 *
 * L'implémentation Android écrit dans le répertoire privé de l'application
 * (Context.filesDir) ; les fichiers ne sont jamais accessibles par d'autres applications.
 * Sur Android 10+ le répertoire est lui-même chiffré par le système (FBE).
 *
 * Nommage des fichiers dans le bucket (chemin transmis au serveur) :
 *   - KYC       : "<customer_id>/<uuid>.jpg"
 *   - Livraison : "<delivery_id>/<uuid>.jpg"
 *
 * PhotoVault.Fake utilise un répertoire temporaire de la JVM et convient aux tests.
 */
interface PhotoVault {
    /**
     * Crée un fichier vide dans le vault et renvoie son [File].
     * L'appelant écrit l'image dedans (CameraX, BitmapFactory, etc.).
     * [name] est le nom relatif dans le bucket (ex. "abc123/selfie.jpg") — utilisé comme nom local.
     */
    fun create(name: String): File

    /**
     * Efface le fichier après envoi confirmé.
     * Silencieux si le fichier n'existe plus.
     */
    fun delete(name: String)

    /** Liste les fichiers présents (pour reprendre les envois interrompus au démarrage). */
    fun pending(): List<String>

    /** Implémentation factice pour les tests unitaires. */
    class Fake(private val dir: File = createTempDir()) : PhotoVault {
        private val files = mutableMapOf<String, File>()

        override fun create(name: String): File {
            val f = File(dir, name.replace('/', '_'))
            f.parentFile?.mkdirs()
            files[name] = f
            return f
        }

        override fun delete(name: String) {
            files.remove(name)?.delete()
        }

        override fun pending(): List<String> = files.keys.filter { files[it]?.exists() == true }
    }
}

@Suppress("DEPRECATION")
private fun createTempDir(): File = kotlin.io.createTempDir("photo-vault-test")
