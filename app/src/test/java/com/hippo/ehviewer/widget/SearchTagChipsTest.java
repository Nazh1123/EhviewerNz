package com.hippo.ehviewer.widget;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.SearchPresetQuery;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;

import okio.Buffer;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN-mdpi")
public class SearchTagChipsTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private SearchEditText edit;

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        ReflectionHelpers.setStaticField(EhTagDatabase.class, "instance", null);
        controller = Robolectric.buildActivity(Activity.class);
        activity = controller.get();
        activity.setTheme(R.style.AppTheme_Main);
        controller.setup().visible();
        edit = new SearchEditText(activity);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setTextDirection(View.TEXT_DIRECTION_LTR);
        FrameLayout parent = new FrameLayout(activity);
        parent.addView(edit, new FrameLayout.LayoutParams(420, 48));
        activity.setContentView(parent);
        edit.requestFocus();
        layout();
    }

    @After public void tearDown() {
        controller.pause().stop().destroy();
        ReflectionHelpers.setStaticField(EhTagDatabase.class, "instance", null);
    }

    private void layout() {
        edit.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        edit.layout(0, 0, 420, 48);
        shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private SearchTagChipSpan[] chips() {
        return edit.getText().getSpans(0, edit.length(), SearchTagChipSpan.class);
    }

    private void tap(int chipIndex, int zone) {
        layout();
        SearchTagChipSpan chip = chips()[chipIndex];
        int start = edit.getText().getSpanStart(chip);
        float x = edit.getLayout().getPrimaryHorizontal(start) + edit.getTotalPaddingLeft() - edit.getScrollX();
        float width = chip.getSize(edit.getPaint(), edit.getText(), 0, 0, null);
        x += zone == 0 ? chip.actionWidth() / 2 : zone == 2 ? width - chip.actionWidth() / 2 : width / 2;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action, x, 24, 0);
            assertTrue(edit.onTouchEvent(event));
            event.recycle();
        }
    }

    @Test public void historyDisplaysChipsWithoutChangingRawQueryAndSupportsAllThreeModes() {
        String raw = "hello l:\"chinese$\" title:\"a b\"";
        edit.setQueryText(raw);
        assertEquals(raw, edit.getText().toString());
        assertEquals(1, chips().length);
        tap(0, 0);
        assertEquals("hello -l:\"chinese$\" title:\"a b\"", edit.getText().toString());
        tap(0, 0);
        assertEquals("hello ~l:\"chinese$\" title:\"a b\"", edit.getText().toString());
        tap(0, 0);
        assertEquals(raw, edit.getText().toString());
        tap(0, 2);
        assertEquals("hello title:\"a b\"", edit.getText().toString());
    }

    @Test public void editingStaysExpandedUntilCaretLeavesAndPreservesTheExactSuffix() {
        edit.setQueryText("l:\"chinese$\" other:color");
        tap(0, 1);
        assertEquals(1, chips().length);
        int caret = edit.getSelectionEnd();
        edit.getText().insert(caret - 2, "_test");
        edit.setSelection(caret + 5);
        layout();
        assertEquals(1, chips().length);
        edit.setSelection(edit.length());
        layout();
        assertEquals(2, chips().length);
        assertEquals("l:\"chinese_test$\" other:color", edit.getText().toString());
    }

    @Test public void imeCompositionIsNeverConvertedAndBackspaceExpandsBeforeDeleting() {
        edit.setQueryText("");
        InputConnection connection = edit.onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        connection.setComposingText("l:chinese", 1);
        assertEquals(0, chips().length);
        connection.finishComposingText();
        assertEquals(0, chips().length);
        connection.commitText(" ", 1);
        layout();
        assertEquals(1, chips().length);
        edit.setSelection("l:chinese".length());
        assertTrue(connection.deleteSurroundingText(1, 0));
        assertEquals("l:chinese ", edit.getText().toString());
        assertEquals(0, chips().length);
        connection.deleteSurroundingText(1, 0);
        assertEquals("l:chines ", edit.getText().toString());
    }

    @Test public void hardwareDeleteAndCrossChipSelectionsUseWholeTags() {
        edit.setQueryText("hello -l:chinese tail");
        edit.setSelection("hello -l:chinese".length());
        edit.onKeyDown(KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        assertEquals("hello -l:chinese tail", edit.getText().toString());
        assertEquals(0, chips().length);
        edit.setQueryText("hello -l:chinese tail");
        edit.setSelection(2, 10);
        assertEquals(2, edit.getSelectionStart());
        assertEquals("hello -l:chinese".length(), edit.getSelectionEnd());
        edit.getText().delete(edit.getSelectionStart(), edit.getSelectionEnd());
        assertEquals("he tail", edit.getText().toString());
    }

    @Test public void suggestionsReplaceTheMiddleTermAndKeepItsOperator() {
        edit.setQueryText("before -l:chi other:color");
        tap(0, 1);
        edit.setSelection("before -l:chi".length());
        assertEquals("language:chi", edit.getSuggestionKeyword());
        edit.replaceSuggestion("l:\"chinese$\"");
        assertEquals("before -l:\"chinese$\" other:color", edit.getText().toString());
        assertEquals(2, chips().length);
    }

    @Test public void translationSettingRefreshesDisplayAndFallsBackWithoutChangingQuery() throws Exception {
        installTranslations();
        assertTrue(EhTagDatabase.isPossible(activity));
        edit.setQueryText("l:\"chinese$\" other:unknown");
        assertEquals("语言:中文", chips()[0].label);
        assertEquals("other:unknown", chips()[1].label);
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        layout();
        assertEquals("language:chinese", chips()[0].label);
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        layout();
        assertEquals("语言:中文", chips()[0].label);
        assertEquals("l:\"chinese$\" other:unknown", edit.getText().toString());
        edit.setQueryText("LANGUAGE:Chinese$ other:UnknownArtist");
        assertEquals("语言:中文", chips()[0].label);
        assertEquals("other:UnknownArtist", chips()[1].label);
    }

    private void installTranslations() throws Exception {
        String data = "l:chinese\r" + encode("中文") + "\n"
                + "n:language\r" + encode("语言") + "\n";
        Buffer source = new Buffer();
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        source.writeInt(bytes.length).write(bytes);
        ReflectionHelpers.setStaticField(EhTagDatabase.class, "instance", new EhTagDatabase("chips-test", source));
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void copyPasteUsesRawTextAndLocalSearchCanDisableChips() {
        String query = "l:\"chinese$\" ~other:color";
        edit.setQueryText(query);
        edit.selectAll();
        assertTrue(edit.onTextContextMenuItem(android.R.id.copy));
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(query, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        edit.setQueryText("");
        clipboard.setPrimaryClip(ClipData.newPlainText("query", query));
        edit.onTextContextMenuItem(android.R.id.paste);
        layout();
        assertEquals(query, edit.getText().toString());
        assertEquals(2, chips().length);
        edit.setTagChipsEnabled(false);
        assertEquals(0, chips().length);
        assertEquals(query, edit.getText().toString());
    }

    @Test public void searchBarCallbacksCannotInvalidateTheTappedChipAndSubmissionUsesRawText() {
        SearchBar bar = new SearchBar(activity);
        SearchEditText input = bar.findViewById(R.id.search_edit_text);
        String[] submitted = {null};
        bar.setHelper(new SearchBar.Helper() {
            public void onClickTitle() {}
            public void onClickLeftIcon() {}
            public void onClickRightIcon() {}
            public void onSearchEditTextClick() { input.refreshTagChips(false); }
            public void onSearchEditTextBackPressed() {}
            public void onApplySearch(String query) { submitted[0] = query; }
        });
        bar.setText("l:chinese");
        assertTrue(input.performAccessibilityAction(0x02000000, null));
        assertEquals("-l:chinese", bar.getText());
        bar.applySearch(false);
        assertEquals("-l:chinese", submitted[0]);
        assertEquals(SearchPresetQuery.EXCLUDE, SearchPresetQuery.getState(submitted[0], "l:chinese"));
        AccessibilityNodeInfo info = input.createAccessibilityNodeInfo();
        assertTrue(info.getActionList().stream().anyMatch(a -> a.getId() == 0x02000002));
        bar.setIsComeFromDownload(true);
        assertEquals(0, input.getText().getSpans(0, input.length(), SearchTagChipSpan.class).length);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void draggingAChipDoesNotToggleOrDeleteItAndScrolledHitTestingStillWorks() {
        String raw = "l:chinese -other:color ~artist:alice";
        edit.setQueryText(raw);
        layout();
        SearchTagChipSpan chip = chips()[0];
        float x = edit.getLayout().getPrimaryHorizontal(0) + edit.getTotalPaddingLeft()
                + chip.getSize(edit.getPaint(), edit.getText(), 0, 0, null) / 2f;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action,
                    action == MotionEvent.ACTION_DOWN ? x : x - 60, 24, 0);
            edit.onTouchEvent(event);
            event.recycle();
        }
        assertEquals(raw, edit.getText().toString());
        assertEquals(3, chips().length);
        assertTrue("Line width=" + edit.getLayout().getLineWidth(0) + ", viewport=" + edit.getWidth(),
                edit.getScrollX() > 0);
        edit.scrollTo(80, 0);
        tap(1, 0);
        assertEquals("l:chinese ~other:color ~artist:alice", edit.getText().toString());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void rendersLightAndDarkSearchBarsAndEllipsizesLongLabels() throws Exception {
        installTranslations();
        Bitmap preview = Bitmap.createBitmap(640, 48 * 4, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        int row = 0;
        for (int theme : new int[]{R.style.AppTheme_Main, R.style.AppTheme_Main_Dark}) {
            Context context = new ContextThemeWrapper(activity, theme);
            for (String query : new String[]{"l:chinese -l:chinese ~l:chinese",
                    "artist:\"a very long artist name that must stay inside one compact chip$\""}) {
                SearchBar bar = new SearchBar(context);
                SearchEditText input = bar.findViewById(R.id.search_edit_text);
                bar.setState(SearchBar.STATE_SEARCH, false);
                bar.setText(query);
                bar.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
                bar.layout(0, 0, 640, 48);
                input.refreshTagChips(true);
                bar.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
                bar.layout(0, 0, 640, 48);
                SearchTagChipSpan span = input.getText().getSpans(0, input.length(), SearchTagChipSpan.class)[0];
                assertTrue(span.getSize(input.getPaint(), input.getText(), 0, 0, null) <= 280);
                assertEquals(48, bar.getMeasuredHeight());
                canvas.save();
                canvas.translate(0, row++ * 48);
                bar.draw(canvas);
                canvas.restore();
                assertNotEquals(preview.getPixel(0, (row - 1) * 48 + 12),
                        preview.getPixel(64, (row - 1) * 48 + 12));
            }
        }
        File root = new File(System.getProperty("user.dir"));
        if (root.getName().equals("app")) root = root.getParentFile();
        File output = new File(root, "artifacts/search-tag-chips/preview.png");
        assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        preview.recycle();
    }
}
