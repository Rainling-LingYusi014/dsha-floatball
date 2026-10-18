package com.dsha.floatball;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int REQ_PICK = 1001;

    private static final int[] HALO_COLORS = {
            0xFFFFFFFF, 0xFF6EE7FF, 0xFF7FB2FF, 0xFFFFD166, 0xFFFF9ECB, 0xFFB9F6A0
    };
    private static final String[] HALO_NAMES = {"白", "青", "蓝", "金", "粉", "绿"};
    private static final int[] CARD_COLORS = {
            0xFF1C1F24, 0xFF000000, 0xFF101A2E, 0xFF1B1226, 0xFF0F1F1A, 0xFF22121A
    };
    private static final String[] CARD_NAMES = {"炭", "黑", "蓝", "紫", "绿", "红"};

    private TextView statusTv, avatarTv;
    private TextView sizeVal, breathVal, alphaIdleVal, alphaActiveVal;
    private TextView cardHVal, cardGapVal, btnHVal;
    private LinearLayout pageBall, pageCard, pageSvc;
    private LinearLayout[] tabPages;
    private TextView[] tabBtns;
    private TextView strokeVal, cardWVal, cardTextVal, cardLinesVal, cardOpacityVal;
    private PreviewView preview;
    private PaletteRow haloRow, strokeRow, cardRow;

    private int pad;
    private boolean manualStop = false;
    private boolean waking = false;
    private int bgColor, panelColor, titleColor, textColor, hintColor, accentColor, btnBg, btnStroke;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        manualStop = BallSettings.manualStop(this);
        pad = dp(16);
        computeTheme();

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(bgColor);
        outer.setPadding(pad, pad, pad, 0);

        outer.addView(title("DSHA 状态球"));
        outer.addView(hint("上方预览固定可见；下面分三页，改动立即生效。"));

        // ---------- 预览（固定，不随页面滚动）----------
        preview = new PreviewView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(250));
        plp.topMargin = dp(10);
        plp.bottomMargin = dp(6);
        preview.setLayoutParams(plp);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(panelColor);
        pbg.setCornerRadius(dp(14));
        preview.setBackground(pbg);
        outer.addView(preview);

        // ---------- 分页 ----------
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor);
        LinearLayout pageBox = new LinearLayout(this);
        pageBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(pageBox, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        pageBall = new LinearLayout(this); pageBall.setOrientation(LinearLayout.VERTICAL);
        pageCard = new LinearLayout(this); pageCard.setOrientation(LinearLayout.VERTICAL);
        pageSvc = new LinearLayout(this); pageSvc.setOrientation(LinearLayout.VERTICAL);
        pageBall.setPadding(0, pad, 0, pad);
        pageCard.setPadding(0, pad, 0, pad);
        pageSvc.setPadding(0, pad, 0, pad);
        pageBox.addView(pageBall);
        pageBox.addView(pageCard);
        pageBox.addView(pageSvc);

        tabPages = new LinearLayout[]{pageBall, pageCard, pageSvc};
        tabBtns = new TextView[3];
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabNames = {"球体", "悬浮框", "服务"};
        for (int i = 0; i < 3; i++) {
            tabBtns[i] = tabButton(tabNames[i], i);
            tabRow.addView(tabBtns[i]);
        }
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trp.bottomMargin = dp(6);
        outer.addView(tabRow, trp);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = pageBall;

        // ---------- 球体 ----------
        root.addView(section("球体"));

        sizeVal = addSlider(root, "尺寸", BallSettings.SIZE_MIN, BallSettings.SIZE_MAX, 1,
                BallSettings.sizeDp(this) - BallSettings.SIZE_MIN, BallSettings.sizeDp(this) + " dp",
                "size_dp", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.SIZE_MIN + progress; }
                    @Override public String label(int v) { return v + " dp"; }
                });

        breathVal = addSlider(root, "呼吸周期", BallSettings.BREATH_MIN, BallSettings.BREATH_MAX, 100,
                (BallSettings.breathMs(this) - BallSettings.BREATH_MIN) / 100,
                secText(BallSettings.breathMs(this)), "breath_ms", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.BREATH_MIN + progress * 100; }
                    @Override public String label(int v) { return secText(v); }
                });

        alphaIdleVal = addSlider(root, "空闲透明度", 20, 100, 1,
                BallSettings.alphaIdlePct(this) - 20, BallSettings.alphaIdlePct(this) + " %",
                "alpha_idle", new OnValue() {
                    @Override public int map(int progress) { return 20 + progress; }
                    @Override public String label(int v) { return v + " %"; }
                });

        alphaActiveVal = addSlider(root, "点按/拖动时透明度", 30, 100, 1,
                BallSettings.alphaActivePct(this) - 30, BallSettings.alphaActivePct(this) + " %",
                "alpha_active", new OnValue() {
                    @Override public int map(int progress) { return 30 + progress; }
                    @Override public String label(int v) { return v + " %"; }
                });

        Switch haloSwitch = new Switch(this);
        haloSwitch.setText("显示呼吸光环");
        haloSwitch.setTextColor(textColor);
        haloSwitch.setChecked(BallSettings.haloOn(this));
        haloSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton btn, boolean on) {
                BallSettings.prefs(MainActivity.this).edit().putBoolean("halo_on", on).apply();
            }
        });
        root.addView(haloSwitch);

        root.addView(rowLabel("光环颜色"));
        haloRow = new PaletteRow(this, "halo_color", HALO_COLORS, HALO_NAMES, false);
        root.addView(haloRow);

        // ---------- 描边 ----------
        root.addView(section("描边（把头像和背景隔开）"));
        strokeVal = addSlider(root, "描边宽度", BallSettings.STROKE_MIN, BallSettings.STROKE_MAX, 1,
                BallSettings.strokeDp(this) - BallSettings.STROKE_MIN,
                BallSettings.strokeDp(this) + " dp", "stroke_dp", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.STROKE_MIN + progress; }
                    @Override public String label(int v) { return v == 0 ? "关闭" : v + " dp"; }
                });
        root.addView(rowLabel("描边颜色"));
        strokeRow = new PaletteRow(this, "stroke_color", HALO_COLORS, HALO_NAMES, false);
        root.addView(strokeRow);

        // ---------- 悬浮框 ----------
        root = pageCard;
        root.addView(section("悬浮框（点球展开的卡片）"));

        cardWVal = addSlider(root, "卡片宽度", BallSettings.CARD_W_MIN, BallSettings.CARD_W_MAX, 10,
                (BallSettings.cardWidthDp(this) - BallSettings.CARD_W_MIN) / 10,
                BallSettings.cardWidthDp(this) + " dp", "card_w", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.CARD_W_MIN + progress * 10; }
                    @Override public String label(int v) { return v + " dp"; }
                });

        cardHVal = addSlider(root, "卡片高度（锁定，内容超出就滚动）", BallSettings.CARD_H_MIN, BallSettings.CARD_H_MAX, 10,
                (BallSettings.cardHeightDp(this) - BallSettings.CARD_H_MIN) / 10,
                BallSettings.cardHeightDp(this) + " dp", "card_h", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.CARD_H_MIN + progress * 10; }
                    @Override public String label(int v) { return v + " dp"; }
                });

        cardGapVal = addSlider(root, "卡片与球的距离", BallSettings.CARD_GAP_MIN, BallSettings.CARD_GAP_MAX, 2,
                (BallSettings.cardGapDp(this) - BallSettings.CARD_GAP_MIN) / 2,
                BallSettings.cardGapDp(this) + " dp", "card_gap", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.CARD_GAP_MIN + progress * 2; }
                    @Override public String label(int v) { return v + " dp"; }
                });

        btnHVal = addSlider(root, "按钮高度", BallSettings.BTN_H_MIN, BallSettings.BTN_H_MAX, 1,
                BallSettings.buttonHeightDp(this) - BallSettings.BTN_H_MIN,
                BallSettings.buttonHeightDp(this) + " dp", "btn_h", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.BTN_H_MIN + progress; }
                    @Override public String label(int v) { return v + " dp"; }
                });

        cardTextVal = addSlider(root, "文字大小", BallSettings.CARD_TEXT_MIN, BallSettings.CARD_TEXT_MAX, 1,
                BallSettings.cardTextSp(this) - BallSettings.CARD_TEXT_MIN,
                BallSettings.cardTextSp(this) + " sp", "card_text", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.CARD_TEXT_MIN + progress; }
                    @Override public String label(int v) { return v + " sp"; }
                });

        cardLinesVal = addSlider(root, "显示动作行数", BallSettings.CARD_LINES_MIN, BallSettings.CARD_LINES_MAX, 1,
                BallSettings.cardLines(this) - BallSettings.CARD_LINES_MIN,
                BallSettings.cardLines(this) + " 行", "card_lines", new OnValue() {
                    @Override public int map(int progress) { return BallSettings.CARD_LINES_MIN + progress; }
                    @Override public String label(int v) { return v + " 行"; }
                });

        cardOpacityVal = addSlider(root, "卡片不透明度", 30, 100, 1,
                BallSettings.cardOpacityPct(this) - 30, BallSettings.cardOpacityPct(this) + " %",
                "card_alpha", new OnValue() {
                    @Override public int map(int progress) { return 30 + progress; }
                    @Override public String label(int v) { return v + " %"; }
                });

        root.addView(rowLabel("卡片底色"));
        cardRow = new PaletteRow(this, "card_bg", CARD_COLORS, CARD_NAMES, false);
        root.addView(cardRow);

        Switch followSwitch = new Switch(this);
        followSwitch.setText("卡片透明度跟随悬浮球");
        followSwitch.setTextColor(textColor);
        followSwitch.setChecked(BallSettings.cardFollowBall(this));
        followSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton btn, boolean on) {
                BallSettings.prefs(MainActivity.this).edit().putBoolean("card_follow", on).apply();
            }
        });
        root.addView(followSwitch);
        root.addView(hint("勾选后忽略上面的卡片不透明度，改用球当前的透明度（空闲/点按两档）。"));

        addThemeButtons(root);

        // ---------- 头像 ----------
        root = pageBall;
        root.addView(section("头像"));
        avatarTv = hint("");
        root.addView(avatarTv);

        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = button("从相册选一张");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(i, REQ_PICK); } catch (Exception ignored) {}
            }
        });
        Button resetAvatar = button("恢复默认");
        resetAvatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BallSettings.prefs(MainActivity.this).edit().putString("avatar_uri", "").apply();
                refresh();
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(8);
        avatarRow.addView(pick, half);
        avatarRow.addView(resetAvatar, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(avatarRow);

        // ---------- 位置与服务 ----------
        root = pageSvc;
        root.addView(section("位置"));
        Button resetPos = button("重置到默认位置");
        resetPos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BallSettings.clearPos(MainActivity.this);
                BallSettings.prefs(MainActivity.this).edit()
                        .putInt("pos_x", Integer.MIN_VALUE)
                        .putInt("pos_y", Integer.MIN_VALUE).apply();
                refresh();
            }
        });
        root.addView(resetPos);

        root.addView(section("服务"));
        statusTv = hint("");
        root.addView(statusTv);

        Button grant = button("① 授予悬浮窗权限");
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                }
                refresh();
            }
        });
        root.addView(grant);

        Button start = button("② 启动状态球");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                manualStop = false;
                BallSettings.setManualStop(MainActivity.this, false);
                startService(new Intent(MainActivity.this, BallService.class));
                refresh();
            }
        });
        root.addView(start);

        Button stop = button("停止状态球");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                manualStop = true;
                BallSettings.setManualStop(MainActivity.this, true);
                // 先让服务自己 stopSelf（MIUI 有时会吞掉外部 stopService）
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                    new java.net.URL("http://127.0.0.1:3091/shutdown").openConnection();
                            c.setConnectTimeout(800);
                            c.setReadTimeout(800);
                            c.getResponseCode();
                            c.disconnect();
                        } catch (Exception ignored) {
                            // 服务没在跑也无所谓，下面还会 stopService
                        }
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                stopService(new Intent(MainActivity.this, BallService.class));
                                refresh();
                            }
                        });
                    }
                }).start();
                refresh();
            }
        });
        root.addView(stop);

        setContentView(outer);
        selectTab(0);
        BallSettings.prefs(this).registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    /**
     * 界面被打开（含被外部唤醒）时，如果悬浮窗权限已给、球却没在跑，
     * 就自动把服务拉起来，然后把界面退回后台，避免挡住用户。
     * 用户自己按过「停止状态球」时不做这件事。
     */
    private void wakeIfNeeded() {
        if (BallService.running || waking || manualStop) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        waking = true;
        try {
            startService(new Intent(this, BallService.class));
        } catch (Exception e) {
            waking = false;
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                waking = false;
                try { moveTaskToBack(true); } catch (Exception ignored) {}
            }
        }, 900);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, final String key) {
        // 拖滑条时每个 tick 都会进来：按需刷新，别每次都重新解码头像、重启动画
        if (preview == null) return;
        if ("avatar_uri".equals(key)) {
            preview.post(new Runnable() {
                @Override public void run() { preview.reloadAvatar(); }
            });
        } else if ("breath_ms".equals(key) || "halo_on".equals(key)) {
            preview.post(new Runnable() {
                @Override public void run() { preview.refreshAnim(); }
            });
        } else {
            preview.post(new Runnable() {
                @Override public void run() { preview.invalidate(); }
            });
        }
    }

    @Override
    protected void onDestroy() {
        BallSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            BallSettings.prefs(this).edit().putString("avatar_uri", uri.toString()).apply();
            refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        wakeIfNeeded();
    }

    private void refresh() {
        boolean can = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        statusTv.setText("悬浮窗权限：" + (can ? "已授予 ✓" : "未授予 ✗（先点①）"));
        statusTv.setTextColor(can ? 0xFF8FD18F : 0xFFFF9A90);

        String uri = BallSettings.avatarUri(this);
        avatarTv.setText("当前头像：" + (uri == null || uri.isEmpty() ? "内置默认图" : "你选的自定义图片"));
        avatarTv.setTextColor(0xFF9AA3B2);

        if (preview != null) preview.reload();
        if (haloRow != null) haloRow.refresh();
        if (strokeRow != null) strokeRow.refresh();
        if (cardRow != null) cardRow.refresh();
    }

    // ---------- 控件工厂 ----------

    private void computeTheme() {
        boolean dark;
        int t = BallSettings.theme(this);
        if (t == 1) dark = false;
        else if (t == 2) dark = true;
        else {
            int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            dark = mode == Configuration.UI_MODE_NIGHT_YES;
        }
        if (dark) {
            bgColor = 0xFF0F1115; panelColor = 0xFF161A21; titleColor = 0xFFFFFFFF;
            textColor = 0xFFCFD6E2; hintColor = 0xFF9AA3B2; accentColor = 0xFF8FB8FF;
            btnBg = 0xFF1E2430; btnStroke = 0xFF3A4A66;
        } else {
            bgColor = 0xFFF5F7FA; panelColor = 0xFFE7EBF2; titleColor = 0xFF111418;
            textColor = 0xFF2A2F38; hintColor = 0xFF6B7280; accentColor = 0xFF2563EB;
            btnBg = 0xFFFFFFFF; btnStroke = 0xFFD5DBE5;
        }
    }

    private void addThemeButtons(LinearLayout root) {
        root.addView(section("应用外观"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"跟随系统", "浅色", "深色"};
        for (int i = 0; i < 3; i++) {
            final int v = i;
            Button b = button(names[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(8);
            b.setLayoutParams(lp);
            if (BallSettings.theme(this) == i) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(accentColor);
                g.setCornerRadius(28);
                b.setBackground(g);
                b.setTextColor(0xFFFFFFFF);
            }
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v2) {
                    BallSettings.setTheme(MainActivity.this, v);
                    recreate();
                }
            });
            row.addView(b);
        }
        root.addView(row);
        root.addView(hint("只影响设置页的样子，不改变手机上悬浮球的显示。"));
    }

    private interface OnValue {
        int map(int progress);
        String label(int value);
    }

    private TextView tabButton(String name, final int idx) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(9), dp(10), dp(9));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectTab(idx); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (idx < 2) lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void selectTab(int idx) {
        if (tabPages == null || tabBtns == null) return;
        for (int i = 0; i < tabPages.length; i++) {
            tabPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? accentColor : panelColor);
            g.setCornerRadius(dp(10));
            tabBtns[i].setBackground(g);
            tabBtns[i].setTextColor(on ? 0xFFFFFFFF : hintColor);
        }
    }

    private TextView addSlider(LinearLayout parent, String name, int min, int max, int step,
                               int progress, String initial, final String key, final OnValue mapper) {
        parent.addView(rowLabel(name));
        final TextView val = new TextView(this);
        val.setText(initial);
        val.setTextSize(12);
        val.setTextColor(0xFF8FB8FF);

        final SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        bar.setProgress(progress);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = mapper.map(p);
                val.setText(mapper.label(v));
                if (fromUser) BallSettings.prefs(MainActivity.this).edit().putInt(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(bar);
        parent.addView(val);
        return val;
    }

    private class PaletteRow extends LinearLayout {
        private final int[] colors;
        private final String key;
        private final TextView[] chips;

        PaletteRow(Context c, String key, int[] colors, String[] names, boolean unused) {
            super(c);
            this.key = key;
            this.colors = colors;
            this.chips = new TextView[colors.length];
            setOrientation(HORIZONTAL);
            for (int i = 0; i < colors.length; i++) {
                final int color = colors[i];
                TextView chip = new TextView(c);
                chip.setText(names[i]);
                chip.setTextSize(11);
                chip.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
                lp.rightMargin = dp(8);
                chip.setLayoutParams(lp);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BallSettings.prefs(MainActivity.this).edit().putInt(key, color).apply();
                        refresh();
                    }
                });
                chips[i] = chip;
                addView(chip);
            }
            refresh();
        }

        void refresh() {
            int cur = BallSettings.prefs(MainActivity.this).getInt(key, colors[0]);
            for (int i = 0; i < chips.length; i++) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(colors[i]);
                g.setStroke(dp(3), cur == colors[i] ? 0xFF3DDC84 : 0x22FFFFFF);
                chips[i].setBackground(g);
                chips[i].setTextColor(luminance(colors[i]) > 0.55 ? 0xFF202020 : 0xFFFFFFFF);
            }
        }

        private double luminance(int color) {
            double r = ((color >> 16) & 0xFF) / 255.0;
            double g = ((color >> 8) & 0xFF) / 255.0;
            double b = (color & 0xFF) / 255.0;
            return 0.2126 * r + 0.7152 * g + 0.0722 * b;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView title(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(22);
        tv.setTextColor(titleColor);
        return tv;
    }

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTextColor(hintColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView rowLabel(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(14);
        tv.setTextColor(textColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView hint(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(12);
        tv.setTextColor(hintColor);
        tv.setPadding(0, dp(6), 0, dp(6));
        return tv;
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(btnBg);
        bg.setCornerRadius(28);
        bg.setStroke(2, btnStroke);
        b.setBackground(bg);
        b.setTextColor(textColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private String secText(int ms) {
        return String.format("%.1f 秒", ms / 1000f);
    }
}
