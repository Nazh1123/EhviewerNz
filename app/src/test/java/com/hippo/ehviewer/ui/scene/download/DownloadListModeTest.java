package com.hippo.ehviewer.ui.scene.download;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DownloadListModeTest {
    private SQLiteDatabase database;
    private TestScene scene;
    private MyEasyRecyclerView recycler;
    private RecyclerViewDragDropManager dragManager;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("mode-test", Context.MODE_PRIVATE));
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        DownloadManager manager = new DownloadManager(context);
        List<DownloadInfo> downloads = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            DownloadInfo info = new DownloadInfo();
            info.gid = i + 1;
            info.label = i < 120 ? "first" : "second";
            downloads.add(info);
        }
        manager.addDownload(downloads);
        scene = new TestScene(context);
        ReflectionHelpers.setField(scene, "mDownloadManager", manager);
        recycler = new MyEasyRecyclerView(context);
        AutoStaggeredGridLayoutManager layout = new AutoStaggeredGridLayoutManager(
                200, StaggeredGridLayoutManager.VERTICAL);
        recycler.setLayoutManager(layout);
        DownloadAdapter originalAdapter = new CardsAdapter();
        dragManager = new RecyclerViewDragDropManager();
        RecyclerView.Adapter<?> adapter = dragManager.createWrappedAdapter(originalAdapter);
        recycler.setAdapter(adapter);
        dragManager.attachRecyclerView(recycler);
        ReflectionHelpers.setField(scene, "mDragDropManager", dragManager);
        ReflectionHelpers.setField(scene, "mOriginalAdapter", originalAdapter);
        ReflectionHelpers.setField(scene, "mRecyclerView", recycler);
        ReflectionHelpers.setField(scene, "mLayoutManager", layout);
        ReflectionHelpers.setField(scene, "mAdapter", adapter);
        selectLabel("first");
    }

    @After
    public void tearDown() {
        dragManager.release();
        recycler.setAdapter(null);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        database.close();
    }

    @Test
    public void returningToNormalRestoresTheLabelAndClearsGroupedScrollState() {
        switchMode(true);
        ReflectionHelpers.setField(scene, "mContinuousRestorePosition", 2);
        ReflectionHelpers.setField(scene, "mContinuousRestoreOffset", -80);
        // Search/filter operations can leave the shared recycler hidden.
        recycler.setVisibility(View.GONE);
        switchMode(false);
        assertEquals("first", scene.mLabel);
        assertEquals(RecyclerView.NO_POSITION,
                (int) ReflectionHelpers.getField(scene, "mContinuousRestorePosition"));
        assertVisibleGallery();
    }

    @Test
    public void selectingLabelsWorksAfterReturningToAnEmptyDefaultLabel() {
        switchMode(true);
        Settings.putRecentDownloadLabel(null);
        // No visible rows: fall back to the previous ordinary-mode selection.
        recycler.setVisibility(View.GONE);
        switchMode(false);
        assertTrue(scene.getList().isEmpty());
        selectLabel("second");
        assertVisibleGallery();
    }

    @Test
    public void returningToNormalSelectsCenteredCollapsedHeaderInsteadOfPreviousLabel() {
        switchMode(true);
        assertEquals("first", Settings.getRecentDownloadLabel());
        // Both 120-gallery sections are collapsed. The nearest row below the short
        // list's empty space is the second section header.
        switchMode(false);
        assertEquals("second", scene.mLabel);
        assertVisibleGallery();
    }

    @Test
    public void returningToNormalSelectsCenteredGalleryInExpandedSection() {
        makeExpandedSections();
        switchMode(true);
        centerGallery(24); // Gallery in the second section.
        switchMode(false);
        assertEquals("second", scene.mLabel);
        assertEquals(20, scene.getList().size());
    }

    @Test
    public void centeredDefaultSectionRemainsDefaultInsteadOfUsingPreviousLabel() {
        makeExpandedSections();
        List<DownloadInfo> defaults = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            DownloadInfo info = new DownloadInfo();
            info.gid = 1000 + i;
            defaults.add(info);
        }
        scene.getDownloadManager().addDownload(defaults);
        switchMode(true);
        centerGallery(7); // A gallery sufficiently far into the default section.
        switchMode(false);
        assertNull(scene.mLabel);
        assertEquals(20, scene.getList().size());
        assertEquals(1000, scene.getList().get(0).gid);
    }

    private void makeExpandedSections() {
        // Leave 20 galleries per section, below the collapse threshold.
        DownloadManager manager = scene.getDownloadManager();
        manager.getLabelDownloadInfoList("first").subList(20, 120).clear();
        manager.getLabelDownloadInfoList("second").subList(20, 120).clear();
    }

    private void centerGallery(int position) {
        ((StaggeredGridLayoutManager) recycler.getLayoutManager())
                .scrollToPositionWithOffset(position, 310);
        layout();
        View child = recycler.getLayoutManager().findViewByPosition(position);
        assertNotNull(child);
        assertTrue(child.getTop() <= 400 && child.getBottom() >= 400);
    }

    @Test
    public void galleriesRenderAfterReturningFromScrolledGroupedListAndChangingLabels() {
        for (int i = 0; i < 3; i++) {
            switchMode(true);
            recycler.scrollToPosition(scene.getDisplayItemCount() - 1);
            layout();
            switchMode(false);
            selectLabel("first");
            assertVisibleGallery();
            selectLabel("second");
            assertVisibleGallery();
        }
    }

    private void switchMode(boolean continuous) {
        ReflectionHelpers.callInstanceMethod(scene, "applyDownloadListMode",
                ReflectionHelpers.ClassParameter.from(boolean.class, continuous));
        layout();
    }

    private void selectLabel(String label) {
        scene.mLabel = label;
        scene.updateForLabel();
        scene.updateView();
        layout();
    }

    private void layout() {
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 800);
    }

    private void assertVisibleGallery() {
        assertEquals(120, recycler.getAdapter().getItemCount());
        assertEquals(View.VISIBLE, recycler.getVisibility());
        assertTrue("Nonempty labels must lay out gallery cards", recycler.getChildCount() > 0);
        boolean visible = false;
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            visible |= child.getBottom() > 0 && child.getTop() < recycler.getHeight();
        }
        assertTrue("Gallery cards must intersect the viewport", visible);
    }

    public static class TestScene extends DownloadsScene {
        private final Context context;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
    }

    // Keep real RecyclerView layout/recycling but avoid image loading and network services.
    private class CardsAdapter extends DownloadAdapter {
        CardsAdapter() { super(scene, scene); setHasStableIds(true); }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            TextView view = new TextView(parent.getContext());
            StaggeredGridLayoutManager.LayoutParams params = new StaggeredGridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, type == 1 ? 50 : 180);
            params.setFullSpan(type == 1);
            view.setLayoutParams(params);
            return new RecyclerView.ViewHolder(view) {};
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView).setText(Long.toString(getItemId(position)));
        }
        @Override public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            ((StaggeredGridLayoutManager.LayoutParams) holder.itemView.getLayoutParams())
                    .setFullSpan(holder.getItemViewType() == 1);
        }
    }
}
