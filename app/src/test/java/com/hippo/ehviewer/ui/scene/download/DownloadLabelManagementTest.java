package com.hippo.ehviewer.ui.scene.download;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
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
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = DownloadLabelManagementTest.ShadowApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DownloadLabelManagementTest {
    private Context context;
    private SQLiteDatabase db;
    private DownloadManager manager;
    private TestScene scene;
    private RecyclerView recycler;
    private Toolbar toolbar;

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        var preferences = context.getSharedPreferences("folder-management", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", preferences);
        db = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(db), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(db).newSession());
        manager = new DownloadManager(context);
        ShadowApplication.manager = manager;
        manager.addLabelFolder("folder");
        manager.addLabel("z child");
        manager.addLabel("a child");
        manager.addLabel("z root");
        manager.addLabel("a root");
        Long folderId = manager.findDownloadLabel("folder").getId();
        manager.findDownloadLabel("z child").setParentId(folderId);
        manager.findDownloadLabel("a child").setParentId(folderId);
        manager.reorderLabels(new ArrayList<>(manager.getLabelList()));
        scene = new TestScene(context);
        scene.mList = manager.getLabelList();
        View view = scene.onCreateView3(LayoutInflater.from(context), null, null);
        recycler = view.findViewById(R.id.recycler_view);
        toolbar = new Toolbar(context);
        toolbar.inflateMenu(R.menu.scene_download_label);
        scene.onMenuCreated(toolbar.getMenu());
        layout();
    }

    @After public void tearDown() {
        scene.onDestroyView();
        ShadowApplication.manager = null;
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        db.close();
    }

    private void layout() {
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 800);
    }
    private void select(String... names) {
        Set<Long> selection = ReflectionHelpers.getField(scene, "mSelectedLabelIds");
        for (String name : names) selection.add(manager.findDownloadLabel(name).getId());
    }
    private AlertDialog action(int id) {
        scene.onMenuItemClick(toolbar.getMenu().findItem(id));
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
    private List<String> order() { return manager.getLabelList().stream().map(DownloadLabel::getLabel).toList(); }
    private <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            T found = find(((ViewGroup) root).getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }

    @Test public void addTypeSelectorChangesInputHintAndCreatesFolder() {
        AlertDialog dialog = action(R.id.action_add);
        RadioGroup types = find(dialog.getWindow().getDecorView(), RadioGroup.class);
        assertNotNull(types);
        TextInputLayout input = find(dialog.getWindow().getDecorView(), TextInputLayout.class);
        assertEquals(context.getString(R.string.download_labels), input.getHint().toString());
        types.check(types.getChildAt(1).getId());
        assertEquals(context.getString(R.string.download_label_folder), input.getHint().toString());
        input.getEditText().setText("new folder");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(manager.findDownloadLabel("new folder").getFolder());
        assertEquals(4, manager.getDownloadLabels().size());
    }

    @Test public void sortRequiresConfirmationAndPreservesFolderMembership() {
        select("z child", "a child", "z root", "a root");
        List<String> original = order();
        AlertDialog dialog = action(R.id.action_classify_selected_labels);
        assertEquals(original, order());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(original, order());
        action(R.id.action_classify_selected_labels).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("folder", "a child", "z child", "a root", "z root"), order());
    }

    @Test public void deleteFolderCheckboxDefaultsToKeepingLabelsAndCancelKeepsEverything() {
        select("folder");
        AlertDialog dialog = action(R.id.action_delete_selected_labels);
        assertFalse(find(dialog.getWindow().getDecorView(), CheckBox.class).isChecked());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(5, manager.getLabelList().size());
        action(R.id.action_delete_selected_labels).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("z child", "a child", "z root", "a root"), order());
        assertNull(manager.findDownloadLabel("z child").getParentId());
    }

    @Test public void deleteFolderCheckboxRemovesChildrenAndMovesGalleriesToDefault() {
        DownloadInfo gallery = new DownloadInfo();
        gallery.gid = 123;
        gallery.label = "z child";
        manager.addDownload(List.of(gallery));
        select("folder");
        AlertDialog dialog = action(R.id.action_delete_selected_labels);
        find(dialog.getWindow().getDecorView(), CheckBox.class).setChecked(true);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("z root", "a root"), order());
        assertNull(manager.getDownloadInfo(123).label);
        assertEquals(1, manager.getDefaultDownloadInfoList().size());
    }

    @Test public void deleteEmptyLabelsIncludesSelectedFolderChildrenButKeepsFolderAndNonemptyLabel() {
        DownloadInfo gallery = new DownloadInfo();
        gallery.gid = 123;
        gallery.label = "z child";
        manager.addDownload(List.of(gallery));
        select("folder");
        AlertDialog dialog = action(R.id.action_delete_empty_labels);
        assertTrue(manager.containLabel("a child"));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("folder", "z child", "z root", "a root"), order());
        assertEquals("1(1)", manager.getFolderCount(manager.findDownloadLabel("folder")));
    }

    private void drop(String anchorName, String targetName, boolean after, boolean cancelled) {
        layout();
        var anchor = manager.findDownloadLabel(anchorName);
        var target = manager.findDownloadLabel(targetName);
        Set<Long> ids = Set.of(manager.findDownloadLabel("z child").getId(), manager.findDownloadLabel("z root").getId());
        ReflectionHelpers.setField(scene, "mDragIds", ids);
        ItemTouchHelper helper = ReflectionHelpers.getField(scene, "mTouchHelper");
        ItemTouchHelper.Callback callback = ReflectionHelpers.getField(helper, "mCallback");
        RecyclerView.ViewHolder holder = recycler.findViewHolderForItemId(anchor.getId());
        RecyclerView.ViewHolder targetHolder = recycler.findViewHolderForItemId(target.getId());
        float destination = targetHolder.itemView.getTop() + targetHolder.itemView.getHeight() * (after ? 0.75f : 0.25f);
        float dy = destination - holder.itemView.getTop() - holder.itemView.getHeight() / 2f;
        callback.onChildDraw(new Canvas(), recycler, holder, 0, dy, ItemTouchHelper.ACTION_STATE_DRAG, true);
        if (cancelled) {
            recycler.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_CANCEL, 0, 0, 0));
        }
        callback.clearView(recycler, holder);
    }

    @Test public void draggingFromEitherSelectedRowGivesSameDestinationOrder() {
        List<DownloadLabel> original = new ArrayList<>(manager.getLabelList());
        drop("z child", "a root", false, false);
        List<String> first = order();
        assertEquals(List.of("folder", "a child", "z child", "z root", "a root"), first);
        manager.findDownloadLabel("z child").setParentId(manager.findDownloadLabel("folder").getId());
        manager.reorderLabels(original);
        recycler.getAdapter().notifyDataSetChanged();
        drop("z root", "a root", false, false);
        assertEquals(first, order());
    }

    @Test public void cancelledDragPreservesOrderAndMembership() {
        List<String> original = order();
        Long parent = manager.findDownloadLabel("z child").getParentId();
        drop("z root", "a root", false, true);
        assertEquals(original, order());
        assertEquals(parent, manager.findDownloadLabel("z child").getParentId());
    }

    public static class TestScene extends DownloadLabelsScene {
        private final Context context;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
    }
    @Implements(EhApplication.class)
    public static class ShadowApplication {
        static DownloadManager manager;
        @Implementation public static DownloadManager getDownloadManager(Context context) { return manager; }
    }
}
