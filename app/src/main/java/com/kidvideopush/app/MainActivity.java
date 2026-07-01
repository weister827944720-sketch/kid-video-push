package com.kidvideopush.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends ComponentActivity {

    private static final String DEFAULT_SERVER = "http://n.dujiaoxian.online:35039";

    private LinearLayout root;
    private EditText serverInput;
    private TextView statusText;
    private LinearLayout listLayout;
    private ProgressBar progress;
    private String sharedText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            sharedText = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        }
        showHome();
    }

    private void showHome() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("孩子视频推送");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        serverInput = new EditText(this);
        serverInput.setText(DEFAULT_SERVER);
        serverInput.setHint("服务器地址");
        serverInput.setSingleLine(true);
        root.addView(serverInput);

        statusText = new TextView(this);
        if (sharedText != null && !sharedText.isEmpty()) {
            statusText.setText("收到分享内容，准备推送");
        }
        statusText.setTextColor(Color.DKGRAY);
        root.addView(statusText);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        if (sharedText != null && !sharedText.isEmpty()) {
            TextView sharedLabel = new TextView(this);
            sharedLabel.setText("分享内容：" + (sharedText.length() > 120 ? sharedText.substring(0, 120) : sharedText));
            root.addView(sharedLabel);

            Button pushBtn = new Button(this);
            pushBtn.setText("推送给平板");
            pushBtn.setOnClickListener(v -> pushSharedLink());
            root.addView(pushBtn);
        }

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新孩子播放列表");
        refreshBtn.setOnClickListener(v -> refreshVideos());
        root.addView(refreshBtn);

        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(listLayout);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    private void pushSharedLink() {
        if (sharedText == null || sharedText.isEmpty()) return;
        new Thread(() -> {
            runOnUiThread(() -> setLoading(true, "正在推送"));
            String serverUrl = serverInput.getText().toString().trim();
            String result;
            try {
                pushLink(serverUrl, sharedText);
                result = "已推送到服务器";
            } catch (Exception e) {
                result = "推送失败：" + e.getMessage();
            }
            final String r = result;
            runOnUiThread(() -> setLoading(false, r));
        }).start();
    }

    private void refreshVideos() {
        new Thread(() -> {
            runOnUiThread(() -> setLoading(true, "正在刷新"));
            String serverUrl = serverInput.getText().toString().trim();
            List<VideoItem> videos = new ArrayList<>();
            String error = null;
            try {
                videos = fetchVideos(serverUrl);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<VideoItem> v = videos;
            final String e = error;
            runOnUiThread(() -> {
                listLayout.removeAllViews();
                if (e != null) {
                    setLoading(false, "刷新失败：" + e);
                } else {
                    for (VideoItem item : v) {
                        addVideoRow(item);
                    }
                    setLoading(false, "已加载 " + v.size() + " 条");
                }
            });
        }).start();
    }

    private void addVideoRow(VideoItem item) {
        String display = (item.title.isEmpty() ? "抖音分享链接" : item.title) + "\n"
                + item.shareUrl + "\n" + item.createdAt;
        TextView row = new TextView(this);
        row.setText(display);
        row.setTextSize(15);
        row.setTextColor(Color.BLACK);
        row.setBackgroundColor(Color.rgb(242, 242, 242));
        row.setPadding(18, 18, 18, 18);
        row.setOnClickListener(v -> showWebPlayer(item.shareUrl));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 10, 0, 10);
        listLayout.addView(row, params);
    }

    private void setLoading(boolean loading, String status) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusText.setText(status);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWebPlayer(String url) {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(Color.BLACK);

        Button back = new Button(this);
        back.setText("返回");
        back.setGravity(Gravity.START);
        back.setOnClickListener(v -> showHome());

        WebView webView = new WebView(this);
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
        webView.loadUrl(url);

        frame.addView(back, new LinearLayout.LayoutParams(-1, -2));
        frame.addView(webView, new LinearLayout.LayoutParams(-1, -1, 1f));
        setContentView(frame);
    }

    private static final String HIDE_DISTRACTIONS_JS =
            "(function() {\n" +
            "  const style = document.createElement('style');\n" +
            "  style.innerHTML = `\n" +
            "    [class*=\"comment\"], [class*=\"like\"], [class*=\"favorite\"], [class*=\"share\"],\n" +
            "    [class*=\"open\"], [class*=\"download\"], [class*=\"login\"], button, header, footer {\n" +
            "      display: none !important;\n" +
            "      visibility: hidden !important;\n" +
            "    }\n" +
            "    video { width: 100vw !important; height: 100vh !important; object-fit: contain !important; }\n" +
            "    body { margin: 0 !important; overflow: hidden !important; background: #000 !important; }\n" +
            "  `;\n" +
            "  document.head.appendChild(style);\n" +
            "})();";

    private static void pushLink(String serverUrl, String rawText) throws Exception {
        String link = extractFirstUrl(rawText);
        if (link == null) link = rawText.trim();
        URL url = new URL(serverUrl.replaceAll("/$", "") + "/api/videos");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        JSONObject body = new JSONObject();
        body.put("shareUrl", link);
        body.put("title", "抖音分享链接");
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8")) {
            writer.write(body.toString());
        }
        int code = conn.getResponseCode();
        if (code < 200 || code > 299) {
            throw new Exception("HTTP " + code);
        }
    }

    private static List<VideoItem> fetchVideos(String serverUrl) throws Exception {
        URL url = new URL(serverUrl.replaceAll("/$", "") + "/api/videos");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        JSONArray array = new JSONArray(sb.toString());
        List<VideoItem> items = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            items.add(new VideoItem(
                    obj.optString("id"),
                    obj.optString("shareUrl"),
                    obj.optString("title"),
                    obj.optString("createdAt")
            ));
        }
        return items;
    }

    private static String extractFirstUrl(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) {
            String url = m.group();
            return url.replaceAll("[，,。.\\s]+$", "");
        }
        return null;
    }

    private static class VideoItem {
        final String id;
        final String shareUrl;
        final String title;
        final String createdAt;

        VideoItem(String id, String shareUrl, String title, String createdAt) {
            this.id = id;
            this.shareUrl = shareUrl;
            this.title = title;
            this.createdAt = createdAt;
        }
    }
}
