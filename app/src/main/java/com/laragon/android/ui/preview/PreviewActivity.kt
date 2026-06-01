package com.laragon.android.ui.preview

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.laragon.android.R
import com.laragon.android.service.LaragonService
import com.laragon.android.util.ServerConfig

class PreviewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentUrl: String = "http://localhost:${ServerConfig.DEFAULT_PORT}/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_preview)
        } catch (e: Throwable) {
            android.util.Log.e("PreviewActivity", "Layout inflation failed", e)
            finish()
            return
        }

        webView = findViewById(R.id.webview_preview)
        setupWebView()
        loadUrl()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) {
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                title = url
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebViewBridge(), "LaragonAndroid")
    }

    private fun loadUrl() {
        val serverState = LaragonService.getServerState()?.value
        if (serverState != LaragonService.ServerState.RUNNING) {
            webView.loadData(
                """
                <html><body style="font-family:sans-serif;padding:40px;text-align:center;">
                <h2>Server Not Running</h2>
                <p>Please start the server from the main screen first.</p>
                </body></html>
                """.trimIndent(),
                "text/html",
                "UTF-8"
            )
            return
        }

        webView.loadUrl(currentUrl)
    }

    fun reload() {
        if (LaragonService.getServerState()?.value == LaragonService.ServerState.RUNNING) {
            webView.reload()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try { webView.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class WebViewBridge {
        @android.webkit.JavascriptInterface
        fun getServerInfo(): String {
            return """{"status":"running","port":${ServerConfig.DEFAULT_PORT}}"""
        }

        @android.webkit.JavascriptInterface
        fun reloadPage() {
            runOnUiThread { webView.reload() }
        }
    }
}
