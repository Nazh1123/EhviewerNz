package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Opt-in host comparison; these timings are not Android device or reader UI latency. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderPreviewDecodeBenchmarkTest {
    @Test public void compareDecodeAndResizePaths() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("readerPreviewBenchmark"));
        Bitmap page = Bitmap.createBitmap(2400, 3600, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(page);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(80);
        for (int y = 100; y < 3600; y += 120) {
            canvas.drawText("Preview decode benchmark 0123456789", 60, y, paint);
            canvas.drawRect(1300, y, 2250, y + 20, paint);
        }
        for (Bitmap.CompressFormat format : new Bitmap.CompressFormat[] {
                Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP}) {
            File file = File.createTempFile("preview-benchmark-", ".image", RuntimeEnvironment.getApplication().getCacheDir());
            try {
                try (FileOutputStream out = new FileOutputStream(file)) { page.compress(format, 90, out); }
                for (String path : new String[] {"bounds", "factory-sample", "decoder-exact", "decoder-sample", "decoder-original"}) {
                    long[] nanos = new long[7];
                    for (int pass = 0; pass < 9; pass++) {
                        long start = System.nanoTime();
                        Bitmap decoded = null;
                        if (path.equals("bounds") || path.equals("factory-sample")) {
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inJustDecodeBounds = true;
                            try (FileInputStream in = new FileInputStream(file)) { BitmapFactory.decodeStream(in, null, options); }
                            if (!path.equals("bounds")) {
                                options.inJustDecodeBounds = false;
                                options.inSampleSize = 32;
                                try (FileInputStream in = new FileInputStream(file)) { decoded = BitmapFactory.decodeStream(in, null, options); }
                            }
                        } else {
                            decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                                if (path.equals("decoder-exact")) decoder.setTargetSize(128, 192);
                                else if (path.equals("decoder-sample")) decoder.setTargetSampleSize(32);
                            });
                        }
                        long elapsed = System.nanoTime() - start;
                        if (pass >= 2) nanos[pass - 2] = elapsed;
                        if (decoded != null) decoded.recycle();
                    }
                    Arrays.sort(nanos);
                    System.out.printf(java.util.Locale.US,
                            "PREVIEW_BENCH format=%s bytes=%d path=%s medianMs=%.3f p85Ms=%.3f runs=7%n",
                            format, file.length(), path, nanos[3] / 1e6, nanos[5] / 1e6);
                }
            } finally { file.delete(); }
        }
        page.recycle();
    }
}
