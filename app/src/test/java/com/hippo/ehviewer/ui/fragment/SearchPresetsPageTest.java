package com.hippo.ehviewer.ui.fragment;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.SettingsActivity;
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
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(application = SearchPresetsPageTest.TestApplication.class, sdk = 28, qualifiers = "zh-rCN-mdpi")
public class SearchPresetsPageTest {
    public static class TestApplication extends EhApplication {
        @Override public void onCreate() { /* Settings only, without networking or native initialization. */ }
    }

    private ActivityController<SettingsActivity> controller;
    private SettingsActivity activity;
    private SearchPresetsFragment page;

    @Before public void setUp() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                .putString("app_language", "zh-CN").commit();
        Settings.initialize(app);
        org.robolectric.util.ReflectionHelpers.setStaticField(com.hippo.ehviewer.client.EhTagDatabase.class, "instance", null);
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        activity = controller.get();
        FragmentManager manager = activity.getSupportFragmentManager();
        manager.executePendingTransactions();
        SettingsHeaders headers = (SettingsHeaders) manager.findFragmentById(R.id.settings);
        for (int i = 0; i < headers.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference preference = headers.getPreferenceScreen().getPreference(i);
            if (SearchFragment.class.getName().equals(preference.getFragment())) {
                preference.performClick();
                break;
            }
        }
        manager.executePendingTransactions();
        SearchFragment search = (SearchFragment) manager.findFragmentById(R.id.settings);
        search.findPreference(Settings.KEY_SEARCH_PRESET_TAGS).performClick();
        manager.executePendingTransactions();
        page = (SearchPresetsFragment) manager.findFragmentById(R.id.settings);
        layout();
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void dedicatedPageHasRowsAndFixedAddButtonAndReturnsWithUpdatedCount() {
        assertEquals(activity.getString(R.string.settings_search_preset_tags), activity.getSupportActionBar().getTitle());
        assertEquals(2, list().getAdapter().getItemCount());
        assertEquals("l:\"chinese$\"", ((TextView) row(0).findViewById(R.id.search_preset_query)).getText().toString());
        add("a:bravo");
        assertEquals(3, list().getAdapter().getItemCount());
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
        assertTrue(activity.getSupportFragmentManager().popBackStackImmediate());
        SearchFragment search = (SearchFragment) activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        assertTrue(search.findPreference(Settings.KEY_SEARCH_PRESET_TAGS).getSummary().toString().contains("3"));
        assertNull(search.findPreference("search_preset_item_0"));
        assertEquals(activity.getString(R.string.settings_search), activity.getSupportActionBar().getTitle());
        assertTrue(activity.getSupportFragmentManager().popBackStackImmediate());
        assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.settings) instanceof SettingsHeaders);
    }

    @Test public void addAndEditValidateTagsAndAliasesWithoutDismissingInvalidDrafts() {
        AlertDialog dialog = openAdd();
        EditText input = dialog.findViewById(R.id.search_preset_input);
        for (String invalid : new String[]{"plain text", "language:CHINESE", "-f:tag"}) {
            input.setText(invalid);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(input.getError());
            assertEquals(2, Settings.getSearchPresetTags().size());
        }
        input.setText("a:bravo");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        layout();
        row(2).findViewById(R.id.search_preset_edit).performClick();
        page.getChildFragmentManager().executePendingTransactions();
        dialog = editor();
        input = dialog.findViewById(R.id.search_preset_input);
        assertEquals("a:bravo", input.getText().toString());
        input.setText("g:charlie");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("g:charlie", Settings.getSearchPresetTags().get(2));
        layout();
        assertEquals("g:charlie", ((TextView) row(2).findViewById(R.id.search_preset_query)).getText().toString());
    }

    @Test public void deleteCanBeCancelledAndEmptyListKeepsAddAvailable() {
        row(0).findViewById(R.id.search_preset_delete).performClick();
        page.getChildFragmentManager().executePendingTransactions();
        AlertDialog dialog = (AlertDialog) ((SearchPresetDialog) page.getChildFragmentManager()
                .findFragmentByTag("preset_delete")).requireDialog();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertEquals(2, Settings.getSearchPresetTags().size());
        for (int i = 0; i < 2; i++) {
            layout();
            row(0).findViewById(R.id.search_preset_delete).performClick();
            page.getChildFragmentManager().executePendingTransactions();
            dialog = (AlertDialog) ((SearchPresetDialog) page.getChildFragmentManager()
                    .findFragmentByTag("preset_delete")).requireDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
        }
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertTrue(Settings.getSearchPresetTags().isEmpty());
        assertEquals(0, list().getAdapter().getItemCount());
        assertEquals(View.VISIBLE, page.requireView().findViewById(R.id.search_presets_empty).getVisibility());
        assertTrue(page.requireView().findViewById(R.id.search_presets_add).isEnabled());
        add("f:tag");
        assertEquals(List.of("f:tag"), Settings.getSearchPresetTags());
        assertEquals(View.GONE, page.requireView().findViewById(R.id.search_presets_empty).getVisibility());
    }

    @Test public void rotationRetainsUnsavedDraftAndReturnsToSameManagementPage() {
        AlertDialog dialog = openAdd();
        ((EditText) dialog.findViewById(R.id.search_preset_input)).setText("a:\"unfinished draft");
        controller.recreate();
        activity = controller.get();
        page = (SearchPresetsFragment) activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        page.getChildFragmentManager().executePendingTransactions();
        dialog = editor();
        EditText input = dialog.findViewById(R.id.search_preset_input);
        assertEquals("a:\"unfinished draft", input.getText().toString());
        assertEquals(2, Settings.getSearchPresetTags().size());
        input.setText("a:finished");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(3, Settings.getSearchPresetTags().size());
        assertEquals(activity.getString(R.string.settings_search_preset_tags), activity.getSupportActionBar().getTitle());
    }

    @Test public void longListScrollsWhileAddButtonStaysOutsideList() {
        java.util.ArrayList<String> tags = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) tags.add("a:artist" + i);
        Settings.putSearchPresetTags(tags);
        page.onResume();
        layout();
        View add = page.requireView().findViewById(R.id.search_presets_add);
        assertTrue(list().getBottom() + ((View) list().getParent()).getTop() <= add.getTop());
        assertNotSame(list(), add.getParent());
        list().scrollToPosition(29);
        layout();
        assertNotNull(list().findViewHolderForAdapterPosition(29));
        assertTrue(add.isShown());
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void rendersDedicatedListInBothThemesWithVisibleEditDeleteAndAddControls() throws Exception {
        android.graphics.Bitmap preview = android.graphics.Bitmap.createBitmap(720, 640,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(preview);
        for (int column = 0; column < 2; column++) {
            Settings.putTheme(column == 0 ? Settings.THEME_LIGHT : Settings.THEME_DARK);
            controller.recreate();
            activity = controller.get();
            page = (SearchPresetsFragment) activity.getSupportFragmentManager().findFragmentById(R.id.settings);
            layout();
            View first = row(0);
            assertTrue(first.findViewById(R.id.search_preset_details).getRight()
                    <= first.findViewById(R.id.search_preset_edit).getLeft());
            assertTrue(first.findViewById(R.id.search_preset_edit).getRight()
                    <= first.findViewById(R.id.search_preset_delete).getLeft());
            assertTrue(first.findViewById(R.id.search_preset_delete).getRight() <= 360);
            canvas.save();
            canvas.translate(column * 360, 0);
            page.requireView().draw(canvas);
            canvas.restore();
        }
        assertNotEquals(preview.getPixel(1, 639), preview.getPixel(361, 639));
        java.io.File root = new java.io.File(System.getProperty("user.dir"));
        if (root.getName().equals("app")) root = root.getParentFile();
        java.io.File output = new java.io.File(root, "artifacts/search-tag-chips/preset-management-preview.png");
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
            assertTrue(preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        }
        preview.recycle();
    }

    @Test public void completesChineseSuggestionAsEnglishTagAndWaitsForExplicitSave() throws Exception {
        installTagDictionary();
        AlertDialog dialog = openAdd();
        android.widget.AutoCompleteTextView input = dialog.findViewById(R.id.search_preset_input);
        filter(input, "爱丽");
        assertEquals(1, input.getAdapter().getCount());
        View suggestion = input.getAdapter().getView(0, null, new android.widget.FrameLayout(activity));
        assertEquals("artist:alice", ((TextView) suggestion.findViewById(R.id.hintView)).getText().toString());
        assertEquals("爱丽丝", ((TextView) suggestion.findViewById(R.id.textView)).getText().toString());
        input.showDropDown();
        input.setListSelection(0);
        input.performCompletion();
        assertEquals("a:\"alice$\"", input.getText().toString());
        assertTrue(dialog.isShowing());
        assertEquals(2, Settings.getSearchPresetTags().size());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("a:\"alice$\"", Settings.getSearchPresetTags().get(2));
    }

    @Test public void editingSupportsNamespaceAliasesAndTranslationPreference() throws Exception {
        installTagDictionary();
        row(0).findViewById(R.id.search_preset_edit).performClick();
        page.getChildFragmentManager().executePendingTransactions();
        AlertDialog dialog = editor();
        android.widget.AutoCompleteTextView input = dialog.findViewById(R.id.search_preset_input);
        filter(input, "loc:\"bea");
        assertEquals(1, input.getAdapter().getCount());
        View recycled = input.getAdapter().getView(0, null, new android.widget.FrameLayout(activity));
        assertEquals("沙滩", ((TextView) recycled.findViewById(R.id.textView)).getText().toString());
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        recycled = input.getAdapter().getView(0, recycled, new android.widget.FrameLayout(activity));
        assertEquals(View.GONE, recycled.findViewById(R.id.textView).getVisibility());
        input.showDropDown();
        input.setListSelection(0);
        input.performCompletion();
        assertEquals("loc:\"beach$\"", input.getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("loc:\"beach$\"", Settings.getSearchPresetTags().get(0));
        assertEquals(2, Settings.getSearchPresetTags().size());
    }

    @Test public void noDictionaryOrNoMatchesStillAllowsManualTagsAndCompletedDuplicatesStayInvalid() throws Exception {
        AlertDialog dialog = openAdd();
        android.widget.AutoCompleteTextView input = dialog.findViewById(R.id.search_preset_input);
        filter(input, "f:manual");
        assertEquals(0, input.getAdapter().getCount());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("f:manual", Settings.getSearchPresetTags().get(2));
        installTagDictionary();
        dialog = openAdd();
        input = dialog.findViewById(R.id.search_preset_input);
        filter(input, "no-match-123");
        assertEquals(0, input.getAdapter().getCount());
        filter(input, "l:chi");
        assertEquals(1, input.getAdapter().getCount());
        input.showDropDown();
        input.setListSelection(0);
        input.performCompletion();
        assertEquals("l:\"chinese$\"", input.getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertNotNull(input.getError());
        assertEquals(3, Settings.getSearchPresetTags().size());
    }

    private static void filter(android.widget.AutoCompleteTextView input, String text) {
        idle();
        assertTrue(input.isAttachedToWindow());
        input.setText(text);
        ((android.widget.Filterable) input.getAdapter()).getFilter().filter(text);
        idle();
    }

    private void installTagDictionary() throws Exception {
        StringBuilder data = new StringBuilder();
        for (String[] entry : new String[][]{{"a:alice", "爱丽丝"}, {"l:chinese", "中文"},
                {"loc:beach", "沙滩"}, {"o:ai generated", "AI 生成"}}) {
            data.append(entry[0]).append('\r').append(android.util.Base64.encodeToString(
                    entry[1].getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)).append('\n');
        }
        byte[] bytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        okio.Buffer source = new okio.Buffer();
        source.writeInt(bytes.length).write(bytes);
        org.robolectric.util.ReflectionHelpers.setStaticField(com.hippo.ehviewer.client.EhTagDatabase.class,
                "instance", new com.hippo.ehviewer.client.EhTagDatabase("preset-hints-test", source));
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
    }

    private void add(String tag) {
        AlertDialog dialog = openAdd();
        ((EditText) dialog.findViewById(R.id.search_preset_input)).setText(tag);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
    }

    private AlertDialog openAdd() {
        page.requireView().findViewById(R.id.search_presets_add).performClick();
        page.getChildFragmentManager().executePendingTransactions();
        return editor();
    }

    private AlertDialog editor() {
        return (AlertDialog) ((SearchPresetDialog) page.getChildFragmentManager()
                .findFragmentByTag("preset_editor")).requireDialog();
    }

    private RecyclerView list() { return page.requireView().findViewById(R.id.search_presets_list); }
    private View row(int position) { return list().findViewHolderForAdapterPosition(position).itemView; }
    private void layout() {
        View root = page.requireView();
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 640);
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}
