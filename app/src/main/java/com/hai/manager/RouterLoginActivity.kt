package com.hai.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class RouterLoginActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val hint = TextView(this).apply {
            text = "سجّل الدخول إلى لوحة الراوتر. بعد نجاح الدخول يمكنك العودة للفحص أو فتح أدوات Wi-Fi."
            textSize = 16f
            setPadding(24, 20, 24, 16)
        }
        val wifiTools = Button(this).apply {
            text = "إدارة Wi-Fi"
            setOnClickListener {
                CookieManager.getInstance().flush()
                startActivity(Intent(this@RouterLoginActivity, WifiToolsActivity::class.java).putExtra(WifiToolsActivity.EXTRA_URL, url))
            }
        }
        val done = Button(this).apply {
            text = "تم تسجيل الدخول — رجوع للفحص"
            setOnClickListener {
                CookieManager.getInstance().flush()
                setResult(RESULT_OK)
                finish()
            }
        }
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(url)

        root.addView(hint)
        root.addView(wifiTools)
        root.addView(done)
        root.addView(webView)
        setContentView(root)
    }

    companion object {
        const val EXTRA_URL = "router_url"
    }
}
