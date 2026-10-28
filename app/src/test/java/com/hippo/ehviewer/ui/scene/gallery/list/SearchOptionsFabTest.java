package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.ehviewer.widget.SearchDatabase;
import com.hippo.ehviewer.widget.SearchLayout;
import com.hippo.view.ViewTransition;
import com.hippo.widget.FabLayout;
import com.hippo.widget.SearchBarMover;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import static com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class SearchOptionsFabTest {
    private GalleryListScene scene;
    private SearchBar bar;
    private SearchLayout options;
    private FloatingActionButton toggle;
    private ViewTransition transition;
    private View root;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        Context context = new ContextThemeWrapper(app, R.style.AppTheme_Main);
        root = LayoutInflater.from(context).inflate(R.layout.scene_gallery_list, null);
        scene = new GalleryListScene();
        bar = root.findViewById(R.id.search_bar);
        options = root.findViewById(R.id.search_layout);
        options.setPadding(options.getPaddingLeft(), options.getPaddingTop()
                        + context.getResources().getDimensionPixelSize(R.dimen.gallery_padding_top_search_bar),
                options.getPaddingRight(), options.getPaddingBottom()
                        + context.getResources().getDimensionPixelSize(R.dimen.gallery_padding_bottom_fab));
        toggle = root.findViewById(R.id.search_options_fab);
        FabLayout menu = root.findViewById(R.id.fab_layout);
        menu.setExpanded(false, false);
        transition = new ViewTransition(root.findViewById(R.id.content_layout), options);
        ReflectionHelpers.setField(scene, "mSearchBar", bar);
        ReflectionHelpers.setField(scene, "mSearchLayout", options);
        ReflectionHelpers.setField(scene, "mSearchOptionsFab", toggle);
        ReflectionHelpers.setField(scene, "mSearchFab", root.findViewById(R.id.search_fab));
        ReflectionHelpers.setField(scene, "mFabLayout", menu);
        ReflectionHelpers.setField(scene, "mViewTransition", transition);
        ReflectionHelpers.setField(scene, "mSearchBarMover", new SearchBarMover(scene, bar));
        bar.setHelper(scene);
        options.setHelper(scene);
        toggle.setOnClickListener(scene);
        SearchDatabase.getInstance(context).addQuery("retained keyword suggestion");
        bar.setText("retained keyword");
        setState(STATE_SIMPLE_SEARCH, false);
    }

    @Test public void togglesOptionsAndSuggestionsWithoutChangingQuery() {
        assertSuggestions();
        toggle.performClick();
        settle();
        assertEquals(STATE_SEARCH, state());
        assertEquals(View.VISIBLE, options.getVisibility());
        assertEquals(View.GONE, bar.findViewById(R.id.list_container).getVisibility());
        assertEquals(1f, icon().getProgress(), 0f);
        assertEquals(toggle.getContext().getString(R.string.search_hide_options), toggle.getContentDescription());

        toggle.performClick();
        settle();
        assertEquals(STATE_SEARCH_SHOW_LIST, state());
        assertSuggestions();
        assertEquals(0f, icon().getProgress(), 0f);
        assertEquals(toggle.getContext().getString(R.string.search_more_options), toggle.getContentDescription());
        assertEquals("retained keyword", bar.getText());

        toggle.performClick();
        settle();
        assertEquals(STATE_SEARCH, state());
        assertEquals(1, transition.getShownViewIndex());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void optionsFollowMultilineInputHeightAndReturnWhenItShrinks() {
        setState(STATE_SEARCH, false);
        bar.setText("artist:alice");
        layoutScene();
        int initialPadding = options.getPaddingTop();
        int gap = options.getChildAt(0).getTop() - bar.getBottom();
        assertTrue(gap >= 0);
        int bottomPadding = options.getPaddingBottom();
        bar.setText(longQuery());
        layoutScene();
        View input = bar.findViewById(R.id.search_input_container);
        assertTrue(input.getHeight() > input.getMinimumHeight());
        assertEquals(initialPadding + input.getHeight() - input.getMinimumHeight(), options.getPaddingTop());
        assertEquals(gap, options.getChildAt(0).getTop() - bar.getBottom());
        int expandedPadding = options.getPaddingTop();
        layoutScene();
        assertEquals(expandedPadding, options.getPaddingTop());
        assertEquals(bottomPadding, options.getPaddingBottom());
        bar.setText("");
        layoutScene();
        assertEquals(initialPadding, options.getPaddingTop());
        assertEquals(gap, options.getChildAt(0).getTop() - bar.getBottom());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void suggestionsDoNotAddTheirHeightToTheOptionsInset() {
        setState(STATE_SEARCH, false);
        String query = longQuery();
        bar.setText(query);
        SearchDatabase.getInstance(bar.getContext()).addQuery(query + " extra");
        layoutScene();
        int padding = options.getPaddingTop();
        int height = bar.getHeight();
        setState(STATE_SEARCH_SHOW_LIST, false);
        layoutScene();
        assertTrue(bar.getHeight() > height);
        assertEquals(padding, options.getPaddingTop());
        setState(STATE_SEARCH, false);
        layoutScene();
        assertEquals(padding, options.getPaddingTop());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void resizingPreservesTheScrolledConditionsPositionRelativeToTheInset() {
        setState(STATE_SEARCH, false);
        bar.setText("artist:alice");
        layoutScene(420);
        options.scrollBy(0, 40);
        int initialPadding = options.getPaddingTop();
        int position = options.getChildAdapterPosition(options.getChildAt(0));
        int offset = options.getChildAt(0).getTop() - initialPadding;
        assertTrue(offset < 0);
        bar.setText(longQuery());
        layoutScene(420);
        assertTrue(options.getPaddingTop() > initialPadding);
        assertEquals(position, options.getChildAdapterPosition(options.getChildAt(0)));
        assertEquals(offset, options.getChildAt(0).getTop() - options.getPaddingTop());
    }

    private String longQuery() {
        return String.join(" ", java.util.Collections.nCopies(8,
                "artist:\"a long artist name that takes up a complete row$\""));
    }

    private void layoutScene() {
        layoutScene(1000);
    }

    private void layoutScene(int height) {
        for (int i = 0; i < 4; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 360, height);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        }
    }

    @Test public void preservesSelectedOptionsWhenCollapsedAndReopened() throws Exception {
        toggle.performClick();
        settle();
        ReflectionHelpers.setField(options, "mMinRatingHundredths", 375);
        ReflectionHelpers.setField(options, "mRatingOverride", true);
        toggle.performClick();
        settle();
        // This state keeps the advanced-search submission path in onApplySearch.
        assertEquals(STATE_SEARCH_SHOW_LIST, state());
        ListUrlBuilder builder = new ListUrlBuilder();
        options.formatListUrlBuilder(builder, bar.getText());
        assertEquals(375, builder.getMinRatingHundredths());
        toggle.performClick();
        settle();
        assertEquals(375, (int) ReflectionHelpers.getField(options, "mMinRatingHundredths"));
        assertEquals("retained keyword", bar.getText());
    }

    @Test public void backFromSuggestionsAfterClickingInputExitsSearch() {
        toggle.performClick();
        settle();
        scene.onSearchEditTextClick();
        settle();
        assertSuggestions();
        assertEquals(0f, icon().getProgress(), 0f);
        scene.onBackPressed();
        settle();
        assertSearchExited();
    }

    @Test public void backFromSuggestionsAfterCollapsingOptionsExitsSearch() {
        toggle.performClick();
        settle();
        toggle.performClick();
        settle();
        assertSuggestions();
        scene.onSearchEditTextBackPressed();
        settle();
        assertSearchExited();
    }

    @Test public void collapsingOptionsReturnsFocusToTheSearchInput() {
        toggle.performClick();
        settle();
        // Simulate editing a condition before returning to the keyword input.
        EditText conditionField = new EditText(bar.getContext());
        ((ViewGroup) bar.getParent()).addView(conditionField);
        assertTrue(conditionField.requestFocus());
        assertFalse(bar.findViewById(R.id.search_edit_text).hasFocus());
        toggle.performClick();
        settle();
        assertSuggestions();
        assertTrue(bar.findViewById(R.id.search_edit_text).hasFocus());
    }

    @Test public void rapidTapsAndExitDoNotLeaveAStaleIconOrSuggestions() {
        toggle.performClick();
        toggle.performClick();
        settle();
        assertSuggestions();
        assertEquals(0f, icon().getProgress(), 0f);

        toggle.performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        toggle.performClick();
        toggle.performClick();
        settle();
        assertEquals(STATE_SEARCH, state());
        assertEquals(1f, icon().getProgress(), 0f);

        setState(STATE_NORMAL, false);
        settle();
        assertEquals(0f, icon().getProgress(), 0f);
        assertEquals(View.INVISIBLE, toggle.getVisibility());
        setState(STATE_SIMPLE_SEARCH, false);
        settle();
        assertSuggestions();
    }

    @Test public void restoresBothExpandedAndCollapsedStateWithoutAnimation() {
        setState(STATE_SEARCH, false);
        assertEquals(1f, icon().getProgress(), 0f);
        assertEquals(View.VISIBLE, options.getVisibility());
        setState(STATE_NORMAL, false);
        setState(STATE_SEARCH_SHOW_LIST, false);
        assertSuggestions();
        assertEquals(0f, icon().getProgress(), 0f);
    }

    @Test public void rightIconStillClearsTheQueryWithoutTogglingOptions() {
        toggle.performClick();
        settle();
        scene.onClickRightIcon();
        assertEquals("", bar.getText());
        assertEquals(STATE_SEARCH, state());
        toggle.performClick();
        settle();
        assertSuggestions();
        assertEquals("", bar.getText());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void optionsIconMatchesExecuteSearchColorInBothStatesAndAllThemes() {
        for (int theme : new int[]{R.style.AppTheme, R.style.AppTheme_Dark, R.style.AppTheme_Black}) {
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme);
            View root = LayoutInflater.from(context).inflate(R.layout.scene_gallery_list, null);
            FloatingActionButton button = root.findViewById(R.id.search_options_fab);
            FloatingActionButton execute = root.findViewById(R.id.search_fab);
            int expectedColor = solidIconColor(execute.getDrawable());
            GalleryListScene themedScene = new GalleryListScene();
            ReflectionHelpers.setField(themedScene, "mSearchOptionsFab", button);
            AddDeleteDrawable reference = new AddDeleteDrawable(context, expectedColor);
            for (int state : new int[]{STATE_SIMPLE_SEARCH, STATE_SEARCH}) {
                ReflectionHelpers.setField(themedScene, "mState", state);
                ReflectionHelpers.callInstanceMethod(themedScene, "updateSearchOptionsFab", from(boolean.class, false));
                float progress = state == STATE_SEARCH ? 1f : 0f;
                reference.setProgress(progress);
                Bitmap actual = render(button.getDrawable());
                assertEquals("Icon color for theme " + theme + " and state " + state,
                        expectedColor, solidIconColor(button.getDrawable()));
                assertTrue(actual.sameAs(render(reference)));
            }
        }
    }

    private int solidIconColor(Drawable drawable) {
        Bitmap bitmap = render(drawable);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) == 255) return pixel;
            }
        }
        throw new AssertionError("Icon has no opaque pixels");
    }

    private Bitmap render(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        drawable.setBounds(12, 12, 36, 36);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void assertSearchExited() {
        assertEquals(STATE_NORMAL, state());
        assertEquals(SearchBar.STATE_NORMAL, bar.getState());
        assertEquals(View.GONE, options.getVisibility());
        assertEquals(View.GONE, bar.findViewById(R.id.list_container).getVisibility());
        assertEquals(View.INVISIBLE, toggle.getVisibility());
        assertEquals(0f, icon().getProgress(), 0f);
    }

    private void assertSuggestions() {
        assertEquals(SearchBar.STATE_SEARCH_LIST, bar.getState());
        assertEquals(View.GONE, options.getVisibility());
        assertEquals(0, transition.getShownViewIndex());
        assertEquals(View.VISIBLE, bar.findViewById(R.id.list_container).getVisibility());
        assertTrue(((ListView) bar.findViewById(R.id.search_bar_list)).getCount() > 0);
    }

    private int state() { return ReflectionHelpers.getField(scene, "mState"); }
    private AddDeleteDrawable icon() { return (AddDeleteDrawable) toggle.getDrawable(); }
    private void settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700)); }
    private void setState(int state, boolean animation) {
        ReflectionHelpers.callInstanceMethod(scene, "setState", from(int.class, state), from(boolean.class, animation));
    }
}
