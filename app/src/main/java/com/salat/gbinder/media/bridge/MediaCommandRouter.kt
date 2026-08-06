package com.salat.gbinder.media.bridge

internal enum class SessionPlaybackState {
    PLAYING,
    NOT_PLAYING,
}

internal interface MediaSessionCommandTarget {
    val packageName: String
    val playbackState: SessionPlaybackState
    val capabilities: Long

    fun play(): Boolean
    fun pause(): Boolean
    fun next(): Boolean
    fun previous(): Boolean
    fun seekTo(position: Long): Boolean
}

internal interface MediaCommandHost {
    fun backendAvailable(): Boolean
    fun blocksMediaCommands(): Boolean
    suspend fun executeNative(request: MediaCommandRequest): MediaCommandResult?
    fun preferredSession(): MediaSessionCommandTarget?
    fun sessions(): List<MediaSessionCommandTarget>
    fun currentMediaPackage(): String
    fun defaultMediaPackage(): String
    fun setCurrentMediaPackage(packageName: String)
    fun beforeSessionPlay()
    suspend fun sendFallback(packageName: String, command: MediaCommand): Boolean
    suspend fun startDefaultAndPlay(packageName: String): Boolean
    suspend fun setSource(
        source: BridgeAudioSource,
        appSource: String?,
        autoplay: Boolean,
    ): Boolean
}

/** Shared command arbitration for hardware keys and Messenger requests. */
internal class MediaCommandRouter(private val host: MediaCommandHost) {
    suspend fun execute(request: MediaCommandRequest): MediaCommandResult {
        if (!host.backendAvailable()) return result(MediaBridgeContract.Status.BACKEND_UNAVAILABLE)

        if (request.command == MediaCommand.SET_SOURCE) {
            val source = request.source ?: return result(MediaBridgeContract.Status.INVALID_REQUEST)
            return if (host.setSource(source, request.appSource, request.autoplay)) {
                result(MediaBridgeContract.Status.OK)
            } else {
                result(MediaBridgeContract.Status.FAILED, "source switch failed")
            }
        }

        if (host.blocksMediaCommands()) {
            return result(MediaBridgeContract.Status.NOT_SUPPORTED, "source owns media keys")
        }

        host.executeNative(request)?.let { return it }

        val target = selectSession()
        if (target != null) return executeOnSession(target, request)

        val fallbackPackage = host.currentMediaPackage().ifBlank { host.defaultMediaPackage() }
        if (fallbackPackage.isBlank()) {
            return result(MediaBridgeContract.Status.NOT_SUPPORTED, "no media target")
        }

        val sent = if ((request.command == MediaCommand.PLAY ||
                request.command == MediaCommand.TOGGLE) &&
            fallbackPackage == host.defaultMediaPackage()
        ) {
            host.beforeSessionPlay()
            host.startDefaultAndPlay(fallbackPackage)
        } else if (request.command == MediaCommand.SEEK_TO) {
            false
        } else {
            if (request.command == MediaCommand.PLAY || request.command == MediaCommand.TOGGLE) {
                host.beforeSessionPlay()
            }
            host.sendFallback(fallbackPackage, request.command)
        }
        if (sent) host.setCurrentMediaPackage(fallbackPackage)
        return if (sent) result(MediaBridgeContract.Status.OK) else {
            result(MediaBridgeContract.Status.NOT_SUPPORTED, "target rejected command")
        }
    }

    private fun selectSession(): MediaSessionCommandTarget? {
        host.preferredSession()?.let { return it }
        val sessions = host.sessions()
        val currentPackage = host.currentMediaPackage()
        if (currentPackage.isNotBlank()) {
            sessions.firstOrNull { it.packageName == currentPackage }?.let { return it }
        }
        return sessions.firstOrNull()
            ?: host.defaultMediaPackage().takeIf(String::isNotBlank)?.let { packageName ->
                sessions.firstOrNull { it.packageName == packageName }
            }
    }

    private fun executeOnSession(
        target: MediaSessionCommandTarget,
        request: MediaCommandRequest,
    ): MediaCommandResult {
        val requiredCapability = when (request.command) {
            MediaCommand.PLAY -> MediaCapabilities.PLAY
            MediaCommand.PAUSE -> MediaCapabilities.PAUSE
            MediaCommand.TOGGLE -> MediaCapabilities.TOGGLE
            MediaCommand.NEXT -> MediaCapabilities.NEXT
            MediaCommand.PREVIOUS -> MediaCapabilities.PREVIOUS
            MediaCommand.SEEK_TO -> MediaCapabilities.SEEK_TO
            MediaCommand.SET_SOURCE -> MediaCapabilities.SET_SOURCE
        }
        if (target.capabilities and requiredCapability == 0L) {
            return result(MediaBridgeContract.Status.NOT_SUPPORTED, "session capability missing")
        }

        val succeeded = when (request.command) {
            MediaCommand.PLAY -> {
                host.beforeSessionPlay()
                target.play()
            }

            MediaCommand.PAUSE -> target.pause()
            MediaCommand.TOGGLE -> if (target.playbackState == SessionPlaybackState.PLAYING) {
                target.pause()
            } else {
                host.beforeSessionPlay()
                target.play()
            }

            MediaCommand.NEXT -> target.next()
            MediaCommand.PREVIOUS -> target.previous()
            MediaCommand.SEEK_TO -> target.seekTo(request.position ?: return result(
                MediaBridgeContract.Status.INVALID_REQUEST,
            ))

            MediaCommand.SET_SOURCE -> false
        }
        if (succeeded) host.setCurrentMediaPackage(target.packageName)
        return if (succeeded) result(MediaBridgeContract.Status.OK) else {
            result(MediaBridgeContract.Status.FAILED, "session command failed")
        }
    }

    private fun result(status: Int, message: String = "") = MediaCommandResult(status, message)
}
