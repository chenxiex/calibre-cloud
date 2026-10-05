package io.github.chenxiex.calibrecloud.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationTransactionGateTest {
    private val callback = "org.example.debug://auth/oauth2redirect"
    private val configuration = OneDriveOAuthConfiguration("fixture", callback)

    @Test
    fun validCallbackConsumesTransactionBeforeExchangeAndRejectsDuplicate() {
        val gate = AuthorizationTransactionGate(configuration)
        gate.begin("fixture state", 100)
        val address = "$callback?code=fixture%2Bcode&state=fixture+state"
        val result = gate.consumeCallback(address, 101) as AuthorizationTransactionGate.CallbackResult.Code
        assertEquals("fixture+code", result.code)
        assertRejected(AuthorizationTransactionGate.Rejection.NO_PENDING, gate.consumeCallback(address, 102))
    }

    @Test
    fun unrelatedAddressAndStateLeavePendingTransactionAvailable() {
        val gate = AuthorizationTransactionGate(configuration)
        gate.begin("expected", 100)
        assertRejected(
            AuthorizationTransactionGate.Rejection.ADDRESS_MISMATCH,
            gate.consumeCallback("org.example.debug://auth/other?code=fixture&state=expected", 101),
        )
        assertRejected(
            AuthorizationTransactionGate.Rejection.STATE_MISMATCH,
            gate.consumeCallback("$callback?code=fixture&state=other", 102),
        )
        assertTrue(gate.consumeCallback("$callback?code=fixture&state=expected", 103) is AuthorizationTransactionGate.CallbackResult.Code)
    }

    @Test
    fun ambiguousOrMissingProtocolParametersNeverReachExchange() {
        listOf(
            "code=fixture", "code=fixture&state=expected&state=expected",
            "code=fixture&%73tate=expected&state=expected",
            "code=fixture&code=fixture&state=expected",
            "error=access_denied&error=access_denied&state=expected",
            "code=fixture&error=access_denied&state=expected",
            "state=expected", "code=&state=expected", "error=&state=expected",
        ).forEach { query ->
            val gate = AuthorizationTransactionGate(configuration)
            gate.begin("expected", 100)
            assertRejected(AuthorizationTransactionGate.Rejection.INVALID_PARAMETERS, gate.consumeCallback("$callback?$query", 101))
            assertTrue(gate.cancel())
        }
    }

    @Test
    fun oauthErrorConsumesOnlyMatchingTransaction() {
        val gate = AuthorizationTransactionGate(configuration)
        gate.begin("expected", 100)
        val result = gate.consumeCallback("$callback?error=access_denied&state=expected&error_description=ignored", 101)
            as AuthorizationTransactionGate.CallbackResult.OAuthError
        assertEquals("access_denied", result.error)
        assertFalse(gate.cancel())
    }

    @Test
    fun expiryAndCancellationPreventLaterDelivery() {
        val gate = AuthorizationTransactionGate(configuration, lifetimeMillis = 10)
        val address = "$callback?code=fixture&state=expected"
        gate.begin("expected", 100)
        assertRejected(AuthorizationTransactionGate.Rejection.EXPIRED, gate.consumeCallback(address, 110))
        assertRejected(AuthorizationTransactionGate.Rejection.NO_PENDING, gate.consumeCallback(address, 111))
        gate.begin("expected", 112)
        assertTrue(gate.cancel())
        assertRejected(AuthorizationTransactionGate.Rejection.NO_PENDING, gate.consumeCallback(address, 113))
    }

    @Test
    fun hostlessCallbackChecksExactPathAndFragment() {
        val gate = AuthorizationTransactionGate(OneDriveOAuthConfiguration("fixture", "org.example.debug:/oauth2redirect"))
        gate.begin("expected", 100)
        listOf(
            "org.example.debug:/other?code=fixture&state=expected",
            "org.example.debug:/oauth2redirect?code=fixture&state=expected#fragment",
        ).forEach { assertRejected(AuthorizationTransactionGate.Rejection.ADDRESS_MISMATCH, gate.consumeCallback(it, 101)) }
        assertTrue(gate.consumeCallback("org.example.debug:/oauth2redirect?code=fixture&state=expected", 102) is AuthorizationTransactionGate.CallbackResult.Code)
    }

    private fun assertRejected(reason: AuthorizationTransactionGate.Rejection, result: AuthorizationTransactionGate.CallbackResult) {
        assertEquals(AuthorizationTransactionGate.CallbackResult.Rejected(reason), result)
    }
}
