package com.hippo.ehviewer.widget;

import android.app.Application;
import android.app.Activity;
import org.robolectric.Robolectric;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.io.FileOutputStream;
import org.robolectric.annotation.GraphicsMode;
import android.view.View;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import static com.hippo.ehviewer.client.SearchPresetQuery.*;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import java.util.List;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN-mdpi")
public class SearchPresetsTest {
    private Context context;
    private Activity activity;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                .putString("app_language", "zh-CN")
                .putString("search_language", "japanese").commit();
        Settings.initialize(app);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Main);
        context = activity;
    }

    @Test public void defaultsUseAppLanguageAndLegacyCustomSlotsMigrateOnlyOnce() {
        assertEquals(List.of("l:\"chinese$\"", "o:\"ai generated$\""), Settings.getSearchPresetTags());
        Settings.putString(Settings.KEY_SEARCH_PRESET_TAGS, null);
        Settings.putString(Settings.KEY_SEARCH_PRESET_TAG_1, "f:stockings");
        assertEquals(List.of("f:stockings", "o:\"ai generated$\""), Settings.getSearchPresetTags());
        Settings.putString(Settings.KEY_SEARCH_PRESET_TAG_1, "a:someone");
        Settings.initialize(context);
        assertEquals("f:stockings", Settings.getSearchPresetTags().get(0));
        Settings.putSearchPresetTags(List.of());
        Settings.initialize(context);
        assertTrue(Settings.getSearchPresetTags().isEmpty());
    }

    @Test public void persistsOrderedUnrestrictedListAndDeduplicatesLegacyAliases() {
        Settings.putString(Settings.KEY_SEARCH_PRESET_TAG_1, "l:chinese");
        Settings.putString(Settings.KEY_SEARCH_PRESET_TAG_2, "language:\"CHINESE$\"");
        assertEquals(List.of("l:chinese"), Settings.getSearchPresetTags());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add("a:artist" + i);
        Settings.putSearchPresetTags(many);
        Settings.initialize(context);
        assertEquals(many, Settings.getSearchPresetTags());
        assertThrows(IllegalArgumentException.class,
                () -> Settings.putSearchPresetTags(List.of("f:tag", "female:tag")));
        assertThrows(IllegalArgumentException.class,
                () -> Settings.putSearchPresetTags(List.of("plain text")));
        assertEquals(many, Settings.getSearchPresetTags());
    }

    @Test public void reflectsQueryAndAddsRemovesOrCyclesWithoutSearchingImmediately() throws Exception {
        SearchLayout layout = newLayout();
        SearchPresetChip language = chip(layout, 0), ai = chip(layout, 1);
        String[] query = {"-language:chinese other:\"ai generated\" f:stockings"};
        listen(layout, query);
        layout.syncPresetTags(query[0]);
        assertEquals(EXCLUDE, language.getState());
        assertEquals(INCLUDE, ai.getState());
        language.performClick();
        assertEquals("other:\"ai generated\" f:stockings", query[0]);
        tapOperator(ai);
        assertEquals("f:stockings -o:\"ai generated$\"", query[0]);
        assertEquals(NORMAL, language.getState());
        assertEquals(EXCLUDE, ai.getState());
        tapOperator(ai);
        assertEquals(OR, ai.getState());
        assertEquals("f:stockings ~o:\"ai generated$\"", query[0]);
        tapOperator(ai);
        assertEquals(INCLUDE, ai.getState());
        language.performClick();
        assertEquals(INCLUDE, language.getState());
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.formatListUrlBuilder(builder, query[0]);
        assertEquals(query[0], builder.getKeyword());
        layout.syncPresetTags("");
        assertEquals(NORMAL, language.getState());
        assertEquals(NORMAL, ai.getState());
    }

    @Test public void searchBarEditsAndPresetActionsStayInSyncIncludingOrAndAliases() {
        SearchLayout layout = newLayout();
        SearchBar bar = new SearchBar(context, null);
        GalleryListScene scene = new GalleryListScene();
        ReflectionHelpers.setField(scene, "mSearchLayout", layout);
        ReflectionHelpers.setField(scene, "mSearchBar", bar);
        bar.setHelper(scene);
        layout.setHelper(scene);
        bar.setText("l:chinese");
        SearchPresetChip language = chip(layout, 0);
        assertEquals(INCLUDE, language.getState());
        tapOperator(language);
        assertEquals("-l:\"chinese$\"", bar.getText());
        bar.setText("~language:chinese unrelated");
        assertEquals(OR, language.getState());
        language.performClick();
        assertEquals("unrelated", bar.getText());
        bar.setText("");
        assertEquals(NORMAL, language.getState());
    }

    @Test public void usesSharedChipLabelRenderingAndChangesRightSignWithQuery() {
        Settings.putSearchPresetTags(List.of("artist:bravo"));
        SearchLayout layout = newLayout();
        SearchPresetChip chip = chip(layout, 0);
        android.text.Spanned text = (android.text.Spanned) chip.getText();
        SearchTagChipSpan span = text.getSpans(0, 1, SearchTagChipSpan.class)[0];
        assertEquals("a: bravo", span.label);
        assertFalse(ReflectionHelpers.getField(span, "active"));
        layout.syncPresetTags("~a:bravo");
        text = (android.text.Spanned) chip.getText();
        span = text.getSpans(0, 1, SearchTagChipSpan.class)[0];
        assertTrue(ReflectionHelpers.getField(span, "active"));
        assertEquals('~', span.operator);
        assertTrue(chip.getContentDescription().toString().contains(context.getString(R.string.search_tag_or)));
    }

    @Test public void longPresetListStaysInOneScrollableRowAndRetainsScrollDuringStateChanges() {
        Settings.putSearchPresetTags(List.of("a:alpha", "f:bravo", "g:charlie", "p:delta", "o:\"long preset label\""));
        SearchLayout layout = newLayout();
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        normal.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        normal.layout(0, 0, 280, normal.getMeasuredHeight());
        HorizontalScrollView scroll = normal.findViewById(R.id.search_preset_row);
        LinearLayout row = normal.findViewById(R.id.search_preset_chips);
        assertEquals(5, row.getChildCount());
        assertTrue(row.getWidth() > scroll.getWidth());
        assertEquals(48, row.getHeight());
        String[] query = {""};
        listen(layout, query);
        long time = android.os.SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        float[] positions = {240, 180, 100, 80};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 30, actions[i], positions[i], 24, 0);
            scroll.dispatchTouchEvent(event);
            event.recycle();
        }
        assertEquals("", query[0]);
        assertTrue(scroll.getScrollX() > 0);
        scroll.scrollTo(row.getWidth() - scroll.getWidth(), 0);
        int offset = scroll.getScrollX();
        assertTrue(offset > 0);
        chip(layout, 4).performClick();
        assertEquals("o:\"long preset label\"", query[0]);
        assertSame(row.getChildAt(4), chip(layout, 4));
        assertEquals(offset, scroll.getScrollX());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void rendersSharedBubblesInLightAndDarkWithDistinctInactiveAndActiveStates() throws Exception {
        Bitmap preview = Bitmap.createBitmap(640, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        for (int row = 0; row < 2; row++) {
            Context themed = new ContextThemeWrapper(activity,
                    row == 0 ? R.style.AppTheme_Main : R.style.AppTheme_Main_Dark);
            canvas.save();
            canvas.translate(0, row * 48);
            android.graphics.Paint background = new android.graphics.Paint();
            background.setColor(row == 0 ? Color.WHITE : Color.rgb(32, 32, 32));
            canvas.drawRect(0, 0, 640, 48, background);
            int x = 0;
            for (int state : new int[]{NORMAL, INCLUDE, EXCLUDE, OR}) {
                SearchPresetChip chip = new SearchPresetChip(themed, "a:bravo", ignored -> {});
                chip.setState(state);
                chip.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
                chip.layout(0, 0, chip.getMeasuredWidth(), 48);
                canvas.save();
                canvas.translate(x, 0);
                chip.draw(canvas);
                canvas.restore();
                assertTrue(chip.getWidth() > 44);
                assertNotEquals(preview.getPixel(x + 30, row * 48 + 10),
                        preview.getPixel(x + 30, row * 48 + 1));
                x += chip.getWidth() + 6;
            }
            canvas.restore();
        }
        File root = new File(System.getProperty("user.dir"));
        if (root.getName().equals("app")) root = root.getParentFile();
        File output = new File(root, "artifacts/search-tag-chips/preset-preview.png");
        assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        preview.recycle();
    }

    private SearchLayout newLayout() {
        SearchLayout layout = new SearchLayout(context, null);
        activity.setContentView(layout);
        layout.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, 280, 1200);
        return layout;
    }

    private static void listen(SearchLayout layout, String[] query) {
        layout.setHelper(new SearchLayout.Helper() {
            public void onSearchPresetQueryChanged(String value) { query[0] = value; }
            public void onChangeSearchMode() {}
            public void onSelectImage() {}
            public void onOpenTagSelector() {}
        });
    }

    private static SearchPresetChip chip(SearchLayout layout, int index) {
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        return (SearchPresetChip) ((LinearLayout) normal.findViewById(R.id.search_preset_chips)).getChildAt(index);
    }

    private static void tapOperator(SearchPresetChip chip) {
        chip.layout(0, 0, 200, 48);
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action, 8, 24, 0);
            chip.onTouchEvent(event);
            event.recycle();
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }
}
