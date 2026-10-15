package com.macedonga.visor.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.core.identity.token.META_REDIRECT_URI
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(val login: MetaLogin) : ViewModel()

/**
 * Meta's login page, in a WebView the app owns, and nothing else. Everything the page does goes to the event
 * log, so one log from a phone that loops at the login says where it looped: every request to Meta, every
 * screen the page shows, every error, and what this WebView is.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MetaLoginPage(onDone: () -> Unit, model: LoginViewModel = hiltViewModel()) {
    val request = model.login.pending
    if (request == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val prefix = "$META_REDIRECT_URI?"
    val note = model.login::note
    AndroidView(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { context ->
            WebView(context).apply {
                // AndroidView leaves a View's layout params at wrap content, and a WebView that
                // wraps its height sizes the page's viewport to the page's own content.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.lookLikeChrome(note)
                describe(context, note)
                addJavascriptInterface(PageBridge(note), "VisorLog")
                var caught = false
                fun take(url: String): Boolean {
                    if (!url.startsWith(prefix)) return false
                    if (!caught) {
                        caught = true
                        model.login.finish(url)
                        onDone()
                    }
                    return true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (take(url)) return true
                        note("navigating to ${request.url.place()}" + (if (request.isRedirect) " (redirect)" else "") + (if (request.hasGesture()) " (tap)" else ""))
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!request.isForMainFrame) request.url.described()?.let { note("${request.method} $it") }
                        return null
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (take(url)) view.stopLoading() else note("loading ${Uri.parse(url).place()}, cookies ${cookieNames(url)}")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        note("loaded ${Uri.parse(url).place()}, cookies ${cookieNames(url)}")
                        view.evaluateJavascript(WATCH, null)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        note("HTTP ${response.statusCode} for ${request.url.described() ?: request.url.place()}")
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        note("${error.description} (${error.errorCode}) for ${request.url.described() ?: request.url.place()}")
                    }

                    // Without this a renderer the system reclaims takes the whole app down with it.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        note(if (detail.didCrash()) "the page crashed" else "the system reclaimed the page")
                        if (!caught) {
                            caught = true
                            onDone()
                        }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val text = message.message()
                        if (message.messageLevel() != ConsoleMessage.MessageLevel.LOG && "Permissions-Policy" !in text) {
                            note("console ${message.messageLevel().name.lowercase()}: ${text.take(200)}")
                        }
                        return true
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        if (!title.isNullOrBlank()) note("title: $title")
                    }
                }
                note("opening ${Uri.parse(request.url).place()}")
                loadUrl(request.url)
            }
        },
        onRelease = {
            model.login.note("page closed")
            it.destroy()
        },
    )
}

/** The page reports its own screens through this: the heading changes as the Bloks flow moves on. */
private class PageBridge(private val sink: (String) -> Unit) {
    @JavascriptInterface
    fun note(text: String) {
        sink(text.take(200))
    }
}

/** Host and path only: the query carries the code, the state and the login's own tokens. */
private fun Uri.place(): String = "${host.orEmpty()}${path.orEmpty()}"

/** A request worth a line: Meta's own endpoints, with the Bloks screen a fetch is for. Static files are not. */
private fun Uri.described(): String? {
    val host = host.orEmpty()
    val path = path.orEmpty()
    if (!(host.endsWith("meta.com") || host.endsWith("facebook.com") || host.endsWith("instagram.com"))) return null
    if (host.startsWith("static.") || path.startsWith("/rsrc.php") || path == "/ajax/bz" || path.startsWith("/favicon")) return null
    if (path.startsWith("/async/wbloks/fetch")) {
        return "bloks ${getQueryParameter("type") ?: "?"} ${getQueryParameter("appid") ?: "?"}"
    }
    return host + path
}

private fun cookieNames(url: String): String =
    CookieManager.getInstance().getCookie(url)?.split(';')?.joinToString(" ") { it.substringBefore('=').trim() }?.ifEmpty { null } ?: "none"

/** What this WebView is, on what phone, over what network: the things that differ between two users. */
private fun WebView.describe(context: Context, note: (String) -> Unit) {
    val pkg = WebView.getCurrentWebViewPackage()
    note("WebView ${pkg?.packageName ?: "unknown"} ${pkg?.versionName ?: ""}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, Visor ${BuildConfig.VERSION_NAME}, ${Locale.getDefault()}, network ${networkWords(context)}")
    note("user agent: ${settings.userAgentString}")
    note("cookies accepted ${CookieManager.getInstance().acceptCookie()}, third-party ${CookieManager.getInstance().acceptThirdPartyCookies(this)}")
}

private fun networkWords(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "none"
    val transports = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "wifi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
        NetworkCapabilities.TRANSPORT_VPN to "vpn",
    ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
    return transports + if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "" else " (not validated)"
}

/**
 * Meta's login treats an in-app page differently from a browser, and the markers are the user agent's `wv` and
 * `Version/4.0`, the `X-Requested-With` header naming the app, and the client hints naming Android WebView and
 * the phone. Present as Chrome on the same engine instead.
 */
private fun WebSettings.lookLikeChrome(note: (String) -> Unit) {
    userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
    val header = WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
    if (header) WebSettingsCompat.setRequestedWithHeaderOriginAllowList(this, emptySet())
    val hints = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
    if (hints) {
        val major = Regex("Chrome/(\\d+)").find(userAgentString)?.groupValues?.get(1) ?: "0"
        val full = Regex("Chrome/([\\d.]+)").find(userAgentString)?.groupValues?.get(1) ?: "0"
        val theirs = WebSettingsCompat.getUserAgentMetadata(this)
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Not:A-Brand").setMajorVersion("8").setFullVersion("8.0.0.0").build(),
        )
        WebSettingsCompat.setUserAgentMetadata(this, UserAgentMetadata.Builder(theirs).setBrandVersionList(brands).setModel("").build())
    }
    note("X-Requested-With ${if (header) "suppressed" else "not controllable on this WebView"}, client hints ${if (hints) "as Chrome" else "not controllable on this WebView"}")
}

/** Installed once per page: reports the heading of the screen and of any dialog as the Bloks flow moves on. */
private val WATCH = """
(function () {
  if (window.__visorWatch) return;
  window.__visorWatch = true;
  var last = '';
  function text(sel) {
    var el = document.querySelector(sel);
    return el ? (el.innerText || '').trim().split('\n')[0].slice(0, 80) : '';
  }
  function report() {
    var screen = text('h1, h2, [role="heading"]');
    var dialog = text('[role="dialog"] h1, [role="dialog"] h2, [role="dialog"] [role="heading"]');
    var now = screen + (dialog ? ' / dialog: ' + dialog : '');
    if (now && now !== last) { last = now; VisorLog.note('screen: ' + now); }
  }
  var timer = null;
  new MutationObserver(function () { clearTimeout(timer); timer = setTimeout(report, 300); })
    .observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  window.addEventListener('error', function (e) { VisorLog.note('js error: ' + (e.message || '').slice(0, 160)); });
  window.addEventListener('unhandledrejection', function (e) { VisorLog.note('js rejection: ' + String(e.reason || '').slice(0, 160)); });
  VisorLog.note('page: cookies ' + (navigator.cookieEnabled ? 'on' : 'off') + ', passkeys ' + (window.PublicKeyCredential ? 'yes' : 'no') + ', webotp ' + ('OTPCredential' in window ? 'yes' : 'no') + ', ' + window.innerWidth + 'x' + window.innerHeight);
  report();
})();
""".trimIndent()
