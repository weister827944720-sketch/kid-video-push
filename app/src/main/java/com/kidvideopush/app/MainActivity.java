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
    private static final int SWIPE_THRESHOLD = 120;
    private static final int SWIPE_VELOCITY_THRESHOLD = 120;
    private static final int PRELOAD_WEBVIEW_COUNT = 2;

    private final List<VideoItem> videos = new ArrayList<>();
    private FrameLayout root;
    private WebView webView;
    private final WebView[] preloadWebViews = new WebView[3];
    private final int[] preloadIndexes = {-1, -1, -1};
    private TextView normalTab;
    private TextView preloadTab;
    private TextView overlay;
    private GestureDetector gestureDetector;
    private int currentIndex = 0;
    private String lastDebugUrl = "";
    private boolean preloadMode = false;

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

        webView = createWebView(false);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(16);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0x99000000);
        normalTab = createTab("普通播放", true);
        preloadTab = createTab("预加载播放", false);
        tabs.addView(normalTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(preloadTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        FrameLayout.LayoutParams tabParams = new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM);
        root.addView(tabs, tabParams);

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

        setContentView(root);
        showOverlay("正在加载推荐视频...");
        loadVideos();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(boolean forPreload) {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        view.getSettings().setLoadWithOverviewMode(false);
        view.getSettings().setUseWideViewPort(false);
        view.setOnTouchListener((v, event) -> {
            if (gestureDetector != null) gestureDetector.onTouchEvent(event);
            return false;
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                String url = request.getUrl().toString();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
                return url.contains("/download") || url.contains("open.douyin") || url.contains("snssdk");
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                view.evaluateJavascript(forPreload && view != getCurrentPreloadWebView() ? "window.__kidPreload=true" : "window.__kidPreload=false", null);
                injectCleaner(view);
                if (forPreload && view != getCurrentPreloadWebView()) {
                    view.postDelayed(() -> preloadVideo(view), 400);
                } else {
                    view.postDelayed(() -> triggerPlay(view), 400);
                }
            }
        });
        return view;
    }

    private TextView createTab(String text, boolean selected) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setGravity(Gravity.CENTER);
        tab.setTextSize(15);
        tab.setTextColor(Color.WHITE);
        tab.setBackgroundColor(selected ? 0xAA333333 : 0x66000000);
        tab.setOnClickListener(v -> setPreloadMode(tab == preloadTab));
        return tab;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setPreloadMode(boolean enabled) {
        if (preloadMode == enabled) return;
        preloadMode = enabled;
        normalTab.setBackgroundColor(enabled ? 0x66000000 : 0xAA333333);
        preloadTab.setBackgroundColor(enabled ? 0xAA333333 : 0x66000000);
        if (videos.isEmpty()) return;
        if (enabled) {
            ensurePreloadViews();
            pauseWebView(webView);
            webView.setVisibility(View.INVISIBLE);
            showPreloadedCurrent(true);
        } else {
            destroyPreloadViews();
            webView.setVisibility(View.VISIBLE);
            playCurrent();
        }
    }

    private void ensurePreloadViews() {
        for (int i = 0; i < PRELOAD_WEBVIEW_COUNT; i++) {
            if (preloadWebViews[i] == null) {
                WebView view = createWebView(true);
                view.setVisibility(View.INVISIBLE);
                preloadWebViews[i] = view;
                root.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
            }
        }
    }

    private void destroyPreloadViews() {
        for (int i = 0; i < preloadWebViews.length; i++) {
            WebView view = preloadWebViews[i];
            if (view != null) {
                pauseWebView(view);
                view.stopLoading();
                view.loadUrl("about:blank");
                root.removeView(view);
                view.destroy();
                preloadWebViews[i] = null;
            }
            preloadIndexes[i] = -1;
        }
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
        if (preloadMode) {
            showPreloadedCurrent(false);
            return;
        }
        VideoItem item = videos.get(currentIndex);
        overlay.setVisibility(View.GONE);
        webView.loadUrl(item.shareUrl);
    }

    private void showPreloadedCurrent(boolean forceLoad) {
        ensurePreloadViews();
        overlay.setVisibility(View.GONE);

        WebView current = getOrAssignPreloadWebView(currentIndex);
        for (WebView view : preloadWebViews) {
            if (view != null && view != current) {
                pauseWebView(view);
                view.setVisibility(View.INVISIBLE);
            }
        }
        current.setVisibility(View.VISIBLE);
        if (forceLoad || preloadIndexes[getPreloadSlot(current)] != currentIndex) {
            current.loadUrl(videos.get(currentIndex).shareUrl);
        } else {
            triggerPlay(current);
        }
        loadAdjacentPreloads();
    }

    private WebView getCurrentPreloadWebView() {
        if (!preloadMode) return null;
        for (int i = 0; i < PRELOAD_WEBVIEW_COUNT; i++) {
            if (preloadIndexes[i] == currentIndex) return preloadWebViews[i];
        }
        return null;
    }

    private WebView getOrAssignPreloadWebView(int index) {
        for (int i = 0; i < PRELOAD_WEBVIEW_COUNT; i++) {
            if (preloadIndexes[i] == index) return preloadWebViews[i];
        }
        int slot = findReusablePreloadSlot();
        preloadIndexes[slot] = index;
        preloadWebViews[slot].loadUrl(videos.get(index).shareUrl);
        return preloadWebViews[slot];
    }

    private int findReusablePreloadSlot() {
        for (int i = 0; i < PRELOAD_WEBVIEW_COUNT; i++) {
            if (preloadIndexes[i] < 0) return i;
        }
        for (int i = 0; i < PRELOAD_WEBVIEW_COUNT; i++) {
            if (Math.abs(preloadIndexes[i] - currentIndex) > 1) return i;
        }
        return 0;
    }

    private int getPreloadSlot(WebView view) {
        for (int i = 0; i < PRELOAD_WEBVIEW_COUNT; i++) {
            if (preloadWebViews[i] == view) return i;
        }
        return 0;
    }

    private void loadAdjacentPreloads() {
        preloadIndex(currentIndex + 1);
    }

    private void preloadIndex(int index) {
        if (index < 0 || index >= videos.size()) return;
        for (int existing : preloadIndexes) {
            if (existing == index) return;
        }
        int slot = findReusablePreloadSlot();
        preloadIndexes[slot] = index;
        preloadWebViews[slot].setVisibility(View.INVISIBLE);
        preloadWebViews[slot].loadUrl(videos.get(index).shareUrl);
    }

    private void triggerPlay() {
        triggerPlay(webView);
    }

    private void triggerPlay(WebView target) {
        if (target == null || target.getWidth() == 0 || target.getHeight() == 0) return;

        float cx = target.getWidth() / 2f;
        float cy = target.getHeight() / 2f;
        long now = android.os.SystemClock.uptimeMillis();

        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, cx, cy, 0);
        target.dispatchTouchEvent(down);
        target.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();

        target.postDelayed(() -> target.evaluateJavascript(
                "(function(){" +
                        "window.__kidPreload=false;" +
                        "var v=document.querySelector('video');" +
                        "if(v){v.muted=false;v.controls=false;var p=v.play();if(p&&p.catch){p.catch(function(){});}}" +
                        "})()",
                null), 100);
    }

    private void preloadVideo(WebView target) {
        target.evaluateJavascript(
                "(function(){" +
                        "window.__kidPreload=true;" +
                        "var v=document.querySelector('video');" +
                        "if(v){v.muted=true;v.controls=false;v.preload='auto';var p=v.play();if(p&&p.then){p.then(function(){setTimeout(function(){try{v.pause();}catch(e){}},150);}).catch(function(){});}}" +
                        "})()",
                null);
    }

    private void pauseWebView(WebView target) {
        if (target == null) return;
        target.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.muted=true;try{v.pause();}catch(e){}}})()", null);
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

    private void injectCleaner() {
        injectCleaner(webView);
    }

    private void injectCleaner(WebView target) {
        target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 80);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 250);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 500);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 900);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 1500);
        target.postDelayed(() -> target.evaluateJavascript(HIDE_DISTRACTIONS_JS, null), 2500);
        webView.postDelayed(this::captureDomDebug, 2500);
    }

    private void captureDomDebug() {
        if (webView == null) return;
        String currentUrl = webView.getUrl() == null ? "" : webView.getUrl();
        if (currentUrl.equals(lastDebugUrl)) return;
        lastDebugUrl = currentUrl;
        webView.evaluateJavascript(DOM_DEBUG_JS, value -> {
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
    }

    private void showTemporaryOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
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
            "      html, body, #root, .container, .video-container { margin:0!important; padding:0!important; overflow:hidden!important; background:#000!important; display:block!important; visibility:visible!important; opacity:1!important; pointer-events:auto!important; }\n" +
            "      .video-container, .horizontal-video { position:fixed!important; inset:0!important; width:100vw!important; height:100vh!important; z-index:1!important; }\n" +
            "      video, #video-player { display:block!important; visibility:visible!important; opacity:1!important; width:100vw!important; height:100vh!important; object-fit:contain!important; position:fixed!important; inset:0!important; z-index:2!important; background:#000!important; }\n" +
            "      .footer { display:block!important; visibility:visible!important; opacity:1!important; position:fixed!important; left:0!important; right:0!important; bottom:0!important; z-index:3!important; pointer-events:none!important; color:#fff!important; }\n" +
            "      .adapt-login-header, .login-header-left, .btn-wrap, .banner-bg, .video-msg-container, .bottom-btn-con-new, .right-con,\n" +
            "      .end-page-info, .end-page-info__container, .end-page-info__waterfall, .end-page-info-button,\n" +
            "      .arco-masking, .arco-popup, .commentBoard_8924a, .commentBoardTopBanner_8924a, .commentList_8924a,\n" +
            "      .video-card__like, .video-card__like__count, .video-card__cover__wrapper,\n" +
            "      [href*=\\\"download\\\"], [href*=\\\"snssdk\\\"], [href*=\\\"open\\\"], [data-e2e*=\\\"like\\\"], [data-e2e*=\\\"comment\\\"] {\n" +
            "        display:none!important; visibility:hidden!important; opacity:0!important; pointer-events:none!important; width:0!important; height:0!important;\n" +
            "      }\n" +
            "    `;\n" +
            "    let style = document.getElementById('kid-clean-style');\n" +
            "    if(!style){ style = document.createElement('style'); style.id='kid-clean-style'; document.head.appendChild(style); }\n" +
            "    style.textContent = css;\n" +
            "    document.querySelectorAll('a,button,span,div').forEach(function(el){\n" +
            "      if(el.closest && el.closest('.footer') && !(el.innerText||el.textContent||'').match(/打开抖音看精彩视频|打开App|去抖音/)) return;\n" +
            "      const text=(el.innerText||el.textContent||'').trim();\n" +
            "      const cls=(el.className||'').toString();\n" +
            "      const href=(el.getAttribute&&el.getAttribute('href'))||'';\n" +
            "      if(/打开抖音看精彩视频|打开App|去抖音|关注|点赞|评论|收藏|分享|登录|下载/.test(text) || /open|download|login|follow|like|comment|favorite|share/i.test(cls+href)){\n" +
            "        el.style.setProperty('display','none','important');\n" +
            "        el.style.setProperty('visibility','hidden','important');\n" +
            "        el.style.setProperty('pointer-events','none','important');\n" +
            "      }\n" +
            "    });\n" +
            "    ['root'].forEach(function(id){ const el=document.getElementById(id); if(el){ el.style.setProperty('display','block','important'); el.style.setProperty('visibility','visible','important'); el.style.setProperty('opacity','1','important'); } });\n" +
            "    document.querySelectorAll('.container,.video-container,.horizontal-video').forEach(function(el){ el.style.setProperty('display','block','important'); el.style.setProperty('visibility','visible','important'); el.style.setProperty('opacity','1','important'); });\n" +
            "    const video=document.querySelector('video');\n" +
            "    if(video){\n" +
            "      video.muted=!!window.__kidPreload; video.controls=false; video.loop=true; video.autoplay=true; video.playsInline=true;\n" +
            "      video.style.cssText='width:100vw!important;height:100vh!important;object-fit:contain!important;position:fixed!important;inset:0!important;z-index:1!important;background:#000!important';\n" +
            "      video.preload='auto'; if(!window.__kidPreload){ const p=video.play(); if(p&&p.catch){ p.catch(function(){}); } }\n" +
            "    }\n" +
            "  }\n" +
            "  hideNoise();\n" +
            "  setInterval(hideNoise, 1200);\n" +
            "})();";

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

    private static class VideoItem {
        final String shareUrl;
        final String createdAt;
        VideoItem(String shareUrl, String createdAt) {
            this.shareUrl = shareUrl;
            this.createdAt = createdAt == null ? "" : createdAt;
        }
    }
}
