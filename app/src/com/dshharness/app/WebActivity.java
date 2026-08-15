package com.dshharness.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 内置浏览器：加载 Harness Web UI（默认）或任意 URL（如 F-Droid 下载页）。
 * Apple 风格：半透明悬浮工具栏 + 内容在工具栏下方滚动 + 关闭/返回/前进/刷新/外部打开。
 */
public class WebActivity extends Activity {

    public static final String EXTRA_URL = "url";
    private static final String DEFAULT_URL = "http://127.0.0.1:3080";

    private WebView web;
    private TextView titleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.length() == 0) url = DEFAULT_URL;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (title != null && title.length() > 0) titleView.setText(title);
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String u, String userAgent, String contentDisposition,
                                                 String mimetype, long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception e) {
                    try {
                        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u));
                        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        dm.enqueue(r);
                    } catch (Exception ignored) {}
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 悬浮工具栏（半透明材质）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Design.dp(this, 6), Design.dp(this, 6), Design.dp(this, 6), Design.dp(this, 6));
        bar.setBackgroundColor(Color.argb(235, 249, 249, 252));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(this, 46), Gravity.TOP);
        root.addView(bar, blp);

        bar.addView(toolButton("✕", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        bar.addView(toolButton("←", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (web.canGoBack()) web.goBack();
            }
        }));
        bar.addView(toolButton("→", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (web.canGoForward()) web.goForward();
            }
        }));
        bar.addView(toolButton("⟳", new View.OnClickListener() {
            @Override public void onClick(View v) { web.reload(); }
        }));

        titleView = new TextView(this);
        titleView.setText("DeepSeek Harness");
        titleView.setTextSize(14);
        titleView.setTextColor(Design.TEXT);
        titleView.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Design.dp(this, 8), 0, Design.dp(this, 4), 0);
        bar.addView(titleView, tlp);

        bar.addView(toolButton("↗", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())));
                } catch (Exception ignored) {}
            }
        }));

        // 内容顶到工具栏下方
        FrameLayout.LayoutParams wlp = (FrameLayout.LayoutParams) web.getLayoutParams();
        wlp.topMargin = Design.dp(this, 46);
        web.setLayoutParams(wlp);

        setContentView(root);
        web.loadUrl(url);
    }

    private TextView toolButton(String glyph, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(17);
        t.setTextColor(Design.BLUE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Design.dp(this, 40), Design.dp(this, 36));
        t.setLayoutParams(lp);
        Design.pressable(t);
        t.setOnClickListener(l);
        return t;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
