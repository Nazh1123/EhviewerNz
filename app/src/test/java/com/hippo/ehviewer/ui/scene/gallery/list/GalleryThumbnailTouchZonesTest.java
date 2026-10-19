package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.ehviewer.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class GalleryThumbnailTouchZonesTest {
    private EasyRecyclerView recycler;
    private FrameLayout item;
    private View info;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        recycler = new EasyRecyclerView(context);
        recycler.setLayoutManager(new LinearLayoutManager(context));
        item = new FrameLayout(context);
        View cover = new View(context);
        cover.setId(R.id.thumb_new);
        info = new View(context);
        info.setId(R.id.thumbnail_info_bar);
        item.addView(cover);
        item.addView(info);
        recycler.addView(item);
        recycler.layout(0, 0, 600, 1000);
        item.layout(100, 200, 300, 550);
        cover.layout(0, 0, 200, 300);
        info.layout(0, 300, 200, 350);
    }

    @Test public void bottomCoverAndInfoBarUseTheSameLeftRightSplit() {
        assertZone(150, 490, true, false);
        assertZone(250, 490, false, true);
        assertZone(150, 520, true, false);
        assertZone(250, 520, false, true);
        // Midpoint belongs to download, with no overlap or gap.
        assertZone(200, 490, false, true);
        assertZone(200, 520, false, true);
    }

    @Test public void upperCoverOutsideEdgesAndHiddenInfoBarAreExcluded() {
        assertZone(150, 400, false, false);
        assertZone(99, 490, false, false);
        assertZone(300, 520, false, false);
        assertZone(150, 550, false, false);
        info.setVisibility(View.GONE);
        assertZone(150, 520, false, false);
        assertZone(250, 520, false, false);
        assertZone(150, 490, true, false);
    }

    private void assertZone(float x, float y, boolean preview, boolean download) {
        ReflectionHelpers.setField(recycler, "mStartX", x);
        ReflectionHelpers.setField(recycler, "mStartY", y);
        assertEquals(preview, zone(true));
        assertEquals(download, zone(false));
    }

    private boolean zone(boolean left) {
        return ReflectionHelpers.callStaticMethod(GalleryListScene.class, "isInThumbnailActionZone",
                ClassParameter.from(EasyRecyclerView.class, recycler),
                ClassParameter.from(View.class, item), ClassParameter.from(boolean.class, left));
    }
}
