package com.hippo.ehviewer.widget;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.widget.ListView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class SearchGidSuggestionTest {
    private ActivityController<Activity> controller;
    private SearchBar bar;
    private ListView suggestions;
    private SearchDatabase database;
    private String appliedQuery;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme_Main);
        controller.setup();
        database = SearchDatabase.getInstance(activity);
        database.clearQuery();
        bar = new SearchBar(activity);
        activity.setContentView(bar);
        bar.setHelper(new SearchBar.Helper() {
            public void onClickTitle() {}
            public void onClickLeftIcon() {}
            public void onClickRightIcon() {}
            public void onSearchEditTextClick() {}
            public void onSearchEditTextBackPressed() {}
            public void onApplySearch(String query) { appliedQuery = query; }
        });
        suggestions = bar.findViewById(R.id.search_bar_list);
    }

    @After public void tearDown() {
        database.clearQuery();
        controller.pause().stop().destroy();
    }

    @Test public void numericSuggestionPrecedesProviderAndHistory() {
        SearchBar.Suggestion provider = new SearchBar.Suggestion() {
            public CharSequence getText(float size) { return "provider"; }
            public CharSequence getText(TextView view) { return "provider"; }
            public void onClick() {}
            public void onLongClick() {}
        };
        bar.setSuggestionProvider(text -> List.of(provider));
        database.addQuery("123456 history");
        bar.setText("123456");

        assertEquals("gid: 123456", label(0));
        assertSame(provider, suggestions.getAdapter().getItem(1));
        assertEquals("123456 history", label(2));
        assertNull(appliedQuery);
    }

    @Test public void clickingSuggestionImmediatelySubmitsGidAndRecordsHistory() {
        bar.setText("123456");
        suggestions.performItemClick(null, 0, 0);

        assertEquals("gid:123456", appliedQuery);
        assertEquals(appliedQuery, bar.getText());
        SearchEditText input = bar.findViewById(R.id.search_edit_text);
        assertEquals(input.length(), input.getSelectionStart());
        assertArrayEquals(new String[]{appliedQuery}, database.getSuggestions("", 128));
    }

    @Test public void nonNumericInputHasNoGidSuggestionAndClearsPreviousOne() {
        bar.setText("123456");
        assertEquals("gid: 123456", label(0));
        for (String text : new String[]{"", "123abc", "abc123", "12 34", "123 ", " 123",
                "-123", "+123", "12.3", "123\n", "１２３", "gid:123"}) {
            bar.setText(text);
            assertEquals(text, 0, suggestions.getAdapter().getCount());
        }
    }

    @Test public void preservesDigitsWithoutNumericOverflow() {
        for (String digits : new String[]{"0", "00123456", "999999999999999999999999999999"}) {
            bar.setText(digits);
            assertEquals("gid: " + digits, label(0));
            suggestions.performItemClick(null, 0, 0);
            assertEquals("gid:" + digits, appliedQuery);
        }
    }

    @Test public void submittingNumericInputNormallyKeepsKeywordSearch() {
        bar.setText("123456");
        bar.applySearch(false);
        assertEquals("123456", appliedQuery);
    }

    @Test public void downloadSearchKeepsItsLocalSuggestions() {
        bar.setIsComeFromDownload(true);
        database.addQuery("123456 history");
        bar.setText("123456");
        assertEquals(1, suggestions.getAdapter().getCount());
        assertEquals("123456 history", label(0));
    }

    private String label(int position) {
        TextView hint = suggestions.getAdapter().getView(position, null, suggestions)
                .findViewById(R.id.hintView);
        return hint.getText().toString();
    }
}
