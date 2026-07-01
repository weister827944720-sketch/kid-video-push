package com.kidvideopush.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val DEFAULT_SERVER = "http://n.dujiaoxian.online:35039"

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var listLayout: LinearLayout
    private lateinit var progress: ProgressBar
    private var sharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
        showHome()
    }

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "孩子视频推送"
            textSize = 24f
            setTextColor(Color.BLACK)
        }
        root.addView(title)

        serverInput = EditText(this).apply {
            setText(DEFAULT_SERVER)
            hint = "服务器地址"
            singleLine = true
        }
        root.addView(serverInput)

        statusText = TextView(this).apply {
            text = if (sharedText.isNullOrBlank()) "" else "收到分享内容，准备推送"
            setTextColor(Color.DKGRAY)
        }
        root.addView(statusText)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)

        if (!sharedText.isNullOrBlank()) {
            root.addView(TextView(this).apply { text = "分享内容：${sharedText!!.take(120)}" })
            root.addView(Button(this).apply {
                text = "推送给平板"
                setOnClickListener { pushSharedLink() }
            })
        }

        root.addView(Button(this).apply {
            text = "刷新孩子播放列表"
            setOnClickListener { refreshVideos() }
        })

        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollView = ScrollView(this).apply { addView(listLayout) }
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun pushSharedLink() {
        val text = sharedText ?: return
        lifecycleScope.launch {
            setLoading(true, "正在推送")
            val message = runCatching { pushLink(serverInput.text.toString(), text) }
                .fold({ "已推送到服务器" }, { "推送失败：${it.message}" })
            setLoading(false, message)
        }
    }

    private fun refreshVideos() {
        lifecycleScope.launch {
            setLoading(true, "正在刷新")
            runCatching { fetchVideos(serverInput.text.toString()) }
                .onSuccess { videos ->
                    listLayout.removeAllViews()
                    videos.forEach { addVideoRow(it) }
                    setLoading(false, "已加载 ${videos.size} 条")
                }
                .onFailure { setLoading(false, "刷新失败：${it.message}") }
        }
    }

    private fun addVideoRow(item: VideoItem) {
        val row = TextView(this).apply {
            text = "${item.title.ifBlank { "抖音分享链接" }}\n${item.shareUrl}\n${item.createdAt}"
            textSize = 15f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(242, 242, 242))
            setPadding(18, 18, 18, 18)
            setOnClickListener { showWebPlayer(item.shareUrl) }
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) }
        listLayout.addView(row, params)
    }

    private fun setLoading(loading: Boolean, status: String) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        statusText.text = status
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebPlayer(url: String) {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val back = Button(this).apply {
            text = "返回"
            gravity = Gravity.START
            setOnClickListener { showHome() }
        }
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    view.evaluateJavascript(HIDE_DISTRACTIONS_JS, null)
                }
            }
            loadUrl(url)
        }
        frame.addView(back, LinearLayout.LayoutParams(-1, -2))
        frame.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(frame)
    }
}

data class VideoItem(
    val id: String,
    val shareUrl: String,
    val title: String,
    val createdAt: String
)

private val HIDE_DISTRACTIONS_JS = """
    (function() {
      const style = document.createElement('style');
      style.innerHTML = `
        [class*="comment"], [class*="like"], [class*="favorite"], [class*="share"],
        [class*="open"], [class*="download"], [class*="login"], button, header, footer {
          display: none !important;
          visibility: hidden !important;
        }
        video { width: 100vw !important; height: 100vh !important; object-fit: contain !important; }
        body { margin: 0 !important; overflow: hidden !important; background: #000 !important; }
      `;
      document.head.appendChild(style);
    })();
""".trimIndent()

private suspend fun pushLink(serverUrl: String, rawText: String) = withContext(Dispatchers.IO) {
    val link = extractFirstUrl(rawText) ?: rawText.trim()
    val connection = URL("${serverUrl.trimEnd('/')}/api/videos").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    connection.doOutput = true
    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(JSONObject().put("shareUrl", link).put("title", "抖音分享链接").toString())
    }
    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
}

private suspend fun fetchVideos(serverUrl: String): List<VideoItem> = withContext(Dispatchers.IO) {
    val text = URL("${serverUrl.trimEnd('/')}/api/videos").readText(Charsets.UTF_8)
    val array = JSONArray(text)
    List(array.length()) { index ->
        val item = array.getJSONObject(index)
        VideoItem(
            id = item.optString("id"),
            shareUrl = item.optString("shareUrl"),
            title = item.optString("title"),
            createdAt = item.optString("createdAt")
        )
    }
}

private fun extractFirstUrl(text: String): String? {
    return Regex("https?://\\S+").find(text)?.value?.trimEnd('，', ',', '。', '.')
}
