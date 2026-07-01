package com.kidvideopush.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String SERVER_URL = "http://n.dujiaoxian.online:35039";
    private static final int MODE_A = 0;
    private static final int MODE_B = 1;
    private static final int SWIPE_THRESHOLD = 120;
    private static final int SWIPE_VELOCITY_THRESHOLD = 120;

    private final List<VideoItem> videos = new ArrayList<>();
    private FrameLayout root;
    private TextView overlay;
    private View gestureLayer;
    private LinearLayout tabs;
    private TextView tabA;
    private TextView tabB;
    private GestureDetector gestureDetector;
    private int currentIndex = 0;
    private int mode = MODE_A;

    private WebView webViewA;
    private WebView preloadWebViewA;
    private WebView parserWebViewB;
    private WebView playerWebViewB;
    private String lastDebugUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            showPushMode(text == null ? "" : text);
            return;
        }

        String clipboardText = readClipboardText();
        if (extractDouyinUrl(clipboardText) != null) {
            showPushMode(clipboardText);
        } else {
            showPlayerMode();
        }
    }

    private String readClipboardText() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) return "";
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? "" : text.toString();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showPlayerMode() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webViewA = createDouyinWebView();
        preloadWebViewA = createDouyinWebView();
        preloadWebViewA.setVisibility(View.GONE);
        parserWebViewB = createDouyinWebView();
        parserWebViewB.setVisibility(View.GONE);
        playerWebViewB = createPlainWebView();
        playerWebViewB.setVisibility(View.GONE);

        root.addView(webViewA, new FrameLayout.LayoutParams(-1, -1));
        root.addView(preloadWebViewA, new FrameLayout.LayoutParams(-1, -1));
        root.addView(parserWebViewB, new FrameLayout.LayoutParams(1, 1));
        root.addView(playerWebViewB, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(16);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0x66000000);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null || videos.isEmpty()) return false;
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY < 0) playNext(); else playPrevious();
                    return true;
                }
                return false;
            }
        });

        gestureLayer = new View(this);
        gestureLayer.setBackgroundColor(Color.TRANSPARENT);
        gestureLayer.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
        root.addView(gestureLayer, new FrameLayout.LayoutParams(-1, -1));
        addTabs();

        setContentView(root);
        showOverlay("正在加载推荐视频...");
        loadVideos();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createPlainWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createDouyinWebView() {
        WebView view = createPlainWebView();
        view.getSettings().setLoadWithOverviewMode(false);
        view.getSettings().setUseWideViewPort(false);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                String url = request.getUrl().toString();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
                return url.contains("/download") || url.contains("open.douyin") || url.contains("snssdk");
            }

            @Override
            public void onPageFinished(WebView webView, String loadedUrl) {
                injectCleaner(webView);
            }
        });
        return view;
    }

    private void addTabs() {
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0x99000000);

        tabA = makeTab("A方案");
        tabB = makeTab("B方案");
        tabs.addView(tabA, new LinearLayout.LayoutParams(0, -1, 1));
        tabs.addView(tabB, new LinearLayout.LayoutParams(0, -1, 1));

        tabA.setOnClickListener(v -> switchMode(MODE_A));
        tabB.setOnClickListener(v -> switchMode(MODE_B));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, 72);
        params.gravity = Gravity.BOTTOM;
        root.addView(tabs, params);
        updateTabs();
    }

    private TextView makeTab(String text) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setTextSize(15);
        tab.setGravity(Gravity.CENTER);
        tab.setTextColor(Color.WHITE);
        return tab;
    }

    private void switchMode(int newMode) {
        if (mode == newMode) return;
        mode = newMode;
        updateTabs();
        playCurrent();
    }

    private void updateTabs() {
        if (tabA == null || tabB == null) return;
        tabA.setBackgroundColor(mode == MODE_A ? 0xCC1D9BF0 : 0x66000000);
        tabB.setBackgroundColor(mode == MODE_B ? 0xCC1D9BF0 : 0x66000000);
    }

    private void loadVideos() {
        new Thread(() -> {
            try {
                List<VideoItem> result = fetchVideos(SERVER_URL);
                Collections.sort(result, (a, b) -> b.createdAt.compareTo(a.createdAt));
                runOnUiThread(() -> {
                    videos.clear();
                    videos.addAll(result);
                    if (videos.isEmpty()) {
                        showOverlay("还没有推送视频\n复制抖音分享文字后打开本 App\n或访问后台网页粘贴推送");
                    } else {
                        currentIndex = 0;
                        playCurrent();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> showOverlay("加载失败\n" + e.getMessage()));
            }
        }).start();
    }

    private void playCurrent() {
        if (videos.isEmpty()) return;
        if (currentIndex < 0) currentIndex = 0;
        if (currentIndex >= videos.size()) currentIndex = videos.size() - 1;
        overlay.setVisibility(View.GONE);
        if (mode == MODE_A) playModeA(); else playModeB();
        updateTabs();
        if (tabs != null) tabs.bringToFront();
        if (gestureLayer != null) gestureLayer.bringToFront();
        if (tabs != null) tabs.bringToFront();
    }

    private void playModeA() {
        playerWebViewB.setVisibility(View.GONE);
        parserWebViewB.setVisibility(View.GONE);
        webViewA.setVisibility(View.VISIBLE);
        preloadWebViewA.setVisibility(View.GONE);
        webViewA.loadUrl(videos.get(currentIndex).shareUrl);
        preloadNextA();
    }

    private void preloadNextA() {
        int next = currentIndex + 1;
        if (next >= videos.size()) return;
        preloadWebViewA.loadUrl(videos.get(next).shareUrl);
    }

    private void playModeB() {
        webViewA.setVisibility(View.GONE);
        preloadWebViewA.setVisibility(View.GONE);
        parserWebViewB.setVisibility(View.GONE);
        playerWebViewB.setVisibility(View.VISIBLE);
        showTemporaryOverlay("B方案解析中...");
        parserWebViewB.loadUrl(videos.get(currentIndex).shareUrl);
        extractPlayUrlWithRetries(0);
    }

    private void extractPlayUrlWithRetries(int attempt) {
        parserWebViewB.postDelayed(() -> parserWebViewB.evaluateJavascript(EXTRACT_PLAY_URL_JS, value -> {
            String playUrl = unquoteJsString(value);
            if (playUrl != null && playUrl.startsWith("http")) {
                playPlainVideo(playUrl);
            } else if (attempt < 8) {
                extractPlayUrlWithRetries(attempt + 1);
            } else {
                showTemporaryOverlay("B方案解析失败，切回A方案");
                mode = MODE_A;
                playCurrent();
            }
        }), attempt == 0 ? 1200 : 700);
    }

    private void playPlainVideo(String playUrl) {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>html,body{margin:0;background:#000;overflow:hidden}video{width:100vw;height:100vh;object-fit:contain;background:#000}</style></head>" +
                "<body><video src='" + escapeHtmlAttr(playUrl) + "' autoplay playsinline loop></video>" +
                "<script>const v=document.querySelector('video');v.controls=false;v.muted=false;function p(){const x=v.play();if(x&&x.catch)x.catch(function(){});}v.oncanplay=p;setInterval(p,500);</script></body></html>";
        playerWebViewB.loadDataWithBaseURL("https://m.douyin.com/", html, "text/html", "UTF-8", null);
    }

    private void playNext() {
        if (currentIndex < videos.size() - 1) {
            currentIndex++;
            playCurrent();
        } else {
            showTemporaryOverlay("已经是最后一个");
        }
    }

    private void playPrevious() {
        if (currentIndex > 0) {
            currentIndex--;
            playCurrent();
        } else {
            showTemporaryOverlay("已经是第一个");
        }
    }

    private void injectCleaner(WebView target) {
        target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 300);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 800);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 1500);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 3000);
        if (target == webViewA) target.postDelayed(this::captureDomDebug, 2500);
    }

    private void captureDomDebug() {
        if (webViewA == null) return;
        String currentUrl = webViewA.getUrl() == null ? "" : webViewA.getUrl();
        if (currentUrl.equals(lastDebugUrl)) return;
        lastDebugUrl = currentUrl;
        webViewA.evaluateJavascript(DOM_DEBUG_JS, value -> {
            if (value == null || value.equals("null")) return;
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("webViewUrl", currentUrl);
                    body.put("payload", new JSONArray("[" + value + "]").get(0));
                    postJson(SERVER_URL + "/debug/dom", body);
                } catch (Exception ignored) {
                }
            }).start();
        });
    }

    private void showOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        if (tabs != null) tabs.bringToFront();
    }

    private void showTemporaryOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        if (tabs != null) tabs.bringToFront();
        overlay.postDelayed(() -> overlay.setVisibility(View.GONE), 800);
    }

    private void showPushMode(String sharedText) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        TextView text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER);
        text.setText("检测到抖音链接\n正在推送给平板...");
        frame.addView(text, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        new Thread(() -> {
            String result;
            try {
                String link = extractDouyinUrl(sharedText);
                if (link == null) throw new Exception("没有找到抖音链接");
                pushLink(SERVER_URL, link);
                result = "已推送到平板\n" + link;
            } catch (Exception e) {
                result = "推送失败\n" + e.getMessage();
            }
            String finalResult = result;
            runOnUiThread(() -> {
                text.setText(finalResult);
                text.postDelayed(() -> {
                    if (finalResult.startsWith("已推送")) finish();
                }, 1800);
            });
        }).start();
    }

    private static final String HIDE_DISTRACTIONS_JS =
            "(function() {\n" +
            "  function hideNoise(){\n" +
            "    const css = `\n" +
            "      html, body, #root, .container, .video-container { margin:0!important; padding:0!important; overflow:hidden!important; background:#000!important; display:block!important; visibility:visible!important; opacity:1!important; }\n" +
            "      .video-container, .horizontal-video { position:fixed!important; inset:0!important; width:100vw!important; height:100vh!important; z-index:1!important; }\n" +
            "      video, #video-player { display:block!important; visibility:visible!important; opacity:1!important; width:100vw!important; height:100vh!important; object-fit:contain!important; position:fixed!important; inset:0!important; z-index:2!important; background:#000!important; }\n" +
            "      .adapt-login-header, .login-header-left, .btn-wrap, .banner-bg, .footer, .bottom-btn-con-new, .right-con,\n" +
            "      .end-page-info, .end-page-info__container, .end-page-info__waterfall, .end-page-info-button,\n" +
            "      .arco-masking, .arco-popup, .commentBoard_8924a, .commentBoardTopBanner_8924a, .commentList_8924a,\n" +
            "      .video-card__like, .video-card__like__count, .video-card__cover__wrapper, .progress_small-wrapper,\n" +
            "      [href*=\\\"download\\\"], [href*=\\\"snssdk\\\"], [href*=\\\"open\\\"], [data-e2e*=\\\"like\\\"], [data-e2e*=\\\"comment\\\"] {\n" +
            "        display:none!important; visibility:hidden!important; opacity:0!important; pointer-events:none!important; width:0!important; height:0!important;\n" +
            "      }\n" +
            "    `;\n" +
            "    let style = document.getElementById('kid-clean-style');\n" +
            "    if(!style){ style = document.createElement('style'); style.id='kid-clean-style'; document.head.appendChild(style); }\n" +
            "    style.textContent = css;\n" +
            "    document.querySelectorAll('a,button,span').forEach(function(el){\n" +
            "      const text=(el.innerText||el.textContent||'').trim();\n" +
            "      const cls=(el.className||'').toString();\n" +
            "      const href=(el.getAttribute&&el.getAttribute('href'))||'';\n" +
            "      if(/打开|App|APP|抖音|关注|点赞|评论|收藏|分享|登录|下载|播放/.test(text) || /open|download|login|follow|like|comment|favorite|share|play|pause/i.test(cls+href)){\n" +
            "        el.style.setProperty('display','none','important');\n" +
            "        el.style.setProperty('visibility','hidden','important');\n" +
            "        el.style.setProperty('pointer-events','none','important');\n" +
            "      }\n" +
            "    });\n" +
            "    ['root'].forEach(function(id){ const el=document.getElementById(id); if(el){ el.style.setProperty('display','block','important'); el.style.setProperty('visibility','visible','important'); el.style.setProperty('opacity','1','important'); } });\n" +
            "    document.querySelectorAll('.container,.video-container,.horizontal-video').forEach(function(el){ el.style.setProperty('display','block','important'); el.style.setProperty('visibility','visible','important'); el.style.setProperty('opacity','1','important'); });\n" +
            "    const video=document.querySelector('video');\n" +
            "    if(video){\n" +
            "      video.muted=false; video.controls=false; video.loop=true; video.autoplay=true; video.playsInline=true;\n" +
            "      video.style.cssText='width:100vw!important;height:100vh!important;object-fit:contain!important;position:fixed!important;inset:0!important;z-index:1!important;background:#000!important';\n" +
            "      const p=video.play(); if(p&&p.catch){ p.catch(function(){}); }\n" +
            "    }\n" +
            "  }\n" +
            "  hideNoise();\n" +
            "  setInterval(hideNoise, 400);\n" +
            "})();";

    private static final String EXTRACT_PLAY_URL_JS =
            "(function(){ var v=document.querySelector('video'); if(!v) return ''; var src=v.currentSrc||v.src||''; if(src && src.indexOf('http')!==0){ src=new URL(src, location.href).href; } return src; })();";

    private static final String DOM_DEBUG_JS =
            "(function(){\n" +
            "  function shortText(v){ return (v||'').replace(/\\s+/g,' ').trim().slice(0,200); }\n" +
            "  function item(el){ return { tag: el.tagName, id: el.id||'', cls: (el.className||'').toString().slice(0,300), text: shortText(el.innerText||el.textContent), href: el.getAttribute&&el.getAttribute('href')||'', role: el.getAttribute&&el.getAttribute('role')||'', dataE2e: el.getAttribute&&el.getAttribute('data-e2e')||'', aria: el.getAttribute&&el.getAttribute('aria-label')||'' }; }\n" +
            "  var noisy=[];\n" +
            "  document.querySelectorAll('a,button,div,span').forEach(function(el){\n" +
            "    var s=[el.innerText, el.textContent, el.className, el.id, el.getAttribute&&el.getAttribute('href'), el.getAttribute&&el.getAttribute('aria-label'), el.getAttribute&&el.getAttribute('data-e2e')].join(' ');\n" +
            "    if(/打开|App|APP|抖音|关注|点赞|评论|收藏|分享|登录|下载|open|download|login|follow|like|comment|favorite|share/i.test(s)){ noisy.push(item(el)); }\n" +
            "  });\n" +
            "  var buttons=Array.prototype.slice.call(document.querySelectorAll('button,[role=button],a')).slice(0,160).map(item);\n" +
            "  var videos=Array.prototype.slice.call(document.querySelectorAll('video')).map(function(v){ return { src: v.currentSrc||v.src||'', paused:v.paused, muted:v.muted, controls:v.controls, autoplay:v.autoplay, width:v.videoWidth, height:v.videoHeight, cls:(v.className||'').toString() }; });\n" +
            "  return { url: location.href, title: document.title, bodyText: shortText(document.body&&document.body.innerText), videos: videos, buttons: buttons, noisy: noisy.slice(0,220), htmlSample: (document.body&&document.body.outerHTML||'').slice(0,12000) };\n" +
            "})();";

    private static void pushLink(String serverUrl, String link) throws Exception {
        URL url = new URL(serverUrl.replaceAll("/$", "") + "/api/videos");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        JSONObject body = new JSONObject();
        body.put("shareUrl", link);
        body.put("text", link);
        body.put("title", "抖音分享链接");
        writeJson(conn, body);
        int code = conn.getResponseCode();
        if (code < 200 || code > 299) throw new Exception("HTTP " + code);
    }

    private static void postJson(String urlString, JSONObject body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        writeJson(conn, body);
        conn.getResponseCode();
    }

    private static void writeJson(HttpURLConnection conn, JSONObject body) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8")) {
            writer.write(body.toString());
        }
    }

    private static List<VideoItem> fetchVideos(String serverUrl) throws Exception {
        URL url = new URL(serverUrl.replaceAll("/$", "") + "/api/videos");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        JSONArray array = new JSONArray(sb.toString());
        List<VideoItem> items = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            String shareUrl = obj.optString("shareUrl");
            if (extractDouyinUrl(shareUrl) != null) {
                items.add(new VideoItem(shareUrl, obj.optString("createdAt")));
            }
        }
        return items;
    }

    private static String extractDouyinUrl(String text) {
        if (text == null) return null;
        Matcher shortMatcher = Pattern.compile("https?://v\\.douyin\\.com/[^\\s，,。]+/", Pattern.CASE_INSENSITIVE).matcher(text);
        if (shortMatcher.find()) return shortMatcher.group();

        Matcher videoMatcher = Pattern.compile("https?://(?:www\\.)?douyin\\.com/video/\\d+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (videoMatcher.find()) return videoMatcher.group();

        Matcher anyMatcher = Pattern.compile("https?://[^\\s，,。]*douyin\\.com/[^\\s，,。]*", Pattern.CASE_INSENSITIVE).matcher(text);
        if (anyMatcher.find()) return anyMatcher.group().replaceAll("[，,。\\s]+$", "");
        return null;
    }

    private static String unquoteJsString(String value) {
        if (value == null || value.equals("null")) return null;
        try {
            return new JSONArray("[" + value + "]").optString(0, null);
        } catch (Exception e) {
            return value.replace("\\\"", "").replace("\"", "");
        }
    }

    private static String escapeHtmlAttr(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("'", "&#39;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class VideoItem {
        final String shareUrl;
        final String createdAt;
        VideoItem(String shareUrl, String createdAt) {
            this.shareUrl = shareUrl;
            this.createdAt = createdAt == null ? "" : createdAt;
        }
    }
}
