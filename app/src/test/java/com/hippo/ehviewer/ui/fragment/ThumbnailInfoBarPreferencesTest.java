package com.hippo.ehviewer.ui.fragment;

import android.app.Application;
import android.os.Bundle;
import android.content.res.XmlResourceParser;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class ThumbnailInfoBarPreferencesTest {
    @Test public void badgeVisibilityFollowsMainSwitchAndLastEnabledSubrow() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        Harness fragment = new Harness();
        activity.getSupportFragmentManager().beginTransaction().add(fragment, "preferences").commitNow();
        TwoStatePreference bar = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR);
        TwoStatePreference details = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS);
        TwoStatePreference title = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_TITLE);
        TwoStatePreference badge = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE);
        assertTrue(badge.isVisible());
        change(bar, true);
        assertTrue(details.isChecked()); assertFalse(badge.isVisible());
        change(title, true);
        change(details, false);
        assertTrue(bar.isChecked()); assertFalse(badge.isVisible());
        change(title, false);
        assertFalse(bar.isChecked()); assertTrue(badge.isVisible());
        change(bar, true);
        change(bar, false);
        assertFalse(details.isChecked()); assertFalse(title.isChecked()); assertTrue(badge.isVisible());
        activity.finish();
    }

    @Test public void bothEntriesFollowInfoSubitemsAndRecordLimitEntriesUseSharedKey() throws Exception {
        List<String> eh = keys(R.xml.eh_settings), fork = keys(R.xml.fork_features_settings);
        for (List<String> list : new List[]{eh, fork}) {
            assertEquals(list.indexOf(Settings.KEY_SHOW_THUMBNAIL_INFO_HIGHLIGHT) + 1,
                    list.indexOf(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE));
        }
        assertEquals(eh.indexOf(Settings.KEY_HISTORY_INFO_SIZE) + 1,
                eh.indexOf(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT));
        assertEquals(fork.indexOf("manual_image_save_location") + 1,
                fork.indexOf(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT));
        assertFalse(fork.contains("launch_page"));
        assertFalse(fork.contains("start_transfer_time"));
        assertArrayEquals(new String[]{"50", "100", "200", "500", "1000"},
                RuntimeEnvironment.getApplication().getResources()
                        .getStringArray(R.array.gallery_update_record_limit_values));
    }

    private void change(TwoStatePreference preference, boolean checked) {
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

    public static class Harness extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle savedState, String rootKey) {
            getPreferenceManager().setSharedPreferencesName("thumbnail-test");
            getPreferenceManager().getSharedPreferences().edit().clear().commit();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            setPreferenceScreen(screen);
            for (String key : new String[]{Settings.KEY_SHOW_THUMBNAIL_INFO_BAR,
                    Settings.KEY_SHOW_THUMBNAIL_INFO_TITLE, Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS,
                    Settings.KEY_SHOW_THUMBNAIL_INFO_HIGHLIGHT, Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE}) {
                SwitchPreferenceCompat preference = new SwitchPreferenceCompat(requireContext());
                preference.setKey(key); preference.setChecked(false); screen.addPreference(preference);
            }
            ThumbnailInfoBarPreferences.bind(this, () -> {});
        }
    }
}
