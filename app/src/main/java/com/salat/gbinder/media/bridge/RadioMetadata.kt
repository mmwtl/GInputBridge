package com.salat.gbinder.media.bridge

import android.media.session.PlaybackState

internal data class RadioMetadata(
    val mediaId: String,
    val title: String,
    val subtitle: String,
)

internal fun radioMetadata(
    frequency: Int,
    band: Int,
    serviceName: String,
    ensembleName: String,
    fallbackTitle: String,
): RadioMetadata {
    val formattedFrequency = formatRadioFrequency(frequency)
    val title = serviceName.trim().ifBlank {
        formattedFrequency.ifBlank { fallbackTitle }
    }
    val subtitle = ensembleName.trim().ifBlank {
        if (title == formattedFrequency) fallbackTitle else formattedFrequency
    }
    return RadioMetadata(
        mediaId = "radio:$band:$frequency:${serviceName.trim()}",
        title = title,
        subtitle = subtitle,
    )
}

internal fun formatRadioFrequency(frequency: Int): String = when (frequency) {
    in 174_000..240_000 -> "${formatScaled(frequency, 1_000)} MHz"
    in 87_500..108_000 -> "${formatScaled(frequency, 1_000)} MHz"
    in 8_750..10_800 -> "${formatScaled(frequency, 100)} MHz"
    in 500..1_800 -> "$frequency kHz"
    else -> ""
}

private fun formatScaled(value: Int, divisor: Int): String {
    val whole = value / divisor
    val remainder = value % divisor
    if (remainder == 0) return whole.toString()
    val fraction = remainder.toString()
        .padStart(divisor.toString().length - 1, '0')
        .trimEnd('0')
    return "$whole.$fraction"
}

internal fun MediaSnapshot.withRadioState(
    station: RadioMetadata?,
    playing: Boolean,
    elapsedRealtime: Long,
): MediaSnapshot {
    if (!backendConnected || audioSource != BridgeAudioSource.RADIO.name) return this

    val nextPlaybackState = if (playing) {
        PlaybackState.STATE_PLAYING
    } else {
        PlaybackState.STATE_PAUSED
    }
    val nextSpeed = if (playing) 1f else 0f
    val playbackChanged = playbackState != nextPlaybackState || speed != nextSpeed
    return copy(
        ownerPackage = RADIO_OWNER_PACKAGE,
        ownerApp = BridgeAudioSource.RADIO.name,
        mediaId = station?.mediaId ?: mediaId,
        title = station?.title ?: title,
        artist = station?.subtitle ?: artist,
        album = if (station != null) "" else album,
        duration = -1L,
        position = -1L,
        updateElapsedRealtime = if (playbackChanged) elapsedRealtime else updateElapsedRealtime,
        speed = nextSpeed,
        playbackState = nextPlaybackState,
        playbackErrorCode = 0,
        playbackErrorMessage = "",
        playbackActions = 0L,
        capabilities = BridgeAudioSource.RADIO.defaultCapabilities(),
        artworkUri = if (station != null) "" else artworkUri,
        artworkRevision = if (station != null && artworkUri.isNotBlank()) {
            artworkRevision + 1L
        } else {
            artworkRevision
        },
    )
}

private const val RADIO_OWNER_PACKAGE = "com.geely.radio.service"
