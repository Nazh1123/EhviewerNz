package com.hippo.ehviewer.ui.scene.download;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.widget.DownloadFastScroller;
import com.hippo.scene.Announcer;
import com.hippo.scene.StageActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in real-touch checks, using uniquely named fixtures and removing only those fixtures. */
@RunWith(AndroidJUnit4.class)
public class DownloadLabelsDeviceTest {
    private final Instrumentation in = InstrumentationRegistry.getInstrumentation();
    private final List<Long> galleryIds = new ArrayList<>();
    private final Set<Long> originalLabels = new HashSet<>();
    private final StringBuilder report = new StringBuilder();
    private MainActivity activity;
    private File evidence;
    private String phase = "setup";

    @Test(timeout = 240000) public void realTouchScrollsAllDownloadSurfacesAndOrganizesNewLabels() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("downloadLabels")));
        DownloadManager manager = EhApplication.getDownloadManager(in.getTargetContext());
        for (DownloadLabel label : manager.getLabelList()) originalLabels.add(label.getId());
        int originalGalleries = manager.getAllDownloadInfoList().size();
        String recent = Settings.getRecentDownloadLabel();
        boolean continuous = Settings.getDownloadLabelContinuousBrowse(), pagination = Settings.getDownloadPagination();
        boolean thumbGuide = Settings.getGuideDownloadThumb(), labelGuide = Settings.getGuideDownloadLabels();
        boolean warning = Settings.getShowWarning(), signin = Settings.getNeedSignIn();
        boolean site = Settings.getSelectSite(), analytics = Settings.getAskAnalytics();
        boolean versionPrompt = Settings.isGalleryVersionUpdatePromptPending();
        String prefix = "Scroll QA " + System.currentTimeMillis() + " ";
        String sourceLabel = prefix + "000", artist = "scroll-qa-" + System.currentTimeMillis();
        String existingLabel = "A: " + artist + "-existing";
        evidence = new File(in.getTargetContext().getExternalFilesDir(null), "download-label-scroll-qa");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        try {
            Bitmap fixture = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888);
            fixture.eraseColor(android.graphics.Color.rgb(40, 95, 140));
            try (var zip = new java.util.zip.ZipOutputStream(new FileOutputStream(new File(evidence, "local-fixture.zip")))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("1.png"));
                fixture.compress(Bitmap.CompressFormat.PNG, 100, zip);
                zip.closeEntry();
            }
            fixture.recycle();
            Settings.putShowWarning(false); Settings.putNeedSignIn(false);
            Settings.putSelectSite(false); Settings.putAskAnalytics(false);
            Settings.putGuideDownloadThumb(false); Settings.puttGuideDownloadLabels(false);
            Settings.setGalleryVersionUpdatePromptPending(false);
            Settings.setDownloadPagination(false); Settings.setDownloadLabelContinuousBrowse(false);
            main(() -> {
                manager.addLabelFolder(prefix + "folder");
                DownloadLabel folder = manager.findDownloadLabel(prefix + "folder");
                for (int i = 0; i < 120; i++) {
                    String name = prefix + String.format(java.util.Locale.US, "%03d", i);
                    manager.addLabel(name);
                    manager.findDownloadLabel(name).setParentId(folder.getId());
                }
                manager.addLabel(existingLabel);
                manager.findDownloadLabel(existingLabel).setParentId(folder.getId());
                manager.reorderLabels(new ArrayList<>(manager.getLabelList()));
                Settings.setDownloadLabelExpanded(folder.getId(), folder.getLabel(), true);
                List<DownloadInfo> fixtures = new ArrayList<>();
                long base = 9_100_000_000_000L + SystemClock.elapsedRealtime();
                for (int i = 0; i < 90; i++) {
                    DownloadInfo info = new DownloadInfo(base + i);
                    info.label = sourceLabel;
                    info.title = "Fast scroll QA gallery " + i;
                    info.token = "0000000000";
                    info.pages = info.total = 1;
                    info.state = DownloadInfo.STATE_FINISH;
                    info.time = System.currentTimeMillis() - i;
                    info.archiveUri = new File(evidence, "local-fixture.zip").toURI().toString();
                    info.simpleTags = new String[]{"artist:" + artist + (i == 1 ? "-existing" : "-new")};
                    galleryIds.add(info.gid);
                    fixtures.add(info);
                }
                manager.addDownload(fixtures);
                Settings.putRecentDownloadLabel(sourceLabel);
            });
            Instrumentation.ActivityMonitor monitor = in.addMonitor(MainActivity.class.getName(), null, false);
            String command = "am start -W -n " + in.getTargetContext().getPackageName() + "/"
                    + MainActivity.class.getName() + " -a " + StageActivity.ACTION_START_SCENE
                    + " --es " + StageActivity.KEY_SCENE_NAME + " "
                    + DownloadsScene.class.getName();
            try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    in.getUiAutomation().executeShellCommand(command))) { while (input.read() != -1) {} }
            activity = (MainActivity) in.waitForMonitorWithTimeout(monitor, 12000);
            in.removeMonitor(monitor);
            assertNotNull("MainActivity did not launch", activity);
            main(() -> {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
            await(() -> activity.getTopScene() instanceof DownloadsScene);
            DownloadsScene normal = (DownloadsScene) activity.getTopScene();
            phase = "normal download list";
            testRecycler(normal.getView(), R.id.fast_scroller, "normal");

            phase = "download label drawer";
            main(() -> normal.openDrawer(Gravity.RIGHT));
            await(() -> findShown(activity.getWindow().getDecorView(), R.id.label_fast_scroller) != null);
            in.waitForIdleSync();
            SystemClock.sleep(500); // Finish the drawer's slide-in before reading screen coordinates.
            DownloadFastScroller drawerBar = (DownloadFastScroller) findShown(activity.getWindow().getDecorView(), R.id.label_fast_scroller);
            ListView drawerList = (ListView) ((View) drawerBar.getParent()).findViewById(R.id.list_view);
            await(drawerBar::isScrollable);
            int first = drawerList.getFirstVisiblePosition();
            drag(drawerBar, 1);
            report.append("drawer first: ").append(first).append(" -> ").append(drawerList.getFirstVisiblePosition()).append('\n');
            assertTrue("Drawer did not seek", drawerList.getFirstVisiblePosition() > first + 30);
            screenshot("drawer-end.png");
            main(() -> normal.closeDrawer(Gravity.RIGHT));

            phase = "tag management";
            main(() -> activity.startScene(new Announcer(DownloadLabelsScene.class)));
            await(() -> activity.getTopScene() instanceof DownloadLabelsScene);
            testRecycler(activity.getTopScene().getView(), R.id.label_fast_scroller, "management");

            phase = "continuous downloads";
            main(() -> {
                Settings.setDownloadLabelContinuousBrowse(true);
                activity.startScene(new Announcer(DownloadsScene.class));
            });
            await(() -> activity.getTopScene() instanceof DownloadsScene);
            DownloadsScene grouped = (DownloadsScene) activity.getTopScene();
            await(grouped::isContinuousLabelBrowse);
            testRecycler(grouped.getView(), R.id.fast_scroller, "continuous");

            phase = "stacked single-label downloads";
            main(() -> {
                Bundle args = new Bundle();
                args.putBoolean(DownloadsScene.KEY_FORCE_SINGLE_LABEL_MODE, true);
                args.putString(DownloadsScene.KEY_LABEL, sourceLabel);
                activity.startScene(new Announcer(SingleLabelDownloadsScene.class).setArgs(args));
            });
            await(() -> activity.getTopScene() instanceof SingleLabelDownloadsScene);
            DownloadsScene single = (DownloadsScene) activity.getTopScene();
            testRecycler(single.getView(), R.id.fast_scroller, "single-label");

            phase = "quick organize button";
            Long oldParent = manager.findDownloadLabel(existingLabel).getParentId();
            main(() -> {
                single.getRecyclerView().intoCustomChoiceMode();
                single.getRecyclerView().toggleItemChecked(single.getAdapterPositionForGallery(galleryIds.get(0)));
                single.getRecyclerView().toggleItemChecked(single.getAdapterPositionForGallery(galleryIds.get(1)));
            });
            click(in.getTargetContext().getString(R.string.quick_organize));
            click(in.getTargetContext().getString(android.R.string.ok));
            await(() -> manager.findDownloadLabel("A: " + artist + "-new") != null);
            DownloadLabel created = manager.findDownloadLabel("A: " + artist + "-new");
            assertNotNull(created.getParentId());
            DownloadLabel sortFolder = manager.getLabelList().stream()
                    .filter(label -> created.getParentId().equals(label.getId())).findFirst().orElseThrow();
            assertTrue(sortFolder.getFolder());
            assertTrue(sortFolder.getLabel().matches("Sort_\\d{8}_\\d{6}(?:_\\d+)?"));
            assertEquals(oldParent, manager.findDownloadLabel(existingLabel).getParentId());
            assertEquals("A: " + artist + "-new", manager.getDownloadInfo(galleryIds.get(0)).label);
            assertEquals(existingLabel, manager.getDownloadInfo(galleryIds.get(1)).label);
            report.append("quick organization folder: ").append(sortFolder.getLabel()).append("; new labels=1; existing label kept its parent\n");
            main(() -> activity.startScene(new Announcer(DownloadLabelsScene.class)));
            await(() -> activity.getTopScene() instanceof DownloadLabelsScene);
            main(() -> ((RecyclerView) activity.getTopScene().getView().findViewById(R.id.recycler_view))
                    .scrollToPosition(manager.getLabelList().indexOf(sortFolder)));
            screenshot("sort-folder.png");
            report.append("PASS: all four recycler/drawer surfaces accepted real injected touch drags after idle\n");
        } finally {
            try { screenshot("last-state.png"); } catch (Throwable ignored) {}
            main(() -> {
                if (activity != null) activity.finish();
                for (Long gid : galleryIds) manager.deleteDownload(gid);
                Set<Long> createdSortFolders = new HashSet<>();
                for (DownloadLabel label : manager.getLabelList()) {
                    if (!originalLabels.contains(label.getId()) && label.getLabel().contains(artist)
                            && label.getParentId() != null) createdSortFolders.add(label.getParentId());
                }
                List<String> names = new ArrayList<>();
                for (DownloadLabel label : manager.getLabelList()) {
                    if (!originalLabels.contains(label.getId()) && (label.getLabel().startsWith(prefix)
                            || label.getLabel().contains(artist) || createdSortFolders.contains(label.getId()))) names.add(label.getLabel());
                }
                manager.deleteLabels(names);
            });
            Settings.putRecentDownloadLabel(recent);
            Settings.setDownloadLabelContinuousBrowse(continuous); Settings.setDownloadPagination(pagination);
            Settings.putGuideDownloadThumb(thumbGuide); Settings.puttGuideDownloadLabels(labelGuide);
            Settings.putShowWarning(warning); Settings.putNeedSignIn(signin);
            Settings.putSelectSite(site); Settings.putAskAnalytics(analytics);
            Settings.setGalleryVersionUpdatePromptPending(versionPrompt);
            report.append("cleanup galleries: ").append(manager.getAllDownloadInfoList().size())
                    .append("; original=").append(originalGalleries).append("; labels=").append(manager.getLabelList().size())
                    .append("; original=").append(originalLabels.size()).append('\n');
            report.append("last phase: ").append(phase).append('\n');
            try (FileOutputStream stream = new FileOutputStream(new File(evidence, "report.txt"))) {
                stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(originalGalleries, manager.getAllDownloadInfoList().size());
            assertEquals(originalLabels.size(), manager.getLabelList().size());
        }
    }

    private void testRecycler(View root, int id, String name) throws Exception {
        DownloadFastScroller bar = root.findViewById(id);
        RecyclerView recycler = root.findViewById(R.id.recycler_view);
        await(bar::isScrollable);
        int before = first(recycler);
        SystemClock.sleep(1800); // The old control disappeared after one second.
        main(() -> assertTrue(bar.isShown() && bar.getAlpha() == 1));
        drag(bar, 1);
        int after = first(recycler);
        assertTrue(name + " did not seek: " + before + " -> " + after, after > before + 15);
        assertTrue(bar.getThumbBounds().height() <= 36.5f * bar.getResources().getDisplayMetrics().density);
        report.append(name).append(" first: ").append(before).append(" -> ").append(after)
                .append("; thumbDp=").append(bar.getThumbBounds().height() / bar.getResources().getDisplayMetrics().density).append('\n');
        screenshot(name + "-end.png");
        drag(bar, 0);
        assertTrue(name + " did not return to start", first(recycler) < 10);
    }
    private int first(RecyclerView recycler) {
        if (recycler.getLayoutManager() instanceof LinearLayoutManager)
            return ((LinearLayoutManager) recycler.getLayoutManager()).findFirstVisibleItemPosition();
        int[] positions = ((StaggeredGridLayoutManager) recycler.getLayoutManager()).findFirstVisibleItemPositions(null);
        return java.util.Arrays.stream(positions).filter(value -> value >= 0).min().orElse(-1);
    }
    private void drag(DownloadFastScroller bar, float destination) throws Exception {
        final float[] points = new float[3];
        main(() -> {
            int[] location = new int[2]; bar.getLocationOnScreen(location);
            RectF thumb = bar.getThumbBounds();
            points[0] = location[0] + bar.getWidth() / 2f;
            points[1] = location[1] + thumb.centerY();
            points[2] = location[1] + bar.getPaddingTop() + thumb.height() / 2
                    + destination * (bar.getHeight() - bar.getPaddingTop() - bar.getPaddingBottom() - thumb.height());
            report.append("drag bounds x=").append(points[0]).append(" fromY=").append(points[1])
                    .append(" toY=").append(points[2]).append('\n');
        });
        long start = SystemClock.uptimeMillis();
        for (int step = 0; step <= 14; step++) {
            float y = points[1] + (points[2] - points[1]) * step / 14;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), step == 0
                    ? MotionEvent.ACTION_DOWN : step == 14 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, points[0], y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(in.getUiAutomation().injectInputEvent(event, true)); event.recycle();
            SystemClock.sleep(20);
        }
        in.waitForIdleSync(); SystemClock.sleep(200);
    }
    private void click(String text) throws Exception {
        await(() -> {
            AccessibilityNodeInfo root = in.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo node = queue.remove();
                if ((text.contentEquals(node.getText() == null ? "" : node.getText())
                        || text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))
                        && node.isVisibleToUser() && node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                for (int i = 0; i < node.getChildCount(); i++) if (node.getChild(i) != null) queue.add(node.getChild(i));
            }
            return false;
        });
    }
    private View findShown(View root, int id) {
        if (root.getId() == id && root.isShown()) return root;
        if (root instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findShown(group.getChildAt(i), id); if (found != null) return found;
            }
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        in.waitForIdleSync(); Bitmap bitmap = in.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
    }
    private interface Action { void run() throws Exception; }
    private interface Check { boolean get() throws Exception; }
    private void await(Check check) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            AtomicReference<Boolean> result = new AtomicReference<>(false);
            main(() -> result.set(check.get()));
            if (result.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Timeout: " + phase);
    }
    private void main(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        in.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(phase, error.get());
    }
}
