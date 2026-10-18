package com.dsha.floatball;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.Closeable;
import java.io.InputStream;

/**
 * 头像解码：统一放后台线程做（解码大图不该卡主线程），
 * 流必须自己关（BitmapFactory 不会替你关）。
 * 球（720px 上限）和设置页预览（512px 上限）共用这一份逻辑。
 */
public final class AvatarUtil {

    public interface Cb { void onBitmap(Bitmap bmp); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AvatarUtil() {}

    public static void loadAsync(final Context c, final int maxPx, final Cb cb) {
        final Context ac = c.getApplicationContext();
        final Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = decode(ac, maxPx);
                MAIN.post(new Runnable() {
                    @Override public void run() { cb.onBitmap(bmp); }
                });
            }
        }, "avatar-decode");
        t.setDaemon(true);
        t.start();
    }

    /** 同步解码（请在后台线程调用）；自定义头像失败回退到内置 avatar.jpg，再失败返回 null。 */
    public static Bitmap decode(Context c, int maxPx) {
        Bitmap bmp = null;
        String uri = BallSettings.avatarUri(c);
        if (uri != null && !uri.isEmpty()) {
            try {
                ContentResolver cr = c.getContentResolver();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                InputStream in = cr.openInputStream(Uri.parse(uri));
                try { BitmapFactory.decodeStream(in, null, o); } finally { close(in); }
                int sample = 1;
                int max = Math.max(o.outWidth, o.outHeight);
                while (max / sample > maxPx) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                InputStream in2 = cr.openInputStream(Uri.parse(uri));
                try { bmp = BitmapFactory.decodeStream(in2, null, o2); } finally { close(in2); }
            } catch (Exception e) {
                bmp = null;
            }
        }
        if (bmp == null) {
            try {
                bmp = BitmapFactory.decodeStream(c.getAssets().open("avatar.jpg"));
            } catch (Exception ignored) {
            }
        }
        return bmp;
    }

    private static void close(Closeable s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }
}
