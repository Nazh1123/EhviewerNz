package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.TorrentDownloadMessage;
import com.hippo.ehviewer.client.data.TorrentInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN")
public class TorrentDownloadControllerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private ActivityController<AppCompatActivity> activityController;
    private Activity activity;
    private TorrentDownloadController controller;

    @Before public void setUp() {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activity = activityController.get();
        activity.setTheme(R.style.AppTheme);
        activityController.setup();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                activity.getSharedPreferences("torrent-ui-test", Context.MODE_PRIVATE));
        activity.getSharedPreferences("torrent-ui-test", Context.MODE_PRIVATE).edit().clear()
                .putBoolean(Settings.KEY_USE_LOCAL_TIMEZONE, false).commit();
        controller = new TorrentDownloadController(new TorrentDownloadController.Host() {
            @Override public Context getEHContext() { return activity; }
            @Override public boolean isHostActive() { return true; }
            @Override public void retryTorrentDownload() { }
        }, 1, "token");
    }

    @After public void tearDown() {
        controller.release();
        activityController.pause().stop().destroy();
    }

    @Test public void listKeepsThreeAlignedRowsAndWebsiteSizeUnits() {
        TorrentInfo info = new TorrentInfo("https://example.org/a.torrent", "名称.zip",
                "2026-11-01 13:19", "128.2 MiB", "13", "4", "50");
        TorrentDownloadController.TorrentListAdapter adapter =
                new TorrentDownloadController.TorrentListAdapter(activity, new TorrentInfo[]{info});
        View row = adapter.getView(0, null, new android.widget.ListView(activity));
        int width = Math.round(300 * activity.getResources().getDisplayMetrics().density);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());

        assertEquals("名称.zip", text(row, R.id.torrent_name));
        assertEquals("做种 13", text(row, R.id.torrent_seeds));
        assertEquals("下载中 4", text(row, R.id.torrent_peers));
        assertEquals("累计下载 50", text(row, R.id.torrent_downloads));
        assertEquals("2026-11-01 13:19", text(row, R.id.torrent_posted));
        assertEquals("128.2 MiB", text(row, R.id.torrent_size));
        View seeds = row.findViewById(R.id.torrent_seeds);
        View peers = row.findViewById(R.id.torrent_peers);
        View downloads = row.findViewById(R.id.torrent_downloads);
        assertEquals(seeds.getTop(), peers.getTop());
        assertEquals(peers.getTop(), downloads.getTop());
        assertTrue(Math.abs(seeds.getWidth() - downloads.getWidth()) <= 2);
        assertEquals(0, seeds.getLeft());
        assertEquals(((ViewGroup) downloads.getParent()).getWidth(), downloads.getRight());
        View size = row.findViewById(R.id.torrent_size);
        assertEquals(((ViewGroup) size.getParent()).getWidth(), size.getRight());
    }

    @Test public void completedDownloadOpensContentUriWithTorrentTypeAndReadPermission() throws Exception {
        // Use a configured FileProvider directory; Windows temporary files may be on another drive.
        File torrent = new File(activity.getCacheDir(), "EhViewer/cache/名称.zip.torrent");
        assertTrue(torrent.getParentFile().isDirectory() || torrent.getParentFile().mkdirs());
        java.nio.file.Files.write(torrent.toPath(), new byte[0]);
        showResult(torrent.getPath(), true);
        View downloadView = ReflectionHelpers.getField(controller, "torrentDownloadView");
        View open = downloadView.findViewById(R.id.torrent_open);
        assertEquals(View.VISIBLE, open.getVisibility());
        assertEquals("打开", ((TextView) open).getText().toString());
        ViewGroup buttons = (ViewGroup) open.getParent();
        assertEquals(R.id.leader, buttons.getChildAt(0).getId());
        assertEquals(R.id.torrent_open, buttons.getChildAt(1).getId());
        assertEquals(R.id.action, buttons.getChildAt(2).getId());
        open.performClick();

        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("application/x-bittorrent", intent.getType());
        assertEquals("content", intent.getData().getScheme());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(intent.getData(), intent.getClipData().getItemAt(0).getUri());

        // Reusing a successful download view for failure must remove the Open action.
        showResult(torrent.getPath(), false);
        assertEquals(View.GONE, open.getVisibility());
    }

    @Test public void missingDownloadedFileShowsErrorAndKeepsDialogAvailable() {
        showResult(new File(files.getRoot(), "missing.torrent").getPath(), true);
        View view = ReflectionHelpers.getField(controller, "torrentDownloadView");
        view.findViewById(R.id.torrent_open).performClick();

        assertNull(shadowOf(activity).getNextStartedActivity());
        assertEquals(activity.getString(R.string.torrent_cannot_open), ShadowToast.getTextOfLatestToast());
        AlertDialog dialog = ReflectionHelpers.getField(controller, "downLoadAlertDialog");
        assertTrue(dialog.isShowing());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void downloadButtonsFitNarrowDialogWithoutOverlap() {
        showResult(new File(files.getRoot(), "test.torrent").getPath(), true);
        View view = ReflectionHelpers.getField(controller, "torrentDownloadView");
        int[] ids = {R.id.leader, R.id.torrent_open, R.id.action};
        float density = activity.getResources().getDisplayMetrics().density;
        for (int widthDp : new int[]{240, 280, 320}) {
            int width = Math.round(widthDp * density);
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            view.layout(0, 0, width, view.getMeasuredHeight());
            int previousRight = -1;
            for (int id : ids) {
                TextView button = view.findViewById(id);
                if (previousRight >= 0) {
                    assertTrue("Buttons must have a visible gap at " + widthDp + "dp",
                            button.getLeft() - previousRight >= Math.round(6 * density));
                }
                assertTrue(button.getRight() <= ((View) button.getParent()).getWidth());
                assertTrue(button.getHeight() >= Math.round(48 * density));
                assertEquals("Chinese action labels should fit on one line at " + widthDp + "dp",
                        1, button.getLineCount());
                assertTrue(button.getLayout().getLineWidth(0)
                        <= button.getWidth() - button.getCompoundPaddingLeft()
                        - button.getCompoundPaddingRight());
                previousRight = button.getRight();
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void enlargedButtonLabelsWrapWithinTheirOwnBounds() {
        showResult(new File(files.getRoot(), "test.torrent").getPath(), true);
        View view = ReflectionHelpers.getField(controller, "torrentDownloadView");
        int[] ids = {R.id.leader, R.id.torrent_open, R.id.action};
        for (int id : ids) {
            TextView button = view.findViewById(id);
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, button.getTextSize() * 1.8f);
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(240 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        for (int id : ids) {
            TextView button = view.findViewById(id);
            android.text.Layout textLayout = button.getLayout();
            assertEquals(button.getText().length(), textLayout.getLineEnd(textLayout.getLineCount() - 1));
            assertTrue(textLayout.getHeight() <= button.getHeight()
                    - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom());
            for (int line = 0; line < textLayout.getLineCount(); line++) {
                assertEquals(0, textLayout.getEllipsisCount(line));
                assertTrue(textLayout.getLineWidth(line) <= button.getWidth()
                        - button.getCompoundPaddingLeft() - button.getCompoundPaddingRight());
            }
        }
    }

    private void showResult(String path, boolean success) {
        TorrentDownloadMessage message = new TorrentDownloadMessage();
        message.name = "名称.zip.torrent";
        message.path = path;
        message.dir = files.getRoot().getPath();
        message.progress = success ? 100 : -1;
        message.failed = !success;
        ReflectionHelpers.callInstanceMethod(controller, "showTorrentDownloadDialog",
                from(TorrentDownloadMessage.class, message), from(boolean.class, success));
    }

    private static String text(View view, int id) {
        return ((TextView) view.findViewById(id)).getText().toString();
    }
}
