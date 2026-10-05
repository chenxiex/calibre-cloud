package io.github.chenxiex.calibrecloud.auth

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real coordinator and AppAuth parsing with deterministic browser and token adapters. */
@RunWith(AndroidJUnit4::class)
class OneDriveAuthorizationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configuration = OneDriveOAuthConfiguration("test-client", "test-debug:/oauth2redirect")

    @Test
    fun matchingCallbackExchangesOnceAndAuthorizationSurvivesRecreation() = runBlocking {
        val store = MemoryStore()
        val platform = FakePlatform()
        val authorization = coordinator(store, platform)
        assertNotNull(authorization.begin())
        assertEquals(LoginStatus.BROWSER, authorization.status)
        val callback = callback(platform)
        authorization.callback(callback)
        assertEquals(LoginStatus.AUTHORIZED, authorization.status)
        assertNull(authorization.issue)
        assertEquals(1, platform.exchanges)
        assertEquals(platform.request!!.codeVerifier, platform.tokenRequest!!.codeVerifier)
        assertEquals("test-code", platform.tokenRequest!!.authorizationCode)
        authorization.callback(callback)
        assertEquals(1, platform.exchanges)
        val restored = coordinator(store, FakePlatform())
        restored.restore()
        assertEquals(LoginStatus.AUTHORIZED, restored.status)
    }

    @Test
    fun rejectedCallbacksDoNotExchangeOrDiscardMatchingPendingRequest() = runBlocking {
        val platform = FakePlatform()
        val authorization = coordinator(MemoryStore(), platform)
        authorization.begin()
        listOf(
            "test-debug:/other?code=test-code&state=${platform.request!!.state}",
            "test-debug:/oauth2redirect?code=test-code&state=wrong-state",
            "${callback(platform)}&state=${platform.request!!.state}",
        ).forEach { address ->
            authorization.callback(address)
            assertEquals(LoginIssue.CALLBACK, authorization.issue)
            assertEquals(0, platform.exchanges)
        }
        authorization.callback(callback(platform))
        assertEquals(1, platform.exchanges)
        assertEquals(LoginStatus.AUTHORIZED, authorization.status)
    }

    @Test
    fun cancellationPersistsAndRejectsLaterBrowserDelivery() = runBlocking {
        val store = MemoryStore()
        val platform = FakePlatform()
        val authorization = coordinator(store, platform)
        authorization.begin()
        val address = callback(platform)
        authorization.cancel()
        assertEquals(LoginStatus.UNSIGNED, authorization.status)
        assertEquals(LoginIssue.CANCELED, authorization.issue)
        assertFalse(JSONObject(store.state!!).has("pending"))
        val restoredPlatform = FakePlatform()
        coordinator(store, restoredPlatform).callback(address)
        assertEquals(0, restoredPlatform.exchanges)
    }

    @Test
    fun oauthErrorsAreClassifiedWithoutExchanging() = runBlocking {
        listOf("access_denied" to LoginIssue.CANCELED, "server_error" to LoginIssue.SERVER).forEach { (error, issue) ->
            val store = MemoryStore()
            val platform = FakePlatform()
            val authorization = coordinator(store, platform)
            authorization.begin()
            authorization.callback(callback(platform, "error", error))
            assertEquals(issue, authorization.issue)
            assertEquals(LoginStatus.UNSIGNED, authorization.status)
            assertEquals(0, platform.exchanges)
            assertFalse(JSONObject(store.state!!).has("pending"))
        }
    }

    @Test
    fun networkAndInvalidGrantTokenErrorsRequireAnExplicitRetry() = runBlocking {
        listOf(
            AuthorizationException.GeneralErrors.NETWORK_ERROR to LoginIssue.NETWORK,
            AuthorizationException.TokenRequestErrors.INVALID_GRANT to LoginIssue.RELOGIN,
        ).forEach { (error, issue) ->
            val platform = FakePlatform().apply { tokenError = error }
            val authorization = coordinator(MemoryStore(), platform)
            authorization.begin()
            authorization.callback(callback(platform))
            assertEquals(issue, authorization.issue)
            assertEquals(if (issue == LoginIssue.NETWORK) LoginStatus.UNSIGNED else LoginStatus.RELOGIN, authorization.status)
            assertEquals(1, platform.exchanges)
        }
    }

    @Test
    fun pendingRequestSurvivesCoordinatorRecreationWithOriginalPkceVerifier() = runBlocking {
        val store = MemoryStore()
        val browserPlatform = FakePlatform()
        coordinator(store, browserPlatform).begin()
        val tokenPlatform = FakePlatform()
        val restored = coordinator(store, tokenPlatform)
        restored.restore()
        assertEquals(LoginStatus.BROWSER, restored.status)
        restored.callback(callback(browserPlatform))
        assertEquals(LoginStatus.AUTHORIZED, restored.status)
        assertEquals(browserPlatform.request!!.codeVerifier, tokenPlatform.tokenRequest!!.codeVerifier)
        assertEquals(1, tokenPlatform.exchanges)
    }

    @Test
    fun codeConsumptionIsDurableBeforeExchangeEvenIfProcessStopsDuringExchange() = runBlocking {
        val store = MemoryStore()
        val platform = FakePlatform().apply {
            beforeExchange = {
                val persisted = JSONObject(store.state!!)
                assertFalse(persisted.has("pending"))
                assertTrue(persisted.getBoolean("interrupted"))
                throw SimulatedProcessStop()
            }
        }
        val authorization = coordinator(store, platform)
        authorization.begin()
        val address = callback(platform)
        var stopped = false
        try {
            authorization.callback(address)
        } catch (_: SimulatedProcessStop) {
            stopped = true
        }
        assertTrue(stopped)
        val recoveredPlatform = FakePlatform()
        val recovered = coordinator(store, recoveredPlatform)
        recovered.restore()
        assertEquals(LoginStatus.RELOGIN, recovered.status)
        assertEquals(LoginIssue.RELOGIN, recovered.issue)
        recovered.callback(address)
        assertEquals(0, recoveredPlatform.exchanges)
    }

    @Test
    fun failedConsumptionWritePreventsTokenExchange() = runBlocking {
        val store = MemoryStore()
        val platform = FakePlatform()
        val authorization = coordinator(store, platform)
        authorization.begin()
        store.failWrites = true
        authorization.callback(callback(platform))
        assertEquals(0, platform.exchanges)
        assertEquals(LoginIssue.STORAGE, authorization.issue)
        assertEquals(LoginStatus.RELOGIN, authorization.status)
    }

    @Test
    fun missingBrowserCreatesNoPendingTransactionAndRetryCanAuthorize() = runBlocking {
        val store = MemoryStore()
        val platform = FakePlatform().apply { browserAvailable = false }
        val authorization = coordinator(store, platform)
        assertNull(authorization.begin())
        assertEquals(LoginIssue.NO_BROWSER, authorization.issue)
        assertEquals(LoginStatus.UNSIGNED, authorization.status)
        assertNull(store.state)
        assertEquals(0, platform.exchanges)
        val restored = coordinator(store, platform)
        restored.restore()
        assertEquals(LoginStatus.UNSIGNED, restored.status)
        platform.browserAvailable = true
        assertNotNull(restored.begin())
        assertEquals(LoginStatus.BROWSER, restored.status)
        assertNull(restored.issue)
        restored.callback(callback(platform))
        assertEquals(LoginStatus.AUTHORIZED, restored.status)
        assertEquals(1, platform.exchanges)
    }

    @Test
    fun refreshInvalidGrantRequiresReloginAndStopsFurtherRefreshAfterRecreation() = runBlocking {
        val store = MemoryStore()
        val platform = FakePlatform()
        val authorization = coordinator(store, platform)
        authorization.begin()
        authorization.callback(callback(platform))
        assertEquals(LoginStatus.AUTHORIZED, authorization.status)
        platform.tokenError = AuthorizationException.TokenRequestErrors.INVALID_GRANT
        authorization.refresh()
        assertEquals("refresh_token", platform.tokenRequest!!.grantType)
        assertEquals("test-refresh-token", platform.tokenRequest!!.refreshToken)
        assertEquals(LoginIssue.RELOGIN, authorization.issue)
        assertEquals(LoginStatus.RELOGIN, authorization.status)
        assertEquals(2, platform.exchanges)
        authorization.refresh()
        assertEquals(2, platform.exchanges)
        val recoveredPlatform = FakePlatform()
        val recovered = coordinator(store, recoveredPlatform)
        recovered.restore()
        assertTrue(recovered.status != LoginStatus.AUTHORIZED)
        recovered.refresh()
        assertEquals(0, recoveredPlatform.exchanges)
        assertNotNull(recovered.begin())
        assertEquals(LoginStatus.BROWSER, recovered.status)
    }

    @Test
    fun corruptCredentialEnvelopeIsClearedWithoutStartingBrowserOrExchange() = runBlocking {
        val store = MemoryStore().apply { state = "invalid-credential-envelope" }
        val platform = FakePlatform()
        val authorization = coordinator(store, platform)
        authorization.restore()
        assertEquals(LoginIssue.STORAGE, authorization.issue)
        assertEquals(LoginStatus.RELOGIN, authorization.status)
        assertEquals(1, store.clears)
        assertNull(store.state)
        assertNull(platform.request)
        assertEquals(0, platform.exchanges)
        authorization.restore()
        assertEquals(1, store.clears)
        assertNotNull(authorization.begin())
        authorization.callback(callback(platform))
        assertEquals(LoginStatus.AUTHORIZED, authorization.status)
        assertEquals(1, store.clears)
    }

    private fun coordinator(store: AuthStateStore, platform: OAuthPlatform) =
        OneDriveAuthorization(context, configuration, store, platform)

    private fun callback(platform: FakePlatform, parameter: String = "code", value: String = "test-code"): String =
        Uri.parse(configuration.redirectUri).buildUpon()
            .appendQueryParameter("state", platform.request!!.state)
            .appendQueryParameter(parameter, value)
            .build().toString()

    private class MemoryStore : AuthStateStore {
        var state: String? = null
        var failWrites = false
        var clears = 0

        override fun read(): String? = state

        override fun write(serializedState: String) {
            if (failWrites) throw AuthStateStorageException()
            state = serializedState
        }

        override fun clear() {
            clears += 1
            state = null
        }
    }

    private class FakePlatform : OAuthPlatform {
        var request: AuthorizationRequest? = null
        var tokenRequest: TokenRequest? = null
        var exchanges = 0
        var tokenError: AuthorizationException? = null
        var beforeExchange: (() -> Unit)? = null
        var browserAvailable = true

        override fun browserIntent(request: AuthorizationRequest): Intent? {
            this.request = request
            return if (browserAvailable) Intent(Intent.ACTION_VIEW, request.toUri()) else null
        }

        override suspend fun exchange(request: TokenRequest): Pair<TokenResponse?, AuthorizationException?> {
            exchanges += 1
            tokenRequest = request
            beforeExchange?.invoke()
            tokenError?.let { return null to it }
            return TokenResponse.Builder(request)
                .setTokenType("Bearer")
                .setAccessToken("test-access-token")
                .setRefreshToken("test-refresh-token")
                .build() to null
        }

        override fun close() = Unit
    }

    /** Errors bypass the coordinator's ordinary exception handling, like abrupt process loss. */
    private class SimulatedProcessStop : Error()
}
