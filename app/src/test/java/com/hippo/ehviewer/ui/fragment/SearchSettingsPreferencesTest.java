package com.hippo.ehviewer.ui.fragment;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.res.XmlResourceParser;
import android.view.ContextThemeWrapper;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.TwoStatePreference;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.widget.SearchLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SearchSettingsPreferencesTest {
    private SearchFragment fragment;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                .putString(Settings.KEY_SEARCH_PRESET_TAG_1, "f:stockings")
                .putString(Settings.KEY_THUMB_SIZE, "105")
                .putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true)
                .putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true).commit();
        Settings.initialize(app);
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        fragment = new SearchFragment();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "search-settings").commitNow();
    }

    @Test public void settingsAreAccessibleOnlyThroughSearchAndKeepExistingValues() throws Exception {
        String[] moved = {Settings.KEY_POPULAR_UPDATES_ONLY, Settings.KEY_SEARCH_PRESET_TAGS, Settings.KEY_THUMB_SIZE, Settings.KEY_PREVIEW_THUMB_SIZE,
                Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, Settings.KEY_SHOW_THUMBNAIL_INFO_TITLE,
                Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, Settings.KEY_SHOW_THUMBNAIL_INFO_HIGHLIGHT,
                Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE, Settings.KEY_GALLERY_LONG_PRESS_QUICK_DOWNLOAD,
                "gallery_preview_immediate_jump"};
        for (String key : moved) {
            assertNotNull(key, fragment.findPreference(key));
            for (int resource : new int[]{R.xml.eh_settings, R.xml.fork_features_settings,
                    R.xml.read_settings, R.xml.download_settings, R.xml.history_settings,
                    R.xml.advanced_settings, R.xml.subscription_settings, R.xml.privacy_settings}) {
                assertFalse(key, keys(resource).contains(key));
            }
        }
        assertEquals("f:stockings", Settings.getSearchPresetTags().get(0));
        assertNull(fragment.findPreference("search_preset_item_0"));
        assertNull(fragment.findPreference("search_preset_add"));
        Preference management = fragment.findPreference(Settings.KEY_SEARCH_PRESET_TAGS);
        assertEquals(SearchPresetsFragment.class.getName(), management.getFragment());
        assertTrue(management.getSummary().toString().contains("2"));
        assertEquals(105, Settings.getThumbSize());
        assertTrue(((TwoStatePreference) fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS)).isChecked());
        try (XmlResourceParser xml = RuntimeEnvironment.getApplication().getResources().getXml(R.xml.settings_headers)) {
            boolean found = false;
            while (xml.next() != XmlResourceParser.END_DOCUMENT) {
                if (xml.getEventType() == XmlResourceParser.START_TAG
                        && SearchFragment.class.getName().equals(xml.getAttributeValue(
                                "http://schemas.android.com/apk/res-auto", "fragment"))) found = true;
            }
            assertTrue(found);
        }
    }

    @Test public void togglesHideRowsImmediatelyAfterReturningAndIgnoreHiddenRating() throws Exception {
        assertTrue(Settings.getEnableSearchPresetTags());
        assertTrue(Settings.getEnablePreciseRatingFilter());
        SearchLayout layout = new SearchLayout(new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme_Main), null);
        layout.setMinimumRating(451);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        View presets = normal.findViewById(R.id.search_preset_row);
        View rating = normal.findViewById(R.id.search_rating_row);
        assertEquals(View.VISIBLE, presets.getVisibility());
        assertEquals(View.VISIBLE, rating.getVisibility());
        change(Settings.KEY_ENABLE_SEARCH_PRESET_TAGS, false);
        layout.syncPresetTags("f:stockings");
        assertEquals(View.GONE, presets.getVisibility());
        assertEquals(View.VISIBLE, rating.getVisibility());
        assertFalse(fragment.findPreference(Settings.KEY_SEARCH_PRESET_TAGS).isEnabled());
        change(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, false);
        layout.syncPresetTags("f:stockings");
        assertEquals(View.GONE, rating.getVisibility());
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.formatListUrlBuilder(builder, "f:stockings");
        assertEquals(0, builder.getMinRatingHundredths());
        assertEquals("f:stockings", builder.getKeyword());
        change(Settings.KEY_ENABLE_SEARCH_PRESET_TAGS, true);
        change(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, true);
        layout.syncPresetTags("f:stockings");
        assertEquals(View.VISIBLE, presets.getVisibility());
        assertEquals(View.VISIBLE, rating.getVisibility());
        layout.formatListUrlBuilder(builder, "f:stockings");
        assertEquals(451, builder.getMinRatingHundredths());
        assertEquals("f:stockings", Settings.getSearchPresetTag1());
    }

    @Test public void movedControlsRetainThumbnailDependencies() {
        change(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, false);
        assertFalse(Settings.getShowThumbnailInfoDetails());
        assertTrue(fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE).isVisible());
        change(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        assertTrue(Settings.getShowThumbnailInfoDetails());
        assertFalse(fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE).isVisible());
    }

    @Test public void persistentRatingFollowsPrecisionSwitchAndValidatesExactInput() throws Exception {
        androidx.preference.Preference rating = fragment.findPreference(Settings.KEY_PERSISTENT_MIN_RATING);
        List<String> order = keys(R.xml.search_settings);
        assertEquals(order.indexOf(Settings.KEY_ENABLE_PRECISE_RATING_FILTER) + 1,
                order.indexOf(Settings.KEY_PERSISTENT_MIN_RATING));
        assertTrue(rating.isEnabled());
        assertTrue(rating.getSummary().toString().contains("0.00"));
        assertTrue(rating.getOnPreferenceClickListener().onPreferenceClick(rating));
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        android.widget.EditText input = findInput(dialog.getWindow().getDecorView());
        for (String invalid : new String[]{"5.01", "4.501", "-1"}) {
            input.setText(invalid);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(input.getError());
            assertEquals(0, Settings.getConfiguredPersistentMinRatingHundredths());
        }
        input.setText("4.00");
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals(400, Settings.getPersistentMinRatingHundredths());
        assertTrue(rating.getSummary().toString().contains("4.00"));
        change(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, false);
        assertFalse(rating.isEnabled());
        assertEquals(0, Settings.getPersistentMinRatingHundredths());
        assertEquals(400, Settings.getConfiguredPersistentMinRatingHundredths());
        change(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, true);
        assertTrue(rating.isEnabled());
        assertEquals(400, Settings.getPersistentMinRatingHundredths());
    }

    @Test public void bubblesSwitchDisablesPresetSubfeatureWithoutDeletingListOrQueries() throws Exception {
        change(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, false);
        assertFalse(fragment.findPreference(Settings.KEY_ENABLE_SEARCH_PRESET_TAGS).isEnabled());
        assertFalse(fragment.findPreference(Settings.KEY_SEARCH_PRESET_TAGS).isEnabled());
        SearchLayout layout = new SearchLayout(new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme_Main), null);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        assertEquals(View.GONE, normal.findViewById(R.id.search_preset_row).getVisibility());
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.formatListUrlBuilder(builder, "-f:stockings");
        assertEquals("-f:stockings", builder.getKeyword());
        change(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, true);
        layout.syncPresetTags(builder.getKeyword());
        assertEquals(View.VISIBLE, normal.findViewById(R.id.search_preset_row).getVisibility());
        assertTrue(fragment.findPreference(Settings.KEY_SEARCH_PRESET_TAGS).isEnabled());
        assertEquals("f:stockings", Settings.getSearchPresetTags().get(0));
    }

    private android.widget.EditText findInput(View view) {
        if (view instanceof android.widget.EditText input) return input;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText input = findInput(group.getChildAt(i));
                if (input != null) return input;
            }
        }
        return null;
    }

    private void change(String key, boolean checked) {
        TwoStatePreference preference = fragment.findPreference(key);
        assertTrue(preference.callChangeListener(checked));
        preference.setChecked(checked);
    }

    private List<String> keys(int resource) throws Exception {
        List<String> keys = new ArrayList<>();
        try (XmlResourceParser xml = RuntimeEnvironment.getApplication().getResources().getXml(resource)) {
            while (xml.next() != XmlResourceParser.END_DOCUMENT) {
                if (xml.getEventType() != XmlResourceParser.START_TAG) continue;
                String key = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "key");
                if (key != null) keys.add(key);
            }
        }
        return keys;
    }
}
