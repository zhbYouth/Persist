package com.riheng.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences(HabitWidgetProvider.PREFS, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequestCode = 4201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "选择习惯图标"), fileChooserRequestCode)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = null
                    false
                }
            }
        }
        webView.addJavascriptInterface(NativeBridge(), "RiHengNative")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pushNativeDataToPage()
            }
        }
        loadLocalApp()
    }


    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fileChooserRequestCode) {
            val callback = fileChooserCallback ?: return
            fileChooserCallback = null
            val result = if (resultCode == RESULT_OK) data?.data?.let { arrayOf(it) } else null
            callback.onReceiveValue(result)
        }
    }

    private fun loadLocalApp() {
        val html = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val nativeJson = prefs.getString(HabitWidgetProvider.DATA_KEY, null)
        val bootstrap = "window.__RIHENG_NATIVE_APP__=true;" + if (nativeJson != null) "window.__RIHENG_WIDGET_BOOTSTRAP__=${JSONObject.quote(nativeJson)};" else "window.__RIHENG_WIDGET_BOOTSTRAP__='';"
        val patched = html.replaceFirst("<head>", "<head><script>$bootstrap</script>")
        webView.loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", patched, "text/html", "UTF-8", null)
    }

    private fun pushNativeDataToPage() {
        val raw = prefs.getString(HabitWidgetProvider.DATA_KEY, null) ?: return
        val quoted = JSONObject.quote(raw)
        webView.evaluateJavascript("if(window.__RIHENGNativeSync){window.__RIHENGNativeSync($quoted);}", null)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) mainHandler.postDelayed({ if (!isFinishing) pushNativeDataToPage() }, 250)
    }

    inner class NativeBridge {
        @JavascriptInterface fun syncData(json: String) {
            prefs.edit().putString(HabitWidgetProvider.DATA_KEY, json).apply()
            mainHandler.post {
                val manager = AppWidgetManager.getInstance(this@MainActivity)
                val ids = manager.getAppWidgetIds(ComponentName(this@MainActivity, HabitWidgetProvider::class.java))
                if (ids.isNotEmpty()) HabitWidgetProvider().onUpdate(this@MainActivity, manager, ids)
            }
        }
        @JavascriptInterface fun getWidgetData(): String = prefs.getString(HabitWidgetProvider.DATA_KEY, "{\"habits\":[],\"checks\":{}}") ?: "{\"habits\":[],\"checks\":{}}"
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        webView.evaluateJavascript("!!document.querySelector('.modal-backdrop.open')") { result ->
            if (result == "true") {
                webView.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}))", null)
            } else {
                finish()
            }
        }
    }
}
