package com.salat.gbinder.media.bridge

import com.geely.lib.oneosapi.mediacenter.MediaCenterManager
import com.geely.lib.oneosapi.mediacenter.bean.DeviceInfo
import com.geely.lib.oneosapi.mediacenter.bean.MediaData
import com.geely.lib.oneosapi.mediacenter.bean.MusicFileData
import com.geely.lib.oneosapi.mediacenter.bean.OnlineUserInfo
import com.geely.lib.oneosapi.mediacenter.bean.SearchResult
import com.geely.lib.oneosapi.mediacenter.constant.MediaCenterConstant
import com.geely.lib.oneosapi.mediacenter.listener.DeviceStateListener
import com.geely.lib.oneosapi.mediacenter.listener.MusicStateListener
import timber.log.Timber

internal class OneOsMediaBridgeAdapter(private val hub: MediaStateHub) {
    private var manager: MediaCenterManager? = null
    private val deviceListeners = mutableMapOf<MediaCenterConstant.AudioSource, DeviceStateListener>()

    private val musicStateListener = object : MusicStateListener {
        override fun onMediaDataChanged(
            source: MediaCenterConstant.AudioSource,
            mediaData: MediaData?,
        ) = hub.onOneOsMediaData(source, mediaData)

        override fun onPlayPositionChanged(
            source: MediaCenterConstant.AudioSource,
            current: Long,
            total: Long,
        ) = hub.onOneOsProgress(source, current, total)

        override fun onPlayStateChanged(
            source: MediaCenterConstant.AudioSource,
            state: MediaCenterConstant.PlayState,
        ) = hub.onOneOsPlayState(source, state)

        override fun onPlayListChanged(
            source: MediaCenterConstant.AudioSource,
            list: MutableList<MediaData>?,
        ) = Unit

        override fun onFavorStateChanged(
            source: MediaCenterConstant.AudioSource,
            mediaData: MediaData?,
        ) = Unit

        override fun onLrcLoad(
            source: MediaCenterConstant.AudioSource,
            lrc: String?,
            time: Long,
        ) = Unit

        override fun onPlayModeChange(
            source: MediaCenterConstant.AudioSource,
            mode: MediaCenterConstant.PlayMode,
        ) = Unit
    }

    fun attach(mediaCenterManager: MediaCenterManager) {
        detach(notify = false)
        manager = mediaCenterManager
        runCatching { mediaCenterManager.musicAdapterManager.addMusicStateListener(musicStateListener) }
            .onFailure(Timber::e)

        mediaCenterManager.musicManagerMap.forEach { (source, musicManager) ->
            val listener = createDeviceListener(source)
            deviceListeners[source] = listener
            runCatching { musicManager.addDeviceStateListener(listener) }.onFailure(Timber::e)
        }

        val currentSource = runCatching { mediaCenterManager.currentAudioSource }
            .getOrDefault(MediaCenterConstant.AudioSource.AUDIO_SOURCE_UNKNOWN)
        val currentApp = runCatching { mediaCenterManager.currentAppSource }
            .getOrDefault(MediaCenterConstant.AppSource.UNKNOWN)
        hub.onBackendConnected(currentSource, currentApp, queryAvailability(mediaCenterManager))
        refreshCurrentMedia()
    }

    fun detach(notify: Boolean = true) {
        val currentManager = manager
        if (currentManager != null) {
            runCatching {
                currentManager.musicAdapterManager.removeMusicStateListener(musicStateListener)
            }.onFailure(Timber::e)
            deviceListeners.forEach { (source, listener) ->
                runCatching {
                    currentManager.musicManagerMap[source]?.removeDeviceStateListener(listener)
                }.onFailure(Timber::e)
            }
        }
        deviceListeners.clear()
        manager = null
        if (notify) hub.onBackendDisconnected("OneOS MediaCenter disconnected")
    }

    fun onSourceChanged(
        source: MediaCenterConstant.AudioSource,
        appSource: MediaCenterConstant.AppSource,
    ) {
        hub.onSourceChanged(source, appSource)
        refreshCurrentMedia()
    }

    private fun refreshCurrentMedia() {
        val currentManager = manager ?: return
        runCatching {
            val source = currentManager.currentAudioSource
            val adapter = currentManager.musicAdapterManager
            hub.onOneOsMediaData(source, adapter.currentMediaData)
            adapter.currentPlayState?.let { hub.onOneOsPlayState(source, it) }
            val data = adapter.currentMediaData
            hub.onOneOsProgress(source, adapter.currentPosition, data?.duration ?: -1L)
        }.onFailure(Timber::e)
    }

