package com.hippo.ehviewer.ui.scene.download;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadBatchActions;
import com.hippo.ehviewer.ui.scene.download.part.DownloadChoiceListener;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.lib.image.Image;
import com.hippo.widget.FabLayout;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
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
        context.getSharedPreferences("mode-test", Context.MODE_PRIVATE).edit().clear().commit();
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
        // New downloads expand their labels. These tests start from explicit
        // collapsed sections so they exercise folding independently of that policy.
        for (DownloadLabel label : manager.getLabelList()) {
            Settings.setDownloadLabelExpanded(label.getId(), label.getLabel(), false);
        }
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
        recycler.setCustomCheckedListener(new DownloadChoiceListener(scene));
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
    public void headerClickTogglesLargeSectionAndKeepsChoiceAfterRefresh() {
        switchMode(true);
        int firstHeader = findHeader("first");
        int collapsedCount = scene.getDisplayItemCount();
        assertTrue(scene.isLabelHeaderCollapsed(firstHeader));

        scene.onLabelHeaderClick(firstHeader);
        layout();
        assertFalse(scene.isLabelHeaderCollapsed(firstHeader));
        assertEquals(collapsedCount + 120, scene.getDisplayItemCount());

        scene.updateForLabel();
        layout();
        assertFalse(scene.isLabelHeaderCollapsed(findHeader("first")));

        scene.onLabelHeaderClick(findHeader("first"));
        layout();
        assertTrue(scene.isLabelHeaderCollapsed(findHeader("first")));
        assertEquals(collapsedCount, scene.getDisplayItemCount());
    }

    @Test
    public void headerClickCanCollapseAndReopenSmallSection() {
        makeSmallSections();
        switchMode(true);
        int firstHeader = findHeader("first");
        int collapsedCount = scene.getDisplayItemCount();
        assertTrue(scene.isLabelHeaderCollapsed(firstHeader));

        scene.onLabelHeaderClick(firstHeader);
        assertFalse(scene.isLabelHeaderCollapsed(firstHeader));
        assertEquals(collapsedCount + 20, scene.getDisplayItemCount());

        scene.onLabelHeaderClick(firstHeader);
        assertTrue(scene.isLabelHeaderCollapsed(firstHeader));
        assertEquals(collapsedCount, scene.getDisplayItemCount());
    }

    @Test
    public void batchSelectAllSkipsHeadersAndCollapsedGalleries() {
        makeSmallSections();
        switchMode(true);
        scene.onLabelHeaderClick(findHeader("first"));
        recycler.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        recycler.intoCustomChoiceMode();

        batchActions().onClickSecondaryFab(null, null, 0);

        assertEquals(20, recycler.getCheckedItemCount());
        SparseBooleanArray checked = recycler.getCheckedItemPositions();
        for (int i = 0; i < checked.size(); i++) {
            if (!checked.valueAt(i)) continue;
            int position = checked.keyAt(i);
            assertFalse(scene.isLabelHeaderPosition(position));
            assertEquals("first", scene.getList().get(scene.positionInList(position)).label);
        }
    }

    @Test
    public void selectAllUsesMostRecentlyCheckedGalleryAcrossExpandedLabels() {
        makeSmallSections();
        switchMode(true);
        scene.onLabelHeaderClick(findHeader("first"));
        scene.onLabelHeaderClick(findHeader("second"));
        recycler.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        recycler.intoCustomChoiceMode();
        recycler.toggleItemChecked(scene.getAdapterPositionForGallery(121));
        recycler.toggleItemChecked(scene.getAdapterPositionForGallery(1));
        batchActions().onClickSecondaryFab(null, null, 0);
        assertEquals(21, recycler.getCheckedItemCount());
        assertTrue(recycler.getCheckedItemPositions().get(scene.getAdapterPositionForGallery(20)));
        assertFalse(recycler.getCheckedItemPositions().get(scene.getAdapterPositionForGallery(122)));
        recycler.toggleItemChecked(scene.getAdapterPositionForGallery(122));
        batchActions().onClickSecondaryFab(null, null, 0);
        assertEquals(40, recycler.getCheckedItemCount());
    }

    private DownloadLabel putLabelsInFolder() {
        DownloadManager manager = scene.getDownloadManager();
        manager.addLabelFolder("folder");
        DownloadLabel folder = manager.findDownloadLabel("folder");
        for (DownloadLabel label : manager.getDownloadLabels()) label.setParentId(folder.getId());
        manager.reorderLabels(new ArrayList<>(manager.getLabelList()));
        return folder;
    }

    @Test
    public void folderCollapseHidesChildrenAndKeepsTheirGalleriesForReopening() {
        makeSmallSections();
        DownloadLabel folder = putLabelsInFolder();
        switchMode(true);
        assertEquals("2(40)", scene.getDownloadManager().getFolderCount(folder));
        assertEquals(40, scene.getList().size());
        assertEquals(2, scene.getDisplayItemCount()); // Default label and folder.
        int header = findHeader("folder");
        assertTrue(scene.isFolderHeader(header));
        assertEquals("2(40)", scene.getLabelHeaderCountText(header));
        scene.onLabelHeaderClick(header);
        assertEquals(4, scene.getDisplayItemCount());
        scene.onLabelHeaderClick(findHeader("first"));
        assertEquals(24, scene.getDisplayItemCount());
        scene.onLabelHeaderClick(findHeader("folder"));
        assertEquals(2, scene.getDisplayItemCount());
        assertEquals(40, scene.getList().size());
        scene.onLabelHeaderClick(findHeader("folder"));
        assertEquals(24, scene.getDisplayItemCount());
    }

    @Test
    public void openingHiddenLabelExpandsItsFolderAndTheLabel() {
        makeSmallSections();
        putLabelsInFolder();
        switchMode(true);
        assertTrue(scene.expandAndScrollToDownloadLabel("second"));
        assertFalse(scene.isLabelHeaderCollapsed(findHeader("folder")));
        assertFalse(scene.isLabelHeaderCollapsed(findHeader("second")));
        assertTrue(scene.getAdapterPositionForGallery(121) >= 0);
    }

    @Test
    public void deletingFolderOnlyPromotesChildrenAndPreservesGalleriesAcrossReload() {
        DownloadLabel folder = putLabelsInFolder();
        DownloadManager manager = scene.getDownloadManager();
        manager.deleteLabel(folder.getLabel());
        assertEquals(2, manager.getDownloadLabels().size());
        assertNull(manager.findDownloadLabel("first").getParentId());
        assertTrue(manager.getDefaultDownloadInfoList().isEmpty());
        assertEquals(120, manager.getLabelDownloadInfoList("first").size());
        DownloadManager reloaded = new DownloadManager(scene.getContext());
        assertNull(reloaded.findDownloadLabel("first").getParentId());
        assertEquals(120, reloaded.getLabelDownloadInfoList("first").size());
    }

    @Test
    public void deletingFolderAndChildrenMovesGalleriesToDefault() {
        putLabelsInFolder();
        DownloadManager manager = scene.getDownloadManager();
        manager.deleteLabels(List.of("folder", "first", "second"));
        assertTrue(manager.getLabelList().isEmpty());
        assertEquals(240, manager.getDefaultDownloadInfoList().size());
        assertNull(manager.getDownloadInfo(121).label);
    }

    @Test
    public void backupImportRemapsFolderIdsWithoutChangingBackupObjects() {
        DownloadManager manager = scene.getDownloadManager();
        DownloadLabel folder = new DownloadLabel(987L, "import folder", 0, true, null);
        DownloadLabel child = new DownloadLabel(988L, "import child", 1, false, 987L);
        manager.addDownloadLabel(List.of(child, folder));
        assertEquals(Long.valueOf(987), folder.getId());
        assertEquals(Long.valueOf(987), child.getParentId());
        DownloadLabel importedFolder = manager.findDownloadLabel("import folder");
        assertNotEquals(Long.valueOf(987), importedFolder.getId());
        assertEquals(importedFolder.getId(), manager.findDownloadLabel("import child").getParentId());
        DownloadManager reloaded = new DownloadManager(scene.getContext());
        assertEquals(importedFolder.getId(), reloaded.findDownloadLabel("import child").getParentId());
        assertEquals(3, manager.getDownloadLabels().size());
    }

    @Test
    public void backupImportPreservesRootOrderAndHandlesFolderLabelNameConflicts() {
        DownloadManager manager = scene.getDownloadManager();
        List<DownloadLabel> backup = List.of(
                new DownloadLabel(900L, "root before", 0, false, null),
                new DownloadLabel(901L, "first", 1, true, null), // Existing download label.
                new DownloadLabel(902L, "new child", 2, false, 901L),
                new DownloadLabel(903L, "root after", 3, false, null));
        manager.addDownloadLabel(backup);
        assertEquals(List.of("first", "second", "root before", "first (2)", "new child", "root after"),
                manager.getLabelList().stream().map(DownloadLabel::getLabel).toList());
        assertTrue(manager.findDownloadLabel("first (2)").getFolder());
        assertFalse(manager.findDownloadLabel("first").getFolder());
        assertEquals(manager.findDownloadLabel("first (2)").getId(), manager.findDownloadLabel("new child").getParentId());
        var conflictingLabel = List.of(new DownloadLabel(904L, "first (2)", 4, false, null));
        assertEquals("first (2) (2)", manager.addDownloadLabel(conflictingLabel).get("first (2)"));
        assertFalse(manager.findDownloadLabel("first (2) (2)").getFolder());
        manager.addDownloadLabel(backup);
        manager.addDownloadLabel(conflictingLabel);
        assertEquals(7, manager.getLabelList().size());
    }

    @Test
    public void batchDeleteMapsGroupedPositionsAndIgnoresCheckedHeaders() {
        makeSmallSections();
        switchMode(true);
        scene.onLabelHeaderClick(findHeader("first"));
        recycler.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        recycler.intoCustomChoiceMode();
        recycler.checkAll(); // Include headers to exercise defensive position mapping.
        Settings.putRemoveImageFiles(false);

        batchActions().onClickSecondaryFab(null, null, 3);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        dialog.findViewById(R.id.download_remove_record).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        DownloadManager manager = scene.getDownloadManager();
        assertFalse(manager.containDownloadInfo(1));
        assertFalse(manager.containDownloadInfo(20));
        assertTrue(manager.containDownloadInfo(121));
        assertTrue(manager.containDownloadInfo(140));
        assertFalse(recycler.isInCustomChoice());
    }

    @Test
    public void translationDeleteButtonUsesSelectedGalleriesWithoutRemovingDownloads() {
        makeSmallSections();
        switchMode(true);
        scene.onLabelHeaderClick(findHeader("first"));
        recycler.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        recycler.intoCustomChoiceMode();
        recycler.checkAll();

        batchActions().onClickSecondaryFab(null, null, 4);

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        TextView message = dialog.findViewById(R.id.gallery_results_summary);
        assertEquals(scene.getContext().getString(R.string.gallery_results_calculating),
                message.getText().toString());
        assertEquals(scene.getContext().getString(R.string.translation_delete_results),
                dialog.findViewById(R.id.delete_translation_results).getContentDescription().toString());
        assertEquals(scene.getContext().getString(R.string.gallery_results_delete_sr),
                dialog.findViewById(R.id.delete_super_resolution_results).getContentDescription().toString());
        assertFalse(dialog.findViewById(R.id.delete_translation_results).isEnabled());
        assertFalse(dialog.findViewById(R.id.delete_super_resolution_results).isEnabled());
        assertFalse(dialog.findViewById(R.id.delete_original_results).isEnabled());
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(scene.getDownloadManager().containDownloadInfo(1));
        assertTrue(scene.getDownloadManager().containDownloadInfo(20));
    }

    @Test
    public void quickOrganizeButtonUsesItsShiftedPositionAfterTranslationDeleteWasAdded() {
        makeSmallSections();
        switchMode(true);
        scene.onLabelHeaderClick(findHeader("first"));
        recycler.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        recycler.intoCustomChoiceMode();
        recycler.checkAll();

        batchActions().onClickSecondaryFab(null, null, 8);

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(scene.getContext().getString(
                R.string.quick_organize_confirm, 20), message.getText().toString());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    }

    private DownloadBatchActions batchActions() {
        Context context = scene.getContext();
        return new DownloadBatchActions(new DownloadBatchActions.Host() {
            @Override public Context getEHContext() { return context; }
            @Override public MainActivity getActivity2() { return new MainActivity(); }
            @Override public MyEasyRecyclerView getRecyclerView() { return recycler; }
            @Override public List<DownloadInfo> getList() { return scene.getList(); }
            @Override public DownloadManager getDownloadManager() { return scene.getDownloadManager(); }
            @Override public int positionInList(int position) { return scene.positionInList(position); }
            @Override public boolean isContinuousLabelBrowse() { return scene.isContinuousLabelBrowse(); }
            @Override public void selectAllInLastCheckedLabel() { scene.selectAllInLastCheckedLabel(); }
            @Override public boolean isLabelHeaderPosition(int position) { return scene.isLabelHeaderPosition(position); }
            @Override public void quickOrganizeDownloads(Context ctx, List<DownloadInfo> infos) {
                throw new AssertionError("Unexpected quick organize");
            }
            @Override public FabLayout getFabLayout() { return null; }
            @Override public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {}
            @Override public void launchGalleryActivity(Intent intent) {
                throw new AssertionError("Unexpected gallery launch");
            }
            @Override public Resources getResources() { return context.getResources(); }
            @Override public String getString(int id) { return context.getString(id); }
            @Override public String getString(int id, Object... args) { return context.getString(id, args); }
        });
    }

    @Test
    public void expandedStateSurvivesNewSceneAndLabelRename() {
        switchMode(true);
        scene.onLabelHeaderClick(findHeader("first"));
        assertFalse(scene.isLabelHeaderCollapsed(findHeader("first")));

        TestScene reopened = new TestScene(scene.getContext());
        ReflectionHelpers.setField(reopened, "mDownloadManager", scene.getDownloadManager());
        ReflectionHelpers.setField(reopened, "mContinuousLabelBrowse", true);
        reopened.updateForLabel();
        assertFalse(reopened.isLabelHeaderCollapsed(findHeader(reopened, "first")));
        assertTrue(reopened.isLabelHeaderCollapsed(findHeader(reopened, "second")));

        scene.getDownloadManager().renameLabel("first", "renamed");
        reopened.updateForLabel();
        assertFalse(reopened.isLabelHeaderCollapsed(findHeader(reopened, "renamed")));
    }

    @Test
    public void deletingLabelClearsItsSavedExpandedState() {
        switchMode(true);
        DownloadLabel first = scene.getDownloadManager().getLabelList().stream()
                .filter(label -> "first".equals(label.getLabel()))
                .findFirst().orElseThrow(AssertionError::new);
        scene.onLabelHeaderClick(findHeader("first"));
        assertTrue(Settings.isDownloadLabelExpanded(first.getId(), "first"));

        scene.getDownloadManager().deleteLabel("first");
        assertFalse(Settings.isDownloadLabelExpanded(first.getId(), "first"));
    }

    @Test
    public void longPressShowsActionsOnlyOnTheSelectedHeader() {
        switchMode(true);
        DownloadAdapter realAdapter = new DownloadAdapter(scene, scene);
        realAdapter.setHasStableIds(true);
        ReflectionHelpers.setField(scene, "mOriginalAdapter", realAdapter);
        ReflectionHelpers.setField(scene, "mAdapter", realAdapter);
        recycler.setAdapter(realAdapter);
        layout();

        View first = headerView("first");
        View second = headerView("second");
        assertTrue(first.performLongClick());
        assertEquals(View.VISIBLE,
                first.findViewById(R.id.label_actions).getVisibility());
        assertTrue(first.findViewById(R.id.action_rename) instanceof android.widget.ImageButton);

        assertTrue(second.performLongClick());
        assertEquals(View.VISIBLE,
                first.findViewById(R.id.label_actions).getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals(View.GONE,
                headerView("first").findViewById(R.id.label_actions).getVisibility());
        assertEquals(View.VISIBLE,
                headerView("second").findViewById(R.id.label_actions).getVisibility());

        realAdapter.dismissLabelActions();
        assertEquals(View.VISIBLE,
                second.findViewById(R.id.label_actions).getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals(View.GONE,
                headerView("second").findViewById(R.id.label_actions).getVisibility());
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
        Settings.setDownloadLabelExpanded(null, null, true);
        switchMode(true);
        centerGallery(7); // A gallery sufficiently far into the default section.
        switchMode(false);
        assertNull(scene.mLabel);
        assertEquals(20, scene.getList().size());
        assertEquals(1000, scene.getList().get(0).gid);
    }

    private void makeExpandedSections() {
        makeSmallSections();
        for (DownloadLabel label : scene.getDownloadManager().getLabelList()) {
            Settings.setDownloadLabelExpanded(label.getId(), label.getLabel(), true);
        }
    }

    @Test
    @Config(shadows = ButtonAppShadow.class)
    public void waitingDownloadButtonLongPressRequestsPriorityInOrdinaryList() {
        assertPriorityButton(false);
    }

    @Test
    @Config(shadows = ButtonAppShadow.class)
    public void waitingDownloadButtonLongPressResolvesGalleryInGroupedList() {
        assertPriorityButton(true);
    }

    @Test
    @Config(shadows = ButtonAppShadow.class)
    public void downloadButtonLongPressDoesNothingForNonWaitingGalleryOrDuringSelection() {
        installButtonAdapter();
        DownloadAdapter.DownloadHolder holder = (DownloadAdapter.DownloadHolder)
                recycler.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        DownloadInfo info = scene.getList().get(scene.positionInList(0));
        info.state = DownloadInfo.STATE_DOWNLOAD;
        assertTrue(holder.stop.performLongClick());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        info.state = DownloadInfo.STATE_WAIT;
        recycler.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        recycler.intoCustomChoiceMode();
        assertTrue(recycler.isInCustomChoice());
        assertTrue(holder.stop.performLongClick());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    private void assertPriorityButton(boolean grouped) {
        if (grouped) {
            makeExpandedSections();
            switchMode(true);
        }
        installButtonAdapter();
        int position = grouped ? findHeader("first") + 1 : 0;
        DownloadInfo info = scene.getList().get(scene.positionInList(position));
        info.state = DownloadInfo.STATE_WAIT;
        DownloadAdapter.DownloadHolder holder = (DownloadAdapter.DownloadHolder)
                recycler.findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        assertTrue(holder.stop.performLongClick());
        Intent request = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(request);
        assertEquals(DownloadService.ACTION_PRIORITIZE, request.getAction());
        assertEquals(info.gid, request.getLongExtra(
                DownloadService.KEY_GID, -1));
        // A long press must not execute the button's ordinary pause action.
        assertEquals(DownloadInfo.STATE_WAIT, info.state);
    }

    private void installButtonAdapter() {
        DownloadAdapter adapter = new DownloadAdapter(scene, scene) {
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                // Exercise actual button listeners and position mapping without image/network work.
                if (holder instanceof DownloadHolder) {
                    ReflectionHelpers.setField(holder, "boundGid",
                            scene.getList().get(scene.positionInList(position)).gid);
                } else {
                    super.onBindViewHolder(holder, position);
                }
            }
        };
        adapter.setHasStableIds(true);
        recycler.setAdapter(adapter);
        ReflectionHelpers.setField(scene, "mOriginalAdapter", adapter);
        ReflectionHelpers.setField(scene, "mAdapter", adapter);
        layout();
    }

    @Implements(EhApplication.class)
    public static class ButtonAppShadow {
        @Implementation
        protected static Conaco<Image> getConaco(Context context) {
            return null;
        }
    }

    private void makeSmallSections() {
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

    private int findHeader(String title) {
        return findHeader(scene, title);
    }

    private int findHeader(TestScene target, String title) {
        for (int position = 0; position < target.getDisplayItemCount(); position++) {
            if (target.isLabelHeaderPosition(position)
                    && title.equals(target.getLabelHeaderTitle(position))) {
                return position;
            }
        }
        throw new AssertionError("Missing download label: " + title);
    }

    private View headerView(String title) {
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(
                findHeader(title));
        assertNotNull(holder);
        return holder.itemView.findViewById(R.id.header_content);
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

    private void organize(java.util.Map<String, List<DownloadInfo>> assignments) {
        ReflectionHelpers.callInstanceMethod(scene, "applyQuickOrganization",
                ReflectionHelpers.ClassParameter.from(Context.class, scene.getContext()),
                ReflectionHelpers.ClassParameter.from(java.util.Map.class, assignments),
                ReflectionHelpers.ClassParameter.from(int.class, 0));
    }

    @Test public void quickOrganizationGroupsOnlyNewLabelsInTimestampedFolder() {
        DownloadManager manager = scene.getDownloadManager();
        var assignments = new java.util.LinkedHashMap<String, List<DownloadInfo>>();
        assignments.put("A: new artist", List.of(manager.getDownloadInfo(1)));
        assignments.put("G: new group", List.of(manager.getDownloadInfo(2)));
        assignments.put("first", List.of(manager.getDownloadInfo(121)));
        organize(assignments);
        DownloadLabel folder = manager.getLabelList().stream().filter(DownloadLabel::getFolder).findFirst().orElseThrow();
        assertTrue(folder.getLabel().matches("Sort_\\d{8}_\\d{6}(?:_\\d+)?"));
        assertEquals(folder.getId(), manager.findDownloadLabel("A: new artist").getParentId());
        assertEquals(folder.getId(), manager.findDownloadLabel("G: new group").getParentId());
        assertNull(manager.findDownloadLabel("first").getParentId());
        assertEquals("2(2)", manager.getFolderCount(folder));
        assertTrue(Settings.isDownloadLabelExpanded(folder.getId(), folder.getLabel()));
        DownloadManager reloaded = new DownloadManager(scene.getContext());
        assertEquals(folder.getId(), reloaded.findDownloadLabel("A: new artist").getParentId());
    }

    @Test public void quickOrganizationWithOnlyExistingLabelsDoesNotCreateEmptyFolder() {
        DownloadManager manager = scene.getDownloadManager();
        organize(java.util.Map.of("second", List.of(manager.getDownloadInfo(1))));
        assertTrue(manager.getLabelList().stream().noneMatch(DownloadLabel::getFolder));
        assertEquals("second", manager.getDownloadInfo(1).label);
    }

    @Test public void quickOrganizationDoesNotUseAFolderAsAGalleryDestination() {
        DownloadManager manager = scene.getDownloadManager();
        manager.addLabelFolder("A: collision");
        organize(java.util.Map.of("A: collision", List.of(manager.getDownloadInfo(1))));
        assertEquals("A: collision (2)", manager.getDownloadInfo(1).label);
        assertFalse(manager.findDownloadLabel("A: collision (2)").getFolder());
        assertNotNull(manager.findDownloadLabel("A: collision (2)").getParentId());
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
