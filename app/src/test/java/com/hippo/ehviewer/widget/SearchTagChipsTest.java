package com.hippo.ehviewer.widget;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.InputType;
import android.text.Layout;
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
        assertEquals("l: 中文", chips()[0].label);
        assertEquals("o: unknown", chips()[1].label);
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        layout();
        assertEquals("l: chinese", chips()[0].label);
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        layout();
        assertEquals("l: 中文", chips()[0].label);
        assertEquals("l:\"chinese$\" other:unknown", edit.getText().toString());
        edit.setQueryText("LANGUAGE:Chinese$ other:UnknownArtist");
        assertEquals("l: 中文", chips()[0].label);
        assertEquals("o: UnknownArtist", chips()[1].label);
    }

    private void installTranslations() throws Exception {
        String data = "a:alice\r" + encode("爱丽丝") + "\n"
                + "l:chinese\r" + encode("中文") + "\n"
                + "n:artist\r" + encode("艺术家") + "\n"
                + "n:language\r" + encode("语言") + "\n";
        Buffer source = new Buffer();
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        source.writeInt(bytes.length).write(bytes);
        ReflectionHelpers.setStaticField(EhTagDatabase.class, "instance", new EhTagDatabase("chips-test", source));
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void namespacePrefixesStayAbbreviatedWithOrWithoutTranslatedValues() throws Exception {
        installTranslations();
        String query = "artist:\"alice$\" LANGUAGE:Chinese$ female:test male:test group:test parody:test"
                + " character:test other:test mixed:test cosplayer:test location:test reclass:test tag:test";
        edit.setQueryText(query);
        assertArrayEquals(new String[]{"a: 爱丽丝", "l: 中文", "f: test", "m: test", "g: test", "p: test",
                        "c: test", "o: test", "x: test", "cos: test", "loc: test", "r: test", "tag: test"},
                java.util.Arrays.stream(chips()).map(chip -> chip.label).toArray(String[]::new));
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        edit.refreshTagChips(true);
        assertEquals("a: alice", chips()[0].label);
        assertEquals("l: Chinese", chips()[1].label);
        assertEquals(query, edit.getText().toString());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void allChipRowsHaveVisibleGapsAndRenderACompactPreview() throws Exception {
        SearchBar bar = attachSearchBar();
        edit = bar.findViewById(R.id.search_edit_text);
        String term = "artist:\"a long artist name that stays inside one chip$\"";
        bar.setText(String.join(" ", java.util.Collections.nCopies(6, term)));
        edit.setSelection(0);
        layoutBar(bar);
        Layout textLayout = edit.getLayout();
        assertTrue(textLayout.getLineCount() >= 4);
        Bitmap rows = Bitmap.createBitmap(edit.getWidth(), textLayout.getHeight()
                + edit.getPaddingTop() + edit.getPaddingBottom(), Bitmap.Config.ARGB_8888);
        Canvas rowCanvas = new Canvas(rows);
        rowCanvas.translate(0, edit.getPaddingTop());
        textLayout.draw(rowCanvas);
        int previousInkBottom = -1;
        for (int line = 0; line < textLayout.getLineCount(); line++) {
            int center = Math.round(edit.getPaddingTop() + textLayout.getLineBaseline(line)
                    + (edit.getPaint().ascent() + edit.getPaint().descent()) / 2);
            assertTrue("Each row must actually render a bubble", rowHasPixels(rows, center));
            int inkTop = Math.max(0, center - 18);
            while (!rowHasPixels(rows, inkTop)) inkTop++;
            int inkBottom = Math.min(rows.getHeight() - 1, center + 18);
            while (!rowHasPixels(rows, inkBottom)) inkBottom--;
            if (line > 0) {
                assertEquals("All row gaps must equal the first gap", 8, inkTop - previousInkBottom - 1);
            }
            previousInkBottom = inkBottom;
        }
        assertEquals(2, rows.getHeight() - previousInkBottom - 1);
        assertEquals(24, Math.round(edit.getPaddingTop() + textLayout.getLineBaseline(0)
                + (edit.getPaint().ascent() + edit.getPaint().descent()) / 2), 1);
        File root = new File(System.getProperty("user.dir"));
        if (root.getName().equals("app")) root = root.getParentFile();
        File output = new File(root, "artifacts/search-tag-chips/compact-lines.png");
        assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(rows.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        rows.recycle();
    }

    private boolean rowHasPixels(Bitmap bitmap, int y) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            if ((bitmap.getPixel(x, y) >>> 24) != 0) return true;
        }
        return false;
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void bothChipDividersAreShortAndSymmetric() {
        SearchTagChipSpan chip = new SearchTagChipSpan("a: bravo", '+', 1,
                android.graphics.Color.BLUE, 200);
        int width = chip.getSize(edit.getPaint(), "x", 0, 1, null);
        Bitmap bitmap = Bitmap.createBitmap(width, 40, Bitmap.Config.ARGB_8888);
        int baseline = Math.round(20 - (edit.getPaint().ascent() + edit.getPaint().descent()) / 2);
        chip.draw(new Canvas(bitmap), "x", 0, 1, 0, 0, baseline, 40, edit.getPaint());
        int leftLine = bitmap.getPixel(17, 15) >>> 24;
        int rightLine = bitmap.getPixel(width - 18, 15) >>> 24;
        assertEquals("The separators must mirror one another", leftLine, rightLine);
        assertTrue("Left separator must be visible", leftLine > (bitmap.getPixel(19, 15) >>> 24));
        assertTrue("Separators should stop well inside the chip", leftLine > (bitmap.getPixel(17, 12) >>> 24));
        bitmap.recycle();
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
    public void multilineSearchGrowsToFourLinesAndScrolledChipsKeepTheirActions() {
        SearchBar bar = attachSearchBar();
        edit = bar.findViewById(R.id.search_edit_text);
        String raw = "artist:alice artist:bravo artist:charlie artist:delta artist:echo artist:foxtrot"
                + " artist:golf artist:hotel artist:india artist:juliet";
        bar.setText("artist:alice");
        layoutBar(bar);
        int singleHeight = bar.getHeight();
        bar.setText(raw);
        edit.setSelection(0);
        layoutBar(bar);
        assertEquals(4, edit.getMaxLines());
        assertTrue(edit.getLineCount() > 4);
        assertTrue(bar.getHeight() > singleHeight);
        assertTrue(edit.getHeight() <= 48 + 40 * 3
                + edit.getTotalPaddingTop() + edit.getTotalPaddingBottom());
        int cappedHeight = bar.getHeight();
        bar.setText(raw + " artist:golf artist:hotel");
        edit.setSelection(0);
        layoutBar(bar);
        assertEquals(cappedHeight, bar.getHeight());
        bar.setText(raw);
        edit.setSelection(0);
        layoutBar(bar);
        edit.scrollTo(0, 0);
        float x = edit.getLayout().getPrimaryHorizontal(0) + edit.getTotalPaddingLeft() + 30;
        float y = edit.getTotalPaddingTop() + edit.getLayout().getLineBottom(0) / 2f;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action,
                    x, action == MotionEvent.ACTION_DOWN ? y : y - 60, 0);
            assertTrue(edit.onTouchEvent(event));
            event.recycle();
        }
        assertEquals(raw, edit.getText().toString());
        layoutBar(bar);
        assertTrue(edit.getScrollY() > 0);
        tapVisibleChip(chips()[3], 0);
        assertEquals(raw.replace("artist:delta", "-artist:delta"), bar.getText());
        layoutBar(bar);
        tapVisibleChip(chips()[3], 2);
        assertEquals(raw.replace("artist:delta ", ""), bar.getText());
        bar.setText("artist:alice");
        layoutBar(bar);
        assertEquals(singleHeight, bar.getHeight());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void settingSwitchesLiveWithoutChangingQueryAndDownloadSearchStaysPlain() {
        assertTrue(Settings.getEnableSearchTagChips());
        SearchBar bar = attachSearchBar();
        edit = bar.findViewById(R.id.search_edit_text);
        String raw = "artist:alice artist:bravo artist:charlie";
        bar.setText(raw);
        layoutBar(bar);
        int expandedHeight = bar.getHeight();
        assertEquals(3, chips().length);
        Settings.putBoolean(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, false);
        layoutBar(bar);
        assertEquals(raw, bar.getText());
        assertEquals(0, chips().length);
        assertEquals(1, edit.getMaxLines());
        assertEquals(1, edit.getLineCount());
        assertTrue(bar.getHeight() < expandedHeight);
        Settings.putBoolean(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, true);
        layoutBar(bar);
        assertEquals(3, chips().length);
        assertEquals(expandedHeight, bar.getHeight());
        EditorInfo info = new EditorInfo();
        assertNotNull(edit.onCreateInputConnection(info));
        assertEquals(EditorInfo.IME_ACTION_SEARCH, info.imeOptions & EditorInfo.IME_MASK_ACTION);
        assertEquals(0, info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        bar.setIsComeFromDownload(true);
        Settings.putBoolean(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, false);
        Settings.putBoolean(Settings.KEY_ENABLE_SEARCH_TAG_CHIPS, true);
        layoutBar(bar);
        assertEquals(0, chips().length);
        assertEquals(1, edit.getMaxLines());
        assertEquals(raw, bar.getText());
    }

    private SearchBar attachSearchBar() {
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
        parent.addView(bar, new FrameLayout.LayoutParams(280, FrameLayout.LayoutParams.WRAP_CONTENT));
        activity.setContentView(parent);
        bar.setState(SearchBar.STATE_SEARCH, false);
        return bar;
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void buttonsStayInFirstRowAndLaterRowsUseBothSidesWithoutChangingCopiedQuery() {
        SearchBar bar = attachSearchBar();
        edit = bar.findViewById(R.id.search_edit_text);
        String query = "f:aaa f:bbb f:ccc f:ddd f:eee f:fff f:ggg f:hhh";
        bar.setText(query);
        edit.setSelection(0);
        layoutBar(bar);
        assertEquals(0, bar.findViewById(R.id.search_menu).getTop());
        assertEquals(0, bar.findViewById(R.id.search_action).getTop());
        assertEquals(280, edit.getWidth());
        assertEquals(48f, edit.getLayout().getPrimaryHorizontal(0), 0.1f);
        assertTrue(edit.getLayout().getPrimaryHorizontal(edit.getLayout().getLineEnd(0) - 1) <= 280 - 48);
        assertEquals(0f, edit.getLayout().getPrimaryHorizontal(edit.getLayout().getLineStart(1)), 0.1f);
        assertTrue(edit.getLayout().getLineMax(1) > 280 - 48);
        assertEquals(query, bar.getText());
        edit.selectAll();
        assertTrue(edit.onTextContextMenuItem(android.R.id.copy));
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(query, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void verticalScrollingMovesTheExistingLayoutWithoutReflowOrSnapping() {
        SearchBar bar = attachSearchBar();
        edit = bar.findViewById(R.id.search_edit_text);
        String query = "f:aa f:bb f:cc f:dd f:ee f:ff f:gg f:hh f:ii f:jj f:kk f:ll";
        bar.setText(query);
        edit.setSelection(0);
        layoutBar(bar);
        int[] starts = new int[edit.getLineCount()];
        float[] lefts = new float[starts.length];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = edit.getLayout().getLineStart(i);
            lefts[i] = edit.getLayout().getPrimaryHorizontal(starts[i]);
        }
        edit.scrollTo(0, 13);
        layoutBar(bar);
        assertEquals(13, edit.getScrollY());
        assertEquals(starts.length, edit.getLineCount());
        for (int i = 0; i < starts.length; i++) {
            assertEquals(starts[i], edit.getLayout().getLineStart(i));
            assertEquals(lefts[i], edit.getLayout().getPrimaryHorizontal(starts[i]), 0.1f);
        }
        assertEquals(12, chips().length);
        assertEquals(4, edit.getMaxLines());
        assertEquals(query, bar.getText());
        assertEquals(0, bar.findViewById(R.id.search_menu).getTop());
        assertEquals(0, bar.findViewById(R.id.search_action).getTop());
        edit.setSelection(edit.length());
        InputConnection connection = edit.onCreateInputConnection(new EditorInfo());
        connection.setComposingText(" f:mm f:nn ", 1);
        layoutBar(bar);
        assertEquals(query + " f:mm f:nn ", bar.getText());
        assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(edit.getText()) >= 0);
        connection.finishComposingText();
    }

    private void layoutBar(SearchBar bar) {
        for (int i = 0; i < 6; i++) {
            bar.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            bar.layout(0, 0, 280, bar.getMeasuredHeight());
            // Process a bounded number of frames: Robolectric's focused TextView can keep
            // requesting its caret rectangle from the synthetic window indefinitely.
            for (int frame = 0; frame < 3; frame++) {
                shadowOf(android.os.Looper.getMainLooper()).runOneTask();
            }
        }
    }

    private void touch(float x, float y) {
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 20, action, x, y, 0);
            assertTrue(edit.onTouchEvent(event));
            event.recycle();
        }
    }

    private void tapVisibleChip(SearchTagChipSpan chip, int zone) {
        int start = edit.getText().getSpanStart(chip);
        int line = edit.getLayout().getLineForOffset(start);
        float left = edit.getLayout().getPrimaryHorizontal(start)
                + edit.getTotalPaddingLeft() - edit.getScrollX();
        float y = (edit.getLayout().getLineTop(line) + edit.getLayout().getLineBottom(line)) / 2f
                + edit.getTotalPaddingTop() - edit.getScrollY();
        assertTrue(y >= 0 && y < edit.getHeight());
        float width = chip.getSize(edit.getPaint(), edit.getText(), 0, 0, null);
        touch(left + (zone == 0 ? chip.actionWidth() / 2 : width - chip.actionWidth() / 2), y);
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
