package com.hippo.ehviewer.widget;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.MotionEvent;
import android.widget.TextView;

import static com.hippo.ehviewer.client.SearchPresetQuery.*;

import androidx.preference.PreferenceManager;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN")
public class SearchPresetsTest {
    private Context context;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                .putString("app_language", "zh-CN")
                .putString("search_language", "japanese").commit();
        Settings.initialize(app);
        context = new ContextThemeWrapper(app, R.style.AppTheme_Main);
    }

    @Test public void initializesFromAppLanguageWithoutMigratingOldSetting() {
        assertEquals("l:\"chinese$\"", Settings.getSearchPresetTag1());
        assertEquals("o:\"ai generated$\"", Settings.getSearchPresetTag2());
        Settings.putString(Settings.KEY_SEARCH_PRESET_TAG_1, "f:stockings");
        Settings.initialize(context);
        assertEquals("f:stockings", Settings.getSearchPresetTag1());
    }

    @Test public void reusesLocalizedLanguageNamesAndKeepsCustomText() {
        assertEquals(context.getString(R.string.language_english),
                SearchPresetSwitch.labelFor(context, "l:english"));
        assertEquals("AI 生成", SearchPresetSwitch.labelFor(context, "o:\"ai generated$\""));
        assertEquals("f:\"stockings\"", SearchPresetSwitch.labelFor(context, "f:\"stockings\""));
    }

    @Test public void reflectsQueryAndUpdatesBothButtonsWithoutSearchingImmediately() throws Exception {
        SearchLayout layout = new SearchLayout(context, null);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        SearchPresetSwitch language = normal.findViewById(R.id.search_preset_tag_1);
        SearchPresetSwitch ai = normal.findViewById(R.id.search_preset_tag_2);
        String[] query = {"-language:chinese other:\"ai generated\" f:stockings"};
        layout.setHelper(new SearchLayout.Helper() {
            public void onSearchPresetQueryChanged(String value) { query[0] = value; }
            public void onChangeSearchMode() {}
            public void onSelectImage() {}
            public void onOpenTagSelector() {}
        });
        layout.syncPresetTags(query[0]);
        assertChecked(language, EXCLUDE);
        assertChecked(ai, INCLUDE);
        tap(language, 0);
        assertEquals("other:\"ai generated\" f:stockings", query[0]);
        tap(ai, -1);
        assertEquals("f:stockings -o:\"ai generated$\"", query[0]);
        assertChecked(language, NORMAL);
        assertChecked(ai, EXCLUDE);
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.formatListUrlBuilder(builder, query[0]);
        assertEquals(query[0], builder.getKeyword());
        layout.syncPresetTags("");
        assertChecked(language, NORMAL);
        assertChecked(ai, NORMAL);
    }

    @Test public void searchBarTextChangesAndButtonChangesStayInSync() {
        SearchLayout layout = new SearchLayout(context, null);
        SearchBar bar = new SearchBar(context, null);
        GalleryListScene scene = new GalleryListScene();
        ReflectionHelpers.setField(scene, "mSearchLayout", layout);
        ReflectionHelpers.setField(scene, "mSearchBar", bar);
        bar.setHelper(scene);
        layout.setHelper(scene);
        bar.setText("l:chinese");
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        SearchPresetSwitch language = normal.findViewById(R.id.search_preset_tag_1);
        assertChecked(language, INCLUDE);
        tap(language, -1);
        assertEquals("-l:\"chinese$\"", bar.getText());
        bar.setText("");
        assertChecked(language, NORMAL);
    }

    @Test public void labelMatchesSearchOptionTypographyAndSharesTheSwitchRow() {
        SearchLayout layout = new SearchLayout(context, null);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        SearchPresetSwitch control = normal.findViewById(R.id.search_preset_tag_1);
        TextView label = control.findViewById(R.id.preset_label);
        TextView option = normal.findViewById(R.id.search_normal_search);
        assertEquals(option.getTextSize(), label.getTextSize(), 0.01f);
        assertEquals(option.getTypeface(), label.getTypeface());
        assertEquals(option.getCurrentTextColor(), label.getCurrentTextColor());
        control.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
        control.layout(0, 0, control.getMeasuredWidth(), control.getMeasuredHeight());
        View track = control.findViewById(R.id.preset_states);
        assertTrue(track.getRight() <= label.getLeft());
        assertEquals(label.getTop() + label.getHeight() / 2,
                track.getTop() + track.getHeight() / 2, 1);
    }

    private static void assertChecked(SearchPresetSwitch control, int state) {
        TriStateSwitch track = control.findViewById(R.id.preset_states);
        assertEquals(state, track.getState());
    }

    private static void tap(SearchPresetSwitch control, int state) {
        TriStateSwitch track = control.findViewById(R.id.preset_states);
        track.layout(0, 0, 88, 48);
        float x = 44 + state * 24;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action, x, 24, 0);
            track.onTouchEvent(event);
            event.recycle();
        }
    }
}
