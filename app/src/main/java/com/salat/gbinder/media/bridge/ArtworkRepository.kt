package com.salat.gbinder.media.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

internal data class ArtworkInput(
    val bitmap: Bitmap? = null,
    val sourceUri: String = "",
)

internal data class NormalizedArtwork(
    val token: String,
    val uri: String,
)

/** Normalizes OEM/session artwork into a small private cache exposed only by per-client URI grants. */
internal class ArtworkRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MAX_EDGE_PX = 512
        private const val JPEG_QUALITY = 88
        private const val MAX_CACHE_FILES = 48
    }

    private val cacheDirectory = File(context.cacheDir, "media_artwork")
    fun normalize(
        input: ArtworkInput,
        onResult: (NormalizedArtwork) -> Unit,
    ) {
        if (input.bitmap == null && input.sourceUri.isBlank()) {
            onResult(NormalizedArtwork("", ""))
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                cacheDirectory.mkdirs()
                val decoded = input.bitmap ?: decodeUri(input.sourceUri)
                decoded ?: return@runCatching null
                var prepared: Bitmap? = null
                try {
                    prepared = prepare(decoded)
                    val token = ArtworkContentIdentity.token(prepared)
                    val target = File(cacheDirectory, "$token.jpg")
                    if (!target.isFile || target.length() <= 0L) {
                        writeNormalized(prepared, target)
                    }
                    pruneCache(target)
                    NormalizedArtwork(token, uriFor(target).toString())
                } finally {
                    if (prepared != null && prepared !== decoded) prepared.recycle()
                    if (input.bitmap == null) decoded.recycle()
                }
            }.onFailure(Timber::e).getOrNull()
            onResult(result ?: NormalizedArtwork("", ""))
        }
    }

    private fun decodeUri(value: String): Bitmap? {
        val uri = runCatching { value.toUri() }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE_PX * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun prepare(source: Bitmap): Bitmap {
        val largest = max(source.width, source.height)
        val scaled = if (largest > MAX_EDGE_PX) {
            val scale = MAX_EDGE_PX.toFloat() / largest.toFloat()
            source.scale(
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
            )
        } else source
        if (scaled.config != Bitmap.Config.HARDWARE) return scaled
        return checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun writeNormalized(source: Bitmap, target: File) {
        val temporary = File.createTempFile(
            "${target.nameWithoutExtension}-",
            ".tmp",
            target.parentFile,
        )
        try {
            FileOutputStream(temporary).use { stream ->
                check(source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                check(target.isFile && target.length() > 0L) {
                    "Unable to publish artwork cache file"
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private fun pruneCache(keep: File) {
        cacheDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it != keep && !it.name.endsWith(".tmp") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHE_FILES - 1)
            .forEach(File::delete)
    }
}

/** Canonical pixel identity, independent of Bitmap allocation and generationId. */
internal object ArtworkContentIdentity {
    fun token(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width)
        return token(bitmap.width, bitmap.height) { row ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
            pixels
        }
    }

    fun token(width: Int, height: Int, rowPixels: (Int) -> IntArray): String {
        require(width > 0 && height > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(width)
        digest.updateInt(height)
        val bytes = ByteArray(width * Int.SIZE_BYTES)
        repeat(height) { row ->
            val pixels = rowPixels(row)
            require(pixels.size == width)
            pixels.forEachIndexed { index, pixel ->
                val offset = index * Int.SIZE_BYTES
                bytes[offset] = (pixel ushr 24).toByte()
                bytes[offset + 1] = (pixel ushr 16).toByte()
                bytes[offset + 2] = (pixel ushr 8).toByte()
                bytes[offset + 3] = pixel.toByte()
            }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update((value ushr 24).toByte())
        update((value ushr 16).toByte())
        update((value ushr 8).toByte())
        update(value.toByte())
    }
}
