package com.salat.gbinder.media.bridge

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.geely.lib.oneosapi.mediacenter.MediaCenterManager
import com.geely.lib.oneosapi.mediacenter.constant.MediaCenterConstant
import timber.log.Timber

internal interface AppMediaCommandEnvironment {
    fun mediaCenter(): MediaCenterManager?
    fun radioBtControlEnabled(): Boolean
    fun gmpInstalled(): Boolean
    fun currentVisiblePackage(): String
    fun preferredController(): MediaController?
    fun allowedControllers(): List<MediaController>
    fun currentMediaPackage(): String
    fun defaultMediaPackage(): String
    fun setCurrentMediaPackage(packageName: String)
    fun beforeSessionPlay()
    suspend fun sendFallback(packageName: String, command: MediaCommand): Boolean
    suspend fun startDefaultAndPlay(packageName: String): Boolean
    suspend fun setSource(source: BridgeAudioSource, appSource: String?, autoplay: Boolean): Boolean
    suspend fun ensureOnlineSource()
    fun log(message: String)
}

internal class AndroidMediaCommandHost(
    private val environment: AppMediaCommandEnvironment,
) : MediaCommandHost {
    companion object {
        private const val RADIO_STATE_PLAY = 0x1000
    }

    override fun backendAvailable(): Boolean =
        environment.mediaCenter()?.isAlive == true ||
                environment.allowedControllers().isNotEmpty() ||
                environment.defaultMediaPackage().isNotBlank()

    override fun blocksMediaCommands(): Boolean =
        environment.mediaCenter()?.currentAudioSource ==
                MediaCenterConstant.AudioSource.AUDIO_SOURCE_CPAA

    override suspend fun executeNative(request: MediaCommandRequest): MediaCommandResult? {
        if (!environment.radioBtControlEnabled()) return null
        val mediaCenter = environment.mediaCenter()?.takeIf { it.isAlive } ?: return null
        val source = mediaCenter.currentAudioSource ?: return null

        if ((request.command == MediaCommand.PLAY || request.command == MediaCommand.TOGGLE) &&
            isExternalForeground(source)
        ) {
            environment.ensureOnlineSource()
            return null
        }

        val supportsNative = when (source) {
            MediaCenterConstant.AudioSource.AUDIO_SOURCE_RADIO,
            MediaCenterConstant.AudioSource.AUDIO_SOURCE_BT,
            MediaCenterConstant.AudioSource.AUDIO_SOURCE_USB -> true

            MediaCenterConstant.AudioSource.AUDIO_SOURCE_ONLINE -> environment.gmpInstalled()
            else -> false
        }
        if (!supportsNative) return null

        return runCatching {
            val handled = if (source == MediaCenterConstant.AudioSource.AUDIO_SOURCE_RADIO) {
                executeRadio(mediaCenter, request)
            } else {
                executeMusicAdapter(mediaCenter, request)
            }
            if (handled) {
                environment.log("[MEDIA_EVENT] routed ${request.command} via MediaCenter, source=$source")
                MediaCommandResult(MediaBridgeContract.Status.OK)
            } else {
                MediaCommandResult(
                    MediaBridgeContract.Status.NOT_SUPPORTED,
                    "native source does not support ${request.command}",
                )
            }
        }.onFailure(Timber::e).getOrElse {
            MediaCommandResult(MediaBridgeContract.Status.FAILED, it.message.orEmpty())
        }
    }

    override fun preferredSession(): MediaSessionCommandTarget? =
        environment.preferredController()?.let(::AndroidMediaSessionTarget)

    override fun sessions(): List<MediaSessionCommandTarget> =
        environment.allowedControllers().map(::AndroidMediaSessionTarget)

    override fun currentMediaPackage(): String = environment.currentMediaPackage()

    override fun defaultMediaPackage(): String = environment.defaultMediaPackage()

    override fun setCurrentMediaPackage(packageName: String) =
        environment.setCurrentMediaPackage(packageName)

    override fun beforeSessionPlay() = environment.beforeSessionPlay()

    override suspend fun sendFallback(packageName: String, command: MediaCommand): Boolean =
        environment.sendFallback(packageName, command)

    override suspend fun startDefaultAndPlay(packageName: String): Boolean =
        environment.startDefaultAndPlay(packageName)

    override suspend fun setSource(
        source: BridgeAudioSource,
        appSource: String?,
        autoplay: Boolean,
    ): Boolean = environment.setSource(source, appSource, autoplay)

    private fun executeRadio(
        mediaCenter: MediaCenterManager,
        request: MediaCommandRequest,
    ): Boolean = when (request.command) {
        MediaCommand.PLAY -> {
            mediaCenter.radioManager.requestAudioSource()
            mediaCenter.radioManager.play()
        }

        MediaCommand.PAUSE -> mediaCenter.radioManager.pause()
        MediaCommand.TOGGLE -> if (mediaCenter.radioManager.radioStatus == RADIO_STATE_PLAY) {
            mediaCenter.radioManager.pause()
        } else {
            mediaCenter.radioManager.requestAudioSource()
            mediaCenter.radioManager.play()
        }

        MediaCommand.NEXT -> mediaCenter.radioManager.seekAsync(0)
        MediaCommand.PREVIOUS -> mediaCenter.radioManager.seekAsync(1)
        MediaCommand.SEEK_TO,
        MediaCommand.SET_SOURCE -> false
    }

    private fun executeMusicAdapter(
        mediaCenter: MediaCenterManager,
        request: MediaCommandRequest,
    ): Boolean {
        val adapter = mediaCenter.musicAdapterManager
        val result = when (request.command) {
            MediaCommand.PLAY -> adapter.play()
            MediaCommand.PAUSE -> adapter.pause()
            MediaCommand.TOGGLE -> if (
                adapter.currentPlayState == MediaCenterConstant.PlayState.MUSIC_STATE_PLAY
            ) adapter.pause() else adapter.play()

            MediaCommand.NEXT -> adapter.next()
            MediaCommand.PREVIOUS -> adapter.prev()
            MediaCommand.SEEK_TO -> adapter.seekTo(request.position ?: return false)
            MediaCommand.SET_SOURCE -> return false
        }
        return result == 1
    }

    private fun isExternalForeground(source: MediaCenterConstant.AudioSource): Boolean {
        val foreground = environment.currentVisiblePackage()
        if (foreground.isBlank()) return false
        val belongsToControlledSession = environment.allowedControllers()
            .any { it.packageName == foreground }
        return belongsToControlledSession &&
                source != MediaCenterConstant.AudioSource.AUDIO_SOURCE_ONLINE
    }
}

