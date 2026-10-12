package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Trace;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.channels.FileChannel;
import java.util.function.Supplier;

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
                    .digest(("static-v3:" + identity + ":" + edge).getBytes(StandardCharsets.UTF_8));
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
        return decoded(identity, edge, decodeStream(source, edge));
    }

    /** Decode a static first frame directly from a file/URI, with a stream fallback. */
    public static Result load(Context context, String identity, int edge, Uri uri, OpenStream source)
            throws IOException {
        return load(context, identity, edge, uri, source, () -> null);
    }

    public static Result load(Context context, String identity, int edge, Uri uri, OpenStream source,
                              Supplier<Bitmap> existingPoster) throws IOException {
        Result cached = cached(context, identity, edge);
        if (cached != null) return cached;
        Trace.beginSection("ReaderPreview.reuse");
        Bitmap poster;
        try { poster = existingPoster.get(); } finally { Trace.endSection(); }
        if (poster != null) return decoded(identity, edge, poster);
        Exception primaryFailure = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Trace.beginSection("ReaderPreview.decodeBitmap");
            try {
                ImageDecoder.OnHeaderDecodedListener header = (decoder, info, input) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    // Let the codec choose efficiently rounded dimensions instead of an arbitrary resize.
                    decoder.setTargetSampleSize(sampleSize(width, height, edge));
                };
                Bitmap bitmap = null;
                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    // Mapping avoids repeated codec reads on regular files. Other URI types keep
                    // the framework source, including provider streams and asset offsets.
                    try (FileInputStream input = new FileInputStream(new File(uri.getPath()))) {
                        long length = input.getChannel().size();
                        if (length > 0 && length <= Integer.MAX_VALUE)
                            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input.getChannel()
                                    .map(FileChannel.MapMode.READ_ONLY, 0, length)), header);
                    } catch (IOException | RuntimeException ignored) {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                        // A mapping failure must not remove support for otherwise readable files.
                    }
                }
                if (bitmap == null) bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.getContentResolver(), uri), header);
                return decoded(identity, edge, bitmap);
            } catch (IOException | RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                primaryFailure = failure;
            } finally { Trace.endSection(); }
        }
        try {
            return decoded(identity, edge, decodeStream(source, edge));
        } catch (IOException | RuntimeException failure) {
            if (primaryFailure != null) failure.addSuppressed(primaryFailure);
            throw failure;
        }
    }

    private static Result decoded(String identity, int edge, Bitmap bitmap) {
        return new Result(bitmap, key(identity, edge), true);
    }

    private static Bitmap decodeStream(OpenStream source, int edge) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inScaled = false;
        // Independent opens avoid assumptions about decoder consumption and rewind support.
        try (InputStream stream = source.open()) { BitmapFactory.decodeStream(stream, null, options); }
        int width = options.outWidth;
        int height = options.outHeight;
        String mime = options.outMimeType;
        if (width <= 0 || height <= 0) throw new IOException("Unable to read preview image dimensions");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        options.inSampleSize = sampleSize(width, height, edge);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream stream = source.open()) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
        if (bitmap == null) {
            throw new IOException("Unable to decode preview: mime=" + mime + ", size=" + width + "x" + height
                    + ", sample=" + options.inSampleSize);
        }
        return bitmap;
    }

    static int sampleSize(int width, int height, int edge) {
        int sample = 1;
        while (sample < (1 << 30) && (long) Math.max(width, height) > (long) sample * Math.max(1, edge)) sample *= 2;
        return sample;
    }

    public static final class Result {
        public final Bitmap bitmap;
        private final String key;
        private final boolean needsSave;
        private Result(Bitmap bitmap, String key, boolean needsSave) {
            this.bitmap = bitmap; this.key = key; this.needsSave = needsSave;
        }

        /** Run on the independent cache writer, so encoding does not delay other cards. */
        public void persist(Context context) {
            if (!needsSave || Thread.currentThread().isInterrupted()) return;
            OutputStreamPipe pipe = cache(context).getOutputStreamPipe(key);
            if (pipe == null) return;
            try {
                pipe.obtain();
                try (OutputStream stream = pipe.open()) {
                    bitmap.compress(bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                            85, stream);
                }
            } catch (IOException | RuntimeException ignored) {
                // A failed cache write must not prevent displaying an already decoded poster.
            } finally { pipe.close(); pipe.release(); }
        }
    }
}
