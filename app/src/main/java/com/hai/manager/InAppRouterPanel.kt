package com.hai.manager

import android.annotation.SuppressLint
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppRouterPanel(
    url: String,
    onClose: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }
    val webView = remember(url) {
        WebViewHolder.webViewFactory(url) { pageUrl, isLoading ->
            currentUrl = pageUrl
            loading = isLoading
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else {
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
                onClick = { if (webView.canGoBack()) webView.goBack() },
                enabled = webView.canGoBack(),
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

private object WebViewHolder {
    @SuppressLint("SetJavaScriptEnabled")
    fun webViewFactory(
        url: String,
        onState: (String, Boolean) -> Unit
    ): WebView = WebView(AppContextHolder.requireContext()).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                onState(url.orEmpty(), true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                onState(url.orEmpty(), false)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        loadUrl(url)
    }
}

/**
 * يوفر Context التطبيق للـWebView من دون الاحتفاظ بـActivity.
 */
object AppContextHolder {
    @Volatile
    private var context: android.content.Context? = null

    fun init(context: android.content.Context) {
        this.context = context.applicationContext
    }

    fun requireContext(): android.content.Context =
        checkNotNull(context) { "AppContextHolder is not initialized" }
}
