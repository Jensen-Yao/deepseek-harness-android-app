package com.dshharness.app;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/**
 * Apple 设计系统（原生 Android 实现）
 * - iOS 系统色板 + 无衬线系统字体
 * - 关键阻尼/欠阻尼弹簧（damping + response 参数化，对应 Apple 官方范式）
 * - 按下即时反馈（pointer-down 缩放）+ 弹簧回弹
 * - 触觉反馈、减少动态效果适配
 */
public final class Design {

    // iOS 系统色
    public static final int BLUE   = Color.parseColor("#007AFF");
    public static final int GREEN  = Color.parseColor("#34C759");
    public static final int RED    = Color.parseColor("#FF3B30");
    public static final int ORANGE = Color.parseColor("#FF9500");
    public static final int PURPLE = Color.parseColor("#AF52DE");
    public static final int BG     = Color.parseColor("#F2F2F7");
    public static final int CARD   = Color.WHITE;
    public static final int TEXT   = Color.parseColor("#1C1C1E");
    public static final int TEXT2  = Color.parseColor("#3A3A3C");
    public static final int TEXT3  = Color.parseColor("#8E8E93");
    public static final int SEP    = Color.parseColor("#E5E5EA");
    public static final int BAR_BG = Color.parseColor("#F9F9F9");

    private Design() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().scaledDensity);
    }

    public static GradientDrawable round(Context c, float rDp, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, rDp));
        return d;
    }

    public static GradientDrawable card(Context c) {
        return round(c, 16, CARD);
    }

    /** Apple 式弹簧插值器：damping（阻尼比）、response（响应时间，秒） */
    public static class Spring implements TimeInterpolator {
        private final float[] table = new float[512];
        public Spring(double damping, double response) {
            double zeta = Math.max(0.15, damping);
            double w = 5.6 / Math.max(0.05, response);   // 角频率，保证约 response 时间收敛
            double wd = w * Math.sqrt(Math.abs(1.0 - zeta * zeta));
            double window = 1.4 * response + 0.3;
            for (int i = 0; i < table.length; i++) {
                double t = (i / (double) (table.length - 1)) * window;
                double v;
                if (zeta >= 1.0) {
                    v = 1.0 - Math.exp(-zeta * w * t) * (1.0 + zeta * w * t);
                } else {
                    v = 1.0 - Math.exp(-zeta * w * t)
                            * (Math.cos(wd * t) + (zeta * w / wd) * Math.sin(wd * t));
                }
                table[i] = (float) Math.max(0.0, Math.min(1.0, v));
            }
        }
        @Override public float getInterpolation(float input) {
            if (input <= 0f) return 0f;
            if (input >= 1f) return 1f;
            return table[(int) (input * (table.length - 1))];
        }
    }

    /** 按压反馈：按下立即缩放（响应发生在 pointer-down），松手弹簧回弹 */
    public static void pressable(final View v) {
        final int[] down = new int[2];
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = (int) e.getRawX();
                        down[1] = (int) e.getRawY();
                        view.animate().cancel();
                        // 明显点击感：缩小 + 变暗 + 触觉震动（响应发生在按下瞬间）
                        view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.65f)
                                .setDuration(90).start();
                        try {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - down[0]) > dp(view.getContext(), 24)
                                || Math.abs(e.getRawY() - down[1]) > dp(view.getContext(), 24)) {
                            view.animate().cancel();
                            view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                    .setDuration(200).start();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(340)
                                .setInterpolator(new Spring(1.0, 0.35)).start();
                        view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
                        return true;
                }
                return false;
            }
        });
    }

    public static void haptic(View v, int type) {
        try { v.performHapticFeedback(type); } catch (Exception ignored) {}
    }

    public static boolean reducedMotion(Context c) {
        try {
            float s = Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return s <= 0f;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 排版（尺寸→字重/字距成套，大标题收紧字距） ----

    public static TextView largeTitle(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(34);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(TEXT);
        if (Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(-0.02f);
        return t;
    }

    public static TextView headline(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(TEXT);
        return t;
    }

    public static TextView body(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(TEXT);
        return t;
    }

    public static TextView footnote(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(TEXT3);
        return t;
    }
}
