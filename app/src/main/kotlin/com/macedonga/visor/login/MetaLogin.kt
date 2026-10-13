package com.macedonga.visor.login

import android.content.Context
import android.webkit.CookieManager
import com.macedonga.visor.core.identity.KeystoreSealer
import com.macedonga.visor.core.identity.token.AuthorizeRequest
import com.macedonga.visor.core.identity.token.WebLoginSource
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The Meta login, for as long as the process lives. */
@Singleton
class MetaLogin @Inject constructor(
    @ApplicationContext context: Context,
    private val log: EventLog,
) {
    val source = WebLoginSource(
        cacheFile = File(context.filesDir, "meta_login.sealed"),
        sealer = KeystoreSealer(alias = "visor.meta.login"),
    )

    sealed interface Status {
        data object LoggedOut : Status
        data object Working : Status
        data object LoggedIn : Status
        data class Failed(val reason: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<Status>(Status.LoggedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The request the login page is showing, or null when no login is under way. */
    var pending: AuthorizeRequest? = null
        private set

    init {
        scope.launch { refresh() }
    }

    fun refresh() {
        if (_status.value is Status.Working) return
        _status.value = if (source.hasCachedLogin) Status.LoggedIn else Status.LoggedOut
    }

    fun begin(): AuthorizeRequest = source.begin().also {
        pending = it
        log.add("Login: opening Meta's login page")
    }

    /** The WebView caught the redirect. Exchange its code and cache the FRLA. */
    fun finish(redirect: String) {
        val request = pending ?: return
        pending = null
        _status.value = Status.Working
        log.add("Login: Meta redirected back, exchanging the code")
        scope.launch {
            _status.value = try {
                source.finish(redirect, request)
                log.add("Login: logged in to Meta")
                Status.LoggedIn
            } catch (error: Exception) {
                log.add("Login failed: ${error.message}")
                Status.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Forget the cached login and the page's session, so the next login starts clean. */
    fun logOut() {
        source.logOut()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _status.value = Status.LoggedOut
        log.add("Login: logged out of Meta")
    }
}
