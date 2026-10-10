package com.hippo.ehviewer.ui.scene.history;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.Gravity;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.EhApplication;
import com.hippo.conaco.Conaco;
import com.hippo.lib.image.Image;
import com.hippo.widget.LoadImageView;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN", shadows = {
        ReadingHistoryUiTest.AppShadow.class, ReadingHistoryUiTest.ImageShadow.class})
public class ReadingHistoryUiTest {
    private Context context;
    private SharedPreferences settings;

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        settings = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        settings.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", settings);
        ReadingHistory.clear(context);
    }

    @Test public void sidebarTracksSettingWhileExistingHistoryRemainsVisible() {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).get();
        NavigationView navigation = new NavigationView(context);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        ReflectionHelpers.setField(main, "mNavView", navigation);
        ReflectionHelpers.callInstanceMethod(main, "updateReadingHistoryNavigationItem");
        assertTrue(navigation.getMenu().findItem(R.id.nav_reading_history).isVisible());
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, false).apply();
        ReflectionHelpers.callInstanceMethod(main, "updateReadingHistoryNavigationItem");
        assertFalse(navigation.getMenu().findItem(R.id.nav_reading_history).isVisible());
        assertTrue(navigation.getMenu().findItem(R.id.nav_history).isVisible());
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, true).apply();
        ReflectionHelpers.callInstanceMethod(main, "updateReadingHistoryNavigationItem");
        assertTrue(navigation.getMenu().findItem(R.id.nav_reading_history).isVisible());
    }

    @Test public void preferenceDefaultsOnInRenamedSavingAndHistoryGroupAndPersistsToggle() {
        org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        FragmentActivity host = controller.get();
        host.setTheme(R.style.AppTheme);
        controller.setup();
        Harness preferences = new Harness();
        host.getSupportFragmentManager().beginTransaction().add(preferences, "preferences").commitNow();
        TwoStatePreference toggle = preferences.findPreference(Settings.KEY_RECORD_READING_HISTORY);
        assertNotNull(toggle);
        assertTrue(toggle.isChecked());
        assertEquals("保存与历史", toggle.getParent().getTitle().toString());
        com.hippo.preference.ListPreference limit = preferences.findPreference(Settings.KEY_READING_HISTORY_SIZE);
        assertNotNull(limit);
        assertEquals("阅读历史记录数量", limit.getTitle().toString());
        assertEquals("100", limit.getValue());
        assertEquals("100", limit.getSummary().toString());
        assertArrayEquals(new CharSequence[]{"100", "500", "1000", "5000", "10000", "20000"},
                limit.getEntryValues());
        limit.setValue("500");
        assertFalse(limit.callChangeListener("invalid"));
        assertEquals(500, Settings.getReadingHistorySize());
        assertEquals(100, Settings.getHistoryInfoSize());
        toggle.setChecked(false);
        assertFalse(Settings.isReadingHistoryEnabled());
        toggle.setChecked(true);
        assertTrue(Settings.isReadingHistoryEnabled());
        controller.pause().stop().destroy();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void listShowsSourceAndTimeAndClickStartsReaderWithOriginalSource() throws Exception {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = 21;
        gallery.token = "token21";
        gallery.title = "Reading example";
        gallery.pages = 78;
        gallery.thumb = "https://example.com/cover.jpg";
        ReadingHistory.record(context, new Intent(GalleryActivity.ACTION_EH)
                .putExtra(GalleryActivity.KEY_GALLERY_INFO, gallery)
                .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD));
        ReadingHistory.Entry visit = ReadingHistory.list(context).get(0);
        ReadingHistory.saveProgress(context, visit.createIntent(context), 1, 78, null);
        TestScene scene = new TestScene(context);
        View root = scene.onCreateView3(LayoutInflater.from(context), null, null);
        RecyclerView recycler = root.findViewById(R.id.recycler_view);
        assertTrue(recycler.getLayoutManager() instanceof com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager);
        RecyclerView.Adapter adapter = recycler.getAdapter();
        assertEquals(1, adapter.getItemCount());
        RecyclerView.ViewHolder row = adapter.createViewHolder(recycler, 0);
        adapter.bindViewHolder(row, 0);
        assertTrue(row.itemView instanceof androidx.cardview.widget.CardView);
        assertEquals("Reading example", ((TextView) row.itemView.findViewById(R.id.title)).getText().toString());
        String metadata = ((TextView) row.itemView.findViewById(R.id.reading_metadata)).getText().toString();
        assertEquals("下载 - 2 / 78", metadata);
        TextView time = row.itemView.findViewById(R.id.reading_time);
        assertEquals(new SimpleDateFormat("yyyy-MM-dd hh:mm", Locale.ROOT).format(new Date(visit.readAt)),
                time.getText().toString());
        assertEquals(Gravity.RIGHT, time.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
        assertEquals(1, time.getMaxLines());
        assertEquals(4, ((TextView) row.itemView.findViewById(R.id.title)).getMaxLines());
        assertFooterLayout(row.itemView, "Short title", "short");
        assertFooterLayout(row.itemView, "Long title\nSecond line\nThird line\nFourth line\nFifth line", "long");
        ((TextView) row.itemView.findViewById(R.id.title)).setTextSize(24);
        assertFooterLayout(row.itemView, "Long title\nSecond line\nThird line\nFourth line\nFifth line", "large-text");
        row.itemView.performClick();
        assertNotNull(scene.launched);
        assertEquals(GalleryActivity.class.getName(), scene.launched.getComponent().getClassName());
        assertEquals(ReadingHistory.DOWNLOAD, scene.launched.getStringExtra(ReadingHistory.KEY_SOURCE));
        assertEquals(21, ((GalleryInfo) scene.launched.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO)).gid);
        ReadingHistory.clear(context);
        ReflectionHelpers.callInstanceMethod(scene, "reload");
        assertEquals(0, adapter.getItemCount());
        assertEquals(View.VISIBLE, root.findViewById(R.id.tip).getVisibility());
        assertEquals("进入阅读器的画廊将在这里显示", ((TextView) root.findViewById(R.id.tip)).getText().toString());
    }

    private void assertFooterLayout(View card, String titleText, String name) throws Exception {
        TextView title = card.findViewById(R.id.title);
        title.setText(titleText);
        int width = Math.round(360 * context.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, width, card.getMeasuredHeight());
        TextView source = card.findViewById(R.id.reading_metadata);
        TextView time = card.findViewById(R.id.reading_time);
        LinearLayout footer = (LinearLayout) time.getParent();
        android.view.ViewGroup content = (android.view.ViewGroup) footer.getParent();
        android.graphics.Bitmap preview = android.graphics.Bitmap.createBitmap(
                card.getWidth(), card.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        card.draw(new android.graphics.Canvas(preview));
        java.io.File directory = new java.io.File("build/reading-history-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new java.io.File(directory, name + ".png"))) {
            assertTrue(preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        }
        preview.recycle();
        int bottomMargin = ((android.view.ViewGroup.MarginLayoutParams) footer.getLayoutParams()).bottomMargin;
        assertEquals(content.getHeight() - bottomMargin, footer.getBottom());
        assertTrue(title.getBottom() <= footer.getTop());
        assertEquals(source.getTop(), time.getTop());
        assertTrue(source.getRight() <= time.getLeft());
        assertEquals(footer.getWidth(), time.getRight());
        assertTrue(source.getWidth() > 0);
        assertTrue(time.getWidth() > 0);
        assertEquals(1, time.getLineCount());
        assertEquals(titleText.startsWith("Short") ? 1 : 4, title.getLineCount());
    }

    public static class Harness extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle state, String rootKey) {
            setPreferencesFromResource(R.xml.fork_features_settings, rootKey);
            com.hippo.ehviewer.ui.fragment.ReadingHistoryPreferences.bind(this);
        }
    }

    private static class TestScene extends ReadingHistoryScene {
        private final Context context;
        private final MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        private Intent launched;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public MainActivity getActivity2() { return activity; }
        @Override public void startActivity(Intent intent) { launched = intent; }
    }

    @Implements(EhApplication.class)
    public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static Conaco<Image> getConaco(Context context) { return null; }
    }

    @Implements(LoadImageView.class)
    public static class ImageShadow extends org.robolectric.shadows.ShadowView {
        @RealObject private LoadImageView view;
        String url;
        @Implementation protected void load(String key, String url, boolean network) { this.url = url; }
        @Implementation protected void load(int id) { view.setImageResource(id); }
        @Implementation protected void load(android.graphics.drawable.Drawable drawable) { view.setImageDrawable(drawable); }
        @Implementation protected void unload() { url = null; view.setImageDrawable(null); }
    }
}
