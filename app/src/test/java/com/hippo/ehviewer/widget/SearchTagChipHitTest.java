package com.hippo.ehviewer.widget;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {28, 36}, qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SearchTagChipHitTest {
    private ActivityController<Activity> controller;
    private SearchBar bar;
    private SearchEditText input;
    private float density;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme_Main);
        controller.setup().visible();
        density = activity.getResources().getDisplayMetrics().density;
        bar = new SearchBar(activity);
        bar.setHelper(new SearchBar.Helper() {
            public void onClickTitle() {}
            public void onClickLeftIcon() {}
            public void onClickRightIcon() {}
            public void onSearchEditTextClick() {}
            public void onSearchEditTextBackPressed() {}
            public void onApplySearch(String query) {}
        });
        input = bar.findViewById(R.id.search_edit_text);
        FrameLayout parent = new FrameLayout(activity);
        parent.addView(bar, new FrameLayout.LayoutParams(Math.round(280 * density), -2));
        activity.setContentView(parent);
        bar.setState(SearchBar.STATE_SEARCH, false);
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    private void showQuery(String query) {
        bar.setText(query);
        input.setSelection(0);
        int width = Math.round(280 * density);
        for (int i = 0; i < 6; i++) {
            bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(1000 * density), View.MeasureSpec.AT_MOST));
            bar.layout(0, 0, width, bar.getMeasuredHeight());
            // Bound native TextView caret frame callbacks in the synthetic window.
            for (int j = 0; j < 3; j++) Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
        }
        input.scrollTo(0, 0);
    }

    private SearchTagChipSpan[] chips() {
        return input.getText().getSpans(0, input.length(), SearchTagChipSpan.class);
    }

    private float centerY(SearchTagChipSpan chip) {
        int line = input.getLayout().getLineForOffset(input.getText().getSpanStart(chip));
        return Math.round(input.getLayout().getLineBaseline(line)
                + (input.getPaint().ascent() + input.getPaint().descent()) / 2)
                + input.getTotalPaddingTop() - input.getScrollY();
    }

    private void tap(SearchTagChipSpan chip, int zone, float offsetDp) {
        int start = input.getText().getSpanStart(chip);
        float width = chip.getSize(input.getPaint(), input.getText(), 0, 0, null);
        float x = input.getLayout().getPrimaryHorizontal(start)
                + input.getTotalPaddingLeft() - input.getScrollX()
                + (zone == 0 ? chip.actionWidth() / 2
                : zone == 2 ? width - chip.actionWidth() / 2 : width / 2);
        float y = centerY(chip) + offsetDp * density;
        assertTrue("Tap must lie inside the visible input", x >= 0 && x < input.getWidth()
                && y >= 0 && y < input.getHeight());
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action, x, y, 0);
            assertTrue(input.onTouchEvent(event));
            event.recycle();
        }
    }

    @Test public void singleChipBottomSupportsModeEditingAndDeletionBeyondTheTextLayout() {
        for (int zone = 0; zone < 3; zone++) {
            showQuery("f:aaa");
            SearchTagChipSpan chip = chips()[0];
            assertTrue(centerY(chip) + 12 * density - input.getTotalPaddingTop()
                    > input.getLayout().getHeight());
            tap(chip, zone, 12);
            assertEquals(zone == 0 ? "-f:aaa" : zone == 2 ? "" : "f:aaa", bar.getText());
            if (zone == 1) assertEquals("The label should open tag editing", 0, chips().length);
        }
    }

    @Test public void secondRowTopDeletesThatChipAndPreservesThePreviousRow() {
        showQuery("f:aaa f:bbb f:ccc");
        SearchTagChipSpan chip = chips()[2];
        int line = input.getLayout().getLineForOffset(input.getText().getSpanStart(chip));
        assertEquals(1, line);
        assertTrue(centerY(chip) - 12 * density - input.getTotalPaddingTop()
                < input.getLayout().getLineTop(line));
        tap(chip, 2, -12);
        assertEquals("f:aaa f:bbb", bar.getText());
    }

    @Test public void finalRowBottomRemainsClickableBeyondTheTextLayout() {
        showQuery("f:aaa f:bbb f:ccc");
        tap(chips()[2], 2, 12);
        assertEquals("f:aaa f:bbb", bar.getText());
    }

    @Test public void gapBelowAChipDoesNotCycleItsOperator() {
        String query = "f:aaa f:bbb f:ccc";
        showQuery(query);
        tap(chips()[0], 0, 20);
        assertEquals(query, bar.getText());
    }

    @Test public void scrolledFinalRowBottomStillDeletesTheCorrectTag() {
        String query = "f:aaa f:bbb f:ccc f:ddd f:eee f:fff f:ggg f:hhh f:iii f:jjj f:kkk f:lll";
        showQuery(query);
        assertTrue(input.getLineCount() > input.getMaxLines());
        input.scrollTo(0, input.getLayout().getHeight() + input.getTotalPaddingTop()
                + input.getTotalPaddingBottom() - input.getHeight());
        assertTrue(input.getScrollY() > 0);
        SearchTagChipSpan[] chips = chips();
        tap(chips[chips.length - 1], 2, 12);
        assertEquals(query.substring(0, query.lastIndexOf(' ')), bar.getText());
    }

    @Test @Config(qualifiers = "zh-rCN-hdpi")
    public void higherDensityKeepsTheSameClickableLowerEdge() {
        showQuery("f:aaa");
        tap(chips()[0], 2, 12);
        assertEquals("", bar.getText());
    }
}
