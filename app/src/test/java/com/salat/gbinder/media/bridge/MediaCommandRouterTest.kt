package com.salat.gbinder.media.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCommandRouterTest {
    private class FakeSession(
        override val packageName: String,
        override var playbackState: SessionPlaybackState = SessionPlaybackState.NOT_PLAYING,
        override var capabilities: Long = MediaCapabilities.ALL,
    ) : MediaSessionCommandTarget {
        val calls = mutableListOf<String>()

        override fun play(): Boolean = true.also { calls += "play" }
        override fun pause(): Boolean = true.also { calls += "pause" }
        override fun next(): Boolean = true.also { calls += "next" }
        override fun previous(): Boolean = true.also { calls += "previous" }
        override fun seekTo(position: Long): Boolean = true.also { calls += "seek:$position" }
    }

    private class FakeHost : MediaCommandHost {
        var available = true
        var blocked = false
        var nativeResult: MediaCommandResult? = null
        var preferred: MediaSessionCommandTarget? = null
        var allSessions = emptyList<MediaSessionCommandTarget>()
        var currentPackage = ""
        var defaultPackage = ""
        var beforePlayCalls = 0
        var startDefaultCalls = 0
        var fallbackCalls = mutableListOf<Pair<String, MediaCommand>>()
        var sourceResult = true
        var selectedSource: BridgeAudioSource? = null

        override fun backendAvailable(): Boolean = available
        override fun blocksMediaCommands(): Boolean = blocked
        override suspend fun executeNative(request: MediaCommandRequest): MediaCommandResult? =
            nativeResult

        override fun preferredSession(): MediaSessionCommandTarget? = preferred
        override fun sessions(): List<MediaSessionCommandTarget> = allSessions
        override fun currentMediaPackage(): String = currentPackage
        override fun defaultMediaPackage(): String = defaultPackage
        override fun setCurrentMediaPackage(packageName: String) {
            currentPackage = packageName
        }

        override fun beforeSessionPlay() {
            beforePlayCalls++
        }

        override suspend fun sendFallback(packageName: String, command: MediaCommand): Boolean {
            fallbackCalls += packageName to command
            return true
        }

        override suspend fun startDefaultAndPlay(packageName: String): Boolean = true.also {
            startDefaultCalls++
        }

        override suspend fun setSource(
            source: BridgeAudioSource,
            appSource: String?,
            autoplay: Boolean,
        ): Boolean {
            selectedSource = source
            return sourceResult
        }
    }

    @Test
    fun `native source routing wins before media sessions`() = runBlocking {
        val session = FakeSession("player")
        val host = FakeHost().apply {
            nativeResult = MediaCommandResult(MediaBridgeContract.Status.OK)
            preferred = session
        }

        val result = MediaCommandRouter(host).execute(request(MediaCommand.NEXT))

        assertTrue(result.succeeded)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `toggle pauses the preferred playing session`() = runBlocking {
        val session = FakeSession("player", SessionPlaybackState.PLAYING)
        val host = FakeHost().apply { preferred = session }

        val result = MediaCommandRouter(host).execute(request(MediaCommand.TOGGLE))

        assertTrue(result.succeeded)
        assertEquals(listOf("pause"), session.calls)
        assertEquals("player", host.currentPackage)
        assertEquals(0, host.beforePlayCalls)
    }

    @Test
    fun `play prepares source before starting session`() = runBlocking {
        val session = FakeSession("player")
        val host = FakeHost().apply { preferred = session }

        val result = MediaCommandRouter(host).execute(request(MediaCommand.PLAY))

        assertTrue(result.succeeded)
        assertEquals(1, host.beforePlayCalls)
        assertEquals(listOf("play"), session.calls)
    }

    @Test
    fun `seek is rejected when selected session lacks capability`() = runBlocking {
        val session = FakeSession(
            packageName = "player",
            capabilities = MediaCapabilities.BASIC_PLAYBACK,
        )
        val host = FakeHost().apply { preferred = session }

        val result = MediaCommandRouter(host).execute(
            request(MediaCommand.SEEK_TO).copy(position = 12_345L),
        )

        assertEquals(MediaBridgeContract.Status.NOT_SUPPORTED, result.status)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `blocked source never falls through to a session`() = runBlocking {
        val session = FakeSession("player")
        val host = FakeHost().apply {
            blocked = true
            preferred = session
        }

        val result = MediaCommandRouter(host).execute(request(MediaCommand.PLAY))

        assertEquals(MediaBridgeContract.Status.NOT_SUPPORTED, result.status)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `set source delegates through the same host`() = runBlocking {
        val host = FakeHost()
        val request = request(MediaCommand.SET_SOURCE).copy(source = BridgeAudioSource.USB)

        val result = MediaCommandRouter(host).execute(request)

        assertTrue(result.succeeded)
        assertEquals(BridgeAudioSource.USB, host.selectedSource)
        assertFalse(host.fallbackCalls.isNotEmpty())
    }

    @Test
    fun `toggle starts default player when no session exists`() = runBlocking {
        val host = FakeHost().apply { defaultPackage = "default.player" }

        val result = MediaCommandRouter(host).execute(request(MediaCommand.TOGGLE))

        assertTrue(result.succeeded)
        assertEquals(1, host.beforePlayCalls)
        assertEquals(1, host.startDefaultCalls)
        assertTrue(host.fallbackCalls.isEmpty())
        assertEquals("default.player", host.currentPackage)
    }

    private fun request(command: MediaCommand) = MediaCommandRequest(
        requestId = "request-1",
        command = command,
    )
}
