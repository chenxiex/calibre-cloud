package io.github.chenxiex.calibrecloud.auth

import io.github.chenxiex.calibrecloud.BuildConfig
import java.net.URI

/**
 * Validated variant configuration. Step 05 prepares protocol inputs without starting authorization.
 * The browser coordinator must additionally validate pending state before exchanging a code.
 */
class OneDriveOAuthConfiguration internal constructor(
    val clientId: String,
    val redirectUri: String,
) {
    val authorizationEndpoint: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
    val tokenEndpoint: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    val responseType: String = "code"
    val codeChallengeMethod: String = "S256"
    val scopes: List<String> = listOf("openid", "offline_access", "https://graph.microsoft.com/Files.ReadWrite")

    /**
     * Match the full configured callback before processing OAuth query parameters.
     * Manifest filters alone cannot check paths without a host. This is an address check,
     * not state validation or proof that a callback belongs to a pending transaction.
     */
    fun acceptsCallback(address: String): Boolean {
        val actual = try { URI(address) } catch (_: Exception) { return false }
        val expected = URI(redirectUri)
        return !actual.isOpaque && actual.rawFragment == null &&
            actual.scheme == expected.scheme && actual.rawAuthority == expected.rawAuthority &&
            actual.rawPath == expected.rawPath
    }

    companion object {
        fun fromBuildConfiguration(): OneDriveOAuthConfiguration? = if (BuildConfig.ONEDRIVE_CONFIGURED) {
            OneDriveOAuthConfiguration(BuildConfig.ONEDRIVE_CLIENT_ID, BuildConfig.ONEDRIVE_REDIRECT_URI)
        } else null

    }
}
