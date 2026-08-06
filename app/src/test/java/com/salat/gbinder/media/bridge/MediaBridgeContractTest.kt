package com.salat.gbinder.media.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBridgeContractTest {
    @Test
    fun `only the advertised protocol version is accepted`() {
        assertEquals(
            MediaBridgeContract.Status.UNSUPPORTED_VERSION,
            MediaBridgeContract.negotiate(MediaBridgeContract.MIN_PROTOCOL_VERSION - 1),
        )
        assertEquals(
            MediaBridgeContract.Status.OK,
            MediaBridgeContract.negotiate(MediaBridgeContract.PROTOCOL_VERSION),
        )
        assertEquals(
            MediaBridgeContract.Status.UNSUPPORTED_VERSION,
            MediaBridgeContract.negotiate(MediaBridgeContract.MAX_PROTOCOL_VERSION + 1),
        )
    }
}