private class AndroidMediaSessionTarget(
    private val controller: MediaController,
) : MediaSessionCommandTarget {
    override val packageName: String get() = controller.packageName.orEmpty()
    override val playbackState: SessionPlaybackState
        get() = if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            SessionPlaybackState.PLAYING
        } else SessionPlaybackState.NOT_PLAYING
    override val capabilities: Long
        get() {
            val actions = controller.playbackState?.actions ?: 0L
            var capabilities = MediaCapabilities.fromPlaybackActions(actions)
            if (capabilities and MediaCapabilities.PLAY != 0L &&
                capabilities and MediaCapabilities.PAUSE != 0L
            ) {
                capabilities = capabilities or MediaCapabilities.TOGGLE
            }
            return capabilities
        }

    override fun play(): Boolean = runCatching {
        controller.transportControls.play()
        true
    }.getOrDefault(false)

    override fun pause(): Boolean = runCatching {
        controller.transportControls.pause()
        true
    }.getOrDefault(false)

    override fun next(): Boolean = dispatchSkip(KeyEvent.KEYCODE_MEDIA_NEXT) {
        controller.transportControls.skipToNext()
    }

    override fun previous(): Boolean = dispatchSkip(KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
        controller.transportControls.skipToPrevious()
    }

    override fun seekTo(position: Long): Boolean = runCatching {
        controller.transportControls.seekTo(position)
        true
    }.getOrDefault(false)

    private fun dispatchSkip(keyCode: Int, fallback: () -> Unit): Boolean = runCatching {
        val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (!down && !up) fallback()
        true
    }.getOrDefault(false)
}
