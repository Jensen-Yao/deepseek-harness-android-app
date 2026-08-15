package com.dshharness.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * DeepSeek Harness —— 通用控制 App
 * 标签页：控制（服务状态/启停/日志）· 部署（Termux 引导 + 一键部署）· 关于（环境信息）
 * 内置 WebView 打开 Harness；Apple 设计语言。
 */
public class MainActivity extends Activity {

    private static final String DAEMON = "http://127.0.0.1:8023";
    private static final String HARNESS = "http://127.0.0.1:3080";
    private static final String TERMUX_PKG = "com.termux";
    private static final String RUN_PERM = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh";
    private static final String FDROID_URL = "https://f-droid.org/packages/com.termux/";
    private static final String REPO_URL = "https://github.com/Jensen-Yao/deepseek-harness-android-app";
    private static final String VIA_PKG = "mark.via";
    /** 候选浏览器（包名 + 显示名），配合 manifest <queries> 检测已装 */
    private static final String[][] BROWSERS = {
            {"mark.via", "Via 浏览器（默认）"},
            {"com.android.chrome", "Chrome"},
            {"com.microsoft.emmx", "Edge"},
            {"org.mozilla.firefox", "Firefox"},
            {"com.oplus.browser", "OPPO 浏览器"},
            {"com.huawei.browser", "华为浏览器"},
            {"com.android.browser", "系统浏览器"},
            {"com.quark.browser", "夸克浏览器"},
            {"com.UCMobile", "UC 浏览器"},
            {"com.tencent.mtt", "QQ 浏览器"},
            {"com.baidu.browser", "百度浏览器"},
    };
    /** 恢复脚本：拉起 sshd + 控制守护进程（幂等），Termux 被杀后一键复活 */
    private static final String RECOVERY_SCRIPT =
            "nohup sshd >/dev/null 2>&1 || true; "
            + "if [ -f $HOME/dsh/control/start-daemon.sh ]; then "
            + "sh $HOME/dsh/control/start-daemon.sh; fi";

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 页面
    private View controlPage, deployPage, aboutPage;
    private View[] pages;
    private final TextView[] tabGlyphs = new TextView[3];
    private final TextView[] tabLabels = new TextView[3];
    private int currentTab = 0;

    // 控制页组件
    private TextView ctlStatus, ctlSub, ctlLog;
    private LinearLayout ctlStatusCard;

    // 部署页组件
    private TextView depPhase, depElapsed, depLog, depTermuxRow, depDaemonRow;
    private LinearLayout depIdleCard, depProgressCard, depDoneCard;
    private LinearLayout termuxActionArea;
    private LinearLayout depPasteCard;
    private LinearLayout depBatteryCard;
    private android.widget.ProgressBar depDownloadProgress;
    private TextView depDownloadText;
    private boolean termuxDownloading = false;
    private boolean deploying = false;
    private long deployStartAt = 0;
    private boolean deployDoneNotified = false;
    private boolean allowGuideShown = false;

    /** 按机型生成 Termux 安装包候选下载源：先按架构匹配小体积专属包，再兜底通用包 */
    private String[] getTermuxApkUrls() {
        String abi = detectedAbi();
        return new String[]{
                // 机型专属包（体积更小），走 GitHub 加速镜像
                "https://gh-proxy.com/https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_" + abi + ".apk",
                "https://ghfast.top/https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_" + abi + ".apk",
                "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_" + abi + ".apk",
                // 通用包（F-Droid 全架构，兼容任何机型）
                "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1022.apk",
                "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1021.apk",
                "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1020.apk",
                "https://mirrors.bfsu.edu.cn/fdroid/repo/com.termux_1022.apk",
                "https://f-droid.org/repo/com.termux_1022.apk",
                "https://d.serctl.com/?uuid=b60d5334-0fd3-4814-829c-0c84529a16ee",
        };
    }

