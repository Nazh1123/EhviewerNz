package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.UniFile;
import com.hippo.widget.LoadImageView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Loads small local thumbnails without changing downloaded files or using the network. */
public final class LocalImageLoader {
    private static SimpleDiskCache cache;
    private LocalImageLoader() {}

    public static void load(LoadImageView view, UniFile file) {
        view.resetClip();
        String uri = file.getUri().toString();
        String key = thumbnailKey(uri + ":" + file.lastModified() + ":" + file.length());
        Context context = view.getContext().getApplicationContext();
        view.load("local-preview:" + key, uri,
                new DataContainer() {
                    @Override public boolean isEnabled() { return true; }
                    @Override public void onUrlMoved(String requestUrl, String responseUrl) {}
                    @Override public boolean save(InputStream stream, long length,
                                                   String mediaType, ProgressNotifier notifier) {
                        return false;
                    }
                    @Override public InputStreamPipe get() {
                        try { return thumbnail(context, file, key); }
                        catch (IOException | RuntimeException failure) { return null; }
                    }
                    @Override public void remove() {}
                }, false);
    }

    private static String thumbnailKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(Character.forDigit((b & 255) >> 4, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Called by Conaco on its worker thread. Bound thumbnail memory even for very large originals. */
    private static synchronized InputStreamPipe thumbnail(Context context, UniFile file, String key) throws IOException {
        if (cache == null) cache = new SimpleDiskCache(new File(context.getCacheDir(), "local_previews"), 32 * 1024 * 1024);
        InputStreamPipe existing = cache.getInputStreamPipe(key);
        if (existing != null) return existing;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = file.openInputStream()) { BitmapFactory.decodeStream(stream, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream stream = file.openInputStream()) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
        if (bitmap == null) return null;
        OutputStreamPipe output = cache.getOutputStreamPipe(key);
        try {
            if (output == null) return null;
            output.obtain();
            try (OutputStream stream = output.open()) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return null;
            }
        } finally {
            bitmap.recycle();
            if (output != null) {
                output.close();
                output.release();
            }
        }
        return cache.getInputStreamPipe(key);
    }
}
