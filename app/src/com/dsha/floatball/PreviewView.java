package com.dsha.floatball;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.view.View;

/** 设置页里的实时预览：按当前参数画出悬浮球 + 状态卡片，呼吸动画跟着参数走。 */
public class PreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF cardRect = new RectF();
    private final RectF btnRect = new RectF();
    private ValueAnimator anim;
    private float phase = 0f;
    private Bitmap avatar;

    private final String[] SAMPLE_STEPS = {
            "看当前屏幕长什么样", "找到要点的那个会话", "点进去并输入内容", "确认发送", "回来汇报结果"
    };

    public PreviewView(Context c) {
        super(c);
        paint.setFilterBitmap(true);
        paint.setSubpixelText(true);
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private float sp() {
        return getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float v) {
        return v * density();
    }

    private int haloC() { return BallSettings.haloColor(getContext()); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loadAvatar();
        startAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    private void startAnim() {
        stopAnim();
        if (!BallSettings.haloOn(getContext())) {
            phase = 0f;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(BallSettings.breathMs(getContext()));
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                phase = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private void stopAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    /** 设置项变了：重读头像与动画参数。 */
    public void reload() {
        loadAvatar();
        startAnim();
        invalidate();
    }

    private void loadAvatar() {
        Context c = getContext();
        Bitmap bmp = null;
        String uri = BallSettings.avatarUri(c);
        if (uri != null && !uri.isEmpty()) {
            try {
                ContentResolver cr = c.getContentResolver();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(cr.openInputStream(Uri.parse(uri)), null, o);
                int sample = 1;
                int max = Math.max(o.outWidth, o.outHeight);
                while (max / sample > 512) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                bmp = BitmapFactory.decodeStream(cr.openInputStream(Uri.parse(uri)), null, o2);
            } catch (Exception ignored) {
                bmp = null;
            }
        }
        if (bmp == null) {
            try {
                bmp = BitmapFactory.decodeStream(c.getAssets().open("avatar.jpg"));
            } catch (Exception ignored) {
            }
        }
        avatar = bmp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float w = getWidth();
        float h = getHeight();

        int sizeDp = BallSettings.sizeDp(c);
        boolean haloOn = BallSettings.haloOn(c);
        int strokeDp = BallSettings.strokeDp(c);
        int strokeColor = BallSettings.strokeColor(c);
        int textSp = BallSettings.cardTextSp(c);
        int cardW = BallSettings.cardWidthDp(c);
        int cardH = BallSettings.cardHeightDp(c);
        int btnH = BallSettings.buttonHeightDp(c);
        int cardAlpha = BallSettings.cardFollowBall(c)
                ? BallSettings.alphaIdlePct(c)
                : BallSettings.cardOpacityPct(c);
        int bgColor = BallSettings.withAlpha(BallSettings.cardBgColor(c), cardAlpha);

        // 未缩放坐标系：球 + 间距 + 卡片
        float r = dp(sizeDp) / 2f;
        float cardWpx = dp(cardW);
        float cardHpx = dp(cardH);
        float contentW = dp(14) + r * 2 + dp(14) + cardWpx + dp(10);
        float contentH = Math.max(r * 2 + dp(16), cardHpx + dp(12));

        float scale = Math.min(1f, Math.min(w / contentW, h / contentH));
        if (scale <= 0f) scale = 1f;
        canvas.save();
        canvas.translate((w - contentW * scale) / 2f, (h - contentH * scale) / 2f);
        canvas.scale(scale, scale);

        float cx = dp(14) + r;
        float cy = contentH / 2f;

        // 光环
        if (haloOn) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(haloC());
            paint.setAlpha((int) (70 + 165 * phase));
            canvas.drawCircle(cx, cy, r + dp(3), paint);
            paint.setStrokeWidth(dp(9));
            paint.setAlpha((int) (20 + 55 * phase));
            canvas.drawCircle(cx, cy, r + dp(4) + dp(4) * phase, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // 头像
        if (avatar != null) {
            int aw = avatar.getWidth(), ah = avatar.getHeight();
            int s = Math.min(aw, ah);
            srcRect.set((aw - s) / 2, (ah - s) / 2, (aw - s) / 2 + s, (ah - s) / 2 + s);
            dstRect.set(cx - r, cy - r, cx + r, cy + r);
            clip.reset();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(avatar, srcRect, dstRect, paint);
            canvas.restore();
        }

        // 描边
        if (strokeDp > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(strokeDp));
            paint.setColor(strokeColor);
            paint.setAlpha(230);
            canvas.drawCircle(cx, cy, r - dp(strokeDp) / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // ---- 卡片：固定宽高，垂直中线与球心对齐 ----
        float cardX = cx + r + dp(14);
        cardRect.set(cardX, cy - cardHpx / 2f, cardX + cardWpx, cy + cardHpx / 2f);

        paint.setColor(bgColor);
        canvas.drawRoundRect(cardRect, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x33FFFFFF);
        canvas.drawRoundRect(cardRect, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.FILL);

        float pad = dp(12);
        float lineH = textSp * sp() * 1.35f;
        float tx = cardX + pad;
        float ty = cardRect.top + pad + textSp * sp();

        // 头部：状态 + 右上角进度
        paint.setTextSize(Math.max(9, textSp - 1) * sp());
        paint.setColor(0xFF8FB8FF);
        canvas.drawText("正在操作你的手机", tx, ty, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(0xFFFFD166);
        paint.setFakeBoldText(true);
        canvas.drawText("3/5", cardRect.right - pad, ty, paint);
        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);

        ty += lineH;
        paint.setTextSize((textSp + 1) * sp());
        paint.setColor(0xFFFFFFFF);
        paint.setFakeBoldText(true);
        canvas.drawText("正在操作 QQ", tx, ty, paint);
        paint.setFakeBoldText(false);

        // 中间可滚动区：步骤列表
        ty += dp(6);
        float listBottom = cardRect.bottom - pad - dp(btnH) - dp(8);
        float lineH2 = lineH * 0.95f;
        for (int i = 0; i < SAMPLE_STEPS.length; i++) {
            float y = ty + lineH2 * (i + 1);
            if (y > listBottom) break;
            boolean done = i < 2, now = i == 2;
            paint.setTextSize((now ? textSp : Math.max(9, textSp - 1)) * sp());
            paint.setColor(done ? 0xFF6F7783 : (now ? 0xFFEAF0FA : 0xFF8A93A0));
            String icon = done ? "✓ " : (now ? "▸ " : "○ ");
            paint.setFakeBoldText(now);
            canvas.drawText(icon + SAMPLE_STEPS[i], tx, y, paint);
            paint.setFakeBoldText(false);
            if (now) {
                paint.setColor(0x1A7FB2FF);
                RectF hl = new RectF(tx - dp(4), y - textSp * sp(), cardRect.right - pad, y + dp(4));
                canvas.drawRoundRect(hl, dp(8), dp(8), paint);
            }
        }

        // 底部固定按钮
        float btnTop = cardRect.bottom - pad - dp(btnH);
        float gap = dp(8);
        float half = (cardWpx - pad * 2 - gap) / 2f;
        btnRect.set(tx, btnTop, tx + half, btnTop + dp(btnH));
        paint.setColor(0xFF2A2E36);
        canvas.drawRoundRect(btnRect, dp(12), dp(12), paint);
        btnRect.set(tx + half + gap, btnTop, tx + half * 2 + gap, btnTop + dp(btnH));
        canvas.drawRoundRect(btnRect, dp(12), dp(12), paint);

        paint.setTextSize(textSp * sp());
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xFFFF9A90);
        paint.setFakeBoldText(true);
        float baseY = btnTop + dp(btnH) / 2f + textSp * sp() * 0.35f;
        canvas.drawText("停止操作", tx + half / 2f, baseY, paint);
        paint.setColor(0xFFCFD6E2);
        canvas.drawText("返回 DSHA", tx + half + gap + half / 2f, baseY, paint);
        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);

        canvas.restore();
    }
}
