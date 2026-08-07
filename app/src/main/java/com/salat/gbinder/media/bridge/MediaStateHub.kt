package com.salat.gbinder.media.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.geely.lib.oneosapi.mediacenter.bean.Frequency
import com.geely.lib.oneosapi.mediacenter.bean.MediaData
import com.geely.lib.oneosapi.mediacenter.constant.MediaCenterConstant
import com.salat.gbinder.R
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Reduces Android MediaSession and OneOS callbacks into the single public snapshot. */
internal class MediaStateHub(
    private val context: Context,
    private val repository: MediaStateRepository,
    private val artworkRepository: ArtworkRepository,
) {
    private val latestArtworkToken = AtomicReference("")

    fun onBackendConnecting() {
        repository.update {
            it.copy(
                backendConnected = false,
                backendErrorCode = MediaBridgeContract.BackendError.CONNECTING,
                backendErrorMessage = "OneOS MediaCenter connecting",
            )
        }
    }

    fun onBackendConnected(
        audioSource: MediaCenterConstant.AudioSource,
        appSource: MediaCenterConstant.AppSource,
        availability: Map<BridgeAudioSource, Pair<Boolean, Boolean>>,
    ) {
        val selected = audioSource.toBridgeSource()
        repository.update { before ->
            val sourceChanged = before.audioSource != selected.name
            before.copy(
                backendConnected = true,
                backendErrorCode = MediaBridgeContract.BackendError.NONE,
                backendErrorMessage = "",
                audioSource = selected.name,
                appSource = appSource.name,
                ownerPackage = if (sourceChanged) ownerPackageFor(selected) else before.ownerPackage,
                ownerApp = if (sourceChanged) ownerLabelFor(selected) else before.ownerApp,
                capabilities = if (sourceChanged) selected.defaultCapabilities() else before.capabilities,
                sources = before.sources.map { source ->
                    val state = availability[runCatching {
                        BridgeAudioSource.valueOf(source.id)
                    }.getOrDefault(BridgeAudioSource.UNKNOWN)]
                    source.copy(
                        connected = state?.first ?: source.connected,
                        available = state?.second ?: source.available,
                        selected = source.id == selected.name,
                    )
                },
            )
        }
    }

    fun onBackendDisconnected(message: String) {
        latestArtworkToken.set("")
        repository.update {
            it.copy(
                backendConnected = false,
                backendErrorCode = MediaBridgeContract.BackendError.ONE_OS_DISCONNECTED,
                backendErrorMessage = message,
                sources = it.sources.map { source ->
                    source.copy(connected = false, available = false, selected = false)
                },
                ownerPackage = "",
                ownerApp = "",
                mediaId = "",
                title = "",
                artist = "",
                album = "",
                duration = -1L,
                position = -1L,
                updateElapsedRealtime = 0L,
                speed = 0f,
                playbackState = PlaybackState.STATE_NONE,
                playbackErrorCode = 0,
                playbackErrorMessage = "",
                playbackActions = 0L,
                capabilities = MediaCapabilities.SET_SOURCE,
                artworkUri = "",
                artworkRevision = if (it.artworkUri.isNotBlank()) {
                    it.artworkRevision + 1L
                } else it.artworkRevision,
            )
        }
    }

    fun onSourceChanged(
        audioSource: MediaCenterConstant.AudioSource,
        appSource: MediaCenterConstant.AppSource,
    ) {
        val selected = audioSource.toBridgeSource()
        repository.update { before ->
            val sourceChanged = before.audioSource != selected.name
            before.copy(
                audioSource = selected.name,
                appSource = appSource.name,
                sources = before.sources.map { source ->
                    source.copy(selected = source.id == selected.name)
                },
                ownerPackage = if (sourceChanged) ownerPackageFor(selected) else before.ownerPackage,
                ownerApp = if (sourceChanged) ownerLabelFor(selected) else before.ownerApp,
                mediaId = if (sourceChanged) "" else before.mediaId,
                title = if (sourceChanged) "" else before.title,
                artist = if (sourceChanged) "" else before.artist,
                album = if (sourceChanged) "" else before.album,
                duration = if (sourceChanged) -1L else before.duration,
                position = if (sourceChanged) -1L else before.position,
                updateElapsedRealtime = if (sourceChanged) 0L else before.updateElapsedRealtime,
                speed = if (sourceChanged) 0f else before.speed,
                playbackState = if (sourceChanged) PlaybackState.STATE_NONE else before.playbackState,
                playbackErrorCode = if (sourceChanged) 0 else before.playbackErrorCode,
                playbackErrorMessage = if (sourceChanged) "" else before.playbackErrorMessage,
                playbackActions = if (sourceChanged) 0L else before.playbackActions,
                capabilities = if (sourceChanged) selected.defaultCapabilities() else before.capabilities,
                artworkUri = if (sourceChanged) "" else before.artworkUri,
                artworkRevision = if (sourceChanged && before.artworkUri.isNotBlank()) {
                    before.artworkRevision + 1L
                } else before.artworkRevision,
            )
        }
        if (repository.snapshot().artworkUri.isBlank()) latestArtworkToken.set("")
    }

    fun onSourceAvailability(
        source: MediaCenterConstant.AudioSource,
        connected: Boolean,
        available: Boolean,
    ) {
        val bridgeSource = source.toBridgeSource().name
        repository.update {
            it.copy(
                sources = it.sources.map { item ->
                    if (item.id == bridgeSource) {
                        item.copy(connected = connected, available = available)
                    } else item
                },
            )
        }
    }

    fun onOneOsError(source: MediaCenterConstant.AudioSource, message: String) {
        repository.update {
            it.copy(
                backendErrorCode = MediaBridgeContract.BackendError.ONE_OS_ERROR,
                backendErrorMessage = "${source.toBridgeSource().name}: $message",
            )
        }
    }

    fun onMediaController(controller: MediaController?) {
        if (controller == null) {
            if (repository.snapshot().audioSource == BridgeAudioSource.ONLINE.name) {
                clearPlayback()
            }
            return
        }
        val bridgeState = repository.snapshot()
        if (bridgeState.backendConnected && bridgeState.audioSource !in setOf(
                BridgeAudioSource.ONLINE.name,
                BridgeAudioSource.UNKNOWN.name,
                BridgeAudioSource.OTHER.name,
            )
        ) return

        val metadata = controller.metadata
        val state = controller.playbackState
        val ownerPackage = controller.packageName.orEmpty()
        val title = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            .ifBlank { metadata.text(MediaMetadata.METADATA_KEY_TITLE) }
        val artist = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            .ifBlank { metadata.text(MediaMetadata.METADATA_KEY_ARTIST) }
        val mediaId = metadata.text(MediaMetadata.METADATA_KEY_MEDIA_ID)
            .ifBlank { stableMediaId(title, artist) }
        val actions = state?.actions ?: 0L

        repository.update { before ->
            val sameMedia = before.mediaId == mediaId && before.ownerPackage == ownerPackage
            before.copy(
                ownerPackage = ownerPackage,
                ownerApp = appLabel(ownerPackage),
                mediaId = mediaId,
                title = title,
                artist = artist,
                album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM),
                duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    ?.takeIf { value -> value > 0L } ?: -1L,
                position = state?.position ?: -1L,
                updateElapsedRealtime = state?.lastPositionUpdateTime
                    ?.takeIf { value -> value > 0L }
                    ?: android.os.SystemClock.elapsedRealtime(),
                speed = state?.playbackSpeed ?: 0f,
                playbackState = state?.state ?: PlaybackState.STATE_NONE,
                // Framework PlaybackState exposes an error message but no numeric error code.
                playbackErrorCode = 0,
                playbackErrorMessage = state?.errorMessage?.toString().orEmpty(),
                playbackActions = actions,
                capabilities = MediaCapabilities.fromPlaybackActions(actions),
                artworkUri = if (sameMedia) {
                    before.artworkUri
                } else "",
                artworkRevision = if (!sameMedia && before.artworkUri.isNotBlank()) {
                    before.artworkRevision + 1L
                } else before.artworkRevision,
            )
        }

        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val sourceUri = metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .ifBlank { metadata.text(MediaMetadata.METADATA_KEY_ART_URI) }
            .ifBlank { metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) }
        normalizeArtwork(ownerPackage, mediaId, bitmap, sourceUri)
    }

    fun onOneOsMediaData(source: MediaCenterConstant.AudioSource, data: MediaData?) {
        if (data == null || repository.snapshot().audioSource != source.toBridgeSource().name) return
        val ownerPackage = nativeOwnerPackage(source)
        val mediaId = data.id?.takeIf(String::isNotBlank)
            ?: stableMediaId(data.name.orEmpty(), data.artist.orEmpty())
        repository.update { before ->
            val sameMedia = before.mediaId == mediaId && before.ownerPackage == ownerPackage
            before.copy(
                ownerPackage = ownerPackage,
                ownerApp = nativeOwnerLabel(source),
                mediaId = mediaId,
                title = data.name.orEmpty(),
                artist = data.artist.orEmpty(),
                album = data.albumName.orEmpty(),
                duration = data.duration.takeIf { value -> value > 0L } ?: -1L,
                capabilities = source.toBridgeSource().defaultCapabilities(),
                artworkUri = if (sameMedia) {
                    before.artworkUri
                } else "",
                artworkRevision = if (!sameMedia && before.artworkUri.isNotBlank()) {
                    before.artworkRevision + 1L
                } else before.artworkRevision,
            )
        }
        normalizeArtwork(ownerPackage, mediaId, data.albumCover, data.albumCoverUri.orEmpty())
    }

    fun onOneOsPlayState(
        source: MediaCenterConstant.AudioSource,
        state: MediaCenterConstant.PlayState,
    ) {
        if (repository.snapshot().audioSource != source.toBridgeSource().name) return
        val androidState = when (state) {
            MediaCenterConstant.PlayState.MUSIC_STATE_PLAY -> PlaybackState.STATE_PLAYING
            MediaCenterConstant.PlayState.MUSIC_STATE_PAUSE -> PlaybackState.STATE_PAUSED
            MediaCenterConstant.PlayState.MUSIC_STATE_STOP -> PlaybackState.STATE_STOPPED
        }
        repository.update {
            it.copy(
                playbackState = androidState,
                playbackErrorCode = 0,
                playbackErrorMessage = "",
                speed = if (androidState == PlaybackState.STATE_PLAYING) 1f else 0f,
                updateElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
                capabilities = source.toBridgeSource().defaultCapabilities(),
            )
        }
    }

    fun onOneOsProgress(
        source: MediaCenterConstant.AudioSource,
        position: Long,
        duration: Long,
    ) {
        if (repository.snapshot().audioSource != source.toBridgeSource().name) return
        val speed = if (repository.snapshot().playbackState == PlaybackState.STATE_PLAYING) 1f else 0f
        repository.updateProgress(position, duration, speed)
    }

    fun onOneOsRadioState(frequency: Frequency?, playing: Boolean) {
        val station = frequency?.let {
            radioMetadata(
                frequency = it.frequency,
                band = it.band,
                serviceName = it.serviceName.orEmpty(),
                ensembleName = it.ensembleName.orEmpty(),
                fallbackTitle = context.getString(R.string.audio_source_radio),
            )
        }
        val updated = repository.update {
            it.withRadioState(
                station = station,
                playing = playing,
                elapsedRealtime = android.os.SystemClock.elapsedRealtime(),
            )
        }
        if (station != null && updated.backendConnected &&
            updated.audioSource == BridgeAudioSource.RADIO.name
        ) {
            latestArtworkToken.set("")
        }
    }

    private fun clearPlayback() {
        latestArtworkToken.set("")
        repository.update { before ->
            before.copy(
                ownerPackage = "",
                ownerApp = "",
                mediaId = "",
                title = "",
                artist = "",
                album = "",
                duration = -1L,
                position = -1L,
                updateElapsedRealtime = 0L,
                speed = 0f,
                playbackState = PlaybackState.STATE_NONE,
                playbackErrorCode = 0,
                playbackErrorMessage = "",
                playbackActions = 0L,
                capabilities = MediaCapabilities.SET_SOURCE,
                artworkUri = "",
                artworkRevision = if (before.artworkUri.isNotBlank()) {
                    before.artworkRevision + 1L
                } else before.artworkRevision,
            )
        }
    }

    private fun normalizeArtwork(
        ownerPackage: String,
        mediaId: String,
        bitmap: android.graphics.Bitmap?,
        sourceUri: String,
    ) {
        val input = ArtworkInput("$ownerPackage|$mediaId", bitmap, sourceUri)
        val token = artworkRepository.token(input)
        latestArtworkToken.set(token)
        artworkRepository.normalize(input, token) { normalized ->
            if (latestArtworkToken.get() != normalized.token) return@normalize
            val snapshot = repository.snapshot()
            if (snapshot.ownerPackage != ownerPackage || snapshot.mediaId != mediaId) return@normalize
            repository.update {
                val uri = normalized.uri
                if (it.artworkUri == uri) it else it.copy(
                    artworkUri = uri,
                    artworkRevision = it.artworkRevision + 1L,
                )
            }
        }
    }

    private fun appLabel(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    private fun stableMediaId(title: String, artist: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest("$title\u0000$artist".toByteArray())
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun MediaMetadata?.text(key: String): String = this?.getString(key).orEmpty()

    private fun nativeOwnerPackage(source: MediaCenterConstant.AudioSource): String = when (source) {
        MediaCenterConstant.AudioSource.AUDIO_SOURCE_BT -> "com.android.bluetooth"
        MediaCenterConstant.AudioSource.AUDIO_SOURCE_USB -> "com.geely.usbservice"
        MediaCenterConstant.AudioSource.AUDIO_SOURCE_RADIO -> "com.geely.radio.service"
        MediaCenterConstant.AudioSource.AUDIO_SOURCE_CPAA -> "com.autolink.carplay.app"
        else -> "com.geely.mediacenterservice"
    }

    private fun nativeOwnerLabel(source: MediaCenterConstant.AudioSource): String =
        source.toBridgeSource().name

    private fun ownerPackageFor(source: BridgeAudioSource): String = when (source) {
        BridgeAudioSource.BT -> "com.android.bluetooth"
        BridgeAudioSource.USB -> "com.geely.usbservice"
        BridgeAudioSource.RADIO -> "com.geely.radio.service"
        BridgeAudioSource.CPAA -> "com.autolink.carplay.app"
        BridgeAudioSource.YUNTING -> "com.geely.mediacenterservice"
        BridgeAudioSource.ONLINE,
        BridgeAudioSource.OTHER,
        BridgeAudioSource.UNKNOWN -> ""
    }

    private fun ownerLabelFor(source: BridgeAudioSource): String =
        if (ownerPackageFor(source).isBlank()) "" else source.name
}

internal fun MediaCenterConstant.AudioSource.toBridgeSource(): BridgeAudioSource = when (this) {
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_USB -> BridgeAudioSource.USB
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_BT -> BridgeAudioSource.BT
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_RADIO -> BridgeAudioSource.RADIO
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_ONLINE -> BridgeAudioSource.ONLINE
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_OTHER -> BridgeAudioSource.OTHER
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_YUNTING -> BridgeAudioSource.YUNTING
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_CPAA -> BridgeAudioSource.CPAA
    MediaCenterConstant.AudioSource.AUDIO_SOURCE_UNKNOWN -> BridgeAudioSource.UNKNOWN
}

internal fun BridgeAudioSource.defaultCapabilities(): Long =
    defaultMediaSources().first { it.id == name }.capabilities
