package com.dsha.floatball;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮球：圆形头像 + 可调呼吸光环。
 * 外观参数全部来自 BallSettings，改动后监听 SharedPreferences 实时生效。
 */
public class BallService extends Service implements StatusServer.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    /** 服务是否在运行；MainActivity 用它判断打开界面时要不要自动把球拉起来。 */
    public static volatile boolean running = false;

    private WindowManager wm;
    private BallView ball;
    private View card;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams cardParams;
    private Bitmap avatar;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ValueAnimator haloAnim;
    private float haloPhase = 0f;
    private boolean cardOpen = false;
    private boolean cardAttached = false;
    private boolean touchActive = false;
    private boolean haloAllowed = false;

    private float density;
    private int sizeDp, ballPx, haloPad, winSize;
    private int breathMs, alphaIdlePct, alphaActivePct, haloColor;
    private boolean haloOn;
    private int cardWidthDp, cardHeightDp, cardGapDp, cardTextSp, cardLines, cardBgColor, cardOpacityPct, btnHDp;
    private int strokeDp, strokeColor;
    private boolean cardFollowBall;

    private int screenW, screenH;

    private String state = "idle";
    private String task = "";
    private String step = "";
    private String recent = "";
    private String[] planSteps = new String[0];
    private int curStep = 0;
    private boolean stopRequested = false;
    /** device = 真的在接管屏幕；chat = 只写回复、读屏、改代码。 */
    private String mode = "device";
    private long runStartTs = 0;

    private TextView taskTv, elapsedTv, stopTv, titleTv, progressTv, backTv;
    private LinearLayout stepsBox;
    private ScrollView cardScroll;
    private String stepsSig = "";
    private long finishedTs = 0;

    private StatusServer server;
    private SharedPreferences pref;

    private final Runnable dimRunnable = new Runnable() {
        @Override public void run() {
            touchActive = false;
            applyAlpha();
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (finishedTs > 0 && System.currentTimeMillis() - finishedTs >= 8000) {
                finishedTs = 0;
                applyAlpha();
            }
            refreshCard();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        readSettings();
        loadAvatar();

        startForegroundWithNotification();
        addBall();

        server = new StatusServer(this);
        new Thread(server).start();

        pref = BallSettings.prefs(this);
        pref.registerOnSharedPreferenceChangeListener(this);

        running = true;
        ui.postDelayed(tickRunnable, 1000);
    }

    // ---------------- 设置 ----------------

    private void readSettings() {
        sizeDp = BallSettings.sizeDp(this);
        breathMs = BallSettings.breathMs(this);
        alphaIdlePct = BallSettings.alphaIdlePct(this);
        alphaActivePct = BallSettings.alphaActivePct(this);
        haloOn = BallSettings.haloOn(this);
        haloColor = BallSettings.haloColor(this);
        strokeDp = BallSettings.strokeDp(this);
        strokeColor = BallSettings.strokeColor(this);
        cardWidthDp = BallSettings.cardWidthDp(this);
        cardHeightDp = BallSettings.cardHeightDp(this);
        cardGapDp = BallSettings.cardGapDp(this);
        cardTextSp = BallSettings.cardTextSp(this);
        cardLines = BallSettings.cardLines(this);
        cardBgColor = BallSettings.cardBgColor(this);
        cardOpacityPct = BallSettings.cardOpacityPct(this);
        cardFollowBall = BallSettings.cardFollowBall(this);
        btnHDp = BallSettings.buttonHeightDp(this);
        haloPad = dp(10);
        ballPx = dp(sizeDp);
        winSize = ballPx + haloPad * 2;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, final String key) {
        ui.post(new Runnable() {
            @Override public void run() {
                readSettings();
                if ("size_dp".equals(key)) resizeBall();
                if ("avatar_uri".equals(key)) loadAvatar();
                if ("breath_ms".equals(key) && haloAnim != null) haloAnim.setDuration(breathMs);
                if ("pos_x".equals(key) || "pos_y".equals(key)) restorePosition();
                if ("halo_on".equals(key)) setHalo("running".equals(state) && isDeviceMode());
                if ("card_follow".equals(key)) applyAlpha();
                if (isCardKey(key) && cardOpen) { closeCard(); openCard(); }
                applyAlpha();
                if (ball != null) ball.invalidate();
            }
        });
    }

    private void loadAvatar() {
        AvatarUtil.loadAsync(this, 720, new AvatarUtil.Cb() {
            @Override public void onBitmap(Bitmap b) {
                avatar = b;
                if (ball != null) ball.invalidate();
            }
        });
    }

    private void resizeBall() {
        if (ball == null) return;
        ballParams.width = winSize;
        ballParams.height = winSize;
        clamp();
        try { wm.updateViewLayout(ball, ballParams); } catch (Exception ignored) {}
        ball.invalidate();
        if (cardOpen) positionCard();
    }

    private void restorePosition() {
        if (ball == null) return;
        int px = BallSettings.posX(this);
        int py = BallSettings.posY(this);
        if (px == Integer.MIN_VALUE || py == Integer.MIN_VALUE) {
            ballParams.x = screenW - winSize;
            ballParams.y = (int) (screenH * 0.34f);
        } else {
            ballParams.x = px;
            ballParams.y = py;
        }
        clamp();
        try { wm.updateViewLayout(ball, ballParams); } catch (Exception ignored) {}
        if (cardOpen) positionCard();
    }

    // ---------------- 通知与球体 ----------------

    private void startForegroundWithNotification() {
        String ch = "ball";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel c = new NotificationChannel(ch, "状态球", NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification n = new Notification.Builder(this, ch)
                .setContentTitle("DSHA 状态球运行中")
                .setContentText("点开 App 可调整外观或停止")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private void addBall() {
        ball = new BallView(this);
        ballParams = new WindowManager.LayoutParams(
                winSize, winSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;

        int px = BallSettings.posX(this);
        int py = BallSettings.posY(this);
        if (px == Integer.MIN_VALUE || py == Integer.MIN_VALUE) {
            ballParams.x = screenW - winSize;
            ballParams.y = (int) (screenH * 0.34f);
        } else {
            ballParams.x = px;
            ballParams.y = py;
        }
        clamp();
        applyAlpha();

        ball.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = ballParams.x;
                        startY = ballParams.y;
                        moved = false;
                        bumpTouch();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (moved) {
                            ballParams.x = (int) (startX + dx);
                            ballParams.y = (int) (startY + dy);
                            clamp();
                            try { wm.updateViewLayout(ball, ballParams); } catch (Exception ignored) {}
                            bumpTouch();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            snapToEdge();
                            BallSettings.savePos(BallService.this, ballParams.x, ballParams.y);
                        } else {
                            toggleCard();
                        }
                        bumpTouch();
                        return true;
                }
                return false;
            }
        });
        wm.addView(ball, ballParams);
    }

    /** 这一轮是不是真的在接管屏幕；只有它才让球呼吸、变亮、弹卡片。 */
    private boolean isDeviceMode() {
        return !"chat".equals(mode);
    }

    /** 刚完成一次任务后的 8 秒内：球用"唤醒时"的透明度，卡片保持展开。 */
    private boolean showingDone() {
        return finishedTs > 0 && System.currentTimeMillis() - finishedTs < 8000;
    }

    private void applyAlpha() {
        if (ball == null) return;
        // 只有"正在接管屏幕"或"刚做完"时才用唤醒时的透明度；纯聊天/读屏时保持待机，不去打扰用户
        boolean awake = touchActive || showingDone() || ("running".equals(state) && isDeviceMode());
        int pct = awake ? alphaActivePct : alphaIdlePct;
        ball.setAlpha(pct / 100f);
        if (cardAttached && card != null) {
            card.setAlpha(cardFollowBall ? pct / 100f : 1f);
        }
    }

    private void bumpTouch() {
        touchActive = true;
        applyAlpha();
        ui.removeCallbacks(dimRunnable);
        ui.postDelayed(dimRunnable, 5000);
    }

    private void clamp() {
        if (ballParams.x < 0) ballParams.x = 0;
        if (ballParams.y < 0) ballParams.y = 0;
        if (ballParams.x > screenW - winSize) ballParams.x = screenW - winSize;
        if (ballParams.y > screenH - winSize) ballParams.y = screenH - winSize;
    }

    private void snapToEdge() {
        ballParams.x = (ballParams.x + winSize / 2 < screenW / 2) ? 0 : screenW - winSize;
        try { wm.updateViewLayout(ball, ballParams); } catch (Exception ignored) {}
        if (cardOpen) positionCard();
    }

    private void setHalo(boolean on) {
        haloAllowed = on && haloOn;
        if (haloAllowed) {
            if (haloAnim == null) {
                haloAnim = ValueAnimator.ofFloat(0f, 1f);
                haloAnim.setDuration(breathMs);
                haloAnim.setRepeatCount(ValueAnimator.INFINITE);
                haloAnim.setRepeatMode(ValueAnimator.REVERSE);
                haloAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        haloPhase = (float) a.getAnimatedValue();
                        if (ball != null) ball.invalidate();
                    }
                });
            } else {
                haloAnim.setDuration(breathMs);
            }
            if (!haloAnim.isStarted()) haloAnim.start();
        } else {
            if (haloAnim != null) haloAnim.cancel();
            haloPhase = 0f;
            if (ball != null) ball.invalidate();
        }
    }

    private boolean isCardKey(String key) {
        return "card_w".equals(key) || "card_text".equals(key)
                || "card_lines".equals(key) || "card_bg".equals(key)
                || "card_alpha".equals(key) || "card_follow".equals(key)
                || "card_h".equals(key) || "card_gap".equals(key)
                || "btn_h".equals(key);
    }

    // ---------------- 状态回调 ----------------

    @Override
    public void onUpdate(final String st, final String t, final String s,
                         final String rec, final String steps, final int cur, final String md) {
        ui.post(new Runnable() {
            @Override public void run() {
                boolean wasRunning = "running".equals(state);
                state = st;
                task = t;
                step = s;
                recent = rec;
                mode = (md == null || md.isEmpty()) ? "device" : ("chat".equals(md) ? "chat" : "device");
                if (steps != null) {
                    if (steps.trim().isEmpty()) {
                        planSteps = new String[0];
                        curStep = 0;
                    } else {
                        planSteps = steps.split("\\|");
                        curStep = cur > 0 ? Math.min(cur, planSteps.length) : 0;
                    }
                } else if (cur > 0) {
                    curStep = Math.min(cur, Math.max(1, planSteps.length));
                }
                if ("running".equals(st) && !wasRunning) runStartTs = System.currentTimeMillis();
                if (!"running".equals(st)) stopRequested = false;
                if (wasRunning && !"running".equals(st)) {
                    finishedTs = System.currentTimeMillis();
                    // 只有真实任务收尾才自动展开卡片；纯文字问答（无计划、没动过手）不打扰
                    if ((planSteps.length > 0 || isDeviceMode()) && !cardOpen) openCard();
                }
                setHalo("running".equals(st) && isDeviceMode());
                refreshCard();
                applyAlpha();
            }
        });
    }

    /** /state 的值要保证 k=v; 解析不被内容破坏：把 ; = % 转义成百分号编码。 */
    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("%", "%25").replace(";", "%3B").replace("=", "%3D")
                .replace("\n", " ").replace("\r", " ");
    }

    @Override
    public String onQueryState() {
        String prog = planSteps.length > 0
                ? Math.min(Math.max(curStep, 1), planSteps.length) + "/" + planSteps.length : "-";
        return "state=" + state + ";stop=" + (stopRequested ? 1 : 0)
                + ";mode=" + mode + ";task=" + esc(task) + ";step=" + esc(step) + ";progress=" + prog;
    }

    @Override
    public void onStopRequest() {
        ui.post(new Runnable() {
            @Override public void run() {
                stopRequested = true;
                refreshCard();
            }
        });
    }

    @Override
    public void onShutdown() {
        ui.post(new Runnable() {
            @Override public void run() {
                closeCard();
                stopSelf();
            }
        });
    }

    @Override
    public void onClearStop() {
        ui.post(new Runnable() {
            @Override public void run() {
                stopRequested = false;
                refreshCard();
            }
        });
    }

    // ---------------- 卡片 ----------------

    private void toggleCard() {
        try {
            if (cardOpen) closeCard(); else openCard();
        } catch (Exception e) {
            closeCard();
        }
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private void openCard() {
        if (card != null) {
            try { wm.removeView(card); } catch (Exception ignored) {}
            card = null;
            cardAttached = false;
        }
        stepsSig = "";
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BallSettings.withAlpha(cardBgColor, cardOpacityPct));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        root.setBackground(bg);

        // ---- 固定头部：状态 + 右上角进度 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        titleTv = label("", Math.max(9, cardTextSp - 1), 0xFF8FB8FF, false);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(titleTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        progressTv = label("", Math.max(9, cardTextSp - 1), 0xFFFFD166, true);
        progressTv.setSingleLine(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.leftMargin = dp(8);
        head.addView(progressTv, pp);
        root.addView(head);

        taskTv = label("", cardTextSp + 1, 0xFFFFFFFF, true);
        taskTv.setMaxLines(2);
        taskTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(3);
        root.addView(taskTv, tp);

        // ---- 唯一可滚动区域：执行进度 ----
        cardScroll = new ScrollView(this);
        cardScroll.setVerticalScrollBarEnabled(false);
        cardScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        cardScroll.addView(stepsBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(6);
        root.addView(cardScroll, sp);

        // ---- 固定底部：已用时 + 按钮 ----
        elapsedTv = label("", Math.max(9, cardTextSp - 1), 0xFF8A93A0, false);
        elapsedTv.setSingleLine(true);
        root.addView(elapsedTv);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);

        stopTv = label("停止操作", cardTextSp, 0xFFFF9A90, true);
        stopTv.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xFF2A2E36);
        sbg.setCornerRadius(dp(12));
        stopTv.setBackground(sbg);
        stopTv.setGravity(Gravity.CENTER);
        stopTv.setSingleLine(true);
        stopTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!"running".equals(state)) return;
                stopRequested = !stopRequested;
                refreshCard();
            }
        });

        backTv = label("返回 DSHA", cardTextSp, 0xFFCFD6E2, true);
        backTv.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(0xFF2A2E36);
        gbg.setCornerRadius(dp(12));
        backTv.setBackground(gbg);
        backTv.setGravity(Gravity.CENTER);
        backTv.setSingleLine(true);
        backTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent it = new Intent();
                    it.setClassName("com.dsh.client",
                            "com.deepseekharness.app.ui.MainActivity");
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                    closeCard();
                } catch (Exception e) {
                    try {
                        Intent it2 = getPackageManager().getLaunchIntentForPackage("com.dsh.client");
                        if (it2 != null) {
                            it2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(it2);
                            closeCard();
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(btnHDp), 1f);
        lp1.rightMargin = dp(8);
        btnRow.addView(stopTv, lp1);
        btnRow.addView(backTv, new LinearLayout.LayoutParams(0, dp(btnHDp), 1f));
        root.addView(btnRow, rowLp);

        card = root;
        cardParams = new WindowManager.LayoutParams(
                dp(cardWidthDp), dp(cardHeightDp),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        cardParams.gravity = Gravity.TOP | Gravity.LEFT;
        applyCardPosition();
        wm.addView(card, cardParams);
        cardAttached = true;
        cardOpen = true;
        refreshCard();
        card.post(new Runnable() {
            @Override public void run() {
                if (cardOpen && cardAttached) { positionCard(); applyAlpha(); realignCard(); }
            }
        });
        applyAlpha();
    }

    /**
     * 用实测屏幕坐标把卡片的中线纠到球心高度上。
     * 窗口坐标受状态栏/导航栏 inset 影响时，纯计算会对不齐，这里量一次再修。
     */
    private void realignCard() {
        if (!cardAttached || card == null) return;
        card.post(new Runnable() {
            @Override public void run() {
                if (!cardAttached || card == null) return;
                try {
                    // 两个窗口都用"实际屏幕坐标"，ballParams 是请求值，系统会再补状态栏 inset
                    int[] cloc = new int[2];
                    card.getLocationOnScreen(cloc);
                    int[] bloc = new int[2];
                    ball.getLocationOnScreen(bloc);
                    int cardCenter = cloc[1] + card.getHeight() / 2;
                    int ballCenter = bloc[1] + ball.getHeight() / 2;
                    int delta = ballCenter - cardCenter;
                    if (Math.abs(delta) > 1) {
                        cardParams.y += delta;
                        wm.updateViewLayout(card, cardParams);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void applyCardPosition() {
        if (cardParams == null) return;
        int cardW = dp(cardWidthDp);
        boolean left = ballParams.x + winSize / 2 > screenW / 2;
        cardParams.x = left
                ? Math.max(0, ballParams.x - cardW - dp(cardGapDp))
                : Math.min(screenW - cardW, ballParams.x + winSize + dp(cardGapDp));
        int cardH = dp(cardHeightDp);
        int centerY = ballParams.y + winSize / 2;
        cardParams.y = Math.max(0, Math.min(screenH - cardH, centerY - cardH / 2));
    }

    private void positionCard() {
        applyCardPosition();
        if (cardAttached && card != null) {
            try { wm.updateViewLayout(card, cardParams); } catch (Exception ignored) {}
            // applyCardPosition 是纯计算，量不出状态栏 inset 的误差，量一次纠回来
            realignCard();
        }
    }

    private void closeCard() {
        if (card != null) {
            try { wm.removeView(card); } catch (Exception ignored) {}
            card = null;
        }
        cardAttached = false;
        cardOpen = false;
    }

    private void refreshCard() {
        if (!cardOpen || taskTv == null) return;
        String st = state;
        boolean dev = isDeviceMode();
        if ("running".equals(st)) {
            if (dev) {
                titleTv.setText("正在操作你的手机");
                titleTv.setTextColor(0xFF8FB8FF);
                taskTv.setText(task.isEmpty() ? "屏幕会自己动，先别碰" : task);
            } else {
                titleTv.setText("正在处理");
                titleTv.setTextColor(0xFFA9B6C6);
                taskTv.setText(task.isEmpty() ? "手机没有在动，你可以照常用" : task);
            }
        } else if (showingDone()) {
            if (dev) {
                titleTv.setText("操作完成 ✓");
                titleTv.setTextColor(0xFF8FE3A8);
                taskTv.setText(task.isEmpty() ? "刚才的操作已经结束" : task);
            } else {
                titleTv.setText("回复已生成 ✓");
                titleTv.setTextColor(0xFF8FE3A8);
                taskTv.setText(task.isEmpty() ? "内容已经输出，可以看了" : task);
            }
        } else if ("error".equals(st)) {
            titleTv.setText("出错了 / 权限掉线");
            titleTv.setTextColor(0xFFFF9A90);
            taskTv.setText(task.isEmpty() ? "需要处理后才能继续" : task);
        } else {
            titleTv.setText("空闲中");
            titleTv.setTextColor(0xFF8A93A0);
            taskTv.setText(task.isEmpty() ? "现在没有在操作手机" : task);
        }

        int total = planSteps.length;
        if (total > 0) {
            progressTv.setVisibility(View.VISIBLE);
            progressTv.setText(Math.min(Math.max(curStep, 1), total) + "/" + total);
        } else {
            progressTv.setVisibility(View.GONE);
        }

        fillSteps();

        if ("running".equals(st) && runStartTs > 0) {
            long sec = (System.currentTimeMillis() - runStartTs) / 1000;
            elapsedTv.setText(String.format("已用时 %02d:%02d", sec / 60, sec % 60));
        } else {
            elapsedTv.setText("");
        }

        // 没在操作时藏掉停止按钮，返回按钮自动占满整行；停止是可逆的。
        boolean running = "running".equals(st) && dev;
        stopTv.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            if (stopRequested) {
                stopTv.setText("继续操作");
                stopTv.setTextColor(0xFF8FE3A8);
            } else {
                stopTv.setText("停止操作");
                stopTv.setTextColor(0xFFFF9A90);
            }
        }
    }

    /** 重建中间可滚动区；内容没变就不动，避免每秒刷新时闪一下。 */
    private void fillSteps() {
        if (stepsBox == null) return;
        StringBuilder sig = new StringBuilder();
        sig.append(state).append('|').append(task).append('|').append(step).append('|')
                .append(recent).append('|').append(curStep).append('|')
                .append(planSteps.length).append('|').append(cardLines);
        for (String s : planSteps) sig.append('|').append(s);
        if (sig.toString().equals(stepsSig)) return;
        stepsSig = sig.toString();

        stepsBox.removeAllViews();
        int total = planSteps.length;
        if (total > 0) {
            for (int i = 0; i < total; i++) {
                int idx = i + 1;
                String text = planSteps[i] == null ? "" : planSteps[i].trim();
                boolean done = idx < curStep;
                boolean now = idx == curStep;
                if (now && step != null && !step.isEmpty()) text = step;
                String icon = done ? "✓" : (now ? "▸" : "○");
                int color = done ? 0xFF6F7783 : (now ? 0xFFEAF0FA : 0xFF8A93A0);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView ic = label(icon, Math.max(9, cardTextSp - 1),
                        now ? 0xFF7FB2FF : color, false);
                ic.setSingleLine(true);
                ic.setPadding(0, dp(3), dp(6), dp(3));
                row.addView(ic, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                TextView tv = label(text, cardTextSp, color, now);
                tv.setMaxLines(3);
                tv.setPadding(0, dp(3), 0, dp(3));
                row.addView(tv, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                if (now) {
                    GradientDrawable hl = new GradientDrawable();
                    hl.setColor(0x1A7FB2FF);
                    hl.setCornerRadius(dp(8));
                    row.setBackground(hl);
                    row.setPadding(dp(4), 0, dp(4), 0);
                }
                stepsBox.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final int target = Math.max(0, Math.min(curStep - 1, total - 1));
            if (cardScroll != null) {
                cardScroll.post(new Runnable() {
                    @Override public void run() {
                        if (stepsBox != null && target < stepsBox.getChildCount()) {
                            View v = stepsBox.getChildAt(target);
                            cardScroll.scrollTo(0, Math.max(0, v.getTop() - dp(20)));
                        }
                    }
                });
            }
        } else {
            if (step != null && !step.isEmpty()) {
                TextView tv = label(step, cardTextSp, 0xFFD2D6DE, false);
                tv.setPadding(0, dp(2), 0, dp(2));
                stepsBox.addView(tv);
            }
            if (recent != null && !recent.isEmpty()) {
                String[] items = recent.split("\\|");
                int from = Math.max(0, items.length - cardLines);
                for (int i = from; i < items.length; i++) {
                    if (items[i].trim().isEmpty()) continue;
                    TextView tv = label("· " + items[i].trim(),
                            Math.max(9, cardTextSp - 1), 0xFFA3ABB8, false);
                    tv.setPadding(0, dp(1), 0, dp(1));
                    stepsBox.addView(tv);
                }
            }
            if (stepsBox.getChildCount() == 0) {
                TextView tv = label("暂无进行中的操作",
                        Math.max(9, cardTextSp - 1), 0xFF8A93A0, false);
                stepsBox.addView(tv);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /** 旋转/分屏/分辨率变化后重读屏幕尺寸，不然球可能被 clamp 在旧屏幕范围外。 */
    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui.post(new Runnable() {
            @Override public void run() {
                density = getResources().getDisplayMetrics().density;
                screenW = getResources().getDisplayMetrics().widthPixels;
                screenH = getResources().getDisplayMetrics().heightPixels;
                readSettings();
                resizeBall();
                if (cardOpen) {
                    positionCard();
                    realignCard();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        try { stopForeground(true); } catch (Exception ignored) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(1);
        } catch (Exception ignored) {}
        if (pref != null) pref.unregisterOnSharedPreferenceChangeListener(this);
        if (server != null) server.shutdown();
        setHalo(false);
        try { if (card != null) wm.removeView(card); } catch (Exception ignored) {}
        try { if (ball != null) wm.removeView(ball); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 视图 ----------------

    private class BallView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final Rect srcRect = new Rect();
        private final RectF dstRect = new RectF();

        BallView(Context c) {
            super(c);
            paint.setFilterBitmap(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = winSize / 2f;
            float cy = winSize / 2f;
            float r = ballPx / 2f;

            if (haloAllowed && haloAnim != null && haloAnim.isRunning()) {
                float t = haloPhase;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                paint.setColor(haloColor);
                paint.setAlpha((int) (70 + 165 * t));
                canvas.drawCircle(cx, cy, r + dp(3), paint);

                paint.setStrokeWidth(dp(9));
                paint.setAlpha((int) (20 + 55 * t));
                canvas.drawCircle(cx, cy, r + dp(4) + dp(4) * t, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            if (avatar != null) {
                int w = avatar.getWidth();
                int h = avatar.getHeight();
                int s = Math.min(w, h);
                srcRect.set((w - s) / 2, (h - s) / 2, (w - s) / 2 + s, (h - s) / 2 + s);
                dstRect.set(cx - r, cy - r, cx + r, cy + r);
                clip.reset();
                clip.addCircle(cx, cy, r, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(255);
                canvas.drawBitmap(avatar, srcRect, dstRect, paint);
                canvas.restore();
            }

            if (strokeDp > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(strokeDp));
                paint.setColor(strokeColor);
                paint.setAlpha(230);
                canvas.drawCircle(cx, cy, r - dp(strokeDp) / 2f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
