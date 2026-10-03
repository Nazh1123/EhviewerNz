package com.hippo.lib.glgallery;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.RectF;
import com.hippo.lib.glview.glrenderer.GLCanvas;
import com.hippo.lib.glview.image.ImageTexture;
import com.hippo.lib.glview.image.ImageWrapper;
import com.hippo.lib.image.Image;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class TranslationOverlayRenderingTest {
    @Test public void overlaySharesDestinationAndScaledCropWhileHidingKeepsSourceAndViewport() {
        List<String> draws = new ArrayList<>();
        RecordingTexture original = texture(400, 600, "original", draws);
        RecordingTexture overlay = texture(200, 300, "overlay", draws);
        ImageView view = new ImageView();
        view.setImageTexture(original);
        view.setOverlayTexture(overlay);
        RectF source = ReflectionHelpers.getField(view, "mSrcActual");
        RectF destination = ReflectionHelpers.getField(view, "mDstActual");
        source.set(40, 60, 360, 540);
        destination.set(0, 20, 640, 980);
        ReflectionHelpers.setField(view, "mScaleOffsetDirty", false);
        ReflectionHelpers.setField(view, "mPositionInRootDirty", false);
        try {
            view.onRender(null);
            assertEquals(Arrays.asList("original", "overlay"), draws);
            assertEquals(new RectF(20, 30, 180, 270), overlay.source);
            assertEquals(original.destination, overlay.destination);
            draws.clear();
            view.setOverlayTexture(null);
            view.onRender(null);
            assertEquals(Arrays.asList("original"), draws);
            assertSame(original, view.getImageTexture());
            assertEquals(new RectF(40, 60, 360, 540), source);
            assertEquals(new RectF(0, 20, 640, 980), destination);
        } finally {
            view.setImageTexture(null);
            original.recycle();
            overlay.recycle();
        }
    }

    private RecordingTexture texture(int width, int height, String name, List<String> draws) {
        ImageWrapper image = new ImageWrapper(Image.create(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)));
        assertTrue(image.obtain());
        return new RecordingTexture(image, name, draws);
    }

    private static class RecordingTexture extends ImageTexture {
        final String name;
        final List<String> draws;
        RectF source;
        RectF destination;
        RecordingTexture(ImageWrapper image, String name, List<String> draws) {
            super(image);
            this.name = name;
            this.draws = draws;
        }
        @Override public void draw(GLCanvas canvas, RectF source, RectF destination) {
            draws.add(name);
            this.source = new RectF(source);
            this.destination = new RectF(destination);
        }
    }
}
