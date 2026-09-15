package com.hai.manager.router

import java.security.MessageDigest

internal enum class ZteAdDigest {
    MD5,
    SHA256
}

/**
 * Pure helpers for the guarded ZTE NCK path.
 *
 * HAI does not infer that a router accepts NCK from the chipset alone. The live
 * WebUI must advertise the same UNLOCK_NETWORK form used by ZTE's own UI before
 * the capability probe can mark NCK entry as available.
 */
internal object ZteNckRuntime {
    val webUiCandidatePaths = listOf(
        "/js/service.js",
        "/js/network_lock.js",
        "/web/js/service.js",
        "/web/js/network_lock.js"
    )

    fun isMc801a(model: String?): Boolean =
        model.orEmpty().contains("MC801A", ignoreCase = true)

    fun exposesUnlockNetwork(source: String): Boolean {
        if (source.isBlank()) return false
        return source.contains("UNLOCK_NETWORK", ignoreCase = true) &&
            source.contains("unlock_network_code", ignoreCase = true)
    }

    fun adDigestFor(version: String?): ZteAdDigest? {
        val value = version.orEmpty().uppercase()
        return when {
            "MC888" in value || "MC889" in value -> ZteAdDigest.SHA256
            "MC801" in value || "MC7010" in value -> ZteAdDigest.MD5
            else -> null
        }
    }

    fun computeAd(waInnerVersion: String?, crVersion: String?, rd: String?): String? {
        val wa = waInnerVersion?.trim().orEmpty()
        val cr = crVersion?.trim().orEmpty()
        val random = rd?.trim().orEmpty()
        if (wa.isBlank() || random.isBlank()) return null

        val digest = adDigestFor(wa) ?: adDigestFor(cr) ?: return null
        val first = hash(digest, wa + cr)
        return hash(digest, first + random)
    }

    private fun hash(digest: ZteAdDigest, value: String): String {
        val algorithm = when (digest) {
            ZteAdDigest.MD5 -> "MD5"
            ZteAdDigest.SHA256 -> "SHA-256"
        }
        return MessageDigest.getInstance(algorithm)
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
