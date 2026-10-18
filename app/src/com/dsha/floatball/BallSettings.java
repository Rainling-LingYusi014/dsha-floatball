package com.dsha.floatball;

import android.content.Context;
import android.content.SharedPreferences;

/** 状态球 + 卡片的所有可调参数；服务监听 SharedPreferences 变化实时生效。 */
public final class BallSettings {

    public static final String PREF = "ball_prefs";

    public static final int SIZE_MIN = 36, SIZE_MAX = 96;
    public static final int BREATH_MIN = 600, BREATH_MAX = 5000;
    public static final int CARD_W_MIN = 180, CARD_W_MAX = 420;
    public static final int CARD_H_MIN = 140, CARD_H_MAX = 560;
    public static final int CARD_TEXT_MIN = 9, CARD_TEXT_MAX = 20;
    public static final int CARD_LINES_MIN = 1, CARD_LINES_MAX = 6;
    public static final int CARD_GAP_MIN = 0, CARD_GAP_MAX = 80;
    public static final int BTN_H_MIN = 24, BTN_H_MAX = 52;
    public static final int STROKE_MIN = 0, STROKE_MAX = 10;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---- 球 ----
    public static int sizeDp(Context c) { return clamp(prefs(c).getInt("size_dp", 56), SIZE_MIN, SIZE_MAX); }
    public static int breathMs(Context c) { return clamp(prefs(c).getInt("breath_ms", 2000), BREATH_MIN, BREATH_MAX); }
    public static int alphaIdlePct(Context c) { return clamp(prefs(c).getInt("alpha_idle", 60), 20, 100); }
    public static int alphaActivePct(Context c) { return clamp(prefs(c).getInt("alpha_active", 80), 30, 100); }
    public static boolean haloOn(Context c) { return prefs(c).getBoolean("halo_on", true); }
    public static int haloColor(Context c) { return prefs(c).getInt("halo_color", 0xFFFFFFFF); }
    public static int strokeDp(Context c) { return clamp(prefs(c).getInt("stroke_dp", 3), STROKE_MIN, STROKE_MAX); }
    public static int strokeColor(Context c) { return prefs(c).getInt("stroke_color", 0xCCFFFFFF); }

    // ---- 头像 ----
    public static String avatarUri(Context c) { return prefs(c).getString("avatar_uri", ""); }

    // ---- 卡片 ----
    public static int cardWidthDp(Context c) { return clamp(prefs(c).getInt("card_w", 260), CARD_W_MIN, CARD_W_MAX); }
    public static int cardHeightDp(Context c) { return clamp(prefs(c).getInt("card_h", 240), CARD_H_MIN, CARD_H_MAX); }
    public static int cardGapDp(Context c) { return clamp(prefs(c).getInt("card_gap", 12), CARD_GAP_MIN, CARD_GAP_MAX); }
    public static int cardTextSp(Context c) { return clamp(prefs(c).getInt("card_text", 13), CARD_TEXT_MIN, CARD_TEXT_MAX); }
    public static int cardLines(Context c) { return clamp(prefs(c).getInt("card_lines", 3), CARD_LINES_MIN, CARD_LINES_MAX); }
    public static int cardBgColor(Context c) { return prefs(c).getInt("card_bg", 0xFF1C1F24); }
    public static int cardOpacityPct(Context c) { return clamp(prefs(c).getInt("card_alpha", 94), 30, 100); }
    public static int buttonHeightDp(Context c) { return clamp(prefs(c).getInt("btn_h", 36), BTN_H_MIN, BTN_H_MAX); }

    // ---- 卡片透明度是否跟随球 ----
    public static boolean cardFollowBall(Context c) { return prefs(c).getBoolean("card_follow", false); }

    // ---- 用户主动停止（跨 Activity 重建保留，避免"关掉又自己回来"）----
    public static boolean manualStop(Context c) { return prefs(c).getBoolean("manual_stop", false); }
    public static void setManualStop(Context c, boolean v) { prefs(c).edit().putBoolean("manual_stop", v).apply(); }

    // ---- App 外观：0 跟随系统 / 1 浅色 / 2 深色 ----
    public static int theme(Context c) { return clamp(prefs(c).getInt("theme", 0), 0, 2); }
    public static void setTheme(Context c, int v) { prefs(c).edit().putInt("theme", v).apply(); }

    // ---- 位置 ----
    public static int posX(Context c) { return prefs(c).getInt("pos_x", Integer.MIN_VALUE); }
    public static int posY(Context c) { return prefs(c).getInt("pos_y", Integer.MIN_VALUE); }
    public static void savePos(Context c, int x, int y) { prefs(c).edit().putInt("pos_x", x).putInt("pos_y", y).apply(); }
    public static void clearPos(Context c) {
        prefs(c).edit().putInt("pos_x", Integer.MIN_VALUE).putInt("pos_y", Integer.MIN_VALUE).apply();
    }

    /** 把纯色与不透明度百分比合成 ARGB。 */
    public static int withAlpha(int rgb, int pct) {
        int a = Math.max(0, Math.min(100, pct)) * 255 / 100;
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
