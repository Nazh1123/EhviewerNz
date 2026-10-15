package com.hippo.ehviewer.translation.engine;

/** Standalone app_process probe of the production JNI, without installing/replacing the reader. */
public class ImageInference {
    native long open(String param, String bin, int threads, boolean half);
    native void close(long handle);
    native boolean upscale(long handle, float[] input, int width, int height, float[] output);

    private float[] run(long handle, float[] source, int w, int h, int x, int y, int tw, int th) {
        int iw = (tw + 1) / 2 * 2 + 14, ih = (th + 1) / 2 * 2 + 14;
        float[] input = new float[iw * ih * 3];
        for (int c = 0; c < 3; ++c) for (int py = 0; py < ih; ++py) for (int px = 0; px < iw; ++px) {
            int sx = Math.max(0, Math.min(w - 1, x + px - 7));
            int sy = Math.max(0, Math.min(h - 1, y + py - 7));
            input[c * iw * ih + py * iw + px] = source[c * w * h + sy * w + sx];
        }
        int ow = (iw - 14) * 2, oh = (ih - 14) * 2;
        float[] output = new float[ow * oh * 3];
        if (!upscale(handle, input, iw, ih, output)) throw new AssertionError("JNI inference failed");
        float[] cropped = new float[tw * th * 4 * 3];
        for (int c = 0; c < 3; ++c) for (int py = 0; py < th * 2; ++py)
            System.arraycopy(output, c * ow * oh + py * ow, cropped, c * tw * th * 4 + py * tw * 2, tw * 2);
        return cropped;
    }

    private void check(long handle, int w, int h) {
        float[] source = new float[w * h * 3];
        for (int c = 0; c < 3; ++c) for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
            source[c * w * h + y * w + x] = ((x * 7 + y * 13 + c * 53) % 256) / 255f;
        float[] full = run(handle, source, w, h, 0, 0, w, h);
        float maxError = 0;
        for (int y = 0; y < h; y += 128) for (int x = 0; x < w; x += 128) {
            int tw = Math.min(128, w - x), th = Math.min(128, h - y);
            float[] tile = run(handle, source, w, h, x, y, tw, th);
            for (int c = 0; c < 3; ++c) for (int py = 0; py < th * 2; ++py) for (int px = 0; px < tw * 2; ++px) {
                float actual = tile[c * tw * th * 4 + py * tw * 2 + px];
                float expected = full[c * w * h * 4 + (y * 2 + py) * w * 2 + x * 2 + px];
                if (!Float.isFinite(actual)) throw new AssertionError("Non-finite output");
                maxError = Math.max(maxError, Math.abs(actual - expected));
            }
        }
        if (maxError > 0.0001f) throw new AssertionError("Tile seam: " + maxError);
        float changed = 0;
        for (int c = 0; c < 3; ++c) for (int y = 0; y < h * 2; ++y) for (int x = 0; x < w * 2; ++x)
            changed = Math.max(changed, Math.abs(full[c * w * h * 4 + y * w * 2 + x]
                    - source[c * w * h + (y / 2) * w + x / 2]));
        if (w * h > 1 && changed < 0.01f) throw new AssertionError("Output is only nearest-neighbour interpolation");
        System.out.println("PASS " + w + "x" + h + " -> " + w * 2 + "x" + h * 2
                + " maxTileError=" + maxError + " neuralDifference=" + changed);
    }

    public static void main(String[] args) {
        System.load(args[0] + "/libehnz_image.so");
        ImageInference api = new ImageInference();
        long start = System.nanoTime();
        long handle = api.open(args[0] + "/scale2.0x_model.param", args[0] + "/scale2.0x_model.bin", 2, false);
        if (handle == 0) throw new AssertionError("Pinned model load failed");
        try {
            api.check(handle, 1, 1);
            api.check(handle, 31, 17);
            api.check(handle, 187, 135); // Both axes cross a seam, including odd final tiles.
            if (api.upscale(handle, new float[3], 16, 16, new float[12]))
                throw new AssertionError("Invalid input admitted");
            if (api.upscale(handle, new float[16 * 16 * 3], 16, 16, new float[1]))
                throw new AssertionError("Invalid output admitted");
            System.out.println("PASS invalid tensor lengths; elapsedMs=" + (System.nanoTime() - start) / 1000000);
        } finally { api.close(handle); }
    }
}