    private String detectedAbi() {
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
        } catch (Exception ignored) {}
        return "arm64-v8a";
    }

    // 关于页
    private LinearLayout envCard;
    private LinearLayout storageCard;
    private LinearLayout browserList;
    private TextView browserPathLabel;
    private String browserDir = "";
    private String viewerPathStr = "";
    private EditText viewerEdit;

    // 弹层
    private FrameLayout overlay;
    private View sheetView;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            refreshControl();
            if (deploying) refreshDeployProgress();
            refreshEnv();
            refreshDaemonRow();
            ui.postDelayed(this, 4000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 外层 FrameLayout：弹层作为顶层兄弟，避免 LinearLayout 权重计算把布局挤乱
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Design.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        rootFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 页面容器
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        controlPage = buildControlPage();
        deployPage = buildDeployPage();
        aboutPage = buildAboutPage();
        pages = new View[]{controlPage, deployPage, aboutPage};
        for (View p : pages) {
            content.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 底部标签栏
        root.addView(buildTabBar());

        // 弹层容器（覆盖全屏，含标签栏）
        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);

        // 常驻部署脚本服务（127.0.0.1:8045，前台服务防后台冻结）：打开 App 即可在 Termux 粘贴命令部署
        startDeployServer();

        selectTab(0);
        refreshControl();
        refreshDeployPage();
        ui.postDelayed(loop, 4000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统安装器/设置页返回时刷新 Termux 状态与向导
        refreshDeployPage();
        refreshControl();
        refreshStorage();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    // ================================================================ 标签栏

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Design.BAR_BG);
        bar.setPadding(0, Design.dp(this, 6), 0, Design.dp(this, 8));

        String[] glyphs = {"●", "⬇", "ⓘ"};
        String[] labels = {"控制", "部署", "关于"};
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(clp);

            TextView g = new TextView(this);
            g.setText(glyphs[i]);
            g.setTextSize(18);
            g.setTextColor(Design.TEXT3);
            g.setGravity(Gravity.CENTER);
            cell.addView(g, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView l = new TextView(this);
            l.setText(labels[i]);
            l.setTextSize(10);
            l.setTextColor(Design.TEXT3);
            l.setGravity(Gravity.CENTER);
            cell.addView(l, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final int idx = i;
            Design.pressable(cell);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });
            bar.addView(cell);
            tabGlyphs[i] = g;
            tabLabels[i] = l;
        }

        // 顶部分隔线
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        View sep = new View(this);
        sep.setBackgroundColor(Color.argb(90, 60, 60, 67));
        wrap.addView(sep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        wrap.addView(bar);
        return wrap;
    }

    private void selectTab(int idx) {
        boolean reduce = Design.reducedMotion(this);
        for (int i = 0; i < pages.length; i++) {
            final View p = pages[i];
            boolean on = i == idx;
            p.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                tabGlyphs[i].setTextColor(Design.BLUE);
                tabLabels[i].setTextColor(Design.BLUE);
                if (!reduce) {
                    p.setAlpha(0f);
                    p.setScaleX(0.985f);
                    p.setScaleY(0.985f);
                    p.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(420)
                            .setInterpolator(new Design.Spring(1.0, 0.4))
                            .start();
                } else {
                    p.setAlpha(1f);
                }
            } else {
                tabGlyphs[i].setTextColor(Design.TEXT3);
                tabLabels[i].setTextColor(Design.TEXT3);
            }
        }
        currentTab = idx;
    }

    // ================================================================ 控制页

    private View buildControlPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Design.dp(this, 16), Design.dp(this, 24), Design.dp(this, 16), Design.dp(this, 16));
        sv.addView(col);

        TextView title = Design.largeTitle(this, "服务控制");
        col.addView(title);
        TextView sub = Design.footnote(this, "DeepSeek Harness · " + getWebUrl());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, Design.dp(this, 2), 0, Design.dp(this, 16));
        sub.setLayoutParams(slp);
        col.addView(sub);

        // 状态卡
        ctlStatusCard = new LinearLayout(this);
        ctlStatusCard.setOrientation(LinearLayout.VERTICAL);
        ctlStatusCard.setPadding(Design.dp(this, 16), Design.dp(this, 16), Design.dp(this, 16), Design.dp(this, 16));
        ctlStatusCard.setBackground(Design.card(this));
        ctlStatus = Design.headline(this, "正在连接…");
        ctlStatus.setGravity(Gravity.CENTER);
        ctlStatusCard.addView(ctlStatus);
        ctlSub = Design.footnote(this, "");
        ctlSub.setGravity(Gravity.CENTER);
        ctlStatusCard.addView(ctlSub);
        col.addView(ctlStatusCard);

        space(col, 12);

        // 打开 Harness（主按钮）
        TextView open = primaryButton("打开 Harness");
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Design.haptic(v, HapticFeedbackConstants.CONFIRM);
                openWeb(getWebUrl());
            }
        });
        col.addView(open);

        space(col, 12);

        // 启停行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(tintButton("启动", Design.GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { action("/api/start"); }
        }));
        row.addView(tintButton("停止", Design.RED, new View.OnClickListener() {
            @Override public void onClick(View v) { action("/api/stop"); }
        }));
        row.addView(tintButton("重启", Design.ORANGE, new View.OnClickListener() {
            @Override public void onClick(View v) { action("/api/restart"); }
        }));
        col.addView(row);

        space(col, 16);

        // 日志卡
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(Design.dp(this, 16), Design.dp(this, 14), Design.dp(this, 16), Design.dp(this, 14));
        logCard.setBackground(Design.card(this));
        TextView logTitle = Design.headline(this, "服务日志");
        logCard.addView(logTitle);
        ctlLog = new TextView(this);
        ctlLog.setTextSize(11);
        ctlLog.setTypeface(Typeface.MONOSPACE);
        ctlLog.setTextColor(Design.TEXT2);
        ctlLog.setText("(暂无日志)");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMargins(0, Design.dp(this, 6), 0, 0);
        ctlLog.setLayoutParams(llp);
        logCard.addView(ctlLog);
        col.addView(logCard);

        return sv;
    }

    private void refreshControl() {
        request("GET", "/api/status", new Callback() {
            @Override public void onResult(JSONObject o) {
                if (!o.optBoolean("ok", false)) { showCtlDisconnected(); return; }
                boolean running = o.optBoolean("running", false);
                String ver = o.optString("dshVersion", "");
                if (running) {
                    ctlStatus.setText("● 运行中");
                    ctlStatus.setTextColor(Design.GREEN);
                    ctlSub.setText("dsh " + ver + " · " + HARNESS);
                    ctlStatusCard.setBackground(Design.round(MainActivity.this, 16, Color.parseColor("#E8F8EE")));
                } else {
                    ctlStatus.setText("○ 已停止");
                    ctlStatus.setTextColor(Design.TEXT3);
                    ctlSub.setText("点击「启动」开始服务");
                    ctlStatusCard.setBackground(Design.card(MainActivity.this));
                }
                JSONArray arr = o.optJSONArray("lastLog");
                StringBuilder sb = new StringBuilder();
                if (arr != null && arr.length() > 0) {
                    for (int i = 0; i < arr.length(); i++) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(arr.optString(i));
                    }
                } else sb.append("(暂无日志)");
                ctlLog.setText(sb.toString());
            }
            @Override public void onError(Exception e) { showCtlDisconnected(); }
        });
    }

    private void showCtlDisconnected() {
        ctlStatus.setText("✕ 控制服务未连接");
        ctlStatus.setTextColor(Design.RED);
        ctlSub.setText("请到「部署」页检查 Termux 状态");
        ctlStatusCard.setBackground(Design.round(this, 16, Color.parseColor("#FEE7E6")));
    }

    private void action(String path) {
        request("POST", path, new Callback() {
            @Override public void onResult(JSONObject o) {
                toast(o.optBoolean("ok", false) ? "操作完成" : "操作失败");
                refreshControl();
            }
            @Override public void onError(Exception e) { toast("控制服务未连接"); }
        });
    }

    // ================================================================ 部署页

    private View buildDeployPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Design.dp(this, 16), Design.dp(this, 24), Design.dp(this, 16), Design.dp(this, 16));
        sv.addView(col);

        col.addView(Design.largeTitle(this, "部署"));

        // 设备卡
        LinearLayout devCard = cardCol();
        devCard.addView(Design.headline(this, "本机设备"));
        addRow(devCard, "型号", Build.MANUFACTURER + " " + Build.MODEL);
        addRow(devCard, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addRow(devCard, "架构", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a");
        col.addView(devCard);

        space(col, 12);

        // Termux 卡
        LinearLayout txCard = cardCol();
        txCard.addView(Design.headline(this, "Termux 环境"));
        depTermuxRow = addRow(txCard, "Termux", "检测中…");
        depDaemonRow = addRow(txCard, "控制服务", "检测中…");
        TextView txTip = Design.footnote(this,
                "未安装时可在本页直接下载并安装 Termux（自动选择最快下载源）；安装后回到本页即可继续。");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, Design.dp(this, 8), 0, 0);
        txTip.setLayoutParams(tlp);
        txCard.addView(txTip);
        // 动态操作区：未安装=下载/安装向导；已安装=恢复按钮
        termuxActionArea = new LinearLayout(this);
        termuxActionArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams taaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taaLp.setMargins(0, Design.dp(this, 12), 0, 0);
        termuxActionArea.setLayoutParams(taaLp);
        txCard.addView(termuxActionArea);
        col.addView(txCard);

        space(col, 12);

        // 粘贴部署卡（万能方式：复制命令 → Termux 粘贴 → 终端与 App 同步日志）
        depPasteCard = cardCol();
        depPasteCard.addView(Design.headline(this, "粘贴部署（万能方式）"));
        TextView cmdBox = new TextView(this);
        cmdBox.setText(PASTE_CMD);
        cmdBox.setTextSize(13);
        cmdBox.setTypeface(Typeface.MONOSPACE);
        cmdBox.setTextColor(Color.parseColor("#E2E8F0"));
        cmdBox.setPadding(Design.dp(this, 12), Design.dp(this, 10), Design.dp(this, 12), Design.dp(this, 10));
        cmdBox.setBackground(Design.round(this, 10, Color.parseColor("#1E293B")));
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cmdLp.setMargins(0, Design.dp(this, 10), 0, 0);
        cmdBox.setLayoutParams(cmdLp);
        depPasteCard.addView(cmdBox);
        LinearLayout pasteRow = new LinearLayout(this);
        pasteRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView copyCmdBtn = tintButton("复制命令", Design.BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", PASTE_CMD));
                    toast("已复制，去 Termux 粘贴回车");
                    Design.haptic(v, HapticFeedbackConstants.CONFIRM);
                    // 进入部署监控态：进度卡开始等待日志
                    deploying = true;
                    deployStartAt = System.currentTimeMillis();
                    deployDoneNotified = false;
                    allowGuideShown = true;
                    refreshDeployPage();
                } catch (Exception e) {
                    toast("复制失败，请长按手动复制");
                }
            }
        });
        TextView openTxBtn = tintButton("打开 Termux", Design.PURPLE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PKG);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                    }
                } catch (Exception ignored) {}
            }
        });
        pasteRow.addView(copyCmdBtn);
        pasteRow.addView(openTxBtn);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.setMargins(0, Design.dp(this, 10), 0, 0);
        pasteRow.setLayoutParams(prLp);
        depPasteCard.addView(pasteRow);
        TextView pasteNote = Design.footnote(this,
                "粘贴回车即开始部署，终端与下方进度卡同步显示日志（无需任何权限）。若提示 curl 不存在，先在 Termux 执行 pkg install -y curl。");
        LinearLayout.LayoutParams pnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pnLp.setMargins(0, Design.dp(this, 10), 0, 0);
        pasteNote.setLayoutParams(pnLp);
        depPasteCard.addView(pasteNote);
        col.addView(depPasteCard);

        space(col, 12);

        // 电池优化引导卡（按机型给路径，防系统冻结 Termux/本应用）
        depBatteryCard = cardCol();
        depBatteryCard.addView(Design.headline(this, "电池优化（防冻结）"));
        TextView bTip = Design.footnote(this, batteryGuideText());
        LinearLayout.LayoutParams bTipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bTipLp.setMargins(0, Design.dp(this, 8), 0, 0);
        bTip.setLayoutParams(bTipLp);
        depBatteryCard.addView(bTip);
        LinearLayout bRow = new LinearLayout(this);
        bRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView goBtn = tintButton("去设置", Design.ORANGE, new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        });
        TextView battDoneBtn = tintButton("已完成", Design.GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("battery_hint_done", true).apply();
                refreshDeployPage();
                toast("已记住，可随时在「关于」页查看说明");
            }
        });
        bRow.addView(goBtn);
        bRow.addView(battDoneBtn);
        LinearLayout.LayoutParams bRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bRowLp.setMargins(0, Design.dp(this, 10), 0, 0);
        bRow.setLayoutParams(bRowLp);
        depBatteryCard.addView(bRow);
        col.addView(depBatteryCard);

        space(col, 12);

        // 部署动作卡（空闲态）
        depIdleCard = cardCol();
        depIdleCard.addView(Design.headline(this, "一键部署"));
        TextView idleDesc = Design.footnote(this,
                "自动完成：依赖安装 → 编译原生模块 → 启动服务。首次约 5~15 分钟，可放心切到后台。");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, Design.dp(this, 6), 0, Design.dp(this, 12));
        idleDesc.setLayoutParams(ilp);
        depIdleCard.addView(idleDesc);
        TextView deployBtn = primaryButton("一键部署 DeepSeek Harness");
        deployBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDeployTap(); }
        });
        depIdleCard.addView(deployBtn);
        col.addView(depIdleCard);

        // 部署进度卡
        depProgressCard = cardCol();
        depProgressCard.addView(Design.headline(this, "正在部署"));
        depPhase = Design.body(this, "准备中…");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, Design.dp(this, 6), 0, Design.dp(this, 4));
        depPhase.setLayoutParams(plp);
        depProgressCard.addView(depPhase);
        depElapsed = Design.footnote(this, "");
        depProgressCard.addView(depElapsed);
        depLog = new TextView(this);
        depLog.setTextSize(11);
        depLog.setTypeface(Typeface.MONOSPACE);
        depLog.setTextColor(Design.TEXT2);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, Design.dp(this, 8), 0, 0);
        depLog.setLayoutParams(dlp);
        depProgressCard.addView(depLog);
        col.addView(depProgressCard);

        // 部署完成卡
        depDoneCard = cardCol();
        TextView doneTitle = Design.headline(this, "✓ 部署完成");
        doneTitle.setTextColor(Design.GREEN);
        depDoneCard.addView(doneTitle);
        TextView doneBtn = primaryButton("打开 Harness");
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWeb(getWebUrl()); }
        });
        LinearLayout.LayoutParams dblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dblp.setMargins(0, Design.dp(this, 12), 0, 0);
        doneBtn.setLayoutParams(dblp);
        depDoneCard.addView(doneBtn);
        col.addView(depDoneCard);

        refreshDeployPage();
        return sv;
    }

    private void refreshDeployPage() {
        boolean tx = termuxInstalled();
        if (tx) {
            String v = termuxVersion();
            depTermuxRow.setText("已安装" + (v != null ? " v" + v : ""));
            depTermuxRow.setTextColor(Design.GREEN);
        } else {
            depTermuxRow.setText("未安装");
            depTermuxRow.setTextColor(Design.RED);
        }
        refreshDaemonRow();
        renderTermuxAction();
        if (deploying) {
            depIdleCard.setVisibility(View.GONE);
            depProgressCard.setVisibility(View.VISIBLE);
            depDoneCard.setVisibility(View.GONE);
            if (depPasteCard != null) depPasteCard.setVisibility(View.GONE);
            if (depBatteryCard != null) depBatteryCard.setVisibility(View.GONE);
        } else {
            depIdleCard.setVisibility(View.VISIBLE);
            depProgressCard.setVisibility(View.GONE);
            depDoneCard.setVisibility(View.GONE);
            if (depPasteCard != null) {
                depPasteCard.setVisibility(tx ? View.VISIBLE : View.GONE);
            }
            // 电池优化引导卡：Termux 已装且用户未标记完成时显示
            if (depBatteryCard != null) {
                boolean hintDone = getSharedPreferences("dsh", MODE_PRIVATE)
                        .getBoolean("battery_hint_done", false);
                depBatteryCard.setVisibility((tx && !hintDone) ? View.VISIBLE : View.GONE);
            }
            // App 重启后从守护进程恢复「部署中/已部署」状态（内存状态丢失也能正确显示）
            request("GET", "/api/deploy", new Callback() {
                @Override public void onResult(JSONObject o) {
                    String log = o.optString("log", "");
                    if (log.contains("部署完成")) {
                        getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("deployed", true).apply();
                        depIdleCard.setVisibility(View.GONE);
                        if (depPasteCard != null) depPasteCard.setVisibility(View.GONE);
                        depDoneCard.setVisibility(View.VISIBLE);
                    } else if (log.length() > 0) {
                        // 部署仍在后台进行 → 恢复进度卡
                        deploying = true;
                        deployStartAt = System.currentTimeMillis();
                        allowGuideShown = true;
                        refreshDeployPage();
                    }
                }
                @Override public void onError(Exception e) { /* 忽略 */ }
            });
        }
    }

    // ================================================================ Termux 下载/安装向导

    /** 依据 Termux 安装/部署状态渲染操作区：未安装=下载+安装向导；已部署=恢复按钮 */
    private void renderTermuxAction() {
        if (termuxActionArea == null) return;
        termuxActionArea.removeAllViews();
        if (termuxInstalled()) {
            // 「恢复服务」只对已部署设备有意义；未部署设备引导走粘贴部署卡
            if (getSharedPreferences("dsh", MODE_PRIVATE).getBoolean("deployed", false)) {
                TextView recoverBtn = secondaryButton("启动 Termux 并恢复服务");
                recoverBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { recoverTermuxEnv(); }
                });
                termuxActionArea.addView(recoverBtn);
            } else {
                TextView hint = Design.footnote(this,
                        "首次部署请使用下方「粘贴部署」卡，或先尝试「一键部署」按钮。");
                termuxActionArea.addView(hint);
            }
            return;
        }

        // ---- 下载向导 ----
        depDownloadText = Design.footnote(this, "自动测速选择最快下载源（清华/北外镜像等）");
        termuxActionArea.addView(depDownloadText);

        TextView dlBtn = primaryButton("下载 Termux 安装包");
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlLp.setMargins(0, Design.dp(this, 8), 0, 0);
        dlBtn.setLayoutParams(dlLp);
        dlBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTermuxDownload(); }
        });
        termuxActionArea.addView(dlBtn);

        depDownloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        depDownloadProgress.setMax(100);
        depDownloadProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(this, 4));
        ppLp.setMargins(0, Design.dp(this, 10), 0, 0);
        depDownloadProgress.setLayoutParams(ppLp);
        termuxActionArea.addView(depDownloadProgress);

        TextView installBtn = secondaryButton("安装 Termux（系统弹出确认）");
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.setMargins(0, Design.dp(this, 8), 0, 0);
        installBtn.setLayoutParams(inLp);
        installBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { installTermuxApk(); }
        });
        termuxActionArea.addView(installBtn);
    }

    /** 测速选最快源并下载 Termux APK（带进度显示，按机型架构匹配安装包） */
    private void startTermuxDownload() {
        if (termuxDownloading) { toast("正在下载中…"); return; }
        termuxDownloading = true;
        final String abi = detectedAbi();
        depDownloadText.setText("已识别机型架构 " + abi + "，正在匹配并测速下载源…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String url = pickFastestUrl(getTermuxApkUrls());
                if (url == null) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            termuxDownloading = false;
                            depDownloadText.setText("✕ 所有下载源不可达，请检查网络后重试");
                        }
                    });
                    return;
                }
                try {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            depDownloadText.setText("下载中…（" + abi + " 专属包 · 最快源：" + hostOf(url) + "）");
                            if (depDownloadProgress != null) depDownloadProgress.setVisibility(View.VISIBLE);
                        }
                    });
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    final long total = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    final File f = new File(getFilesDir(), "termux.apk");
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[65536];
                    long got = 0;
                    long lastUi = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 250) {
                            lastUi = now;
                            final long g = got;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (total > 0) {
                                        int pct = (int) (g * 100 / total);
                                        if (depDownloadProgress != null) depDownloadProgress.setProgress(pct);
                                        depDownloadText.setText("下载中 " + fmtMb(g) + " / " + fmtMb(total)
                                                + "  (" + pct + "%)");
                                    } else {
                                        depDownloadText.setText("下载中 " + fmtMb(g));
                                    }
                                }
                            });
                        }
                    }
                    out.close();
                    in.close();
                    c.disconnect();
                    if (f.length() < 20 * 1024 * 1024) {
                        f.delete();
                        ui.post(new Runnable() {
                            @Override public void run() {
                                termuxDownloading = false;
                                depDownloadText.setText("✕ 下载文件异常，请重试");
                            }
                        });
                        return;
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            termuxDownloading = false;
                            if (depDownloadProgress != null) {
                                depDownloadProgress.setProgress(100);
                                depDownloadProgress.setVisibility(View.GONE);
                            }
                            depDownloadText.setText("✓ 已下载 " + fmtMb(f.length()) + "，点击下方按钮安装");
                            Design.haptic(termuxActionArea, HapticFeedbackConstants.CONFIRM);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            termuxDownloading = false;
                            depDownloadText.setText("✕ 下载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 触发系统安装器安装已下载的 Termux APK */
    private void installTermuxApk() {
        File f = new File(getFilesDir(), "termux.apk");
        if (!f.exists()) {
            toast("请先下载 Termux 安装包");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            showSheet("需要开启「安装未知应用」",
                    "Android 要求允许本应用安装应用。点击「去开启」→ 在设置中允许「DeepSeek Harness」→ 返回本页重新点击安装。",
                    new String[]{"去开启"}, new Runnable[]{
                            new Runnable() {
                                @Override public void run() {
                                    try {
                                        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:" + getPackageName()));
                                        startActivity(i);
                                    } catch (Exception e) { toast("无法打开设置"); }
                                }
                            }});
            return;
        }
        try {
            Uri uri = Uri.parse("content://com.dshharness.app.apk/termux.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("系统安装器已打开，请确认安装");
        } catch (Exception e) {
            toast("无法启动安装器：" + e.getMessage());
        }
    }

    /** 对候选 URL 做 HEAD 测速，返回最快可达者；全部失败返回 null */
    private String pickFastestUrl(String[] urls) {
        String best = null;
        double bestTime = Double.MAX_VALUE;
        for (String u : urls) {
            try {
                long t0 = System.currentTimeMillis();
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                long ms = System.currentTimeMillis() - t0;
                c.disconnect();
                if (code >= 200 && code < 400 && ms < bestTime) {
                    bestTime = ms;
                    best = u;
                }
            } catch (Exception ignored) { /* 该源不可达，跳过 */ }
        }
        return best;
    }

    private String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    private String fmtMb(long bytes) {
        return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
    }

    /** 按机型生成电池优化（防冻结）设置指引 */
    private String batteryGuideText() {
        String m = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.US) : "";
        if (m.contains("oneplus") || m.contains("oppo") || m.contains("realme")) {
            return "本机为 ColorOS/氢OS：设置 → 电池 → 更多设置 → 应用耗电管理 → 将「Termux」和「DeepSeek Harness」设为「允许完全后台行为 / 不限制」。否则切后台后部署与服务会被系统冻结。";
        }
        if (m.contains("huawei") || m.contains("honor")) {
            return "本机为鸿蒙/EMUI：设置 → 应用和服务 → 应用启动管理 → 将「Termux」与「DeepSeek Harness」设为「手动管理」并打开全部开关；电池 → 更多电池设置 → 设为「不限制」。";
        }
        if (m.contains("xiaomi") || m.contains("redmi")) {
            return "本机为 MIUI/澎湃OS：设置 → 应用设置 → 应用管理 → 找到「Termux」与「DeepSeek Harness」→ 省电策略设为「无限制」并允许自启动。";
        }
        if (m.contains("vivo") || m.contains("iqoo")) {
            return "本机为 OriginOS：设置 → 电池 → 后台耗电管理 → 将「Termux」与「DeepSeek Harness」设为「允许后台高耗电」。";
        }
        if (m.contains("samsung")) {
            return "本机为 One UI：设置 → 电池 → 后台使用限制 → 将「Termux」与「DeepSeek Harness」加入「不休眠应用」。";
        }
        return "请在系统设置 → 电池/应用管理中，将「Termux」和「DeepSeek Harness」设为「不限制 / 允许后台运行」，否则系统会冻结后台部署与服务。";
    }

    /** 打开系统电池优化设置页 */
    private void openBatterySettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS));
            } catch (Exception e2) {
                toast("无法打开设置，请按提示路径手动操作");
            }
        }
    }

    // ================================================================ 浏览器选择

    /** 已保存的外部浏览器包名；"system" = 系统默认；默认 Via */
    private String getSavedBrowserPkg() {
        return getSharedPreferences("dsh", MODE_PRIVATE).getString("browser_pkg", VIA_PKG);
    }

    private boolean isBrowserInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用已选浏览器打开地址（失败回落系统默认） */
    private void openExternal(String url) {
        String pkg = getSavedBrowserPkg();
        if (!"system".equals(pkg)) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) { /* 回落到系统默认 */ }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("无法打开浏览器");
        }
    }

    /** 浏览器选择弹层：系统默认 + 已装浏览器；点选即保存并打开 */
    private void showBrowserChooser() {
        overlay.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 24));
        sheet.setBackground(Design.round(this, 20, Color.WHITE));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.bottomMargin = Design.dp(this, 10);
        sheet.setLayoutParams(slp);

        sheet.addView(Design.headline(this, "选择浏览器打开 Harness"));
        TextView hint = Design.footnote(this,
                "默认为 Via 浏览器；点选后立即打开并记住选择。下次可直接用此浏览器打开。");
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.setMargins(0, Design.dp(this, 6), 0, Design.dp(this, 8));
        hint.setLayoutParams(hLp);
        sheet.addView(hint);

        final String saved = getSavedBrowserPkg();

        // 系统默认行
        TextView sysRow = new TextView(this);
        sysRow.setText("🌐 系统默认浏览器" + ("system".equals(saved) ? "  ✓" : ""));
        sysRow.setTextSize(15);
        sysRow.setTextColor("system".equals(saved) ? Design.BLUE : Design.TEXT);
        sysRow.setPadding(0, Design.dp(this, 11), 0, Design.dp(this, 11));
        sysRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("dsh", MODE_PRIVATE).edit().putString("browser_pkg", "system").apply();
                dismissSheet();
                openExternal(getWebUrl());
            }
        });
        Design.pressable(sysRow);
        sheet.addView(sysRow);

        // 已安装浏览器行
        int found = 0;
        for (String[] b : BROWSERS) {
            final String pkg = b[0];
            final String name = b[1];
            if (!isBrowserInstalled(pkg)) continue;
            found++;
            TextView row = new TextView(this);
            row.setText("🧭 " + name + (pkg.equals(saved) ? "  ✓" : ""));
            row.setTextSize(15);
            row.setTextColor(pkg.equals(saved) ? Design.BLUE : Design.TEXT);
            row.setPadding(0, Design.dp(this, 11), 0, Design.dp(this, 11));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    getSharedPreferences("dsh", MODE_PRIVATE).edit().putString("browser_pkg", pkg).apply();
                    dismissSheet();
                    openExternal(getWebUrl());
                }
            });
            Design.pressable(row);
            sheet.addView(row);
        }
        if (found == 0) {
            sheet.addView(Design.footnote(this, "（未检测到常见浏览器，将使用系统默认）"));
        }

        TextView cancelBtn = secondaryButton("取消");
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.setMargins(0, Design.dp(this, 10), 0, 0);
        cancelBtn.setLayoutParams(cLp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        sheet.addView(cancelBtn);

        overlay.addView(sheet);
        overlay.setVisibility(View.VISIBLE);
    }

    private void onDeployTap() {
        if (!termuxInstalled()) {
            showSheet("需要先安装 Termux",
                    "检测到本机未安装 Termux。DeepSeek Harness 需要运行在 Termux 环境中。\n\n"
                            + "点击「前往下载」打开 F-Droid 官方页面，下载并安装 Termux（安装时如提示"
                            + "「未知来源应用」请允许）。安装完成后回到本页即可。",
                    new String[]{"前往下载"}, new Runnable[]{
                            new Runnable() { @Override public void run() { openWeb(FDROID_URL); } }});
            return;
        }
        if (checkSelfPermission(RUN_PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{RUN_PERM}, 101);
            toast("请在系统弹窗中允许「运行命令」权限");
            return;
        }
        showSheet("开始部署",
                "将自动完成：更新系统 → 安装编译工具链 → 下载并安装 DeepSeek Harness → 启动服务。\n\n"
                        + "首次部署约需 5~15 分钟（需下载约 300MB），请保持手机联网，Termux 可切到后台但请勿清理。",
                new String[]{"取消", "开始部署"}, new Runnable[]{
                        new Runnable() { @Override public void run() { dismissSheet(); } },
                        new Runnable() { @Override public void run() { startDeploy(); } }});
    }

    private void startDeploy() {
        dismissSheet();
        startDeployServer();
        deploying = true;
        deployStartAt = System.currentTimeMillis();
        deployDoneNotified = false;
        allowGuideShown = false;
        depLog.setText("");
        refreshDeployPage();
        selectTab(1);
        try {
            sendRunCommand(new String[]{"-c", DeployAssets.BOOTSTRAP});
            toast("已开始部署，正在后台进行");
            Design.haptic(depIdleCard, HapticFeedbackConstants.CONFIRM);
        } catch (Exception e) {
            toast("启动部署失败：" + e.getMessage());
            deploying = false;
            refreshDeployPage();
        }
    }

    /** 刷新部署页「控制服务」状态行 */
    private void refreshDaemonRow() {
        if (depDaemonRow == null) return;
        request("GET", "/api/ping", new Callback() {
            @Override public void onResult(JSONObject o) {
                if (o.optBoolean("ok", false)) {
                    depDaemonRow.setText("在线");
                    depDaemonRow.setTextColor(Design.GREEN);
                } else {
                    showDaemonDown();
                }
            }
            @Override public void onError(Exception e) {
                showDaemonDown();
            }
        });
    }

    /** 控制服务不可达：已部署过=离线（可用恢复按钮）；从未部署=未部署（正常，点一键部署） */
    private void showDaemonDown() {
        boolean deployed = getSharedPreferences("dsh", MODE_PRIVATE).getBoolean("deployed", false);
        if (deployed) {
            depDaemonRow.setText("离线");
            depDaemonRow.setTextColor(Design.RED);
        } else {
            depDaemonRow.setText("未部署");
            depDaemonRow.setTextColor(Design.TEXT3);
        }
    }

    /** 一键拉起 Termux 并通过 RUN_COMMAND 恢复守护进程 + sshd */
    /** 开启 Termux 外部应用权限的命令（粘贴到 Termux 执行一次即可） */
    private static final String ALLOW_CMD =
            "echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings";

    private void recoverTermuxEnv() {
        // OPPO 等机型禁止电脑端授权，必须在 App 内弹窗申请运行时权限
        if (checkSelfPermission(RUN_PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{RUN_PERM}, 100);
            return;
        }
        toast("正在启动 Termux…");
        if (depDaemonRow != null) {
            depDaemonRow.setText("恢复中…");
            depDaemonRow.setTextColor(Design.ORANGE);
        }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PKG);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        } catch (Exception ignored) {}
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    sendRunCommand(new String[]{"-c", RECOVERY_SCRIPT});
                    toast("已请求 Termux 恢复控制服务");
                } catch (Exception e) {
                    toast("恢复请求失败：" + e.getMessage());
                }
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        refreshDaemonRow();
                        refreshControl();
                        refreshEnv();
                        // 仍离线 → 大概率是 Termux 侧 allow-external-apps 未开启，弹引导
                        request("GET", "/api/ping", new Callback() {
                            @Override public void onResult(JSONObject o) { /* 已恢复 */ }
                            @Override public void onError(Exception e) { showAllowExternalAppsGuide(); }
                        });
                    }
                }, 6000);
            }
        }, 2500);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("权限已获得，正在恢复服务…");
                recoverTermuxEnv();
            } else {
                showSheet("未获得「运行命令」权限",
                        "需要允许本应用在 Termux 中执行命令才能继续。\n\n可到系统设置 → 应用 → DeepSeek Harness → 权限 → 允许「运行命令」，然后重试。",
                        new String[]{"知道了"}, new Runnable[]{
                                new Runnable() { @Override public void run() { dismissSheet(); } }});
            }
        } else if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("权限已获得，请再次点击「一键部署」");
            } else {
                showSheet("未获得「运行命令」权限",
                        "需要允许本应用在 Termux 中执行命令才能继续。\n\n可到系统设置 → 应用 → DeepSeek Harness → 权限 → 允许「运行命令」，然后重试。",
                        new String[]{"知道了"}, new Runnable[]{
                                new Runnable() { @Override public void run() { dismissSheet(); } }});
            }
        }
    }

    /** 万能粘贴部署命令：App 本地服务 8045 提供脚本，Termux 内 curl | sh 一键部署（带超时防挂死） */
    private static final String PASTE_CMD =
            "curl -fsSL --connect-timeout 5 --max-time 60 http://127.0.0.1:8045/deploy.sh | sh";

    /** 部署超时兜底引导：优先粘贴命令方式（无需任何权限），其次开启外部权限后重试 */
    private void showAllowExternalAppsGuide() {
        startDeployServer();
        showSheet("部署通道被拦截，改用粘贴命令",
                "自动部署被 Termux 拦截（外部应用权限未开启）。\n\n"
                        + "【推荐】点「复制命令」→ 打开 Termux 粘贴回车，部署立即开始，日志实时显示在本页与终端。\n\n"
                        + "命令：\n" + PASTE_CMD + "\n\n"
                        + "若提示 curl: command not found，先在 Termux 执行 pkg install -y curl 再粘贴。\n\n"
                        + "也可先修复外部权限（复制执行下面命令并完全重启 Termux），再回到本页重新点「一键部署」：\n"
                        + ALLOW_CMD,
                new String[]{"复制命令", "打开 Termux", "我已在 Termux 执行"},
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                try {
                                    android.content.ClipboardManager cm =
                                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", PASTE_CMD));
                                    toast("部署命令已复制，去 Termux 粘贴回车");
                                } catch (Exception e) {
                                    toast("复制失败，请长按手动复制");
                                }
                            }
                        },
                        new Runnable() {
                            @Override public void run() {
                                try {
                                    Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PKG);
                                    if (launch != null) {
                                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(launch);
                                    }
                                } catch (Exception ignored) {}
                            }
                        },
                        new Runnable() {
                            @Override public void run() {
                                toast("部署已在 Termux 中运行，本页稍后自动显示日志");
                                ui.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        refreshDaemonRow();
                                        refreshControl();
                                        refreshEnv();
                                    }
                                }, 2000);
                            }
                        }});
    }

    private void sendRunCommand(String[] args) {
        Intent i = new Intent();
        i.setClassName(TERMUX_PKG, "com.termux.app.RunCommandService");
        i.setAction("com.termux.RUN_COMMAND");
        i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_SH);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        startService(i);
    }

    /** 启动部署脚本前台服务（防后台冻结），失败时退化为进程内线程 */
    private void startDeployServer() {
        try {
            Intent i = new Intent(this, DeployServerService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            // 兼容极端情况：进程内直接跑
            new Thread(new Runnable() {
                @Override public void run() {
                    DeployServer.run(DeployAssets.BOOTSTRAP);
                }
            }).start();
        }
    }

    private void refreshDeployProgress() {
        final long elapsed = (System.currentTimeMillis() - deployStartAt) / 1000;
        depElapsed.setText(String.format(Locale.US, "已用时 %02d:%02d", elapsed / 60, elapsed % 60));
        request("GET", "/api/deploy", new Callback() {
            @Override public void onResult(JSONObject o) {
                String log = o.optString("log", "");
                depLog.setText(log.length() > 0 ? log : "…等待进度输出…");
                String[] lines = log.split("\n");
                String last = lines.length > 0 ? lines[lines.length - 1] : "";
                if (last.startsWith("[deploy]")) {
                    depPhase.setText(last);
                }
                if (log.contains("部署完成")) {
                    finishDeploySuccess();
                }
            }
            @Override public void onError(Exception e) {
                // 守护进程未就绪 → 尝试早期进度服务(8024)，让日志从第 0 秒可见
                fetchRawProgressLog();
            }
        });
    }

    /** 部署早期阶段日志：优先 App 自身服务(8045/log，脚本心跳上报)，退回 busybox(8024) */
    private void fetchRawProgressLog() {
        new Thread(new Runnable() {
            @Override public void run() {
                String text = fetchText("http://127.0.0.1:8045/log");
                if (text == null) text = fetchText("http://127.0.0.1:8024/deploy.log");
                if (text != null) {
                    final String t = text;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            depLog.setText(t.length() > 0 ? t : "…等待进度输出…");
                            String[] lines = t.split("\n");
                            String last = lines.length > 0 ? lines[lines.length - 1] : "";
                            if (last.startsWith("[deploy]")) depPhase.setText(last);
                            if (t.contains("部署完成")) finishDeploySuccess();
                        }
                    });
                } else {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            // 进度通道暂不可达：可能是依赖安装阶段（守护进程未上线），
                            // 也可能是 Termux 未执行命令——超时后弹引导卡（仅一次，不打断）
                            long elapsed = (System.currentTimeMillis() - deployStartAt) / 1000;
                            if (elapsed > 90 && !allowGuideShown) {
                                allowGuideShown = true;
                                showAllowExternalAppsGuide();
                            } else if (!deployDoneNotified) {
                                depPhase.setText("部署进行中（依赖安装阶段暂无日志，请以 Termux 终端输出为准）…");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private String fetchText(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            int code = c.getResponseCode();
            if (code != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            c.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void finishDeploySuccess() {
        if (deployDoneNotified) return;
        deployDoneNotified = true;
        deploying = false;
        getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("deployed", true).apply();
        refreshDeployPage();
        depIdleCard.setVisibility(View.GONE);
        depProgressCard.setVisibility(View.GONE);
        depDoneCard.setVisibility(View.VISIBLE);
        toast("部署完成");
        Design.haptic(depDoneCard, HapticFeedbackConstants.CONFIRM);
        refreshControl();
    }

    // ================================================================ 关于页

    private View buildAboutPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Design.dp(this, 16), Design.dp(this, 24), Design.dp(this, 16), Design.dp(this, 16));
        sv.addView(col);

        col.addView(Design.largeTitle(this, "关于"));

        // 可配置 Web 地址（本机域名/局域网 IP 可配）
        LinearLayout webCard = cardCol();
        webCard.addView(Design.headline(this, "Web 地址"));
        TextView webTip = Design.footnote(this,
                "默认本机回环 http://127.0.0.1:3080。若将服务映射到局域网 IP/域名/端口，可在此配置后，"
                        + "「打开 Harness」与 Via 打开均使用此地址。");
        LinearLayout.LayoutParams wtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wtlp.setMargins(0, Design.dp(this, 6), 0, Design.dp(this, 10));
        webTip.setLayoutParams(wtlp);
        webCard.addView(webTip);
        final EditText urlInput = new EditText(this);
        urlInput.setText(getWebUrl());
        urlInput.setSingleLine(true);
        urlInput.setTextSize(15);
        urlInput.setTextColor(Design.TEXT);
        urlInput.setPadding(Design.dp(this, 12), Design.dp(this, 10), Design.dp(this, 12), Design.dp(this, 10));
        urlInput.setBackground(Design.round(this, 10, Color.parseColor("#E9E9EE")));
        webCard.addView(urlInput);
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView saveBtn = tintButton("保存地址", Design.BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String u = urlInput.getText().toString().trim();
                if (u.length() == 0 || (!u.startsWith("http://") && !u.startsWith("https://"))) {
                    toast("地址需以 http:// 或 https:// 开头");
                    return;
                }
                getSharedPreferences("dsh", MODE_PRIVATE).edit().putString("web_url", u).apply();
                toast("已保存：" + u);
                Design.haptic(v, HapticFeedbackConstants.CONFIRM);
            }
        });
        TextView resetBtn = tintButton("恢复默认", Design.TEXT3, new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("dsh", MODE_PRIVATE).edit().remove("web_url").apply();
                urlInput.setText(HARNESS);
                toast("已恢复默认地址");
            }
        });
        urlRow.addView(saveBtn);
        urlRow.addView(resetBtn);
        LinearLayout.LayoutParams urlRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlRowLp.setMargins(0, Design.dp(this, 10), 0, 0);
        urlRow.setLayoutParams(urlRowLp);
        webCard.addView(urlRow);
        col.addView(webCard);

        space(col, 12);

        envCard = cardCol();
        envCard.addView(Design.headline(this, "运行环境"));
        col.addView(envCard);

        space(col, 12);

        // 存储位置卡：查看/浏览/编辑 harness 数据（技能、MCP、会话、附件），可迁移到共享存储
        storageCard = cardCol();
        LinearLayout storageHead = new LinearLayout(this);
        storageHead.setOrientation(LinearLayout.HORIZONTAL);
        storageHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView stTitle = Design.headline(this, "存储位置");
        storageHead.addView(stTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView stSync = tintButton("同步守护进程", Design.PURPLE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd",
                            "curl -fsSL http://127.0.0.1:8045/update.sh | sh"));
                    toast("已复制更新命令，去 Termux 粘贴回车");
                    Design.haptic(v, HapticFeedbackConstants.CONFIRM);
                } catch (Exception e) { toast("复制失败"); }
            }
        });
        storageHead.addView(stSync, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        storageCard.addView(storageHead);
        TextView stTip = Design.footnote(this,
                "点条目浏览目录/查看文件；「· 技能目录」「· MCP 配置」为常用入口。");
        storageCard.addView(stTip);
        LinearLayout stActions = new LinearLayout(this);
        stActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView moveBtn = tintButton("迁移数据到共享存储", Design.ORANGE, new View.OnClickListener() {
            @Override public void onClick(View v) { migrateDataToSdcard(); }
        });
        TextView refreshBtn = tintButton("刷新", Design.BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { refreshStorage(); }
        });
        stActions.addView(moveBtn);
        stActions.addView(refreshBtn);
        LinearLayout.LayoutParams staLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        staLp.setMargins(0, Design.dp(this, 10), 0, 0);
        stActions.setLayoutParams(staLp);
        storageCard.addView(stActions);
        storageCard.addView(Design.footnote(this, "（存储清单加载中…）"));
        col.addView(storageCard);

        space(col, 12);

        LinearLayout actCard = cardCol();
        actCard.addView(Design.headline(this, "快捷操作"));
        TextView viaBtn = secondaryButton("选择浏览器打开 Harness");
        viaBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBrowserChooser(); }
        });
        actCard.addView(viaBtn);
        TextView repoBtn = secondaryButton("部署项目（GitHub）");
        repoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWeb(REPO_URL); }
        });
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rblp.setMargins(0, Design.dp(this, 8), 0, 0);
        repoBtn.setLayoutParams(rblp);
        actCard.addView(repoBtn);
        col.addView(actCard);

        space(col, 20);

        TextView foot = Design.footnote(this,
                "DeepSeek Harness 通用控制端 · v1.0\n服务仅监听本机回环地址，数据不出设备。");
        foot.setGravity(Gravity.CENTER);
        col.addView(foot);

        return sv;
    }

    private void refreshEnv() {
        request("GET", "/api/env", new Callback() {
            @Override public void onResult(JSONObject o) {
                envCard.removeAllViews();
                envCard.addView(Design.headline(MainActivity.this, "运行环境"));
                addRow(envCard, "Node.js", o.optString("node", "未知"));
                addRow(envCard, "dsh 版本", o.optString("dshVersion", "未安装"));
                String disk = o.optString("diskAvail", "");
                addRow(envCard, "可用空间", disk.length() > 0 ? disk : "未知");
                long up = o.optLong("uptime", 0);
                addRow(envCard, "控制服务", up > 0 ? "在线 · 已运行 " + up + "s" : "离线");
            }
            @Override public void onError(Exception e) {
                envCard.removeAllViews();
                envCard.addView(Design.headline(MainActivity.this, "运行环境"));
                addRow(envCard, "控制服务", "未连接");
            }
        });
    }

    // ================================================================ 存储管理

    /** 刷新存储清单（守护进程 /api/storage） */
    private void refreshStorage() {
        if (storageCard == null) return;
        request("GET", "/api/storage", new Callback() {
            @Override public void onResult(final JSONObject o) {
                // 重建清单区（保留标题/说明/按钮：先记录子视图数量再清理）
                storageCard.removeAllViews();
                LinearLayout storageHead = new LinearLayout(MainActivity.this);
                storageHead.setOrientation(LinearLayout.HORIZONTAL);
                storageHead.setGravity(Gravity.CENTER_VERTICAL);
                storageHead.addView(Design.headline(MainActivity.this, "存储位置"),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView syncBtn = tintButton("同步守护进程", Design.PURPLE, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd",
                                    "curl -fsSL http://127.0.0.1:8045/update.sh | sh"));
                            toast("已复制更新命令，去 Termux 粘贴回车");
                        } catch (Exception e) { toast("复制失败"); }
                    }
                });
                storageHead.addView(syncBtn, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                storageCard.addView(storageHead);
                storageCard.addView(Design.footnote(MainActivity.this,
                        "标准存储可随时「设置」位置（自动迁移+软链接，harness 无感）。"
                        + (o.optBoolean("sdcardWritable", false) ? "" : "\n共享存储未授权：迁移到 /sdcard 前需在 Termux 执行 termux-setup-storage。")));

                // 标准存储配置表（始终显示，可设位置）
                JSONArray std = o.optJSONArray("standard");
                if (std != null) {
                    for (int i = 0; i < std.length(); i++) {
                        final JSONObject it = std.optJSONObject(i);
                        if (it == null) continue;
                        final String type = it.optString("type", "");
                        final String label = it.optString("label", type);
                        final String path = it.optString("path", "");
                        final String target = it.isNull("target") ? "" : it.optString("target", "");
                        final boolean symlink = it.optBoolean("symlink", false);
                        final boolean exists = it.optBoolean("exists", false);
                        final long sizeKb = it.optLong("sizeKb", 0);

                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);

                        TextView info = new TextView(MainActivity.this);
                        info.setText("📁 " + label
                                + "\n" + (symlink && target.length() > 0 ? "→ " + target : path)
                                + (exists ? " · " + fmtSize(sizeKb) : " · 尚未创建"));
                        info.setTextSize(13);
                        info.setTextColor(symlink ? Design.ORANGE : Design.TEXT2);
                        info.setPadding(0, Design.dp(MainActivity.this, 8), 0, Design.dp(MainActivity.this, 8));
                        info.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (!exists) { toast("该目录尚未创建（部署后自动生成）"); return; }
                                showFileBrowser(path);
                            }
                        });
                        Design.pressable(info);
                        row.addView(info, new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                        TextView setBtn = tintButton("设置", Design.BLUE, new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                showSetStorageSheet(type, label, path, symlink ? target : "");
                            }
                        });
                        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                                Design.dp(MainActivity.this, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
                        sbLp.setMargins(Design.dp(MainActivity.this, 6), 0, 0, 0);
                        setBtn.setLayoutParams(sbLp);
                        row.addView(setBtn);
                        storageCard.addView(row);
                    }
                }

                // MCP 配置入口
                final String mcpPath = o.isNull("mcp") ? "" : o.optString("mcp", "");
                if (mcpPath.length() > 0) {
                    TextView mcpRow = new TextView(MainActivity.this);
                    mcpRow.setText("📄 · MCP 配置\n" + mcpPath);
                    mcpRow.setTextSize(13);
                    mcpRow.setTextColor(Design.TEXT2);
                    mcpRow.setPadding(0, Design.dp(MainActivity.this, 8), 0, Design.dp(MainActivity.this, 8));
                    mcpRow.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { openFileViewer(mcpPath); }
                    });
                    Design.pressable(mcpRow);
                    storageCard.addView(mcpRow);
                }

                // 其他数据条目（可浏览）
                JSONArray items = o.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    for (int i = 0; i < items.length(); i++) {
                        final JSONObject it = items.optJSONObject(i);
                        if (it == null) continue;
                        final String label = it.isNull("label") ? "" : it.optString("label", "");
                        final String name = it.optString("name", "");
                        final String path = it.optString("path", "");
                        final boolean dir = it.optBoolean("dir", true);
                        final boolean exists = it.optBoolean("exists", true);
                        final long sizeKb = it.optLong("sizeKb", 0);
                        TextView row = new TextView(MainActivity.this);
                        String title = label.length() > 0 ? label : name;
                        if (label.length() > 0 && name.length() > 0) title = label + "  " + name;
                        row.setText((dir ? "📁 " : "📄 ") + title + "\n" + path
                                + (exists ? " · " + fmtSize(sizeKb) : " · 不存在"));
                        row.setTextSize(13);
                        row.setTextColor(Design.TEXT2);
                        row.setPadding(0, Design.dp(MainActivity.this, 8), 0, Design.dp(MainActivity.this, 8));
                        row.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (!exists) { toast("该路径不存在"); return; }
                                if (dir) showFileBrowser(path);
                                else openFileViewer(path);
                            }
                        });
                        Design.pressable(row);
                        storageCard.addView(row);
                    }
                }

                LinearLayout stActions = new LinearLayout(MainActivity.this);
                stActions.setOrientation(LinearLayout.HORIZONTAL);
                TextView moveBtn = tintButton("统一数据根", Design.ORANGE, new View.OnClickListener() {
                    @Override public void onClick(View v) { showBulkStorageSheet(); }
                });
                TextView refreshBtn = tintButton("刷新", Design.BLUE, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshStorage(); }
                });
                stActions.addView(moveBtn);
                stActions.addView(refreshBtn);
                LinearLayout.LayoutParams staLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                staLp.setMargins(0, Design.dp(MainActivity.this, 10), 0, 0);
                stActions.setLayoutParams(staLp);
                storageCard.addView(stActions);
            }
            @Override public void onError(Exception e) {
                storageCard.removeAllViews();
                storageCard.addView(Design.headline(MainActivity.this, "存储位置"));
                storageCard.addView(Design.footnote(MainActivity.this,
                        "控制服务未连接：请在部署页确认守护进程在线（或使用「同步守护进程」更新后重试）。"));
            }
        });
    }

    /** 存储位置设置弹层：自定义目标路径（迁移+软链）或还原默认 */
    private void showSetStorageSheet(final String type, final String label,
                                     final String currentPath, final String currentTarget) {
        overlay.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 24));
        sheet.setBackground(Design.round(this, 20, Color.WHITE));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.bottomMargin = Design.dp(this, 10);
        sheet.setLayoutParams(slp);

        TextView t = Design.headline(this, "设置「" + label + "」位置");
        sheet.addView(t);
        TextView hint = Design.footnote(this,
                "当前：" + (currentTarget.length() > 0 ? currentTarget : (currentPath.length() > 0 ? currentPath : "默认 ~/.dsh/" + type))
                + "\n新位置需以 /sdcard/ 或 /data/data/com.termux/files/home/ 开头；应用后自动迁移数据并软链接，harness 无感。");
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.setMargins(0, Design.dp(this, 8), 0, Design.dp(this, 10));
        hint.setLayoutParams(hLp);
        sheet.addView(hint);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentTarget.length() > 0 ? currentTarget : "/sdcard/dsh-data/" + type);
        input.setTextSize(14);
        input.setPadding(Design.dp(this, 12), Design.dp(this, 10), Design.dp(this, 12), Design.dp(this, 10));
        input.setBackground(Design.round(this, 10, Color.parseColor("#E9E9EE")));
        sheet.addView(input);

        TextView applyBtn = primaryButton("迁移到该位置");
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.setMargins(0, Design.dp(this, 12), 0, 0);
        applyBtn.setLayoutParams(aLp);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String to = input.getText().toString().trim();
                if (to.length() == 0 || !to.startsWith("/")) {
                    toast("请输入绝对路径");
                    return;
                }
                dismissSheet();
                toast("正在迁移 " + label + " …");
                request("POST", "/api/set-storage?type=" + type + "&to=" + Uri.encode(to), new Callback() {
                    @Override public void onResult(JSONObject o) {
                        toast(label + " 已迁移到 " + o.optString("target", to));
                        refreshStorage();
                    }
                    @Override public void onError(Exception e) {
                        toast("迁移失败：" + e.getMessage());
                        refreshStorage();
                    }
                });
            }
        });
        sheet.addView(applyBtn);

        TextView resetBtn = secondaryButton("还原默认位置");
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.setMargins(0, Design.dp(this, 8), 0, 0);
        resetBtn.setLayoutParams(rLp);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissSheet();
                toast("正在还原 " + label + " …");
                request("POST", "/api/set-storage?type=" + type + "&to=default", new Callback() {
                    @Override public void onResult(JSONObject o) {
                        toast(label + " 已还原到默认位置");
                        refreshStorage();
                    }
                    @Override public void onError(Exception e) {
                        toast("还原失败：" + e.getMessage());
                        refreshStorage();
                    }
                });
            }
        });
        sheet.addView(resetBtn);

        TextView cancelBtn = secondaryButton("取消");
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.setMargins(0, Design.dp(this, 8), 0, 0);
        cancelBtn.setLayoutParams(cLp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        sheet.addView(cancelBtn);

        overlay.addView(sheet);
        overlay.setVisibility(View.VISIBLE);
    }

    /** 统一数据根设置弹层：一个根目录管全部四类数据 */
    private void showBulkStorageSheet() {
        overlay.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 24));
        sheet.setBackground(Design.round(this, 20, Color.WHITE));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.bottomMargin = Design.dp(this, 10);
        sheet.setLayoutParams(slp);

        sheet.addView(Design.headline(this, "统一数据根（一键笼统迁移）"));
        TextView hint = Design.footnote(this,
                "设置一个总目录后，会话存储/附件存储/技能目录/工作区将统一迁到：\n"
                        + "  <根目录>/sessions\n  <根目录>/attachments\n  <根目录>/skills\n  <根目录>/workspace\n\n"
                        + "自动迁移数据并软链接回原位置，harness 无感。根目录需以 /sdcard/ 或 Termux 家目录开头。");
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.setMargins(0, Design.dp(this, 8), 0, Design.dp(this, 10));
        hint.setLayoutParams(hLp);
        sheet.addView(hint);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("/sdcard/dsh-data");
        input.setTextSize(14);
        input.setPadding(Design.dp(this, 12), Design.dp(this, 10), Design.dp(this, 12), Design.dp(this, 10));
        input.setBackground(Design.round(this, 10, Color.parseColor("#E9E9EE")));
        sheet.addView(input);

        TextView applyBtn = primaryButton("全部迁入该根目录");
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.setMargins(0, Design.dp(this, 12), 0, 0);
        applyBtn.setLayoutParams(aLp);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String to = input.getText().toString().trim();
                if (to.length() == 0 || !to.startsWith("/")) { toast("请输入绝对路径"); return; }
                dismissSheet();
                toast("正在统一迁移全部数据…");
                request("POST", "/api/set-storage-all?to=" + Uri.encode(to), new Callback() {
                    @Override public void onResult(JSONObject o) {
                        JSONArray results = o.optJSONArray("results");
                        int okN = 0, failN = 0;
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                if (results.optJSONObject(i) != null && results.optJSONObject(i).optBoolean("ok", false)) okN++;
                                else failN++;
                            }
                        }
                        toast("统一迁移完成：成功 " + okN + "，失败 " + failN);
                        refreshStorage();
                    }
                    @Override public void onError(Exception e) {
                        toast("统一迁移失败：" + e.getMessage());
                        refreshStorage();
                    }
                });
            }
        });
        sheet.addView(applyBtn);

        TextView resetBtn = secondaryButton("全部还原默认位置");
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.setMargins(0, Design.dp(this, 8), 0, 0);
        resetBtn.setLayoutParams(rLp);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissSheet();
                toast("正在全部还原默认位置…");
                request("POST", "/api/set-storage-all?to=default", new Callback() {
                    @Override public void onResult(JSONObject o) {
                        toast("已全部还原默认位置");
                        refreshStorage();
                    }
                    @Override public void onError(Exception e) {
                        toast("还原失败：" + e.getMessage());
                        refreshStorage();
                    }
                });
            }
        });
        sheet.addView(resetBtn);

        TextView cancelBtn = secondaryButton("取消");
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.setMargins(0, Design.dp(this, 8), 0, 0);
        cancelBtn.setLayoutParams(cLp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        sheet.addView(cancelBtn);

        overlay.addView(sheet);
        overlay.setVisibility(View.VISIBLE);
    }

    private String fmtSize(long kb) {
        if (kb < 1024) return kb + " KB";
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }

    /** 文件浏览器弹层 */
    private void showFileBrowser(final String path) {
        browserDir = path;
        overlay.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Design.dp(this, 16), Design.dp(this, 16), Design.dp(this, 16), Design.dp(this, 20));
        sheet.setBackground(Design.round(this, 20, Color.WHITE));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (getResources().getDisplayMetrics().heightPixels * 0.72),
                Gravity.BOTTOM);
        sheet.setLayoutParams(slp);

        browserPathLabel = Design.footnote(this, path);
        browserPathLabel.setTypeface(Typeface.MONOSPACE);
        sheet.addView(browserPathLabel);

        ScrollView sv = new ScrollView(this);
        browserList = new LinearLayout(this);
        browserList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(browserList);
        sheet.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView upBtn = tintButton("上级目录", Design.BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String p = browserDir;
                int idx = p.lastIndexOf('/');
                if (idx > 0) showFileBrowser(p.substring(0, idx));
                else toast("已是根目录");
            }
        });
        TextView closeBtn = tintButton("关闭", Design.TEXT3, new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        row.addView(upBtn);
        row.addView(closeBtn);
        sheet.addView(row);

        overlay.addView(sheet);
        overlay.setVisibility(View.VISIBLE);
        loadBrowser(path);
    }

    private void loadBrowser(final String path) {
        browserPathLabel.setText(path);
        request("GET", "/api/browse?path=" + Uri.encode(path), new Callback() {
            @Override public void onResult(JSONObject o) {
                if (browserList == null) return;
                browserList.removeAllViews();
                JSONArray entries = o.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    browserList.addView(Design.footnote(MainActivity.this, "（空目录）"));
                    return;
                }
                for (int i = 0; i < entries.length(); i++) {
                    final JSONObject e = entries.optJSONObject(i);
                    if (e == null) continue;
                    final String name = e.optString("name", "");
                    final boolean dir = e.optBoolean("dir", false);
                    final long sizeKb = e.optLong("sizeKb", 0);
                    TextView rowTv = new TextView(MainActivity.this);
                    rowTv.setText((dir ? "📁 " : "📄 ") + name + (dir ? "" : " · " + fmtSize(sizeKb)));
                    rowTv.setTextSize(14);
                    rowTv.setTextColor(dir ? Design.TEXT : Design.TEXT2);
                    rowTv.setPadding(0, Design.dp(MainActivity.this, 10), 0, Design.dp(MainActivity.this, 10));
                    final String childPath = path + "/" + name;
                    rowTv.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (dir) loadBrowser(childPath);
                            else openFileViewer(childPath);
                        }
                    });
                    Design.pressable(rowTv);
                    browserList.addView(rowTv);
                }
            }
            @Override public void onError(Exception e) {
                if (browserList != null) {
                    browserList.removeAllViews();
                    browserList.addView(Design.footnote(MainActivity.this, "加载失败：" + e.getMessage()));
                }
            }
        });
    }

    /** 文本查看/编辑器弹层 */
    private void openFileViewer(final String path) {
        request("GET", "/api/read?path=" + Uri.encode(path), new Callback() {
            @Override public void onResult(JSONObject o) {
                final String content = o.optString("content", "");
                viewerPathStr = path;
                overlay.removeAllViews();
                View scrim = new View(MainActivity.this);
                scrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
                scrim.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { dismissSheet(); }
                });
                overlay.addView(scrim, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                LinearLayout sheet = new LinearLayout(MainActivity.this);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setPadding(Design.dp(MainActivity.this, 16), Design.dp(MainActivity.this, 16),
                        Design.dp(MainActivity.this, 16), Design.dp(MainActivity.this, 20));
                sheet.setBackground(Design.round(MainActivity.this, 20, Color.WHITE));
                FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.78), Gravity.BOTTOM);
                sheet.setLayoutParams(slp);

                TextView vp = Design.footnote(MainActivity.this, path);
                vp.setTypeface(Typeface.MONOSPACE);
                sheet.addView(vp);

                viewerEdit = new EditText(MainActivity.this);
                viewerEdit.setText(content);
                viewerEdit.setTextSize(12);
                viewerEdit.setTypeface(Typeface.MONOSPACE);
                viewerEdit.setGravity(Gravity.TOP);
                viewerEdit.setBackgroundColor(Color.parseColor("#F4F4F6"));
                sheet.addView(viewerEdit, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView saveBtn = tintButton("保存", Design.GREEN, new View.OnClickListener() {
                    @Override public void onClick(View v) { saveViewerFile(); }
                });
                TextView closeBtn = tintButton("关闭", Design.TEXT3, new View.OnClickListener() {
                    @Override public void onClick(View v) { dismissSheet(); }
                });
                row.addView(saveBtn);
                row.addView(closeBtn);
                sheet.addView(row);

                overlay.addView(sheet);
                overlay.setVisibility(View.VISIBLE);
            }
            @Override public void onError(Exception e) {
                toast("无法读取：" + e.getMessage());
            }
        });
    }

    private void saveViewerFile() {
        if (viewerEdit == null || viewerPathStr.length() == 0) return;
        postText("/api/write?path=" + Uri.encode(viewerPathStr), viewerEdit.getText().toString(),
                new Callback() {
                    @Override public void onResult(JSONObject o) {
                        toast("已保存");
                        Design.haptic(storageCard, HapticFeedbackConstants.CONFIRM);
                    }
                    @Override public void onError(Exception e) {
                        toast("保存失败：" + e.getMessage());
                    }
                });
    }

    /** 把会话/附件/技能/工作区数据迁移到 /sdcard/dsh-data（软链接回，原路径不变） */
    private void migrateDataToSdcard() {
        toast("开始迁移（会话→附件→技能→工作区）…");
        final String[] dirs = {"sessions", "attachments", "skills", "workspace"};
        final int[] okCount = {0};
        final int[] total = {dirs.length};
        for (String d : dirs) {
            request("POST", "/api/move-sdcard?dir=" + d, new Callback() {
                @Override public void onResult(JSONObject o) {
                    okCount[0]++;
                    if (okCount[0] >= total[0]) {
                        toast("迁移完成 " + okCount[0] + "/" + total[0] + "（数据在 /sdcard/dsh-data）");
                        refreshStorage();
                    }
                }
                @Override public void onError(Exception e) {
                    okCount[0]++;
                    if (okCount[0] >= total[0]) {
                        toast("迁移有部分失败，请检查共享存储授权");
                        refreshStorage();
                    }
                }
            });
        }
    }

    /** POST 文本（编辑保存用） */
    private void postText(final String path, final String body, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(DAEMON + path).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(6000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    c.getOutputStream().write(body.getBytes("UTF-8"));
                    c.getOutputStream().flush();
                    int code = c.getResponseCode();
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    c.disconnect();
                    final JSONObject o = new JSONObject(sb.toString());
                    ui.post(new Runnable() {
                        @Override public void run() { cb.onResult(o); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { cb.onError(e); }
                    });
                }
            }
        }).start();
    }

    // ================================================================ 通用组件

    private LinearLayout cardCol() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Design.dp(this, 16), Design.dp(this, 14), Design.dp(this, 16), Design.dp(this, 14));
        c.setBackground(Design.card(this));
        return c;
    }

    private TextView addRow(LinearLayout card, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView k = Design.body(this, key);
        k.setTextColor(Design.TEXT3);
        TextView v = Design.body(this, value);
        v.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        row.addView(k, klp);
        row.addView(v, vlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, Design.dp(this, 8), 0, 0);
        row.setLayoutParams(rlp);
        card.addView(row);
        return v;
    }

    private void space(LinearLayout col, int dp) {
        View s = new View(this);
        col.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(this, dp)));
    }

    private TextView primaryButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, Design.dp(this, 14), 0, Design.dp(this, 14));
        b.setBackground(Design.round(this, 12, Design.BLUE));
        Design.pressable(b);
        return b;
    }

    private TextView secondaryButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(Design.BLUE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Design.dp(this, 10), Design.dp(this, 12), Design.dp(this, 10), Design.dp(this, 12));
        b.setBackground(Design.round(this, 12, Color.parseColor("#EAF2FF")));
        Design.pressable(b);
        return b;
    }

    private TextView tintButton(String text, final int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(color);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, Design.dp(this, 12), 0, Design.dp(this, 12));
        b.setBackground(Design.round(this, 12, Color.argb(26, Color.red(color), Color.green(color), Color.blue(color))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Design.dp(this, 4), 0, Design.dp(this, 4), 0);
        b.setLayoutParams(lp);
        Design.pressable(b);
        b.setOnClickListener(l);
        return b;
    }

    // ================================================================ 弹层（Apple 风格底部卡片）

    private void showSheet(String title, String body, String[] buttons, Runnable[] actions) {
        overlay.removeAllViews();

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissSheet(); }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 20), Design.dp(this, 24));
        sheet.setBackground(Design.round(this, 20, Color.WHITE));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.bottomMargin = Design.dp(this, 10);
        sheet.setLayoutParams(slp);

        TextView t = Design.headline(this, title);
        t.setTextSize(19);
        sheet.addView(t);
        TextView b = Design.body(this, body);
        b.setTextColor(Design.TEXT2);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, Design.dp(this, 10), 0, Design.dp(this, 16));
        b.setLayoutParams(blp);
        sheet.addView(b);

        for (int i = 0; i < buttons.length; i++) {
            final int idx = i;
            boolean primary = (i == buttons.length - 1 && buttons.length > 1);
            TextView btn = primary ? primaryButton(buttons[i]) : secondaryButton(buttons[i]);
            if (i > 0) {
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.setMargins(0, Design.dp(this, 8), 0, 0);
                btn.setLayoutParams(plp);
            }
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismissSheet();
                    if (actions != null && idx < actions.length && actions[idx] != null) {
                        actions[idx].run();
                    }
                }
            });
            sheet.addView(btn);
        }
        overlay.addView(sheet);
        sheetView = sheet;
        overlay.setVisibility(View.VISIBLE);

        if (!Design.reducedMotion(this)) {
            sheet.setTranslationY(Design.dp(this, 220));
            sheet.animate().translationY(0).setDuration(460)
                    .setInterpolator(new Design.Spring(0.82, 0.35)).start();
        }
    }

    private void dismissSheet() {
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
        sheetView = null;
    }

    // ================================================================ 工具

    private boolean termuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String termuxVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return pi.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** 用户可配置的 Harness Web 地址（本机域名/局域网 IP/端口），默认本机回环 */
    private String getWebUrl() {
        String u = getSharedPreferences("dsh", MODE_PRIVATE).getString("web_url", null);
        if (u == null || u.trim().length() == 0) return HARNESS;
        return u.trim();
    }

    private void openWeb(String url) {
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_URL, url);
        startActivity(i);
    }

    private interface Callback {
        void onResult(JSONObject o);
        void onError(Exception e);
    }

    private void request(final String method, final String path, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(DAEMON + path).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(1800);
                    c.setReadTimeout(6000);
                    int code = c.getResponseCode();
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    c.disconnect();
                    final JSONObject o = new JSONObject(sb.toString());
                    ui.post(new Runnable() {
                        @Override public void run() { cb.onResult(o); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { cb.onError(e); }
                    });
                }
            }
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
