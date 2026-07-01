package com.kidvideopush.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val DEFAULT_SERVER = "http://10.0.2.2:3000"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(sharedText = sharedText)
                }
            }
        }
    }
}

data class VideoItem(
    val id: String,
    val shareUrl: String,
    val title: String,
    val createdAt: String
)

@Composable
fun App(sharedText: String?) {
    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER) }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            status = "收到分享内容，准备推送"
        }
    }

    if (selectedUrl != null) {
        ControlledWebPlayer(url = selectedUrl!!, onBack = { selectedUrl = null })
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("孩子视频推送", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it.trim() },
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        if (!sharedText.isNullOrBlank()) {
            Text("分享内容：${sharedText.take(120)}")
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    loading = true
                    status = runCatching { pushLink(serverUrl, sharedText) }
                        .fold({ "已推送到服务器" }, { "推送失败：${it.message}" })
                    loading = false
                }
            }) {
                Text("推送给平板")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    loading = true
                    status = "正在刷新"
                    videos = runCatching { fetchVideos(serverUrl) }
                        .fold({ it }, { status = "刷新失败：${it.message}"; emptyList() })
                    if (videos.isNotEmpty()) status = "已加载 ${videos.size} 条"
                    loading = false
                }
            }) {
                Text("刷新孩子播放列表")
            }
        }

        Spacer(Modifier.height(8.dp))
        if (loading) CircularProgressIndicator()
        if (status.isNotBlank()) Text(status)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(videos) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF2F2F2))
                        .clickable { selectedUrl = item.shareUrl }
                        .padding(12.dp)
                ) {
                    Text(item.title.ifBlank { "抖音分享链接" }, style = MaterialTheme.typography.titleMedium)
                    Text(item.shareUrl, style = MaterialTheme.typography.bodySmall)
                    Text(item.createdAt, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ControlledWebPlayer(url: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
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
            },
            modifier = Modifier.fillMaxSize()
        )
        Button(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        ) {
            Text("返回")
        }
    }
}

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
