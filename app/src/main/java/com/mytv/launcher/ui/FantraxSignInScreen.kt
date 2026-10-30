package com.mytv.launcher.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.mytv.launcher.fantasy.FantasyLeague

/**
 * Fantrax's own sign-in page, once: Fantrax only shows the league's live
 * scoring to its members. The sign-in stays in the TV's web cookies, which
 * the fantasy panel uses.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FantraxSignInScreen(onPageLoaded: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(LocalBackground.current).padding(horizontal = 48.dp, vertical = 20.dp)) {
        Text(
            "Sign in to Fantrax with your email and password (\"Sign in with Google\" doesn't work on a TV). Back returns to settings.",
            fontSize = 14.sp,
            color = Palette.InkSoft,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) = onPageLoaded(url)
                    }
                    loadUrl(FantasyLeague.LOGIN_URL)
                    requestFocus()
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
