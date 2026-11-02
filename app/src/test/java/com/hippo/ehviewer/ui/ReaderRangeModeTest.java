package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.download.GalleryPageRange;
import com.hippo.ehviewer.gallery.DirGalleryProvider;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.gallery.RangeGalleryProvider;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN", shadows = {
        ReaderRangeModeTest.App.class, ReaderRangeModeTest.Directory.class, ReaderRangeModeTest.Downloads.class})
public class ReaderRangeModeTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static UniFile directory;
    private static final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private static DownloadInfo info;
    private static GalleryPageRange submitted;
    private static boolean changed;
    private Context context;
    private GalleryDownloadRangeStore store;
    private static final ExecutorService executor = new AbstractExecutorService() {
        @Override public void execute(Runnable command) { work.add(command); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    };
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", context.getSharedPreferences("range-ui", 0));
        context.getSharedPreferences("download_page_ranges", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        store = GalleryDownloadRangeStore.get(context);
        directory = UniFile.fromFile(folder.newFolder());
        info = new DownloadInfo(9001); info.token = "token"; info.title = "Test"; info.pages = 30;
        info.state = DownloadInfo.STATE_FINISH;
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8XcAAAAASUVORK5CYII=");
        for (int page : new int[]{9, 19, 29}) try (java.io.OutputStream output =
                directory.createFile(SpiderDen.generateImageFilename(page, ".png")).openOutputStream()) { output.write(png); }
        store.apply(info, GalleryPageRange.parse("10,20,30", 30));
        work.clear(); changed = false; submitted = null;
    }
    @After public void cleanup() {
        if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
        work.clear(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
    }
    private TestReader reader(boolean explicitFull) {
        Intent intent = new Intent(context, TestReader.class).setAction(GalleryActivity.ACTION_EH)
                .putExtra(GalleryActivity.KEY_GALLERY_INFO, info);
        if (explicitFull) intent.putExtra(GalleryActivity.KEY_RANGE_READER_MODE, false);
        TestReader reader = Robolectric.buildActivity(TestReader.class, intent).get();
        reader.setTheme(R.style.AppTheme_Gallery);
        ReflectionHelpers.callInstanceMethod(reader, "onInit");
        GalleryProvider2 provider = ReflectionHelpers.getField(reader, "mGalleryProvider");
        if (provider instanceof RangeGalleryProvider range) {
            assertTrue(range.hasReadablePages()); ReflectionHelpers.setField(provider, "mSize", 3);
        } else ReflectionHelpers.setField(reader, "mGalleryProvider", new DirGalleryProvider(directory) {
            @Override public int size() { return 30; }
        });
        return reader;
    }
    private AlertDialog menu(TestReader reader, int page) {
        ReflectionHelpers.callInstanceMethod(reader, "showPageDialog", ReflectionHelpers.ClassParameter.from(int.class, page));
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
    @Test public void defaultsToRangeButAnExplicitFullReaderStaysFull() {
        TestReader range = reader(false), full = reader(true);
        assertTrue(ReflectionHelpers.getField(range, "mRangeReaderMode"));
        assertFalse(ReflectionHelpers.getField(full, "mRangeReaderMode"));
        assertEquals("阅读完整画廊", menu(range, 1).getListView().getAdapter().getItem(6));
        assertEquals("仅阅读已下载区间", menu(full, 18).getListView().getAdapter().getItem(6));
    }
    @Test public void rangeToFullUsesSourcePageAndRetainsTheSameDownloadRecord() {
        TestReader reader = reader(false);
        menu(reader, 1).getListView().performItemClick(null, 6, 6);
        assertNotNull(reader.switched);
        assertFalse(reader.switched.getBooleanExtra(GalleryActivity.KEY_RANGE_READER_MODE, true));
        assertEquals(19, reader.switched.getIntExtra(GalleryActivity.KEY_PAGE, -1));
        assertTrue(GalleryDownloadRangeStore.isRange(info.gid));
    }
    @Test public void fullToRangeUsesAnExplicitSourceAnchorAfterAsynchronousValidation() {
        TestReader reader = reader(true);
        menu(reader, 18).getListView().performItemClick(null, 6, 6);
        assertNull(reader.switched);
        while (!work.isEmpty()) work.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(reader.switched);
        assertTrue(reader.switched.getBooleanExtra(GalleryActivity.KEY_RANGE_READER_MODE, false));
        assertEquals(18, reader.switched.getIntExtra("range_source_page", -1));
        RangeGalleryProvider provider = new RangeGalleryProvider(context, info, 18);
        assertTrue(provider.hasReadablePages()); assertEquals(1, provider.getStartPage());
        assertEquals(19, provider.getSourcePage(1));
    }
    @Test public void menuTypographyMatchesOriginalAcrossAllReaderThemes() {
        for (int theme : new int[]{R.style.AppTheme_Gallery, R.style.AppTheme_Gallery_Dark, R.style.AppTheme_Gallery_Black}) {
            TestReader reader = reader(false); reader.setTheme(theme);
            AlertDialog original = new AlertDialog.Builder(reader).setItems(new CharSequence[]{"刷新"}, null).show();
            AlertDialog current = menu(reader, 0);
            TextView expected = original.getListView().getAdapter().getView(0, null, original.getListView()).findViewById(android.R.id.text1);
            TextView actual = current.getListView().getAdapter().getView(0, null, current.getListView()).findViewById(android.R.id.text1);
            assertEquals(expected.getTextSize(), actual.getTextSize(), 0f);
            assertEquals(expected.getTypeface(), actual.getTypeface());
            assertEquals(expected.getCurrentTextColor(), actual.getCurrentTextColor());
            assertEquals(expected.getMinHeight(), actual.getMinHeight());
            assertEquals(expected.getPaddingLeft(), actual.getPaddingLeft());
            assertEquals(expected.getPaddingRight(), actual.getPaddingRight());
            original.dismiss(); current.dismiss();
        }
    }
    @Test public void rangeDialogPrefillsClosedRangesAndRejectsInvalidInputBeforeSubmitting() {
        TestReader reader = reader(false);
        RangeDownloadDialog.show(reader, info, new DownloadManager(context));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = findInput(dialog.getWindow().getDecorView()); assertNotNull(input);
        assertEquals("10,20,30", input.getText().toString());
        input.setText("0,40"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(changed); assertTrue(dialog.isShowing());
        input.setText("-5,25-"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(changed); assertEquals("1-5,25-30", submitted.toString());
    }
    @Test public void rangeDialogUsesAnInlinePlaceholderAcrossAppThemes() {
        for (int theme : new int[]{R.style.AppTheme_Main, R.style.AppTheme_Main_Dark, R.style.AppTheme_Main_Black}) {
            TestReader reader = reader(false); reader.setTheme(theme);
            RangeDownloadDialog.show(reader, info, new DownloadManager(context));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText input = findInput(dialog.getWindow().getDecorView()); assertNotNull(input);
            assertEquals(androidx.appcompat.widget.AppCompatEditText.class, input.getClass());
            assertTrue(input.getParent() instanceof android.widget.LinearLayout);
            assertEquals(reader.getString(R.string.range_download_input_hint), input.getHint().toString());
            input.setText("");
            input.requestFocus();
            assertTrue(input.getText().toString().isEmpty());
            input.setText("0,40"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(input.getError());
            input.setText("1-30");
            assertNull(input.getError());
            assertEquals("1-30", input.getText().toString());
            input.setText("");
            assertEquals(reader.getString(R.string.range_download_input_hint), input.getHint().toString());
            dialog.dismiss();
        }
    }
    @Test public void fullDownloadActionIsAboveTheThreeOriginalRemovalActionsOnlyForRanges() {
        TestReader reader = reader(false);
        DownloadRemovalDialog.show(reader, info, new DownloadManager(context));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        View full = dialog.findViewById(R.id.download_full_gallery); assertNotNull(full);
        assertEquals(0, ((ViewGroup) full.getParent()).indexOfChild(full));
        full.performClick(); assertTrue(changed); assertNull(submitted);
        store.remove(info.gid);
        DownloadRemovalDialog.show(reader, info, new DownloadManager(context));
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNull(dialog.findViewById(R.id.download_full_gallery));
        View first = dialog.findViewById(R.id.download_remove_record);
        assertEquals(3, ((ViewGroup) first.getParent()).getChildCount());
    }
    private EditText findInput(View view) {
        if (view instanceof EditText input) return input;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText input = findInput(group.getChildAt(i)); if (input != null) return input;
        }
        return null;
    }
    public static class TestReader extends GalleryActivity {
        Intent switched;
        @Override void restartReaderForMode(Intent intent) { switched = intent; }
    }
    @Implements(EhApplication.class) public static class App {
        @Implementation protected static ExecutorService getExecutorService(Context context) { return executor; }
    }
    @Implements(SpiderDen.class) public static class Directory {
        @Implementation protected static UniFile getGalleryDownloadDir(GalleryInfo gallery) { return directory; }
        @Implementation protected static UniFile getExistingGalleryDownloadDir(GalleryInfo gallery) { return directory; }
    }
    @Implements(DownloadManager.class) public static class Downloads {
        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected DownloadInfo getDownloadInfo(long gid) { return info; }
        @Implementation protected void changePageRange(GalleryInfo gallery, GalleryPageRange range, java.util.function.Consumer<String> error) {
            changed = true; submitted = range;
        }
    }
}