    private fun queryAvailability(
        mediaCenterManager: MediaCenterManager,
    ): Map<BridgeAudioSource, Pair<Boolean, Boolean>> {
        val selected = runCatching { mediaCenterManager.currentAudioSource }.getOrNull()
        val result = mutableMapOf<BridgeAudioSource, Pair<Boolean, Boolean>>()
        mediaCenterManager.musicManagerMap.forEach { (source, musicManager) ->
            val available = runCatching { musicManager.isAlive }.getOrDefault(false)
            val connected = when (source) {
                MediaCenterConstant.AudioSource.AUDIO_SOURCE_ONLINE,
                MediaCenterConstant.AudioSource.AUDIO_SOURCE_YUNTING -> available

                else -> runCatching { !musicManager.devicesInfo.isNullOrEmpty() }.getOrDefault(false)
            } || source == selected
            result[source.toBridgeSource()] = connected to available
        }
        val radioAvailable = runCatching { mediaCenterManager.radioManager.isAlive }.getOrDefault(false)
        result[BridgeAudioSource.RADIO] = ((selected == MediaCenterConstant.AudioSource.AUDIO_SOURCE_RADIO) to radioAvailable)
        result[BridgeAudioSource.OTHER] = false to false
        result[BridgeAudioSource.UNKNOWN] = false to false
        return result
    }

    private fun createDeviceListener(
        registeredSource: MediaCenterConstant.AudioSource,
    ): DeviceStateListener = object : DeviceStateListener {
        override fun onDeviceStateChanged(
            source: MediaCenterConstant.AudioSource,
            state: MediaCenterConstant.DeviceState,
            info: DeviceInfo?,
        ) {
            val connected = when (state) {
                MediaCenterConstant.DeviceState.DEVICE_USB_MOUNT,
                MediaCenterConstant.DeviceState.DEVICE_USB_SCAN_COMPLETE,
                MediaCenterConstant.DeviceState.DEVICE_BT_CONNECTED,
                MediaCenterConstant.DeviceState.DEVICE_CPAA_CONNECTED_CP,
                MediaCenterConstant.DeviceState.DEVICE_CPAA_CONNECTED_AA -> true

                MediaCenterConstant.DeviceState.DEVICE_USB_UNMOUNT,
                MediaCenterConstant.DeviceState.DEVICE_BT_DISCONNECTED,
                MediaCenterConstant.DeviceState.DEVICE_BT_UNBOUND,
                MediaCenterConstant.DeviceState.DEVICE_BT_OFF,
                MediaCenterConstant.DeviceState.DEVICE_CPAA_DISCONNECTED -> false

                else -> return
            }
            hub.onSourceAvailability(source, connected = connected, available = true)
        }

        override fun onDeviceError(
            source: MediaCenterConstant.AudioSource,
            error: Int,
            errorMsg: String?,
        ) = hub.onOneOsError(source, "device error $error: ${errorMsg.orEmpty()}")

        override fun onBluetoothDeviceChange(
            source: MediaCenterConstant.AudioSource,
            deviceInfoList: MutableList<DeviceInfo>?,
        ) = hub.onSourceAvailability(
            source,
            connected = !deviceInfoList.isNullOrEmpty(),
            available = true,
        )

        override fun onAppExistStateChanged(
            source: MediaCenterConstant.AudioSource,
            appSource: MediaCenterConstant.AppSource,
            existed: Boolean,
        ) = hub.onSourceAvailability(source, connected = existed, available = existed)

        override fun onAppDied(appSource: MediaCenterConstant.AppSource) {
            hub.onSourceAvailability(registeredSource, connected = false, available = false)
        }

        override fun onScanPathFinish(
            source: MediaCenterConstant.AudioSource,
            musicFileDataList: MutableList<MusicFileData>?,
        ) = Unit

        @Suppress("DEPRECATION")
        override fun onSearchSongResult(
            source: MediaCenterConstant.AudioSource,
            appSource: MediaCenterConstant.AppSource,
            searchResults: MutableList<SearchResult>?,
        ) = Unit

        @Suppress("DEPRECATION")
        override fun onUserInfoResult(
            source: MediaCenterConstant.AudioSource,
            appSource: MediaCenterConstant.AppSource,
            userInfo: OnlineUserInfo?,
        ) = Unit
    }
}
