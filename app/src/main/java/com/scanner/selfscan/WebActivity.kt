package com.scanner.selfscan

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class WebActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (u.startsWith("http://") || u.startsWith("https://")) return false
                ScanRouter.handle(this@WebActivity, u)
                return true
            }
        }
        wv.setDownloadListener { url, _, _, mime, _ ->
            if (mime == "application/vnd.android.package-archive" || url.substringBefore("?").endsWith(".apk", true))
                ScanRouter.downloadApk(this, url)
            else ScanRouter.view(this, url)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })
        intent.getStringExtra("url")?.let { wv.loadUrl(it) }
    }
}
