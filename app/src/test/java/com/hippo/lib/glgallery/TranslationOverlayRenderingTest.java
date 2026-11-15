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
    @Test public void replacingResultBasePreservesZoomAndPanAcrossResolutionChanges() {
        List<String> draws = new ArrayList<>();
        GalleryPageView page = new GalleryPageView(null, 0, 0, 0, 0, 0);
        ImageView view = page.getImageView();
        view.layout(0, 0, 800, 1000);
        page.setImage(texture(400, 600, "ordinary", draws));
        view.setScaleOffset(ImageView.SCALE_FIT, ImageView.START_POSITION_CENTER, 1f);
        view.scale(310, 420, 2f);
        view.scroll(37, 61, new int[2]);
        RectF expected = new RectF(ReflectionHelpers.<RectF>getField(view, "mDst"));
        float scale = view.getScale();
        try {
            page.setResultImage(texture(800, 1200, "downloaded original", draws));
            view.layout(0, 0, 800, 1000);
            assertViewport(expected, view);
            assertEquals(scale / 2, view.getScale(), .001f);
            page.setResultImage(texture(400, 600, "ordinary enhancement base", draws));
            view.layout(0, 0, 800, 1000);
            assertViewport(expected, view);
            assertEquals(scale, view.getScale(), .001f);
        } finally {
            page.setImage(null);
        }
    }

    private void assertViewport(RectF expected, ImageView view) {
        RectF actual = ReflectionHelpers.getField(view, "mDst");
        assertEquals(expected.left, actual.left, .001f);
        assertEquals(expected.top, actual.top, .001f);
        assertEquals(expected.right, actual.right, .001f);
        assertEquals(expected.bottom, actual.bottom, .001f);
        assertFalse(ReflectionHelpers.<Boolean>getField(view, "mScaleOffsetDirty"));
    }

    @Test public void superResolutionAndTranslationUseOriginalGeometryAndIndependentComparison() {
        List<String> draws = new ArrayList<>();
        RecordingTexture original = texture(400, 600, "original", draws);
        RecordingTexture enhanced = texture(800, 1200, "enhanced", draws);
        RecordingTexture overlay = texture(200, 300, "overlay", draws);
        ImageView view = new ImageView();
        view.setImageTexture(original);
        RectF source = ReflectionHelpers.getField(view, "mSrcActual");
        RectF destination = ReflectionHelpers.getField(view, "mDstActual");
        source.set(40, 60, 360, 540);
        destination.set(0, 20, 640, 980);
        ReflectionHelpers.setField(view, "mScaleOffsetDirty", false);
        ReflectionHelpers.setField(view, "mPositionInRootDirty", false);
        try {
            view.setEnhancedTexture(enhanced);
            view.setOverlayTexture(overlay);
            view.onRender(null);
            assertEquals(Arrays.asList("enhanced", "overlay"), draws);
            assertEquals(new RectF(80, 120, 720, 1080), enhanced.source);
            assertEquals(new RectF(20, 30, 180, 270), overlay.source);
            assertEquals(enhanced.destination, overlay.destination);
            assertSame(original, view.getImageTexture());
            assertFalse(ReflectionHelpers.<Boolean>getField(view, "mScaleOffsetDirty"));
            draws.clear();
            view.setEnhancedTexture(null);
            view.onRender(null);
            assertEquals(Arrays.asList("original", "overlay"), draws);
            assertEquals(new RectF(40, 60, 360, 540), source);
            assertEquals(new RectF(0, 20, 640, 980), destination);
            draws.clear();
            view.setEnhancedTexture(enhanced);
            view.setOverlayTexture(null);
            view.onRender(null);
            assertEquals(Arrays.asList("enhanced"), draws);
        } finally {
            view.setEnhancedTexture(null);
            view.setOverlayTexture(null);
            view.setImageTexture(null);
            original.recycle(); enhanced.recycle(); overlay.recycle();
        }
    }

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
