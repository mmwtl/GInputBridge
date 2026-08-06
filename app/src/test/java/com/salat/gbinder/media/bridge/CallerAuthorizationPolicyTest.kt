package com.salat.gbinder.media.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerAuthorizationPolicyTest {
    private val atlas = AtlasMediaWidgetIdentity.PACKAGE_NAME
    private val allowed = setOf(atlas)

    @Test
    fun `package and certificate must both match`() {
        assertEquals(
            atlas,
            CallerAuthorizationPolicy.selectAuthorizedPackage(
                uidPackages = setOf(atlas),
                allowedPackages = allowed,
                certificateMatches = { true },
            ),
        )
        assertNull(
            CallerAuthorizationPolicy.selectAuthorizedPackage(
                uidPackages = setOf(atlas),
                allowedPackages = allowed,
                certificateMatches = { false },
            ),
        )
        assertNull(
            CallerAuthorizationPolicy.selectAuthorizedPackage(
                uidPackages = setOf("attacker.example"),
                allowedPackages = allowed,
                certificateMatches = { true },
            ),
        )
    }

    @Test
    fun `another package sharing the uid cannot substitute for Atlas`() {
        assertNull(
            CallerAuthorizationPolicy.selectAuthorizedPackage(
                uidPackages = setOf("attacker.example"),
                allowedPackages = allowed,
                certificateMatches = { packageName -> packageName == atlas },
            ),
        )
    }
}
