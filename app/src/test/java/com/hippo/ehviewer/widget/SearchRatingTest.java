package com.hippo.ehviewer.widget;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.RatingBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SearchRating;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class SearchRatingTest {
    private Context context;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        context = new ContextThemeWrapper(app, R.style.AppTheme_Main);
    }

    @Test public void validatesRangeAndPrecisionWithoutRoundingInvalidInput() {
        for (String input : new String[]{"0.00", "0.01", "4.50", "4.51", "4.99", "5.00"}) {
            assertEquals(input, SearchRating.format(SearchRating.parse(input)));
        }
        for (String input : new String[]{"", "-0.01", "5.01", "4.501", "NaN", "1e0", "6", "4,50"}) {
            try { SearchRating.parse(input); fail(input); }
            catch (IllegalArgumentException expected) { }
        }
        assertTrue(SearchRating.matches(4.51f, 451));
        assertFalse(SearchRating.matches(4.509f, 451));
        assertFalse(SearchRating.matches(Float.NaN, 451));
    }

    @Test public void inputUpdatesExactStarsInvalidInputStaysOpenAndStateRestores() throws Exception {
        SearchLayout layout = new SearchLayout(context, null);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        TextView value = normal.findViewById(R.id.search_rating_value);
        RatingBar stars = normal.findViewById(R.id.search_rating_stars);
        assertEquals("0.00", value.getText().toString());
        assertTrue((value.getPaintFlags() & android.graphics.Paint.UNDERLINE_TEXT_FLAG) != 0);
        value.performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = findInput(dialog.getWindow().getDecorView());
        input.setText("5.01");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertNotNull(input.getError());
        input.setText("4.99");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals("4.99", value.getText().toString());
        assertEquals(4.99f, stars.getRating(), 0.011f);
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.formatListUrlBuilder(builder, "test");
        assertEquals(499, builder.getMinRatingHundredths());
        assertTrue(builder.build().contains("f_srdd=5"));
        assertFalse(builder.build().contains("f_sh=on"));
        Parcelable state = layout.onSaveInstanceState();
        SearchLayout restored = new SearchLayout(context, null);
        restored.onRestoreInstanceState(state);
        restored.formatListUrlBuilder(builder, "test");
        assertEquals(499, builder.getMinRatingHundredths());
        stars.getOnRatingBarChangeListener().onRatingChanged(stars, 3.26f, true);
        assertEquals("3.50", value.getText().toString());
    }

    @Test public void preservesThresholdAcrossParcelBookmarksAndAllSearchModes() {
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setKeyword("artist:test");
        builder.setMinRatingHundredths(451);
        for (int mode : new int[]{ListUrlBuilder.MODE_NORMAL, ListUrlBuilder.MODE_SUBSCRIPTION,
                ListUrlBuilder.MODE_UPLOADER, ListUrlBuilder.MODE_TAG}) {
            builder.setMode(mode);
            assertTrue(builder.build().contains("f_srdd=4"));
        }
        ListUrlBuilder copy = new ListUrlBuilder();
        copy.set(QuickSearch.quickSearchFromJson(builder.toQuickSearch().toJson()));
        assertEquals(451, copy.getMinRatingHundredths());
        assertTrue(copy.equalsQuickSearch(builder.toQuickSearch()));
        Parcel parcel = Parcel.obtain();
        builder.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        assertEquals(451, ListUrlBuilder.CREATOR.createFromParcel(parcel).getMinRatingHundredths());
        parcel.recycle();
        builder.setMinRatingHundredths(150);
        assertFalse(builder.build().contains("f_srdd"));
        builder.reset();
        assertEquals(0, builder.getMinRatingHundredths());
    }

    @Test public void ratingRowFitsAndTouchSelectsHalfStars() {
        SearchLayout layout = new SearchLayout(context, null);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        normal.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST));
        normal.layout(0, 0, normal.getMeasuredWidth(), normal.getMeasuredHeight());
        RatingBar stars = normal.findViewById(R.id.search_rating_stars);
        TextView value = normal.findViewById(R.id.search_rating_value);
        View row = (View) stars.getParent();
        View preset = normal.findViewById(R.id.search_preset_tag_1).findViewById(R.id.preset_states);
        assertEquals(preset.getHeight(), row.getHeight());
        assertTrue(stars.getWidth() > 0);
        assertTrue(stars.getRight() <= value.getLeft());
        assertTrue(value.getRight() <= row.getWidth());
        float x = stars.getPaddingLeft() + (stars.getWidth() - stars.getPaddingLeft()
                - stars.getPaddingRight()) * 3.26f / 5f;
        for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(0, 100, action,
                    x, stars.getHeight() / 2f, 0);
            stars.onTouchEvent(event);
            event.recycle();
        }
        assertEquals("3.50", value.getText().toString());
        layout.setMinimumRating(451);
        assertEquals(4.51f, stars.getRating(), 0.011f);
    }

    @Test public void subscriptionPlannerRetainsPreciseThresholdAndSeparatesDifferentThresholds() {
        QuickSearch first = new QuickSearch();
        first.mode = ListUrlBuilder.MODE_UPLOADER;
        first.keyword = "Alice";
        first.advanceSearch = first.minRating = first.pageFrom = first.pageTo = -1;
        first.subscribed = true;
        first.minRatingHundredths = 451;
        QuickSearch second = QuickSearch.quickSearchFromJson(first.toJson());
        second.keyword = "Bob";
        second.minRatingHundredths = 499;
        var sources = com.hippo.ehviewer.client.BookmarkSubscriptionPlanner.plan(java.util.List.of(first, second));
        assertEquals(2, sources.size());
        assertTrue(sources.stream().anyMatch(source -> source.createBuilder().getMinRatingHundredths() == 451));
        assertTrue(sources.stream().anyMatch(source -> source.createBuilder().getMinRatingHundredths() == 499));
    }

    @Test public void selectsStrongestSafeSiteOptionAtEveryHundredth() {
        for (int threshold = 0; threshold <= 500; threshold++) {
            int expected = -1;
            for (int option = 2; option <= 5; option++) {
                if (option * 100 - 25 <= threshold) expected = option;
            }
            assertEquals("Threshold " + threshold, expected, SearchRating.serverMinimum(threshold));
        }
        ListUrlBuilder builder = new ListUrlBuilder();
        for (int[] pair : new int[][]{{174, -1}, {175, 2}, {274, 2}, {275, 3},
                {374, 3}, {375, 4}, {474, 4}, {475, 5}, {500, 5}}) {
            builder.setMinRatingHundredths(pair[0]);
            String option = android.net.Uri.parse(builder.build()).getQueryParameter("f_srdd");
            assertEquals(pair[1] == -1 ? null : Integer.toString(pair[1]), option);
        }
    }

    @Test public void ratingOnlyEditKeepsPopularAndMergedFeedsAndNeverAddsSiteRating() throws Exception {
        for (int mode : new int[]{ListUrlBuilder.MODE_WHATS_HOT,
                ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION, ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION}) {
            SearchLayout layout = new SearchLayout(context, null);
            layout.setSourceMode(mode);
            layout.setMinimumRating(480);
            ListUrlBuilder builder = new ListUrlBuilder();
            layout.formatListUrlBuilder(builder, "");
            assertEquals(mode, builder.getMode());
            assertEquals(480, builder.getEffectiveMinRatingHundredths());
            if (builder.build() != null) assertFalse(builder.build().contains("f_sr"));
            layout.setMinimumRating(0);
            layout.formatListUrlBuilder(builder, "");
            assertEquals(mode, builder.getMode());
            layout.formatListUrlBuilder(builder, "artist:test");
            assertEquals(ListUrlBuilder.MODE_NORMAL, builder.getMode());
        }
    }

    @Test public void removingServerRatingPreservesOtherFiltersAndLegacyBoundaries() {
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setKeyword("test");
        builder.setAdvanceSearch(ListUrlBuilder.DEFAULT_ADVANCE);
        builder.setPageFrom(20);
        builder.setMinRatingHundredths(480);
        String url = builder.buildWithoutRatingFilter();
        assertFalse(url.contains("f_sr"));
        assertTrue(url.contains("f_spf=20"));
        assertEquals(480, builder.getMinRatingHundredths());
        builder.clearRatingOverride();
        builder.setMinRating(4);
        assertEquals(375, builder.getEffectiveMinRatingHundredths());
        assertFalse(builder.buildWithoutRatingFilter().contains("f_sr"));
    }

    @Test public void persistentRatingIsAnOverridableDefaultInEveryFeed() throws Exception {
        Settings.putPersistentMinRatingHundredths(400);
        for (int mode : new int[]{ListUrlBuilder.MODE_NORMAL, ListUrlBuilder.MODE_SUBSCRIPTION,
                ListUrlBuilder.MODE_TAG, ListUrlBuilder.MODE_UPLOADER, ListUrlBuilder.MODE_WHATS_HOT,
                ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION, ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION}) {
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.setMode(mode);
            assertEquals(400, builder.getEffectiveMinRatingHundredths());
            assertFalse(builder.hasRatingOverride());
            if (mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_SUBSCRIPTION) {
                assertTrue(builder.build().contains("f_srdd=4"));
            }
            builder.setMinRatingHundredths(350);
            assertEquals(350, builder.getEffectiveMinRatingHundredths());
            builder.setMinRatingHundredths(0);
            assertEquals(0, builder.getEffectiveMinRatingHundredths());
            ListUrlBuilder copy = new ListUrlBuilder();
            copy.set(QuickSearch.quickSearchFromJson(builder.toQuickSearch().toJson()));
            assertTrue(copy.hasRatingOverride());
            assertEquals(0, copy.getEffectiveMinRatingHundredths());
            Parcel parcel = Parcel.obtain();
            builder.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            assertEquals(0, ListUrlBuilder.CREATOR.createFromParcel(parcel).getEffectiveMinRatingHundredths());
            parcel.recycle();
            builder.clearRatingOverride();
            copy.set(builder.toQuickSearch());
            assertFalse(copy.hasRatingOverride());
            assertEquals(400, copy.getEffectiveMinRatingHundredths());
        }
        assertEquals(400, Settings.getConfiguredPersistentMinRatingHundredths());
        SearchLayout layout = new SearchLayout(context, null);
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        assertEquals("4.00", ((TextView) normal.findViewById(R.id.search_rating_value)).getText().toString());
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.onRestoreInstanceState(layout.onSaveInstanceState());
        layout.formatListUrlBuilder(builder, "test");
        assertFalse(builder.hasRatingOverride());
        assertEquals(400, builder.getEffectiveMinRatingHundredths());
        layout.setMinimumRating(0);
        layout.formatListUrlBuilder(builder, "test");
        assertTrue(builder.hasRatingOverride());
        assertEquals(0, builder.getEffectiveMinRatingHundredths());
        assertFalse(builder.build().contains("f_sr"));
        assertEquals(400, new ListUrlBuilder().getEffectiveMinRatingHundredths());
    }

    @Test public void turningPrecisionOffRestoresNativeRowAndItsValue() throws Exception {
        Settings.putPersistentMinRatingHundredths(400);
        SearchLayout layout = new SearchLayout(context, null);
        AdvanceSearchTable table = ReflectionHelpers.getField(layout, "mTableAdvanceSearch");
        View nativeRow = (View) table.findViewById(R.id.minimum_rating).getParent();
        table.setMinRating(5);
        assertEquals(View.GONE, nativeRow.getVisibility());
        View normal = ReflectionHelpers.getField(layout, "mNormalView");
        ((androidx.appcompat.widget.SwitchCompat) normal.findViewById(R.id.search_enable_advance)).setChecked(true);
        Settings.putBoolean(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, false);
        layout.syncPresetTags("");
        assertEquals(View.VISIBLE, nativeRow.getVisibility());
        assertEquals(5, table.getMinRating());
        ListUrlBuilder builder = new ListUrlBuilder();
        layout.formatListUrlBuilder(builder, "test");
        assertTrue(builder.build().contains("f_srdd=5"));
        assertEquals(0, builder.getEffectiveMinRatingHundredths());
        Settings.putBoolean(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, true);
        layout.syncPresetTags("");
        assertEquals(View.GONE, nativeRow.getVisibility());
        layout.formatListUrlBuilder(builder, "test");
        assertTrue(builder.build().contains("f_srdd=4"));
        Settings.putBoolean(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, false);
        layout.syncPresetTags("");
        assertEquals(View.VISIBLE, nativeRow.getVisibility());
        assertEquals(5, table.getMinRating());
    }

    private EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText input = findInput(group.getChildAt(i));
                if (input != null) return input;
            }
        }
        return null;
    }
}
