package com.kuyubu.news

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class NewsItem(val title: String, val link: String, val date: String)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var ticker: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var keyword: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(Color.rgb(246, 248, 251))
        }

        val heading = TextView(this).apply {
            text = "全球新闻"
            textSize = 26f
            setTextColor(Color.rgb(25, 43, 70))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(heading)

        val subheading = TextView(this).apply {
            text = "实时资讯 · 关键词检索"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(3), 0, dp(14))
        }
        root.addView(subheading)

        val tickerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(26, 48, 79))
        }
        val tag = TextView(this).apply {
            text = "快讯"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.rgb(205, 55, 55))
        }
        tickerPanel.addView(tag)
        ticker = TextView(this).apply {
            text = "正在获取最新新闻……"
            textSize = 14f
            setTextColor(Color.WHITE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(dp(12), 0, 0, 0)
        }
        tickerPanel.addView(ticker, LinearLayout.LayoutParams(0, dp(34), 1f))
        root.addView(tickerPanel)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(10))
        }
        keyword = EditText(this).apply {
            hint = "输入新闻关键词，例如：人工智能"
            textSize = 14f
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(Color.WHITE)
        }
        searchRow.addView(keyword, LinearLayout.LayoutParams(0, dp(48), 1f))
        val searchButton = Button(this).apply {
            text = "搜索"
            setOnClickListener { loadNews(keyword.text.toString().trim()) }
        }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(88), dp(48)))
        root.addView(searchRow)

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("国际", "科技", "经济", "体育").forEach { word ->
            val chip = Button(this).apply {
                text = word
                textSize = 12f
                setOnClickListener {
                    keyword.setText(word)
                    loadNews(word)
                }
            }
            quickRow.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(quickRow)

        status = TextView(this).apply {
            text = "新闻列表"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(6))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        loadNews("")
    }

    private fun loadNews(query: String) {
        status.text = if (query.isBlank()) "正在加载最新新闻…" else "正在搜索：$query"
        executor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val feed = if (query.isBlank()) {
                    "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
                } else {
                    "https://news.google.com/rss/search?q=$encoded&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
                }
                val items = fetchFeed(feed)
                mainHandler.post {
                    renderItems(items)
                    ticker.text = if (items.isEmpty()) "暂时没有找到相关新闻" else
                        items.take(8).joinToString("     •     ") { it.title }
                    status.text = if (items.isEmpty()) "没有找到新闻，请更换关键词或稍后重试"
                        else "找到 ${items.size} 条新闻 · 点击标题阅读原文"
                }
            } catch (e: Exception) {
                mainHandler.post {
                    status.text = "新闻加载失败，请检查网络后重试"
                    ticker.text = "暂时无法连接新闻源"
                    renderItems(emptyList())
                }
            }
        }
    }

    private fun fetchFeed(feedUrl: String): List<NewsItem> {
        val connection = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NewsAndroid/1.0")
        }
        try {
            connection.inputStream.use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                val result = mutableListOf<NewsItem>()
                var event = parser.eventType
                var inItem = false
                var title = ""
                var link = ""
                var date = ""
                while (event != XmlPullParser.END_DOCUMENT && result.size < 40) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "item" -> {
                                inItem = true
                                title = ""
                                link = ""
                                date = ""
                            }
                            "title" -> if (inItem) title = parser.nextText()
                            "link" -> if (inItem) link = parser.nextText()
                            "pubDate" -> if (inItem) date = parser.nextText()
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                result.add(NewsItem(title, link, date))
                            }
                            inItem = false
                        }
                    }
                    event = parser.next()
                }
                return result
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun renderItems(items: List<NewsItem>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无新闻内容"
                textSize = 16f
                setTextColor(Color.GRAY)
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }
            list.addView(empty)
            return
        }
        items.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.WHITE)
            }
            val titleView = TextView(this).apply {
                text = "${index + 1}. ${item.title}"
                textSize = 16f
                setTextColor(Color.rgb(30, 45, 65))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(this@MainActivity, ArticleActivity::class.java).apply {
                            putExtra(ArticleActivity.EXTRA_URL, item.link)
                            putExtra(ArticleActivity.EXTRA_TITLE, item.title)
                        })
                    }
                }
            }
            card.addView(titleView)
            if (item.date.isNotBlank()) {
                val dateView = TextView(this).apply {
                    text = item.date
                    textSize = 11f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(7), 0, 0)
                }
                card.addView(dateView)
            }
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
