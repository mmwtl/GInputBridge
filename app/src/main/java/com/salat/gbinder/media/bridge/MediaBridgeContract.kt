package com.salat.gbinder.media.bridge

import android.os.Bundle

/** Public Messenger/Bundle contract. Keep key values stable across protocol versions. */
object MediaBridgeContract {
    const val SERVICE_ACTION = "com.salat.gbinder.media.BIND"
    const val SERVICE_PACKAGE = "com.salat.gbinder"
    const val SERVICE_CLASS = "com.salat.gbinder.media.bridge.MediaBridgeService"

    const val PROTOCOL_VERSION = 1
    const val MIN_PROTOCOL_VERSION = 1
    const val MAX_PROTOCOL_VERSION = 1

    object ClientMessage {
        const val REGISTER = 1
        const val UNREGISTER = 2
        const val GET_SNAPSHOT = 3
        const val COMMAND = 4
    }

    object ServerMessage {
        const val REGISTERED = 100
        const val SNAPSHOT = 101
        const val COMMAND_RESULT = 102
        const val ERROR = 103
    }

    object Key {
        const val PROTOCOL_VERSION = "protocolVersion"
        const val MIN_PROTOCOL_VERSION = "minProtocolVersion"
        const val MAX_PROTOCOL_VERSION = "maxProtocolVersion"
        const val REQUEST_ID = "requestId"
        const val STATUS = "status"
        const val MESSAGE = "message"

        const val GENERATION = "generation"
        const val TIMESTAMP = "timestamp"
        const val BACKEND_CONNECTED = "backendConnected"
        const val BACKEND_ERROR_CODE = "backendErrorCode"
        const val BACKEND_ERROR_MESSAGE = "backendErrorMessage"
        const val AUDIO_SOURCE = "audioSource"
        const val APP_SOURCE = "appSource"
        const val SOURCES = "sources"
        const val SOURCE_ID = "id"
        const val SOURCE_CONNECTED = "connected"
        const val SOURCE_AVAILABLE = "available"
        const val SOURCE_SELECTED = "selected"
        const val SOURCE_CAPABILITIES = "capabilities"

        const val OWNER_PACKAGE = "ownerPackage"
        const val OWNER_APP = "ownerApp"
        const val MEDIA_ID = "mediaId"
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val DURATION = "duration"
        const val POSITION = "position"
        const val UPDATE_ELAPSED_REALTIME = "updateElapsedRealtime"
        const val SPEED = "speed"
        const val PLAYBACK_STATE = "playbackState"
        const val PLAYBACK_ERROR_CODE = "playbackErrorCode"
        const val PLAYBACK_ERROR_MESSAGE = "playbackErrorMessage"
        const val PLAYBACK_ACTIONS = "playbackActions"
        const val CAPABILITIES = "capabilities"
        const val ARTWORK_URI = "artworkUri"
        const val ARTWORK_REVISION = "artworkRevision"

        const val COMMAND = "command"
        const val COMMAND_POSITION = "position"
        const val COMMAND_SOURCE = "source"
        const val COMMAND_APP_SOURCE = "appSource"
        const val COMMAND_AUTOPLAY = "autoplay"
    }

    object Status {
        const val OK = 0
        const val INVALID_REQUEST = 1
        const val UNSUPPORTED_VERSION = 2
        const val UNAUTHORIZED = 3
        const val UNKNOWN_COMMAND = 4
        const val BACKEND_UNAVAILABLE = 5
        const val NOT_SUPPORTED = 6
        const val FAILED = 7
        const val NOT_REGISTERED = 8
    }

    object BackendError {
        const val NONE = 0
        const val CONNECTING = 1
        const val ONE_OS_DISCONNECTED = 2
        const val ONE_OS_ERROR = 3
    }

    fun negotiate(clientVersion: Int): Int = when {
        clientVersion < MIN_PROTOCOL_VERSION || clientVersion > MAX_PROTOCOL_VERSION ->
            Status.UNSUPPORTED_VERSION

        else -> Status.OK
    }
}

