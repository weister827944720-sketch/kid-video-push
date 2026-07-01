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
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String SERVER_URL = "http://n.dujiaoxian.online:35039";
    private static final int SWIPE_THRESHOLD = 120;
    private static final int SWIPE_VELOCITY_THRESHOLD = 120;

    private final List<VideoItem> videos = new ArrayList<>();
    private FrameLayout root;
    private WebView webView;
    private TextView overlay;
    private GestureDetector gestureDetector;
    private int currentIndex = 0;

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

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                view.evaluateJavascript(HIDE_DISTRACTIONS_JS, null);
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

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

        root.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
        webView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });

        setContentView(root);
        showOverlay("正在加载推荐视频...");
        loadVideos();
    }

    private void loadVideos() {
        new Thread(() -> {
            try {
                List<VideoItem> result = fetchVideos(SERVER_URL);
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
        VideoItem item = videos.get(currentIndex);
        overlay.setVisibility(View.GONE);
        webView.loadUrl(item.shareUrl);
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

    private void showOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
    }

    private void showTemporaryOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
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
            "  const style = document.createElement('style');\n" +
            "  style.innerHTML = `\n" +
            "    [class*=\"comment\"], [class*=\"like\"], [class*=\"favorite\"], [class*=\"share\"],\n" +
            "    [class*=\"open\"], [class*=\"download\"], [class*=\"login\"], button, header, footer {\n" +
            "      display: none !important; visibility: hidden !important;\n" +
            "    }\n" +
            "    video { width: 100vw !important; height: 100vh !important; object-fit: cover !important; }\n" +
            "    body { margin: 0 !important; overflow: hidden !important; background: #000 !important; }\n" +
            "  `;\n" +
            "  document.head.appendChild(style);\n" +
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
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8")) {
            writer.write(body.toString());
        }
        int code = conn.getResponseCode();
        if (code < 200 || code > 299) throw new Exception("HTTP " + code);
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
            if (extractDouyinUrl(shareUrl) != null) items.add(new VideoItem(shareUrl));
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
        VideoItem(String shareUrl) {
            this.shareUrl = shareUrl;
        }
    }
}
