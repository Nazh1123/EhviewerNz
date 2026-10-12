package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small static posters, with a bounded disk cache independent of reader originals. */
public final class ReaderPreviewBitmapCache {
    private static SimpleDiskCache cache;
    private ReaderPreviewBitmapCache() { }

    public interface OpenStream { InputStream open() throws IOException; }

    private static synchronized SimpleDiskCache cache(Context context) {
        if (cache == null) cache = new SimpleDiskCache(
                new File(context.getCacheDir(), "reader_previews"), 32 * 1024 * 1024);
        return cache;
    }

    private static String key(String identity, int edge) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((identity + ":" + edge).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte b : hash) {
                key.append(Character.forDigit((b & 255) >> 4, 16));
                key.append(Character.forDigit(b & 15, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static Result cached(Context context, String identity, int edge) throws IOException {
        String key = key(identity, edge);
        InputStreamPipe pipe = cache(context).getInputStreamPipe(key);
        if (pipe == null) return null;
        boolean invalid = true;
        try {
            pipe.obtain();
            Bitmap bitmap = BitmapFactory.decodeStream(pipe.open());
            invalid = bitmap == null;
            return bitmap != null ? new Result(bitmap, key, false) : null;
        } catch (IOException ignored) {
            return null;
        } finally {
            pipe.close(); pipe.release();
            if (invalid) cache(context).remove(key);
        }
    }

    public static Result load(Context context, String identity, int edge, OpenStream source)
            throws IOException {
        Result cached = cached(context, identity, edge);
        if (cached != null) return cached;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = source.open()) { BitmapFactory.decodeStream(stream, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight, edge);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream stream = source.open()) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
        return bitmap != null ? new Result(bitmap, key(identity, edge), true) : null;
    }

    static int sampleSize(int width, int height, int edge) {
        int sample = 1;
        while (Math.max(width, height) / sample > Math.max(1, edge)) sample *= 2;
        return sample;
    }

    public static final class Result {
        public final Bitmap bitmap;
        private final String key;
        private final boolean needsSave;
        private Result(Bitmap bitmap, String key, boolean needsSave) {
            this.bitmap = bitmap; this.key = key; this.needsSave = needsSave;
        }

        /** Run after posting the bitmap to the UI, so encoding does not delay first display. */
        public void persist(Context context) {
            if (!needsSave || Thread.currentThread().isInterrupted()) return;
            OutputStreamPipe pipe = cache(context).getOutputStreamPipe(key);
            if (pipe == null) return;
            try {
                pipe.obtain();
                try (OutputStream stream = pipe.open()) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
            } catch (IOException | RuntimeException ignored) {
                // A failed cache write must not prevent displaying an already decoded poster.
            } finally { pipe.close(); pipe.release(); }
        }
    }
}
