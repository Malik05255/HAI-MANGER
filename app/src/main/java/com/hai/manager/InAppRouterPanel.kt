package com.hai.manager

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppRouterPanel(
    url: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember(context, url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                    loading = true
                    currentUrl = pageUrl.orEmpty()
                    canGoBack = view?.canGoBack() == true
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    CookieManager.getInstance().flush()
                    loading = false
                    currentUrl = pageUrl.orEmpty()
                    canGoBack = view?.canGoBack() == true
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(url)
        }
    }

    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
            canGoBack = webView.canGoBack()
        } else {
            CookieManager.getInstance().flush()
            onClose()
        }
    }

    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("إعدادات الراوتر", style = MaterialTheme.typography.titleLarge)
        Text(
            currentUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            maxLines = 1
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (webView.canGoBack()) {
                        webView.goBack()
                        canGoBack = webView.canGoBack()
                    }
                },
                enabled = canGoBack,
                modifier = Modifier.weight(1f)
            ) { Text("رجوع") }
            OutlinedButton(
                onClick = { webView.reload() },
                modifier = Modifier.weight(1f)
            ) { Text("تحديث") }
            Button(
                onClick = {
                    CookieManager.getInstance().flush()
                    onClose()
                },
                modifier = Modifier.weight(1f)
            ) { Text("إغلاق") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.url.isNullOrBlank()) view.loadUrl(url)
            }
        )
    }
}
