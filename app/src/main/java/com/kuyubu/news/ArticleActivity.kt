package com.kuyubu.news

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class ArticleActivity : Activity() {
    companion object {
        const val EXTRA_URL = "article_url"
        const val EXTRA_TITLE = "article_title"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var speechStatus: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var chineseVoiceAvailable = true
    private var articleText = ""
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var paused = false
    private var pendingStart = false
    private var articleUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        articleUrl = url
        val articleTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = articleTitle.ifBlank { "新闻正文" }
            textSize = 18f
            setTextColor(Color.rgb(25, 43, 70))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(title)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addControl(controls, "开始播报") { startReading() }
        addControl(controls, "暂停") { pauseReading() }
        addControl(controls, "继续") { resumeReading() }
        addControl(controls, "停止") { stopReading() }
        header.addView(controls)

        speechStatus = TextView(this).apply {
            text = "正在打开新闻，稍后可播报正文"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(speechStatus)

        val pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        addControl(pageControls, "刷新网页") {
            speechStatus.text = "正在重新加载网页…"
            webView.reload()
        }
        addControl(pageControls, "用浏览器打开") {
            try {
                if (articleUrl.startsWith("https://") || articleUrl.startsWith("http://")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(articleUrl)))
                } else {
                    speechStatus.text = "新闻链接格式无效"
                }
            } catch (_: Exception) {
                Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(pageControls)
        root.addView(header)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(ArticleBridge(), "AndroidReader")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100 && articleText.isBlank()) {
                        speechStatus.text = "网页加载中… $newProgress%"
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme?.lowercase(Locale.ROOT)
                    return scheme != "https" && scheme != "http"
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    if (articleText.isBlank()) speechStatus.text = "网页已打开，正在识别正文…"
                    extractArticleText()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        speechStatus.text = "网页打开失败：网络中断或网站拒绝访问。可刷新，或改用外部浏览器。"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        speechStatus.text = "网页返回错误（HTTP " + errorResponse?.statusCode + "），可尝试外部浏览器。"
                    }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    val languageResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    chineseVoiceAvailable = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                    engine.setSpeechRate(0.95f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post { speechStatus.text = "正在播报正文…" }
                        }
                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                if (!paused) {
                                    chunkIndex++
                                    speakCurrentChunk()
                                }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                speechStatus.text = "语音播报遇到问题，请检查系统语音服务"
                            }
                        }
                    })
                    ttsReady = true
                    if (pendingStart) {
                        pendingStart = false
                        startReading()
                    }
                }
            } else {
                mainHandler.post { speechStatus.text = "系统语音服务初始化失败" }
            }
        }

        if (url.startsWith("https://")) {
            webView.loadUrl(url)
        } else {
            speechStatus.text = "新闻链接无效"
        }
    }

    private fun addControl(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(3)
        })
    }

    private fun extractArticleText() {
        val script = """
            (function() {
              var candidates = Array.from(document.querySelectorAll('article, main, [role="main"]'));
              candidates.sort(function(a,b) {
                return (b.innerText || '').length - (a.innerText || '').length;
              });
              var root = candidates[0] || document.body;
              if (!root) return;
              var clone = root.cloneNode(true);
              clone.querySelectorAll('script,style,noscript,nav,header,footer,aside,form,button,svg').forEach(function(n){n.remove();});
              var text = (clone.innerText || clone.textContent || '').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').replace(/\n\s*\n\s*\n/g,'\n\n').trim();
              if (text.length < 120 && document.body) text = (document.body.innerText || '').trim();
              AndroidReader.onArticleText(text);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private inner class ArticleBridge {
        @JavascriptInterface
        fun onArticleText(text: String?) {
            mainHandler.post {
                val cleaned = text.orEmpty().trim()
                if (cleaned.length > articleText.length) {
                    articleText = cleaned
                    chunks = splitIntoChunks(cleaned)
                    chunkIndex = 0
                    speechStatus.text = if (cleaned.length < 120) {
                        "页面正文较短，可能只有新闻摘要"
                    } else {
                        "正文已提取（约 " + cleaned.length + " 字），可以开始播报"
                    }
                } else if (articleText.isBlank()) {
                    speechStatus.text = "暂未提取到正文；可尝试刷新页面"
                }
            }
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(1000)
        val pieces = text.split(Regex("(?<=[。！？；.!?])"))
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            var remaining = piece
            while (remaining.length > limit) {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
                result.add(remaining.substring(0, limit))
                remaining = remaining.substring(limit)
            }
            if (current.length + remaining.length > limit && current.isNotEmpty()) {
                result.add(current.toString())
                current.clear()
            }
            current.append(remaining)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.filter { it.isNotBlank() }
    }

    private fun startReading() {
        if (articleText.isBlank()) {
            speechStatus.text = "正文还在加载，请稍候"
            return
        }
        if (!ttsReady) {
            pendingStart = true
            speechStatus.text = "正在准备语音引擎…"
            return
        }
        if (!chineseVoiceAvailable) {
            Toast.makeText(this, "系统未提供中文语音，请在设备语音设置中安装中文语音数据", Toast.LENGTH_LONG).show()
            speechStatus.text = "缺少中文语音数据"
            return
        }
        if (chunkIndex >= chunks.size) chunkIndex = 0
        paused = false
        speakCurrentChunk()
    }

    private fun pauseReading() {
        if (ttsReady && !paused) {
            paused = true
            tts?.stop()
            speechStatus.text = "已暂停；继续时从当前段落重新开始"
        }
    }

    private fun resumeReading() {
        if (articleText.isBlank()) {
            speechStatus.text = "正文还在加载，请稍候"
            return
        }
        if (!ttsReady) {
            pendingStart = true
            return
        }
        paused = false
        speakCurrentChunk()
    }

    private fun stopReading() {
        paused = true
        pendingStart = false
        tts?.stop()
        chunkIndex = 0
        speechStatus.text = "播报已停止"
    }

    private fun speakCurrentChunk() {
        if (paused) return
        if (chunks.isEmpty()) {
            speechStatus.text = "没有可播报的正文"
            return
        }
        if (chunkIndex >= chunks.size) {
            speechStatus.text = "正文播报完成"
            return
        }
        val result = tts?.speak(
            chunks[chunkIndex],
            TextToSpeech.QUEUE_FLUSH,
            null,
            "news_chunk_" + chunkIndex
        )
        if (result == TextToSpeech.ERROR) speechStatus.text = "语音播报启动失败"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        paused = true
        tts?.stop()
        tts?.shutdown()
        webView.stopLoading()
        webView.removeJavascriptInterface("AndroidReader")
        webView.destroy()
        super.onDestroy()
    }
}
