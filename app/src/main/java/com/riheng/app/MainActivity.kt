package com.riheng.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences(HabitWidgetProvider.PREFS, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExportJson: String? = null

    companion object {
        private const val REQ_FILE_CHOOSER = 4201
        private const val REQ_EXPORT = 4202
        private const val REQ_IMPORT = 4203
        private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    }

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
                if (filePathCallback == null) return false
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val accept = fileChooserParams?.acceptTypes?.firstOrNull { it.contains("/") } ?: "image/*"
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = accept
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_FILE_CHOOSER)
                    true
                } catch (e: Exception) {
                    // 无法打开选择器：必须回调 null，否则网页里的上传按钮之后会一直失效
                    this@MainActivity.fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    true
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
        when (requestCode) {
            REQ_FILE_CHOOSER -> {
                val callback = fileChooserCallback ?: return
                fileChooserCallback = null
                callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            }
            REQ_EXPORT -> finishExport(resultCode, data?.data)
            REQ_IMPORT -> finishImport(resultCode, data?.data)
        }
    }

    private fun loadLocalApp() {
        val html = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val nativeJson = prefs.getString(HabitWidgetProvider.DATA_KEY, null)
        val bootstrap = "window.__RIHENG_NATIVE_APP__=true;window.__RIHENG_WIDGET_BOOTSTRAP__=" +
            (if (nativeJson != null) JSONObject.quote(nativeJson) else "''") + ";"
        val patched = html.replaceFirst("<head>", "<head><script>$bootstrap</script>")
        webView.loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", patched, "text/html", "UTF-8", null)
    }

    private fun pushNativeDataToPage() {
        val raw = prefs.getString(HabitWidgetProvider.DATA_KEY, null) ?: return
        val quoted = JSONObject.quote(raw)
        webView.evaluateJavascript("if(window.__RIHENGNativeSync){window.__RIHENGNativeSync($quoted);}", null)
    }

    /** 通知网页发生了某个原生事件（导出成功/失败等），由网页负责弹提示。 */
    private fun notifyPage(event: String) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "if(window.__RIHENGNativeEvent){window.__RIHENGNativeEvent(${JSONObject.quote(event)});}", null
        )
    }

    // ---------- 备份导出 ----------
    private fun startExport(fileName: String, json: String) {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "riheng-backup.json" }
        pendingExportJson = json
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, safeName)
        }
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: ActivityNotFoundException) {
            pendingExportJson = null
            notifyPage("export-failed")
        }
    }

    private fun finishExport(resultCode: Int, uri: Uri?) {
        val json = pendingExportJson
        pendingExportJson = null
        if (resultCode != RESULT_OK || uri == null || json == null) return // 用户取消，不提示
        Thread {
            val ok = try {
                val stream = contentResolver.openOutputStream(uri, "wt")
                if (stream == null) {
                    false
                } else {
                    stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    true
                }
            } catch (e: Exception) {
                false
            }
            mainHandler.post { notifyPage(if (ok) "exported" else "export-failed") }
        }.start()
    }

    // ---------- 备份导入 ----------
    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: ActivityNotFoundException) {
            notifyPage("import-failed")
        }
    }

    private fun readTextLimited(uri: Uri): String? {
        val input = contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) return null
                buffer.write(chunk, 0, n)
            }
            return String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun finishImport(resultCode: Int, uri: Uri?) {
        if (resultCode != RESULT_OK || uri == null) return
        Thread {
            val text = try { readTextLimited(uri) } catch (e: Exception) { null }
            mainHandler.post {
                if (text == null) {
                    notifyPage("import-failed")
                } else if (::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "if(window.__RIHENGImportText){window.__RIHENGImportText(${JSONObject.quote(text)});}", null
                    )
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            pushNativeDataToPage()
        }
        // 应用回到前台时顺便刷新小组件（日期可能已经跨天）
        HabitWidgetProvider.updateAll(this)
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    inner class NativeBridge {
        @JavascriptInterface fun syncData(json: String) {
            prefs.edit().putString(HabitWidgetProvider.DATA_KEY, json).apply()
            mainHandler.post {
                val manager = AppWidgetManager.getInstance(this@MainActivity)
                val ids = manager.getAppWidgetIds(ComponentName(this@MainActivity, HabitWidgetProvider::class.java))
                if (ids.isNotEmpty()) HabitWidgetProvider.updateAll(this@MainActivity)
            }
        }

        @JavascriptInterface fun getWidgetData(): String =
            prefs.getString(HabitWidgetProvider.DATA_KEY, "{\"habits\":[],\"checks\":{}}") ?: "{\"habits\":[],\"checks\":{}}"

        @JavascriptInterface fun exportBackup(fileName: String, json: String) {
            mainHandler.post { startExport(fileName, json) }
        }

        @JavascriptInterface fun importBackup() {
            mainHandler.post { startImport() }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            finish()
            return
        }
        // 先让网页处理：关弹窗 / 从统计、设置页回到「今日」；都不需要时才退出应用
        webView.evaluateJavascript("(window.__RIHENGHandleBack?window.__RIHENGHandleBack():false)") { result ->
            if (result != "true") finish()
        }
    }
}
