package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#06080F")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#06080F"))
    )
    setContent { RxTourBdHost(onExitApp = { finish() }) }
  }
}

class RxBridge(
  private val appContext: Context,
  private val webViewProvider: () -> WebView?
) {
  private val executor = Executors.newSingleThreadExecutor()
  private val vaultPrefs by lazy {
    appContext.getSharedPreferences("rx_room_vault_prefs", Context.MODE_PRIVATE)
  }

  @JavascriptInterface
  fun copyToClipboard(label: String?, text: String?): Boolean {
    return try {
      val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
      val clip = ClipData.newPlainText(label ?: "RX TOUR BD", text ?: "")
      cm.setPrimaryClip(clip)
      true
    } catch (_: Exception) {
      false
    }
  }

  @JavascriptInterface
  fun saveMatchRoomVault(matchId: String?, roomId: String?, roomPass: String?) {
    val mid = matchId?.trim().orEmpty()
    if (mid.isEmpty()) return
    vaultPrefs.edit()
      .putString("room_id_$mid", roomId?.trim().orEmpty())
      .putString("room_pass_$mid", roomPass?.trim().orEmpty())
      .apply()
  }

  @JavascriptInterface
  fun registerVerifiedJoin(userId: String?, userEmail: String?, matchId: String?) {
    val mid = matchId?.trim().orEmpty()
    if (mid.isEmpty()) return
    val uid = userId?.trim().orEmpty()
    val mail = userEmail?.trim()?.lowercase().orEmpty()
    val editor = vaultPrefs.edit()
    if (uid.isNotEmpty()) editor.putBoolean("joined_${mid}_uid_$uid", true)
    if (mail.isNotEmpty()) editor.putBoolean("joined_${mid}_mail_$mail", true)
    editor.apply()
  }

  @JavascriptInterface
  fun getAuthorizedRoomCredentials(userId: String?, userEmail: String?, matchId: String?): String {
    val res = JSONObject()
    val mid = matchId?.trim().orEmpty()
    val uid = userId?.trim().orEmpty()
    val mail = userEmail?.trim()?.lowercase().orEmpty()
    if (mid.isEmpty() || (uid.isEmpty() && mail.isEmpty())) {
      res.put("authorized", false)
      res.put("joined", false)
      return res.toString()
    }
    val hasUidJoin = uid.isNotEmpty() && vaultPrefs.getBoolean("joined_${mid}_uid_$uid", false)
    val hasMailJoin = mail.isNotEmpty() && vaultPrefs.getBoolean("joined_${mid}_mail_$mail", false)
    val joined = hasUidJoin || hasMailJoin
    if (!joined) {
      res.put("authorized", false)
      res.put("joined", false)
      return res.toString()
    }
    res.put("authorized", true)
    res.put("joined", true)
    res.put("matchId", mid)
    res.put("roomId", vaultPrefs.getString("room_id_$mid", "") ?: "")
    res.put("roomPass", vaultPrefs.getString("room_pass_$mid", "") ?: "")
    return res.toString()
  }

  private val logoLock = Any()

  private fun logoStoreFile(): File {
    return File(appContext.filesDir, "tournament_category_logos.json")
  }

  private fun readLogoStoreUnlocked(): JSONObject {
    val f = logoStoreFile()
    if (!f.exists()) return JSONObject()
    return try {
      val txt = f.readText(Charsets.UTF_8)
      if (txt.isBlank()) JSONObject() else JSONObject(txt)
    } catch (_: Exception) {
      JSONObject()
    }
  }

  private fun writeLogoStoreUnlocked(obj: JSONObject): Boolean {
    return try {
      logoStoreFile().writeText(obj.toString(), Charsets.UTF_8)
      true
    } catch (_: Exception) {
      false
    }
  }

  @JavascriptInterface
  fun saveCategoryLogo(categoryId: String?, logoJson: String?): Boolean {
    val cid = categoryId?.trim().orEmpty()
    val raw = logoJson?.trim().orEmpty()
    if (cid.isEmpty() || raw.isEmpty()) return false
    return synchronized(logoLock) {
      try {
        val store = readLogoStoreUnlocked()
        store.put(cid, JSONObject(raw))
        writeLogoStoreUnlocked(store)
      } catch (_: Exception) {
        false
      }
    }
  }

  @JavascriptInterface
  fun removeCategoryLogo(categoryId: String?): Boolean {
    val cid = categoryId?.trim().orEmpty()
    if (cid.isEmpty()) return false
    return synchronized(logoLock) {
      val store = readLogoStoreUnlocked()
      if (store.has(cid)) {
        store.remove(cid)
        writeLogoStoreUnlocked(store)
      } else {
        true
      }
    }
  }

  @JavascriptInterface
  fun getCategoryLogosJson(): String {
    return synchronized(logoLock) {
      readLogoStoreUnlocked().toString()
    }
  }

  @JavascriptInterface
  fun syncServerTime() {
    executor.execute {
      val serverMs = fetchUtcFromNetwork() ?: return@execute
      val webView = webViewProvider() ?: return@execute
      webView.post {
        webView.evaluateJavascript("window.rxSetServerTime && window.rxSetServerTime($serverMs);", null)
      }
    }
  }

  private fun fetchUtcFromNetwork(): Long? {
    val endpoints = listOf(
      "https://www.google.com/generate_204",
      "https://cloudflare.com/cdn-cgi/trace"
    )
    for (endpoint in endpoints) {
      var conn: HttpURLConnection? = null
      try {
        conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
          requestMethod = "HEAD"
          connectTimeout = 4500
          readTimeout = 4500
          instanceFollowRedirects = false
          useCaches = false
        }
        conn.connect()
        val headerDate = conn.date
        if (headerDate > 1_700_000_000_000L) {
          return headerDate
        }
      } catch (_: Exception) {
      } finally {
        runCatching { conn?.disconnect() }
      }
    }
    return null
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RxTourBdHost(onExitApp: () -> Unit) {
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

  val imagePickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
      val callback = pendingFileCallback
      pendingFileCallback = null
      if (uri != null) {
        callback?.onReceiveValue(arrayOf(uri))
      } else {
        callback?.onReceiveValue(null)
      }
    }

  BackHandler {
    val webView = webViewRef
    if (webView != null) {
      webView.evaluateJavascript("(window.rxHandleBack ? window.rxHandleBack() : 'exit')") { result ->
        val cleaned = result?.trim('"')
        if (cleaned != "handled") {
          if (webView.canGoBack()) {
            webView.goBack()
          } else {
            onExitApp()
          }
        }
      }
    } else {
      onExitApp()
    }
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(ComposeColor(0xFF06080F))
        .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        WebView(context).apply {
          setBackgroundColor(Color.parseColor("#06080F"))
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_NO_CACHE
          }
          overScrollMode = WebView.OVER_SCROLL_NEVER
          isVerticalScrollBarEnabled = false
          isHorizontalScrollBarEnabled = false

          addJavascriptInterface(RxBridge(context.applicationContext) { webViewRef }, "RxAndroidBridge")

          webChromeClient =
            object : WebChromeClient() {
              override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
              ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return try {
                  imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                  )
                  true
                } catch (_: Exception) {
                  pendingFileCallback?.onReceiveValue(null)
                  pendingFileCallback = null
                  false
                }
              }
            }

          webViewClient =
            object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
              ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https" || scheme == "tg" || scheme == "mailto") {
                  runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                  }
                  return true
                }
                return false
              }
            }

          loadUrl("file:///android_asset/index.html")
          webViewRef = this
        }
      },
      update = { webViewRef = it }
    )
  }
}
