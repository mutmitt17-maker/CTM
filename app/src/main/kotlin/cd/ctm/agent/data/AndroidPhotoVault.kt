package cd.ctm.agent.data

import android.content.Context
import com.ctm.credit.security.PhotoVault
import java.io.File

/**
 * Stockage privé local des photos KYC et de remise avant envoi vers Supabase Storage.
 * Préserve les chemins relatifs `<bucket>/<entity_id>/<nom>.jpg` à l'intérieur de `filesDir/photo_vault`.
 */
class AndroidPhotoVault(context: Context) : PhotoVault {
    private val root = File(context.filesDir, "photo_vault").apply { mkdirs() }

    private fun resolveSafe(name: String): File {
        val cleaned = name.trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        require(cleaned.isNotBlank()) { "Nom de fichier photo invalide." }
        return File(root, cleaned)
    }

    override fun create(name: String): File {
        val file = resolveSafe(name)
        file.parentFile?.mkdirs()
        return file
    }

    override fun delete(name: String) {
        val file = runCatching { resolveSafe(name) }.getOrNull() ?: return
        file.delete()
        var parent = file.parentFile
        while (parent != null && parent != root) {
            if (parent.listFiles()?.isEmpty() == true) {
                parent.delete()
                parent = parent.parentFile
            } else {
                break
            }
        }
    }

    fun fileFor(name: String): File? = runCatching { resolveSafe(name) }.getOrNull()?.takeIf { it.isFile }

    fun readBytes(name: String): ByteArray? = fileFor(name)?.readBytes()

    override fun pending(): List<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
}
