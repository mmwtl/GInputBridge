package com.salat.gbinder.media.bridge

import android.content.Context
import com.salat.gbinder.util.isTrustedByCertificate

internal object AtlasMediaWidgetIdentity {
    const val PACKAGE_NAME = "com.mmwtl.atlasmediawidget"

    // AtlasMediaWidget is expected to use the AtlasAppWidget release signing key.
    val CERT_SHA256 = setOf(
        "EA:F9:F1:B2:DC:55:DB:19:6B:41:C2:C9:47:96:4D:68:09:A6:C9:54:D8:AA:7B:45:AD:6D:72:13:3A:F0:21:7E",
    )
}

internal object CallerAuthorizationPolicy {
    fun selectAuthorizedPackage(
        uidPackages: Set<String>,
        allowedPackages: Set<String>,
        certificateMatches: (String) -> Boolean,
    ): String? = allowedPackages
        .asSequence()
        .filter(uidPackages::contains)
        .firstOrNull(certificateMatches)
}

internal class MediaBridgeCallerVerifier(
    private val context: Context,
    private val allowedPackages: Set<String> = setOf(AtlasMediaWidgetIdentity.PACKAGE_NAME),
    private val allowedCertificates: Map<String, Set<String>> = mapOf(
        AtlasMediaWidgetIdentity.PACKAGE_NAME to AtlasMediaWidgetIdentity.CERT_SHA256,
    ),
) {
    fun authorize(sendingUid: Int): String? {
        if (sendingUid < 0) return null
        val packages = context.packageManager.getPackagesForUid(sendingUid).orEmpty().toSet()
        return CallerAuthorizationPolicy.selectAuthorizedPackage(
            uidPackages = packages,
            allowedPackages = allowedPackages,
        ) { packageName ->
            val certificates = allowedCertificates[packageName].orEmpty()
            certificates.isNotEmpty() && isTrustedByCertificate(packageName, certificates, context)
        }
    }
}
