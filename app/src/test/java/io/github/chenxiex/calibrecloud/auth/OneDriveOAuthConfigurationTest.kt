package io.github.chenxiex.calibrecloud.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveOAuthConfigurationTest {
    @Test
    fun callbackMustMatchFullConfiguredAddressBeforeProcessingParameters() {
        val configuration = OneDriveOAuthConfiguration("fixture", "org.example.debug://auth/oauth2redirect")
        assertTrue(configuration.acceptsCallback("org.example.debug://auth/oauth2redirect?code=fixture&state=fixture"))
        listOf(
            "org.example://auth/oauth2redirect?code=fixture",
            "org.example.debug://other/oauth2redirect",
            "org.example.debug://auth/another",
            "org.example.debug://auth/oauth2redirect/child",
            "org.example.debug://auth/%6fauth2redirect",
            "org.example.debug://user@auth/oauth2redirect",
            "org.example.debug://auth:80/oauth2redirect",
            "org.example.debug://auth/oauth2redirect#code=fixture",
            "not a uri",
        ).forEach { assertFalse(it, configuration.acceptsCallback(it)) }
    }

    @Test
    fun hostlessCallbackStillChecksPathEvenThoughManifestCannot() {
        val configuration = OneDriveOAuthConfiguration("fixture", "org.example.debug:/oauth2redirect")
        assertTrue(configuration.acceptsCallback("org.example.debug:/oauth2redirect?state=fixture"))
        assertFalse(configuration.acceptsCallback("org.example.debug:/different?state=fixture"))
        assertFalse(configuration.acceptsCallback("org.example.debug://auth/oauth2redirect"))
    }
}
