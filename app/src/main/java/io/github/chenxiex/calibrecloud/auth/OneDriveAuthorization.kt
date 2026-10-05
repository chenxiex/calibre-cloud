package io.github.chenxiex.calibrecloud.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.SystemClock
import android.provider.Settings
import io.github.chenxiex.calibrecloud.auth.AuthorizationTransactionGate.CallbackResult
import io.github.chenxiex.calibrecloud.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import net.openid.appauth.browser.BrowserSelector
import org.json.JSONObject
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
                AuthorizationServiceConfiguration(Uri.parse(config.authorizationEndpoint), Uri.parse(config.tokenEndpoint)),
                config.clientId, ResponseTypeValues.CODE, Uri.parse(config.redirectUri),
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
                    val response = AuthorizationResponse.Builder(request!!).fromUri(Uri.parse(address)).build()
                    val next = AuthState(response, null)
                    val (token, error) = exchange(response.createTokenExchangeRequest())
                    next.update(token, error)
                    if (error != null || !next.isAuthorized) {
                        failure(classifyAuthorizationFailure(error))
                    } else {
                        auth = next
                        status = LoginStatus.AUTHORIZED
                        issue = null
                    }
                    persist()
                    log(if (status == LoginStatus.AUTHORIZED) "authorized" else "exchange_failed")
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    failure(LoginIssue.NETWORK)
                    try { persist() } catch (_: Exception) { failure(LoginIssue.STORAGE) }
                } catch (_: Exception) {
                    failure(LoginIssue.STORAGE)
                }
            }
        }
    }

    /** Refresh is explicit, asynchronous and does not contact Graph or run on every resume. */
    suspend fun refresh(onExchange: () -> Unit = {}) = mutex.withLock {
        if (!loaded) recover()
        if (!auth.isAuthorized) return@withLock
        status = LoginStatus.EXCHANGING
        onExchange()
        try {
            val (token, error) = exchange(auth.createTokenRefreshRequest())
            auth.update(token, error)
            if (error != null) failure(classifyAuthorizationFailure(error))
            else { status = LoginStatus.AUTHORIZED; issue = null }
            persist()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            failure(LoginIssue.NETWORK)
        } catch (_: Exception) {
            failure(LoginIssue.RELOGIN)
        }
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