internal fun MediaSnapshot.toBundle(): Bundle = Bundle().apply {
    putInt(MediaBridgeContract.Key.PROTOCOL_VERSION, protocolVersion)
    putLong(MediaBridgeContract.Key.GENERATION, generation)
    putLong(MediaBridgeContract.Key.TIMESTAMP, timestamp)
    putBoolean(MediaBridgeContract.Key.BACKEND_CONNECTED, backendConnected)
    putInt(MediaBridgeContract.Key.BACKEND_ERROR_CODE, backendErrorCode)
    putString(MediaBridgeContract.Key.BACKEND_ERROR_MESSAGE, backendErrorMessage)
    putString(MediaBridgeContract.Key.AUDIO_SOURCE, audioSource)
    putString(MediaBridgeContract.Key.APP_SOURCE, appSource)
    putParcelableArrayList(
        MediaBridgeContract.Key.SOURCES,
        ArrayList(sources.map(MediaSourceSnapshot::toBundle)),
    )
    putString(MediaBridgeContract.Key.OWNER_PACKAGE, ownerPackage)
    putString(MediaBridgeContract.Key.OWNER_APP, ownerApp)
    putString(MediaBridgeContract.Key.MEDIA_ID, mediaId)
    putString(MediaBridgeContract.Key.TITLE, title)
    putString(MediaBridgeContract.Key.ARTIST, artist)
    putString(MediaBridgeContract.Key.ALBUM, album)
    putLong(MediaBridgeContract.Key.DURATION, duration)
    putLong(MediaBridgeContract.Key.POSITION, position)
    putLong(MediaBridgeContract.Key.UPDATE_ELAPSED_REALTIME, updateElapsedRealtime)
    putFloat(MediaBridgeContract.Key.SPEED, speed)
    putInt(MediaBridgeContract.Key.PLAYBACK_STATE, playbackState)
    putInt(MediaBridgeContract.Key.PLAYBACK_ERROR_CODE, playbackErrorCode)
    putString(MediaBridgeContract.Key.PLAYBACK_ERROR_MESSAGE, playbackErrorMessage)
    putLong(MediaBridgeContract.Key.PLAYBACK_ACTIONS, playbackActions)
    putLong(MediaBridgeContract.Key.CAPABILITIES, capabilities)
    putString(MediaBridgeContract.Key.ARTWORK_URI, artworkUri)
    putLong(MediaBridgeContract.Key.ARTWORK_REVISION, artworkRevision)
}

private fun MediaSourceSnapshot.toBundle(): Bundle = Bundle().apply {
    putString(MediaBridgeContract.Key.SOURCE_ID, id)
    putBoolean(MediaBridgeContract.Key.SOURCE_CONNECTED, connected)
    putBoolean(MediaBridgeContract.Key.SOURCE_AVAILABLE, available)
    putBoolean(MediaBridgeContract.Key.SOURCE_SELECTED, selected)
    putLong(MediaBridgeContract.Key.SOURCE_CAPABILITIES, capabilities)
}

internal fun Bundle.toMediaCommandRequest(): MediaCommandRequest? {
    val requestId = getString(MediaBridgeContract.Key.REQUEST_ID)?.takeIf(String::isNotBlank)
        ?: return null
    val command = getString(MediaBridgeContract.Key.COMMAND)
        ?.let { runCatching { MediaCommand.valueOf(it) }.getOrNull() }
        ?: return null

    return when (command) {
        MediaCommand.SEEK_TO -> {
            if (!containsKey(MediaBridgeContract.Key.COMMAND_POSITION)) return null
            val position = getLong(MediaBridgeContract.Key.COMMAND_POSITION)
            if (position < 0L) return null
            MediaCommandRequest(requestId, command, position = position)
        }

        MediaCommand.SET_SOURCE -> {
            val source = getString(MediaBridgeContract.Key.COMMAND_SOURCE)
                ?.let { runCatching { BridgeAudioSource.valueOf(it) }.getOrNull() }
                ?: return null
            MediaCommandRequest(
                requestId = requestId,
                command = command,
                source = source,
                appSource = getString(MediaBridgeContract.Key.COMMAND_APP_SOURCE),
                autoplay = getBoolean(MediaBridgeContract.Key.COMMAND_AUTOPLAY, true),
            )
        }

        else -> MediaCommandRequest(requestId, command)
    }
}
