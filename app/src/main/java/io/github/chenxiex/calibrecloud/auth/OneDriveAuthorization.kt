package io.github.chenxiex.calibrecloud.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import android.provider.Settings
import androidx.core.net.toUri
import io.github.chenxiex.calibrecloud.auth.AuthorizationTransactionGate.CallbackResult
import io.github.chenxiex.calibrecloud.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import net.openid.appauth.browser.BrowserSelector
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/** Only authorization endpoints are used here; file access belongs to the storage backend. */
class OneDriveAuthorization(
    private val context: Context,
    private val configuration: OneDriveOAuthConfiguration?,
    private val store: AuthStateStore,
    private val platform: OAuthPlatform = AppAuthPlatform(context),
) {
    private val mutex = Mutex()
    private var loaded = false
    private var auth = AuthState()
    private var authorizationSession: UUID? = null
    private var authorizedSubject: String? = null
    private var pending: AuthorizationRequest? = null
    private var created = 0L
    private var operationId = UUID.randomUUID().toString()
    private var boot = 0
    private fun currentBoot(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    var status = LoginStatus.UNSIGNED
        private set
    var issue: LoginIssue? = null
        private set

    suspend fun restore() = mutex.withLock {
        if (!loaded) recover()
        if (pending != null && (currentBoot() != boot || SystemClock.elapsedRealtime() - created !in 0 until 600_000)) {
            pending = null
            issue = LoginIssue.EXPIRED
            status = if (auth.isAuthorized) LoginStatus.AUTHORIZED else LoginStatus.UNSIGNED
            try { persist() } catch (_: Exception) { failure(LoginIssue.STORAGE) }
        }
    }

    suspend fun begin(): Intent? = mutex.withLock {
        if (!loaded) recover()
        val config = configuration ?: return@withLock null
        try {
            val request = AuthorizationRequest.Builder(
                AuthorizationServiceConfiguration(config.authorizationEndpoint.toUri(), config.tokenEndpoint.toUri()),
                config.clientId, ResponseTypeValues.CODE, config.redirectUri.toUri(),
            ).setScopes(config.scopes).build()
            val intent = withContext(Dispatchers.IO) { platform.browserIntent(request) }
                ?: run { issue = LoginIssue.NO_BROWSER; return@withLock null }
            pending = request
            created = SystemClock.elapsedRealtime()
            boot = currentBoot()
            operationId = UUID.randomUUID().toString()
            issue = null
            status = LoginStatus.BROWSER
            persist()
            log("browser")
            intent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(LoginIssue.STORAGE)
            null
        }
    }

    suspend fun cancel(problem: LoginIssue = LoginIssue.CANCELED) = mutex.withLock {
        if (!loaded) recover()
        pending = null
        issue = problem
        status = if (auth.isAuthorized) LoginStatus.AUTHORIZED else LoginStatus.UNSIGNED
        try { persist() } catch (_: Exception) { failure(LoginIssue.STORAGE) }
        log(problem.name)
    }

    suspend fun callback(address: String, onExchange: () -> Unit = {}) = mutex.withLock {
        if (!loaded) recover()
        val config = configuration ?: return@withLock
        val request = pending
        val gate = AuthorizationTransactionGate(config)
        if (request != null && currentBoot() == boot) gate.begin(request.state!!, created)
        when (val result = gate.consumeCallback(address, SystemClock.elapsedRealtime())) {
            is CallbackResult.Rejected -> {
                issue = LoginIssue.CALLBACK
                log("callback_rejected")
            }
            is CallbackResult.OAuthError -> {
                pending = null
                issue = if (result.error == "access_denied") LoginIssue.CANCELED else LoginIssue.SERVER
                status = if (auth.isAuthorized) LoginStatus.AUTHORIZED else LoginStatus.UNSIGNED
                try { persist() } catch (_: Exception) { failure(LoginIssue.STORAGE) }
                log("authorization_failed")
            }
            is CallbackResult.Code -> {
                // Persist consumption before network I/O. A crash must never redeem the code twice.
                pending = null
                status = LoginStatus.EXCHANGING
                try {
                    persist(interrupted = true)
                    onExchange()
                    val response = AuthorizationResponse.Builder(request!!).fromUri(address.toUri()).build()
                    val next = AuthState(response, null)
                    val (token, error) = exchange(response.createTokenExchangeRequest())
                    next.update(token, error)
                    if (error != null || !next.isAuthorized) {
                        failure(classifyAuthorizationFailure(error))
                    } else {
                        auth = next
                        authorizationSession = UUID.randomUUID()
                        authorizedSubject = next.parsedIdToken?.subject?.takeIf { it.isNotBlank() }
                        status = LoginStatus.AUTHORIZED
                        issue = null
                    }
                    persist()
                    log(if (status == LoginStatus.AUTHORIZED) "authorized" else "exchange_failed")
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    failure(LoginIssue.NETWORK)
                    try { persist() } catch (_: Exception) { failure(LoginIssue.STORAGE) }
                } catch (cancelled: CancellationException) {
                    status = LoginStatus.RELOGIN
                    issue = LoginIssue.RELOGIN
                    throw cancelled
                } catch (_: Exception) {
                    failure(LoginIssue.STORAGE)
                }
            }
        }
    }

    /** Non-secret identity for binding queued requests to one successful login, preserved by refresh. */
    suspend fun sessionId(): UUID? = mutex.withLock {
        if (!loaded) recover()
        authorizationSession.takeIf { auth.isAuthorized && status != LoginStatus.RELOGIN }
    }

    /**
     * Trusted storage callers may bind a drive to the subject from the authorized ID token.
     * Production AppAuth validates ID tokens received from the configured HTTPS token endpoint
     * before accepting its response. The subject is preserved beside the login generation in
     * the same encrypted envelope because refresh responses may omit the original ID token.
     * No refresh occurs here, and older authorizations without an ID token return null.
     * The subject must never be logged or treated as a display name.
     */
    suspend fun accountSubject(expectedSession: UUID? = null): String? = mutex.withLock {
        if (!loaded) recover()
        if (expectedSession != null && authorizationSession != expectedSession) return@withLock null
        if (!auth.isAuthorized || status == LoginStatus.RELOGIN) return@withLock null
        authorizedSubject
    }

    /** Trusted storage callers alone consume this token; it must never be persisted or logged outside AuthState. */
    suspend fun backendAccessToken(forceRefresh: Boolean = false, expectedSession: UUID? = null): String? = mutex.withLock {
        if (!loaded) recover()
        if (expectedSession != null && authorizationSession != expectedSession) return@withLock null
        if (!auth.isAuthorized || status == LoginStatus.RELOGIN) {
            issue = LoginIssue.RELOGIN
            return@withLock null
        }
        if (forceRefresh || auth.needsTokenRefresh || auth.accessToken == null) {
            if (!refreshLocked()) return@withLock null
        }
        auth.accessToken
    }

    /** UI refresh is explicit; backend token access also refreshes only when required. */
    suspend fun refresh(onExchange: () -> Unit = {}) = mutex.withLock {
        if (!loaded) recover()
        if (!auth.isAuthorized || status == LoginStatus.RELOGIN) return@withLock
        refreshLocked(onExchange)
    }

    private suspend fun refreshLocked(onExchange: () -> Unit = {}): Boolean {
        status = LoginStatus.EXCHANGING
        onExchange()
        try {
            val (token, error) = exchange(auth.createTokenRefreshRequest())
            if (error != null || token?.accessToken == null) {
                val problem = classifyAuthorizationFailure(error)
                // Transient endpoint failures must not invalidate the existing refresh credential.
                if (problem == LoginIssue.RELOGIN) auth.update(token, error)
                failure(problem)
            } else {
                auth.update(token, null)
                status = LoginStatus.AUTHORIZED
                issue = null
            }
            persist()
            return issue == null && status == LoginStatus.AUTHORIZED
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            failure(LoginIssue.NETWORK)
        } catch (cancelled: CancellationException) {
            status = if (auth.isAuthorized) LoginStatus.AUTHORIZED else LoginStatus.UNSIGNED
            throw cancelled
        } catch (_: IOException) {
            failure(LoginIssue.NETWORK)
        } catch (_: AuthStateStorageException) {
            failure(LoginIssue.STORAGE)
        } catch (_: Exception) {
            failure(LoginIssue.RELOGIN)
        }
        return false
    }

    private suspend fun exchange(request: TokenRequest): Pair<TokenResponse?, AuthorizationException?> = withTimeout(60_000) {
        platform.exchange(request)
    }

    private suspend fun recover() {
        loaded = true
        try {
            val raw = withContext(Dispatchers.IO) { store.read() } ?: return
            val data = JSONObject(raw)
            if (data.getString("client") != configuration?.clientId ||
                data.getString("redirect") != configuration?.redirectUri) {
                withContext(Dispatchers.IO) { store.clear() }
                failure(LoginIssue.RELOGIN)
                return
            }
            auth = AuthState.jsonDeserialize(data.getString("auth"))
            authorizationSession = data.optString("session").takeIf { it.isNotEmpty() }?.let(UUID::fromString)
            authorizedSubject = data.optString("subject").takeIf { it.isNotBlank() }
                ?: auth.parsedIdToken?.subject?.takeIf { it.isNotBlank() }
            pending = if (data.has("pending")) AuthorizationRequest.jsonDeserialize(data.getString("pending")) else null
            created = data.optLong("created")
            boot = data.optInt("boot", -1)
            operationId = data.getString("operation")
            status = when {
                data.optBoolean("interrupted") || data.optString("status") == LoginStatus.RELOGIN.name -> LoginStatus.RELOGIN
                pending != null -> LoginStatus.BROWSER
                auth.isAuthorized -> LoginStatus.AUTHORIZED
                else -> LoginStatus.UNSIGNED
            }
            issue = if (data.has("issue")) LoginIssue.valueOf(data.getString("issue")) else null
            if (status == LoginStatus.RELOGIN && issue == null) issue = LoginIssue.RELOGIN
            // Migrate previously authorized envelopes without keeping a second credential store.
            if (auth.isAuthorized && status != LoginStatus.RELOGIN &&
                (authorizationSession == null || (!data.has("subject") && authorizedSubject != null))) {
                if (authorizationSession == null) authorizationSession = UUID.randomUUID()
                persist()
            }
        } catch (cancelled: CancellationException) {
            loaded = false
            throw cancelled
        } catch (_: Exception) {
            failure(LoginIssue.STORAGE)
            try { withContext(Dispatchers.IO) { store.clear() } } catch (_: Exception) { /* Retry can report storage failure. */ }
        }
    }

    private suspend fun persist(interrupted: Boolean = false) {
        val data = JSONObject().put("auth", auth.jsonSerializeString())
            .put("client", configuration?.clientId).put("redirect", configuration?.redirectUri)
            .put("status", status.name).put("issue", issue?.name)
            .put("created", created).put("boot", boot).put("operation", operationId).put("interrupted", interrupted)
        authorizationSession?.let { data.put("session", it.toString()) }
        authorizedSubject?.let { data.put("subject", it) }
        pending?.let { data.put("pending", it.jsonSerializeString()) }
        withContext(Dispatchers.IO) { store.write(data.toString()) }
    }

    private fun failure(problem: LoginIssue) {
        pending = null
        issue = problem
        status = when (problem) {
            LoginIssue.NETWORK, LoginIssue.SERVER -> if (auth.isAuthorized) LoginStatus.AUTHORIZED else LoginStatus.UNSIGNED
            else -> LoginStatus.RELOGIN
        }
        log(problem.name)
    }

    private fun log(stage: String) {
        val message = "operation=$operationId stage=$stage"
        if (BuildConfig.DEBUG) Log.d("OneDriveAuth", message)
        else if (issue != null) Log.w("OneDriveAuth", message)
    }

    fun close() { platform.close() }
}

enum class LoginStatus { UNSIGNED, BROWSER, EXCHANGING, AUTHORIZED, RELOGIN }
enum class LoginIssue { CANCELED, NETWORK, NO_BROWSER, SERVER, CALLBACK, STORAGE, RELOGIN, EXPIRED }

internal fun classifyAuthorizationFailure(error: AuthorizationException?): LoginIssue = when {
    error == null -> LoginIssue.SERVER
    error.type == AuthorizationException.TYPE_GENERAL_ERROR &&
        error.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code -> LoginIssue.NETWORK
    error.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && error.error == "invalid_grant" -> LoginIssue.RELOGIN
    else -> LoginIssue.SERVER
}

/** Injectable protocol/browser adapter; production uses AppAuth, tests supply deterministic outcomes. */
interface OAuthPlatform {
    fun browserIntent(request: AuthorizationRequest): Intent?
    suspend fun exchange(request: TokenRequest): Pair<TokenResponse?, AuthorizationException?>
    fun close()
}

private class AppAuthPlatform(private val context: Context) : OAuthPlatform {
    private var service: AuthorizationService? = null
    override fun browserIntent(request: AuthorizationRequest): Intent? {
        val browser = BrowserSelector.getAllBrowsers(context).firstOrNull() ?: return null
        return Intent(Intent.ACTION_VIEW, request.toUri()).setPackage(browser.packageName)
            .addCategory(Intent.CATEGORY_BROWSABLE)
    }

    override suspend fun exchange(request: TokenRequest): Pair<TokenResponse?, AuthorizationException?> =
        suspendCancellableCoroutine { continuation ->
            val active = service ?: AuthorizationService(context).also { service = it }
            active.performTokenRequest(request) { response, error ->
                if (continuation.isActive) continuation.resume(response to error)
            }
        }

    override fun close() { service?.dispose() }
}

/** Carries only the expected login generation through a scheduled source operation, never a token. */
class OneDriveAuthorizationSession(val id: UUID) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<OneDriveAuthorizationSession>
}
