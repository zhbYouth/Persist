package com.riheng.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private val prefs by lazy { getSharedPreferences(HabitWidgetProvider.PREFS, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingOpen: Triple<String, String, String>? = null   // 小组件带来的「打开哪个页面 / 分组 / 哪条待办」
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExportJson: String? = null

    // 启动：网页就绪前不绘制第一帧，让系统启动页一直保留；就绪后再淡出并让页面内容上浮
    @Volatile private var pageIsReady = false
    private var revealed = false

    companion object {
        const val EXTRA_OPEN_PAGE = "open_page"
        const val EXTRA_OPEN_VIEW = "open_view"
        const val EXTRA_OPEN_ID = "open_id"
        private const val REQ_FILE_CHOOSER = 4201
        private const val REQ_EXPORT = 4202
        private const val REQ_IMPORT = 4203
        private const val REQ_NOTIFY = 4204
        private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        private const val SPLASH_MAX_MS = 2000L
        private const val PAGE_BG = 0xFFF5F7FB.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 从启动主题换回正式主题（必须在 super.onCreate 之前）
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(PAGE_BG) }
        webView = WebView(this).apply { setBackgroundColor(PAGE_BG) }
        root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        setupSplash()
        setupKeyboardInsets()

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
        // 兜底：网页万一没发出就绪信号，也不能一直卡在启动页
        mainHandler.postDelayed({ markPageReady() }, SPLASH_MAX_MS)
    }

    // ---------- 启动动画 ----------
    private fun setupSplash() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!pageIsReady) return false // 暂不绘制，系统启动页继续显示
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splashView ->
                // 启动页淡出的同时，页面内容开始上浮
                revealPage()
                splashView.iconView?.animate()?.alpha(0f)?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(320)?.start()
                splashView.animate().alpha(0f).setDuration(380).withEndAction { splashView.remove() }.start()
            }
        }
    }

    private fun markPageReady() {
        if (pageIsReady) return
        pageIsReady = true
        findViewById<View>(android.R.id.content).invalidate()
        // Android 12+ 由启动页退出回调触发 reveal；其余情况（含启动页没出现的热启动）这里直接触发
        mainHandler.postDelayed({ revealPage() }, if (Build.VERSION.SDK_INT >= 31) 450L else 0L)
    }

    private fun revealPage() {
        if (revealed || !::webView.isInitialized) return
        revealed = true
        webView.evaluateJavascript("if(window.__RIHENGReveal){window.__RIHENGReveal();}", null)
    }

    // ---------- 键盘：动画开始时把「目标高度 + 时长」告诉页面，由页面在合成线程上完成动画 ----------
    // 系统的时序是：onPrepare → 先按【最终】状态分发一次 insets → onStart → onProgress… → onEnd。
    // 所以「分发 insets 的回调」拿到的是终点值，不能当作当前值；只有 onProgress 才是逐帧的当前值。
    // 旧版把两者都当「当前值」发给页面，页面先被推到终点、再退回起点、再逐帧爬升，而且每帧都改整页样式 → 掉帧。
    // 现在：整个键盘动画只发一次（onStart），不逐帧通知。
    private var imeAnimating = false
    private var imeEndCaptured = false // 本次动画的终点值（onPrepare 之后的第一次分发）是否已记录
    private var imeTargetCss = 0f      // 键盘高度（CSS px）：静止时是当前值，动画中是终点值
    private var imeSentCss = -1f       // 最近一次发给页面的值

    private fun sendKeyboard(css: Float, durationMs: Long) {
        imeSentCss = css
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("if(window.__RIHENGKeyboard){window.__RIHENGKeyboard($css,$durationMs);}", null)
    }

    private fun setupKeyboardInsets() {
        if (Build.VERSION.SDK_INT < 30) return // 更老的系统由 adjustResize 处理
        window.setDecorFitsSystemWindows(false)
        val density = resources.displayMetrics.density

        fun imeCss(insets: WindowInsets): Float {
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            return (ime - bars).coerceAtLeast(0) / density
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            // 只给状态栏/导航栏/刘海留出位置；键盘不在这里处理，交给页面平滑移动
            val pad = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            if (!imeAnimating) {
                imeTargetCss = imeCss(insets)
                // 没有键盘动画的变化（输入法切换高度、旋转等）：自己发一次，让页面用 200ms 过渡
                if (imeTargetCss != imeSentCss) sendKeyboard(imeTargetCss, 200)
            } else if (!imeEndCaptured) {
                // 动画开始前系统先按终点状态分发一次：只记这一次，之后即使再分发（中间值）也忽略
                imeTargetCss = imeCss(insets)
                imeEndCaptured = true
            }
            insets
        }
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) { imeAnimating = true; imeEndCaptured = false }
            }

            override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) sendKeyboard(imeTargetCss, animation.durationMillis)
                return bounds
            }

            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets = insets

            override fun onEnd(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                imeAnimating = false
                // 保险：动画结束后以最终状态为准，和已发送的不一致就立即校正（不带动画）
                val actual = root.rootWindowInsets?.let { imeCss(it) } ?: imeTargetCss
                imeTargetCss = actual
                if (actual != imeSentCss) sendKeyboard(actual, 0)
            }
        })
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
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Persist-backup.json" }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) notifyPage(if (Reminder.canNotify(this)) "reminder-allowed" else "reminder-denied")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureOpen(intent)
    }

    private fun captureOpen(i: Intent?) {
        val page = i?.getStringExtra(EXTRA_OPEN_PAGE) ?: return
        pendingOpen = Triple(page, i.getStringExtra(EXTRA_OPEN_VIEW) ?: "", i.getStringExtra(EXTRA_OPEN_ID) ?: "")
        i.removeExtra(EXTRA_OPEN_PAGE)
    }

    /** 页面可能还没加载完：函数还不存在就隔一会儿再试。 */
    private fun dispatchOpen(tries: Int = 0) {
        val o = pendingOpen ?: return
        if (!::webView.isInitialized) return
        val js = "(function(){if(typeof window.__RIHENGOpen==='function'){window.__RIHENGOpen(" +
            JSONObject.quote(o.first) + "," + JSONObject.quote(o.second) + "," + JSONObject.quote(o.third) + ");return 1;}return 0;})()"
        webView.evaluateJavascript(js) { r ->
            if (r == "1") pendingOpen = null
            else if (tries < 40) mainHandler.postDelayed({ dispatchOpen(tries + 1) }, 150)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            pushNativeDataToPage()
        }
        captureOpen(intent)
        dispatchOpen()
        // 应用回到前台时顺便刷新小组件（日期可能已经跨天）
        HabitWidgetProvider.updateAll(this)
        TodoWidgetProvider.updateAll(this)
        Reminder.schedule(this)
        TodoReminders.fire(this)     // 回到前台时补发错过的提醒，并重新排下一个闹钟
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            root.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class NativeBridge {
        @JavascriptInterface fun syncData(json: String) {
            prefs.edit().putString(HabitWidgetProvider.DATA_KEY, json).apply()
            mainHandler.post {
                val manager = AppWidgetManager.getInstance(this@MainActivity)
                val ids = manager.getAppWidgetIds(ComponentName(this@MainActivity, HabitWidgetProvider::class.java))
                if (ids.isNotEmpty()) HabitWidgetProvider.updateAll(this@MainActivity)
                TodoWidgetProvider.updateAll(this@MainActivity)
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

        /** 每日提醒的当前设置：{enabled, minutes, allowed}（allowed = 系统是否允许本应用发通知）。 */
        @JavascriptInterface fun getReminder(): String =
            JSONObject().put("enabled", Reminder.enabled(this@MainActivity)).put("minutes", Reminder.minutes(this@MainActivity))
                .put("allowed", Reminder.canNotify(this@MainActivity)).put("todo", TodoReminders.status(this@MainActivity)).toString()

        /** 立刻发一条测试通知；返回 false = 系统没允许本应用发通知。 */
        @JavascriptInterface fun testNotification(): Boolean = TodoReminders.test(this@MainActivity)

        /** 打开系统里本应用的「通知」设置页。 */
        @JavascriptInterface fun openNotificationSettings() {
            mainHandler.post {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (e2: Exception) {} }
            }
        }

        /** 打开系统的「电池优化」列表，把本应用设为「不限制」，提醒才不会被后台清理拦掉。 */
        @JavascriptInterface fun openBatterySettings() {
            mainHandler.post {
                try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (e2: Exception) {} }
            }
        }

        @JavascriptInterface fun setReminder(enabled: Boolean, minutes: Int) {
            Reminder.set(this@MainActivity, enabled, minutes)
            if (enabled && !Reminder.canNotify(this@MainActivity) && Build.VERSION.SDK_INT >= 33) {
                mainHandler.post { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
            }
        }

        /** 网页把接下来要提醒的待办（id、标题、时间戳）整份交给原生。 */
        @JavascriptInterface fun setTodoReminders(json: String) { TodoReminders.set(this@MainActivity, json) }

        /** 网页里用到了需要通知的功能（比如给待办设了时间），请求通知权限。 */
        @JavascriptInterface fun requestNotifications() {
            if (!Reminder.canNotify(this@MainActivity) && Build.VERSION.SDK_INT >= 33) {
                mainHandler.post { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
            }
        }

        /** 网页首屏已就绪：撤掉启动页，开始进场动画。 */
        @JavascriptInterface fun pageReady() {
            mainHandler.post { markPageReady() }
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
