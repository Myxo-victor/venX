package com.venjsx

import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.venjsx.core.VenjsXEngine

class MainActivity : AppCompatActivity() {
  private var bridgeWebView: WebView? = null
  private var nativeRoot: FrameLayout? = null
  private var venjsXEngine: VenjsXEngine? = null

  private val assetUrl = "file:///android_asset/app/index.html"
  private val devUrl = "http://10.0.2.2:5173/index.html"
  private var attemptedDevUrl = false
  private var devPageLoaded = false

  override fun onCreate(savedInstanceState: Bundle?) {
    // Drop launch/splash theme immediately so it doesn't linger while JS boots.
    setTheme(R.style.Theme_Venjsx_App)
    super.onCreate(savedInstanceState)
    window.statusBarColor = Color.parseColor("#FFFFFF")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      window.navigationBarColor = Color.parseColor("#FFFFFF")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    val appHost = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    }

    nativeRoot = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    }

    bridgeWebView = WebView(this).apply {
      layoutParams = FrameLayout.LayoutParams(1, 1)
      visibility = View.GONE
    }

    configureBridgeWebView(bridgeWebView!!)
    venjsXEngine = VenjsXEngine(this, nativeRoot!!, bridgeWebView!!)
    bridgeWebView!!.addJavascriptInterface(venjsXEngine!!, "Android")
    venjsXEngine?.handleNotificationTapFromIntent(intent)

    // Only attempt dev server on emulators. Physical devices cannot reach 10.0.2.2.
    if (BuildConfig.DEBUG && isProbablyEmulator()) {
      attemptedDevUrl = true
      devPageLoaded = false
      bridgeWebView!!.loadUrl(devUrl)

      // Extra safety: if dev server doesn't load quickly, fall back to packaged assets.
      Handler(Looper.getMainLooper()).postDelayed({
        val webView = bridgeWebView ?: return@postDelayed
        if (attemptedDevUrl && !devPageLoaded) {
          attemptedDevUrl = false
          webView.loadUrl(assetUrl)
        }
      }, 1500)
    } else {
      bridgeWebView!!.loadUrl(assetUrl)
    }

    appHost.addView(nativeRoot)
    appHost.addView(bridgeWebView)
    setContentView(appHost)

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        val webView = bridgeWebView
        if (webView == null) {
          isEnabled = false
          onBackPressedDispatcher.onBackPressed()
          isEnabled = true
          return
        }

        webView.evaluateJavascript(
          "(function(){try{return (window.__venjsHandleNativeBack && window.__venjsHandleNativeBack()) ? '1' : '0';}catch(e){return '0';}})();"
        ) { result ->
          val normalized = result?.replace("\"", "")?.trim()
          val consumed = normalized == "1" || normalized.equals("true", ignoreCase = true)
          if (!consumed) {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
          }
        }
      }
    })
  }

  private fun configureBridgeWebView(webView: WebView) {
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    // Always fetch fresh local assets during development.
    settings.cacheMode = WebSettings.LOAD_NO_CACHE

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      settings.allowFileAccessFromFileURLs = true
      settings.allowUniversalAccessFromFileURLs = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    webView.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (attemptedDevUrl && url.startsWith(devUrl)) {
          devPageLoaded = true
        }
      }

      @Deprecated("Deprecated in Java")
      override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
      ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        if (attemptedDevUrl && failingUrl != null && failingUrl.startsWith(devUrl)) {
          attemptedDevUrl = false
          view.loadUrl(assetUrl)
        }
      }

      override fun onReceivedError(
        view: WebView,
        request: android.webkit.WebResourceRequest,
        error: android.webkit.WebResourceError
      ) {
        super.onReceivedError(view, request, error)
        val url = request.url?.toString() ?: ""
        if (attemptedDevUrl && request.isForMainFrame && url.startsWith(devUrl)) {
          attemptedDevUrl = false
          view.loadUrl(assetUrl)
        }
      }
    }
    webView.webChromeClient = object : WebChromeClient() {
      override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
      ): Boolean {
        AlertDialog.Builder(this@MainActivity)
          .setTitle("Message")
          .setMessage(message ?: "")
          .setCancelable(false)
          .setPositiveButton("OK") { _, _ ->
            result?.confirm()
          }
          .setOnCancelListener {
            result?.cancel()
          }
          .show()
        return true
      }
    }
    webView.clearCache(true)
    webView.clearHistory()
    webView.clearFormData()
  }

  private fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val brand = Build.BRAND.lowercase()
    val device = Build.DEVICE.lowercase()
    val product = Build.PRODUCT.lowercase()

    return fingerprint.contains("generic") ||
      fingerprint.contains("unknown") ||
      model.contains("google_sdk") ||
      model.contains("emulator") ||
      model.contains("android sdk built for") ||
      brand.contains("generic") ||
      device.contains("generic") ||
      product.contains("sdk") ||
      product.contains("emulator") ||
      product.contains("simulator")
  }

  override fun onDestroy() {
    bridgeWebView?.let {
      it.removeJavascriptInterface("Android")
      it.destroy()
    }
    bridgeWebView = null
    nativeRoot = null
    venjsXEngine = null
    super.onDestroy()
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    venjsXEngine?.handleNotificationTapFromIntent(intent)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    venjsXEngine?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }
}
