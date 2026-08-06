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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal data class ArtworkInput(
    val mediaKey: String,
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
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun token(input: ArtworkInput): String {
        val bitmapIdentity = input.bitmap?.let { bitmap ->
            "${bitmap.width}x${bitmap.height}:${bitmap.generationId}"
        }.orEmpty()
        return sha256("${input.mediaKey}|${input.sourceUri}|$bitmapIdentity")
    }

    fun normalize(
        input: ArtworkInput,
        token: String = token(input),
        onResult: (NormalizedArtwork) -> Unit,
    ) {
        if (input.bitmap == null && input.sourceUri.isBlank()) {
            onResult(NormalizedArtwork(token, ""))
            return
        }

        val target = File(cacheDirectory, "$token.jpg")
        if (target.isFile && target.length() > 0L) {
            onResult(NormalizedArtwork(token, uriFor(target).toString()))
            return
        }
        if (inFlight.putIfAbsent(token, true) != null) return

        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                cacheDirectory.mkdirs()
                val decoded = input.bitmap ?: decodeUri(input.sourceUri)
                decoded ?: return@runCatching null
                writeNormalized(decoded, target, recycleSource = input.bitmap == null)
                pruneCache(target)
                NormalizedArtwork(token, uriFor(target).toString())
            }.onFailure(Timber::e).getOrNull()
            inFlight.remove(token)
            onResult(result ?: NormalizedArtwork(token, ""))
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

    private fun writeNormalized(source: Bitmap, target: File, recycleSource: Boolean) {
        val largest = max(source.width, source.height)
        val normalized = if (largest > MAX_EDGE_PX) {
            val scale = MAX_EDGE_PX.toFloat() / largest.toFloat()
            source.scale(
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
            )
        } else source

        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                check(normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream))
                stream.fd.sync()
            }
            check(temporary.renameTo(target)) { "Unable to publish artwork cache file" }
        } finally {
            temporary.delete()
            if (normalized !== source) normalized.recycle()
            if (recycleSource) source.recycle()
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
