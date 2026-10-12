package com.hippo.ehviewer.ui.fragment;

import android.app.Application;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Xml;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.preference.ThumbnailSizePreference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE, qualifiers = "zh-rCN")
public class ThumbnailSizePreferencesTest {
    @Before public void reset() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
    }

    @Test public void legacyPresetMigratesOnceThenSelectionsAreIndependent() {
        for (int preset = 0; preset <= 2; preset++) {
            reset();
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                    .edit().putString(Settings.KEY_THUMB_SIZE, Integer.toString(preset)).commit();
            Settings.initialize(RuntimeEnvironment.getApplication());
            assertEquals(preset, Settings.getThumbSize());
            assertEquals(preset, Settings.getPreviewThumbSize());
            Settings.putString(Settings.KEY_THUMB_SIZE, "105");
            Settings.putString(Settings.KEY_PREVIEW_THUMB_SIZE, "108");
            Settings.initialize(RuntimeEnvironment.getApplication());
            assertEquals(105, Settings.getThumbSize());
            assertEquals(108, Settings.getPreviewThumbSize());
        }
        assertEquals(R.dimen.gallery_grid_column_width_large, Settings.getThumbSizeResId(0));
        assertEquals(R.dimen.gallery_grid_column_width_middle, Settings.getThumbSizeResId(1));
        assertEquals(R.dimen.gallery_grid_column_width_small, Settings.getThumbSizeResId(2));
    }

    @Test public void bothDialogsShowExplanationAndPersistTheirOwnNumericSelection() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        Harness fragment = new Harness();
        activity.getSupportFragmentManager().beginTransaction().add(fragment, "sizes").commitNow();
        ThumbnailSizePreference cover = fragment.findPreference(Settings.KEY_THUMB_SIZE);
        ThumbnailSizePreference preview = fragment.findPreference(Settings.KEY_PREVIEW_THUMB_SIZE);
        checkDialog(cover, 8, "封面缩略图大小", "5");
        assertEquals(105, Settings.getThumbSize());
        assertEquals(1, Settings.getPreviewThumbSize());
        checkDialog(preview, 11, "预览缩略图大小", "8");
        assertEquals(105, Settings.getThumbSize());
        assertEquals(108, Settings.getPreviewThumbSize());
        activity.finish();
    }

    private void checkDialog(ThumbnailSizePreference preference, int count, String title,
                             String lastChoice) {
        assertEquals(count, preference.getEntries().length);
        assertEquals("大", preference.getEntries()[0].toString());
        assertEquals("中", preference.getEntries()[1].toString());
        assertEquals("小", preference.getEntries()[2].toString());
        for (int i = 3; i < count; i++) {
            assertEquals(Integer.toString(i - 2), preference.getEntries()[i].toString());
        }
        preference.showDialog();
        AlertDialog dialog = (AlertDialog) preference.getDialog();
        TextView heading = dialog.findViewById(R.id.title);
        TextView description = dialog.findViewById(R.id.description);
        assertEquals(title, heading.getText().toString());
        assertEquals("调整竖屏每行显示的缩略图数量。大 / 中 / 小 为原版本预设值的保留项。",
                description.getText().toString());
        assertTrue(description.getTextSize() < heading.getTextSize());
        dialog.getListView().performItemClick(null, count - 1, count - 1);
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals(lastChoice, preference.getSummary().toString());
    }

    public static class Harness extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle savedState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            setPreferenceScreen(screen);
            try (XmlResourceParser xml = getResources().getXml(R.xml.eh_settings)) {
                while (xml.next() != XmlResourceParser.END_DOCUMENT) {
                    if (xml.getEventType() == XmlResourceParser.START_TAG
                            && ThumbnailSizePreference.class.getName().equals(xml.getName())) {
                        screen.addPreference(new ThumbnailSizePreference(requireContext(), Xml.asAttributeSet(xml)));
                    }
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
