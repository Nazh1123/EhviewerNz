package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.GalleryUpdateRecord;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class GalleryUpdateLogDialogTest {
    private GalleryUpdateRecord record(boolean complete) {
        return record(complete, true);
    }

    private GalleryUpdateRecord record(boolean complete, boolean hasTokens) {
        return record(complete, hasTokens, new int[]{99,100,101,102,103,104,105,106,107,119,120});
    }

    private GalleryUpdateRecord record(boolean complete, boolean hasTokens, int[] additions) {
        return ReflectionHelpers.callConstructor(GalleryUpdateRecord.class,
                ReflectionHelpers.ClassParameter.from(long.class, 200L),
                ReflectionHelpers.ClassParameter.from(long.class, 100L),
                ReflectionHelpers.ClassParameter.from(long.class, 1791076310000L),
                ReflectionHelpers.ClassParameter.from(int.class, 100),
                ReflectionHelpers.ClassParameter.from(int.class, 121),
                ReflectionHelpers.ClassParameter.from(boolean.class, complete),
                ReflectionHelpers.ClassParameter.from(int[].class, additions),
                ReflectionHelpers.ClassParameter.from(int[].class, new int[]{0,1}),
                ReflectionHelpers.ClassParameter.from(int.class, 0),
                ReflectionHelpers.ClassParameter.from(String.class, ""),
                ReflectionHelpers.ClassParameter.from(String.class, ""),
                ReflectionHelpers.ClassParameter.from(long[].class, new long[0]),
                ReflectionHelpers.ClassParameter.from(long.class, 50L),
                ReflectionHelpers.ClassParameter.from(String[].class, hasTokens
                        ? IntStream.range(0, 121).mapToObj(i -> "token" + i).toArray(String[]::new)
                        : new String[0]),
                ReflectionHelpers.ClassParameter.from(String.class, "gallery-token"));
    }

    @Test public void versionHistoryIsIdleUntilClickedAndLogUsesCorrectPageRanges() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AtomicInteger historyLoads = new AtomicInteger();
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(true), historyLoads::incrementAndGet);
        ShadowLooper.idleMainLooper();
        assertEquals(0, historyLoads.get());
        String text = text(dialog.getWindow().getDecorView());
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_added)));
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_deleted)));
        assertTrue(text.contains("+11"));
        assertTrue(text.contains("-2"));
        assertTrue(text.contains("p100–108, p120–121"));
        assertTrue(text.contains("p1–2"));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, historyLoads.get());
        activity.finish();
    }

    @Test public void incompleteMetadataDisplaysNoticeInsteadOfDefinitiveCounts() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(false), () -> {});
        String text = text(dialog.getWindow().getDecorView());
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_incomplete)));
        assertFalse(text.contains("p100"));
        dialog.dismiss(); activity.finish();
    }

    @Test public void fourActionsAreAvailableAndLocalActionsRunOnlyAfterClick() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AtomicInteger reads = new AtomicInteger(), histories = new AtomicInteger(), versions = new AtomicInteger();
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(true), reads::incrementAndGet,
                histories::incrementAndGet, versions::incrementAndGet);
        assertEquals(0, reads.get() + histories.get() + versions.get());
        View decor = dialog.getWindow().getDecorView();
        assertTrue(text(decor).contains(activity.getString(R.string.gallery_update_history_local_notice)));
        findText(decor, activity.getString(R.string.gallery_update_log_read)).performClick();
        assertEquals(1, reads.get());
        findText(decor, activity.getString(R.string.gallery_update_log_history)).performClick();
        assertEquals(1, histories.get());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, versions.get());
        dialog.dismiss(); activity.finish();
    }

    @Test public void legacyRecordWithoutTokenSnapshotDisablesReadButKeepsHistoryAccessible() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AtomicInteger reads = new AtomicInteger(), histories = new AtomicInteger();
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(true, false),
                reads::incrementAndGet, histories::incrementAndGet, () -> {});
        View decor = dialog.getWindow().getDecorView();
        View read = findText(decor, activity.getString(R.string.gallery_update_log_read));
        assertFalse(read.isEnabled());
        TextView notice = decor.findViewById(R.id.gallery_update_log_read_notice);
        assertEquals(View.VISIBLE, notice.getVisibility());
        assertEquals(activity.getString(R.string.gallery_update_log_legacy_notice), notice.getText().toString());
        read.performClick();
        assertEquals(0, reads.get());
        View history = findText(decor, activity.getString(R.string.gallery_update_log_history));
        assertTrue(history.isEnabled());
        history.performClick();
        assertEquals(1, histories.get());
        dialog.dismiss(); activity.finish();
    }

    @Test public void historyRowsKeepNumberTimeAndColoredCountsInDialogList() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        CharSequence name = GalleryUpdateLogDialog.historyName(activity, record(true), 3);
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(record(true).completedAt));
        assertEquals("3. " + time + "   +11 -2", name.toString());
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setItems(new CharSequence[]{name}, (ignored, index) -> {}).show();
        TextView row = (TextView) dialog.getListView().getAdapter().getView(0, null, dialog.getListView());
        assertTrue(row.getText() instanceof Spanned);
        Spanned label = (Spanned) row.getText();
        ForegroundColorSpan[] spans = label.getSpans(0, label.length(), ForegroundColorSpan.class);
        assertEquals(2, spans.length);
        assertEquals("+11", label.subSequence(label.getSpanStart(spans[0]), label.getSpanEnd(spans[0])).toString());
        assertEquals(activity.getColor(R.color.deep_green_600), spans[0].getForegroundColor());
        assertEquals("-2", label.subSequence(label.getSpanStart(spans[1]), label.getSpanEnd(spans[1])).toString());
        assertEquals(activity.getColor(R.color.red_500), spans[1].getForegroundColor());
        assertTrue(GalleryUpdateLogDialog.historyName(activity, record(false), 4).toString()
                .startsWith("4. " + time + " · "));
        dialog.dismiss(); activity.finish();
    }

    @Test public void incompleteOrUnavailableLocalUpdatesKeepHistoryAccessible() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(false), () -> fail("Cannot read"),
                () -> {}, () -> {});
        assertFalse(findText(dialog.getWindow().getDecorView(),
                activity.getString(R.string.gallery_update_log_read)).isEnabled());
        assertTrue(findText(dialog.getWindow().getDecorView(),
                activity.getString(R.string.gallery_update_log_history)).isEnabled());
        dialog.dismiss();
        dialog = GalleryUpdateLogDialog.show(activity, record(true), null, () -> {}, () -> {});
        assertFalse(findText(dialog.getWindow().getDecorView(),
                activity.getString(R.string.gallery_update_log_read)).isEnabled());
        dialog.dismiss(); activity.finish();
    }

    private View findText(View view, String value) {
        if (view instanceof TextView label && value.contentEquals(label.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findText(group.getChildAt(i), value);
                if (match != null) return match;
            }
        }
        return null;
    }

    @Test public void failureUsesSameDialogWithTimeSourceReasonAndRetainedParents() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        GalleryUpdateRecord record = GalleryUpdateRecord.failure(200, 12341255, 1791076310000L,
                "qweifoaeofj", "Missing .ehviewer", new long[]{12341255, 100});
        AtomicInteger historyLoads = new AtomicInteger();
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record, historyLoads::incrementAndGet);
        String text = text(dialog.getWindow().getDecorView());
        assertTrue(text.contains(activity.getString(R.string.gallery_update_error_log)));
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_time)));
        assertTrue(text.contains("GID: 12341255"));
        assertTrue(text.contains("qweifoaeofj"));
        assertTrue(text.contains("Missing .ehviewer"));
        assertTrue(text.contains(activity.getString(R.string.gallery_update_error_parents_retained,
                "12341255, 100")));
        assertFalse(text.contains(activity.getString(R.string.gallery_update_log_incomplete)));
        assertEquals(0, historyLoads.get());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, historyLoads.get());
        dialog.dismiss(); activity.finish();
    }

    @Test public void failureBeforeSourceSelectionAndAfterCleanupDoesNotClaimParentsRetained() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity,
                GalleryUpdateRecord.failure(200, 0, 123, "", "Version lookup failed", new long[0]), () -> {});
        String text = text(dialog.getWindow().getDecorView());
        assertTrue(text.contains(activity.getString(R.string.gallery_update_error_source_unknown)));
        assertFalse(text.contains("GID: 0"));
        assertFalse(text.contains(activity.getString(R.string.gallery_update_error_parents_retained, "")));
        dialog.dismiss(); activity.finish();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void longLogsKeepActionsVisibleAcrossThemesAndSmallScreens() throws Exception {
        RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h640dp-mdpi");
        GalleryUpdateRecord longRecord = record(true, true,
                IntStream.range(0, 61).map(i -> i * 2).toArray());
        render("light", R.style.AppTheme, record(true), 360, 720, 1f, false);
        render("dark", R.style.AppTheme_Dark, record(true), 360, 720, 1f, false);
        render("legacy", R.style.AppTheme, record(true, false), 320, 600, 1f, true);
        render("landscape-large-text", R.style.AppTheme_Dark, longRecord, 600, 360, 1.3f, true);
        render("failure", R.style.AppTheme_Black, GalleryUpdateRecord.failure(200, 100,
                1791076310000L, "Old gallery", "Unable to read local images.\n".repeat(30),
                new long[]{100, 50}), 360, 600, 1f, true);
    }

    private void render(String name, int theme, GalleryUpdateRecord record, int width, int maxHeight,
                        float fontScale, boolean expectScrolling) throws Exception {
        RuntimeEnvironment.setQualifiers("zh-rCN-w" + width + "dp-h" + (maxHeight + 80) + "dp-mdpi");
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(theme);
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record, () -> {}, () -> {}, () -> {});
        ShadowLooper.idleMainLooper();
        View decor = dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        Bitmap image = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(image));
        File directory = new File("build/update-log-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        image.recycle();
        assertTrue(decor.getHeight() <= maxHeight);
        View scroll = decor.findViewById(R.id.gallery_update_log_scroll);
        View content = decor.findViewById(R.id.gallery_update_log_content);
        View actions = decor.findViewById(R.id.gallery_update_log_actions);
        assertTrue("Log viewport must remain visible: " + name, scroll.getHeight() >= 48);
        assertTrue("Actions must sit outside the log viewport: " + name, scroll.getBottom() <= actions.getTop());
        if (expectScrolling) assertTrue("Long logs must scroll: " + name, content.getHeight() > scroll.getHeight());
        for (int id : new int[]{R.id.gallery_update_log_read_action, R.id.gallery_update_log_history_action}) {
            View button = decor.findViewById(id);
            Rect visible = new Rect();
            assertTrue(button.getGlobalVisibleRect(visible));
            assertEquals("Action must not be clipped: " + name, button.getHeight(), visible.height());
            assertTrue(button.getHeight() >= 48);
        }
        dialog.dismiss(); activity.finish();
    }

    private String text(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView label) text.append(label.getText()).append('\n');
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) text.append(text(group.getChildAt(i)));
        }
        return text.toString();
    }
}
