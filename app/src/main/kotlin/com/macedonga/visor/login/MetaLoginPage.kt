package com.macedonga.visor.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
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
import com.macedonga.visor.core.identity.token.META_REDIRECT_URI
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(val login: MetaLogin) : ViewModel()

/** Meta's login page, in a WebView the app owns, and nothing else. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MetaLoginPage(onDone: () -> Unit, model: LoginViewModel = hiltViewModel()) {
    val request = model.login.pending
    if (request == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val prefix = "$META_REDIRECT_URI?"
    AndroidView(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { context ->
            WebView(context).apply {
                // AndroidView leaves a View's layout params at wrap content, and a WebView that
                // wraps its height sizes the page's viewport to the page's own content.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
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
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        take(request.url.toString())

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (take(url)) view.stopLoading() else super.onPageStarted(view, url, favicon)
                    }
                }
                loadUrl(request.url)
            }
        },
        onRelease = { it.destroy() },
    )
}
