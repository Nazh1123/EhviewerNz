package com.hippo.lib.glgallery;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import com.hippo.lib.glview.image.ImageTexture;
import com.hippo.lib.glview.image.ImageWrapper;
import com.hippo.lib.image.Image;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class GalleryIncrementalUpdateTest {
    private Context context;
    private GalleryView view;
    private RecordingAdapter adapter;
    private GalleryView.LayoutManager layout;
    private final List<ImageTexture> textures = new ArrayList<>();

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        adapter = new RecordingAdapter();
        view = new GalleryView.Builder(context, adapter).build();
        view.bounds().set(0, 0, 1000, 1000);
    }

    @After public void cleanup() {
        if (layout != null) layout.onDetach();
        for (ImageTexture texture : textures) texture.recycle();
    }

    @Test public void appendingPagesKeepsPagerTextureZoomAndNeighborsInBothDirections() {
        for (int mode : new int[]{PagerLayoutManager.MODE_LEFT_TO_RIGHT, PagerLayoutManager.MODE_RIGHT_TO_LEFT}) {
            PagerLayoutManager pager = new PagerLayoutManager(context, view, ImageView.SCALE_FIT, ImageView.START_POSITION_TOP_RIGHT, 1f, 10);
            layout = pager;
            pager.setMode(mode);
            pager.onAttach(adapter);
            pager.setCurrentIndex(1);
            pager.onFill();
            GalleryPageView current = pager.findPageByIndex(1);
            ImageTexture texture = current.getImageTexture();
            ReflectionHelpers.setField(pager, "mScaleValue", 2.5f);
            int binds = adapter.binds;
            adapter.size = 5;
            pager.onDataChanged(new int[]{0, 1, 2});
            pager.onFill();
            assertSame(current, pager.findPageByIndex(1));
            assertSame(texture, current.getImageTexture());
            assertEquals(2.5f, (float) ReflectionHelpers.getField(pager, "mScaleValue"), 0f);
            assertEquals(binds, adapter.binds);
            pager.onDetach();
            layout = null;
            adapter.size = 3;
        }
    }

    @Test public void earlierDownloadsReindexPagerWithoutReloadingTheCurrentSource() {
        PagerLayoutManager pager = new PagerLayoutManager(context, view, ImageView.SCALE_FIT, ImageView.START_POSITION_TOP_RIGHT, 1f, 10);
        layout = pager;
        pager.onAttach(adapter);
        pager.setCurrentIndex(1);
        pager.onFill();
        GalleryPageView current = pager.findPageByIndex(1);
        ImageTexture texture = current.getImageTexture();
        adapter.size = 6;
        // A new image before every existing image; both old neighbors cease being adjacent.
        pager.onDataChanged(new int[]{1, 3, 5});
        assertEquals(3, pager.getCurrentIndex());
        assertSame(current, pager.findPageByIndex(3));
        pager.onFill();
        assertSame(texture, current.getImageTexture());
        assertNotNull(pager.findPageByIndex(2));
        assertNotNull(pager.findPageByIndex(4));
    }

    @Test public void appendingAtTheEndMakesTheNextPageAvailableWithoutRebindingCurrent() {
        PagerLayoutManager pager = new PagerLayoutManager(context, view, ImageView.SCALE_FIT, ImageView.START_POSITION_TOP_RIGHT, 1f, 10);
        layout = pager;
        pager.onAttach(adapter);
        pager.setCurrentIndex(2);
        pager.onFill();
        GalleryPageView current = pager.findPageByIndex(2);
        int binds = adapter.binds;
        adapter.size = 4;
        pager.onDataChanged(new int[]{0, 1, 2});
        pager.onFill();
        assertSame(current, pager.findPageByIndex(2));
        assertNotNull(pager.findPageByIndex(3));
        assertEquals(binds + 1, adapter.binds);
    }

    @Test public void earlierDownloadsKeepScrollAnchorTextureScaleAndOffset() {
        ScrollLayoutManager scroll = new ScrollLayoutManager(context, view, 10);
        layout = scroll;
        scroll.onAttach(adapter);
        scroll.setCurrentIndex(1);
        scroll.onFill();
        GalleryPageView current = scroll.findPageByIndex(1);
        ImageTexture texture = current.getImageTexture();
        int top = current.bounds().top;
        ReflectionHelpers.setField(scroll, "mScale", 1.5f);
        adapter.size = 6;
        scroll.onDataChanged(new int[]{1, 3, 5});
        assertSame(current, scroll.findPageByIndex(3));
        assertEquals(top, (int) ReflectionHelpers.getField(scroll, "mKeepTop"));
        assertEquals(1.5f, (float) ReflectionHelpers.getField(scroll, "mScale"), 0f);
        scroll.onFill();
        assertSame(current, scroll.findPageByIndex(3));
        assertSame(texture, current.getImageTexture());
        assertEquals(top, current.bounds().top);
    }

    private class RecordingAdapter extends GalleryView.Adapter {
        int size = 3;
        int binds;
        @Override public void onBind(GalleryPageView page, int index) {
            binds++;
            ImageWrapper wrapper = new ImageWrapper(Image.create(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)));
            assertTrue(wrapper.obtain());
            ImageTexture texture = new ImageTexture(wrapper);
            textures.add(texture);
            page.showImage();
            page.setImage(texture);
        }
        @Override public void onUnbind(GalleryPageView page, int index) { page.setImage(null); }
        @Override public String getError() { return null; }
        @Override public int size() { return size; }
    }
}
