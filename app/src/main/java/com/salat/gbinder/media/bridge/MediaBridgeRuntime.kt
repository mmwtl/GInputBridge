package com.salat.gbinder.media.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MediaBridgeDependencies(
    val stateRepository: MediaStateRepository,
    val commandRouter: MediaCommandRouter,
)

internal object MediaBridgeRuntime {
    private val lock = Any()
    private var dependencies: MediaBridgeDependencies? = null
    private val mutableClientCount = MutableStateFlow(0)

    val clientCount: StateFlow<Int> = mutableClientCount.asStateFlow()

    fun initialize(value: MediaBridgeDependencies) = synchronized(lock) {
        dependencies = value
    }

    fun dependencies(): MediaBridgeDependencies? = synchronized(lock) { dependencies }

    fun clientRegistered() = synchronized(lock) {
        mutableClientCount.value = mutableClientCount.value + 1
    }

    fun clientUnregistered() = synchronized(lock) {
        mutableClientCount.value = (mutableClientCount.value - 1).coerceAtLeast(0)
    }
}
