package cd.ctm.agent.data

import android.content.Context
import com.ctm.credit.security.PhotoVault
import java.io.File

/** Stockage privé local des photos KYC et de remise avant envoi. */
class AndroidPhotoVault(context: Context) : PhotoVault {
    private val root = File(context.filesDir, "photo_vault").apply { mkdirs() }

    override fun create(name: String): File {
        val safeName = name.replace("..", "_").replace('/', '_')
        val file = File(root, safeName)
        file.parentFile?.mkdirs()
        return file
    }

    override fun delete(name: String) {
        val safeName = name.replace("..", "_").replace('/', '_')
        File(root, safeName).delete()
    }

    override fun pending(): List<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
}
