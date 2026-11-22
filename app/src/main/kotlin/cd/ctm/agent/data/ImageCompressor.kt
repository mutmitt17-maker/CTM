package cd.ctm.agent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Redimensionne et compresse les photos (pièce d'identité, selfie, remise) en JPEG <= 4,5 Mo
 * afin de respecter la limite serveur de 5 Mo sur Supabase Storage.
 */
object ImageCompressor {
    private const val MAX_EDGE_PX = 1600
    private const val TARGET_MAX_BYTES = 4_500_000

    fun compressUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sampleSize = 1
        var maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sampleSize > MAX_EDGE_PX * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return false

        val orientation = openStream(context, uri)?.use { input ->
            runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = rotateIfNeeded(decoded, orientation)
        val scaled = scaleDownIfNeeded(oriented)

        var quality = 85
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 15
        } while (bytes.size > TARGET_MAX_BYTES && quality >= 40)

        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()

        destFile.parentFile?.mkdirs()
        destFile.writeBytes(bytes)
        return bytes.size <= 5 * 1024 * 1024
    }

    private fun openStream(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    private fun scaleDownIfNeeded(bmp: Bitmap): Bitmap {
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide <= MAX_EDGE_PX) return bmp
        val ratio = MAX_EDGE_PX.toFloat() / maxSide.toFloat()
        val targetW = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
    }

    private fun rotateIfNeeded(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
}
