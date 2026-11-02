package com.hippo.ehviewer.widget;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.ContextThemeWrapper;
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
public class SearchCursorTest {
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        controller = Robolectric.buildActivity(Activity.class);
        activity = controller.get();
        activity.setTheme(R.style.AppTheme_Main);
        controller.setup().visible();
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void cursorUsesTheSameNativeDrawableAndColorAsAnOrdinaryThemedInput() {
        for (int theme : new int[]{R.style.AppTheme_Main, R.style.AppTheme_Main_Dark}) {
            Context context = new ContextThemeWrapper(activity, theme);
            SearchBar bar = new SearchBar(context);
            SearchEditText search = bar.findViewById(R.id.search_edit_text);
            androidx.appcompat.widget.AppCompatEditText plain = new androidx.appcompat.widget.AppCompatEditText(context);
            Drawable cursor = nativeCursor(search);
            Drawable ordinary = nativeCursor(plain);
            assertEquals(ordinary.getClass(), cursor.getClass());
            assertEquals(color(ordinary), color(cursor));
            assertNotNull(cursor.getConstantState());
        }
    }

    @Test public void everyLineKeepsTheOrdinaryNativeCursorHeight() {
        SearchBar bar = attachedBar();
        SearchEditText input = bar.findViewById(R.id.search_edit_text);
        bar.setText("a:alice f:bravo o:charlie p:delta a:echo g:foxtrot o:golf a:hotel");
        measure(bar);
        assertTrue(input.getLineCount() >= 4);
        androidx.appcompat.widget.AppCompatEditText plain = new androidx.appcompat.widget.AppCompatEditText(activity);
        plain.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, input.getTextSize());
        plain.setIncludeFontPadding(false);
        if (android.os.Build.VERSION.SDK_INT >= 35) plain.setLocalePreferredLineHeightForMinimumUsed(false);
        plain.setText("normal");
        plain.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int fontHeight = plain.getLayout().getHeight();
        android.graphics.Rect padding = new android.graphics.Rect();
        for (int i = 0; i < input.getLineCount(); i++) {
            input.setSelection(input.getLayout().getLineStart(i));
            Object editor = org.robolectric.util.ReflectionHelpers.getField(input, "mEditor");
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(editor, "updateCursorPosition");
            Drawable cursor = org.robolectric.util.ReflectionHelpers.getField(editor, "mDrawableForCursor");
            cursor.getPadding(padding);
            assertEquals("Line " + i, fontHeight,
                    cursor.getBounds().height() - padding.top - padding.bottom);
        }
    }

    @Test public void rapidCursorBoundsAndStateChangesDoNotShareDrawableBounds() {
        Drawable original = nativeCursor(new SearchBar(activity).findViewById(R.id.search_edit_text));
        assertNotNull(original.getConstantState());
        Drawable other = original.getConstantState().newDrawable(activity.getResources(), activity.getTheme()).mutate();
        for (int i = 0; i < 200; i++) {
            original.setState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused});
            original.setBounds(0, i, original.getIntrinsicWidth(), i + (i % 2 == 0 ? 40 : 120));
            other.setState(new int[]{android.R.attr.state_enabled});
            other.setBounds(8, 0, 8 + other.getIntrinsicWidth(), 48);
            assertEquals(i, original.getBounds().top);
            assertEquals(8, other.getBounds().left);
            original.mutate();
            color(original);
            color(other);
        }
    }

    @Test public void rapidSelectionFocusAndSearchModeChangesRemainDrawable() {
        SearchBar bar = new SearchBar(activity);
        bar.setHelper(new SearchBar.Helper() {
            public void onClickTitle() {}
            public void onClickLeftIcon() {}
            public void onClickRightIcon() {}
            public void onSearchEditTextClick() {}
            public void onSearchEditTextBackPressed() {}
            public void onApplySearch(String query) {}
        });
        FrameLayout parent = new FrameLayout(activity);
        parent.addView(bar, new FrameLayout.LayoutParams(300, -2));
        activity.setContentView(parent);
        bar.setState(SearchBar.STATE_SEARCH, false);
        SearchEditText input = bar.findViewById(R.id.search_edit_text);
        Bitmap image = Bitmap.createBitmap(300, 240, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        String[] queries = {"", "plain text", "a:alice f:bravo o:charlie p:delta a:echo g:foxtrot o:golf",
                "a:alice plain text"};
        for (int i = 0; i < 48; i++) {
            bar.setText(queries[i % queries.length]);
            input.setSelection(i % 2 == 0 ? 0 : input.length());
            if (i % 3 == 0) input.clearFocus(); else input.requestFocus();
            if (i % 4 == 0) {
                Settings.putBoolean(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, i % 8 == 0);
                bar.setIsComeFromDownload(i % 8 != 0);
            }
            bar.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.AT_MOST));
            bar.layout(0, 0, 300, bar.getMeasuredHeight());
            bar.draw(canvas);
            for (int frame = 0; frame < 2; frame++) {
                org.robolectric.shadows.ShadowLooper main = Shadows.shadowOf(Looper.getMainLooper());
                if (main.isIdle()) break;
                main.runOneTask();
            }
        }
        image.recycle();
    }

    @Test public void visibleChipRowsKeepTheirFullBackgroundAndTwoPixelBottomPadding() throws Exception {
        SearchBar bar = attachedBar();
        SearchEditText input = bar.findViewById(R.id.search_edit_text);
        String term = "artist:\"a long artist name that stays inside one chip$\"";
        bar.setText(String.join(" ", java.util.Collections.nCopies(8, term)));
        input.setCursorVisible(false);
        input.setSelection(0);
        measure(bar);
        assertEquals("Each long chip must occupy one row without blank rows", 8, input.getLineCount());
        for (int end = 0; end < 2; end++) {
            if (end == 1) {
                input.setSelection(input.length());
                measure(bar);
                int bottomScroll = input.getLayout().getHeight()
                        - (input.getHeight() - input.getCompoundPaddingTop() - input.getCompoundPaddingBottom());
                input.scrollTo(0, bottomScroll);
                assertTrue(input.getScrollY() > 0);
            } else input.scrollTo(0, 0);
            Bitmap image = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
            // Draw through the real parent so ViewGroup applies the child's scroll transform.
            Canvas viewport = new Canvas(image);
            viewport.drawColor(Color.WHITE);
            input.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            ((View) input.getParent()).draw(viewport);
            int bottom = image.getHeight() - 1;
            outer: for (; bottom >= 0; bottom--) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (image.getPixel(x, bottom) != Color.WHITE) break outer;
                }
            }
            java.io.File root = new java.io.File(System.getProperty("user.dir"));
            if (root.getName().equals("app")) root = root.getParentFile();
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(root,
                    "artifacts/search-tag-chips/native-cursor-viewport-" + end + "-" + android.os.Build.VERSION.SDK_INT + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            assertEquals("Viewport bottom padding", 2, image.getHeight() - bottom - 1);
            image.recycle();
        }
    }

    private Drawable nativeCursor(android.widget.TextView input) {
        if (android.os.Build.VERSION.SDK_INT >= 29) return input.getTextCursorDrawable();
        int resource = org.robolectric.util.ReflectionHelpers.getField(input, "mCursorDrawableRes");
        return input.getContext().getDrawable(resource);
    }

    private SearchBar attachedBar() {
        SearchBar bar = new SearchBar(activity);
        bar.setHelper(new SearchBar.Helper() {
            public void onClickTitle() {}
            public void onClickLeftIcon() {}
            public void onClickRightIcon() {}
            public void onSearchEditTextClick() {}
            public void onSearchEditTextBackPressed() {}
            public void onApplySearch(String query) {}
        });
        FrameLayout parent = new FrameLayout(activity);
        parent.addView(bar, new FrameLayout.LayoutParams(300, -2));
        activity.setContentView(parent);
        bar.setState(SearchBar.STATE_SEARCH, false);
        return bar;
    }

    private void measure(SearchBar bar) {
        for (int i = 0; i < 6; i++) {
            bar.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.AT_MOST));
            bar.layout(0, 0, 300, bar.getMeasuredHeight());
            // Include future frame callbacks while keeping the caret loop bounded.
            for (int frame = 0; frame < 3; frame++) {
                Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
            }
        }
    }

    private int color(Drawable drawable) {
        int width = Math.max(1, drawable.getIntrinsicWidth());
        drawable.setBounds(0, 0, width, 48);
        Bitmap image = Bitmap.createBitmap(width, 48, Bitmap.Config.ARGB_8888);
        drawable.draw(new Canvas(image));
        int pixel = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(image.getPixel(x, y)) > Color.alpha(pixel)) pixel = image.getPixel(x, y);
            }
        }
        image.recycle();
        assertTrue(Color.alpha(pixel) > 0);
        return Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel));
    }
}
