package com.hippo.ehviewer.client;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.res.XmlResourceParser;
import android.net.NetworkCapabilities;
import android.preference.PreferenceManager;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.fragment.DownloadFragment;
import com.hippo.ehviewer.ui.fragment.ForkFeaturesFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.TwoStatePreference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN", manifest = Config.NONE)
public class BrowsingPrioritySettingsTest {
    @Before public void setup() {
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
    }

    @Test public void bothSettingsEntrancesUseTheSameDefaultOffPreferenceAndExplanation() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        assertFalse(Settings.getMobileBrowsingPriority());
        for (int resource : new int[]{R.xml.download_settings, R.xml.fork_features_settings}) {
            int matches = 0;
            try (XmlResourceParser xml = app.getResources().getXml(resource)) {
                while (xml.next() != XmlResourceParser.END_DOCUMENT) {
                    if (xml.getEventType() != XmlResourceParser.START_TAG) continue;
                    String ns = "http://schemas.android.com/apk/res/android";
                    if (!Settings.KEY_MOBILE_BROWSING_PRIORITY.equals(xml.getAttributeValue(ns, "key"))) continue;
                    matches++;
                    assertFalse(xml.getAttributeBooleanValue(ns, "defaultValue", true));
                    assertEquals(R.string.settings_download_mobile_browsing_priority,
                            xml.getAttributeResourceValue(ns, "title", 0));
                    assertEquals(R.string.settings_download_mobile_browsing_priority_summary,
                            xml.getAttributeResourceValue(ns, "summary", 0));
                }
            }
            assertEquals(1, matches);
        }
        assertEquals("移动网络时浏览优先", app.getString(R.string.settings_download_mobile_browsing_priority));
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(Settings.KEY_MOBILE_BROWSING_PRIORITY, true).commit();
        assertTrue(Settings.getMobileBrowsingPriority());
    }

    private NetworkCapabilities capabilities(int... transports) {
        NetworkCapabilities result = ShadowNetworkCapabilities.newInstance();
        for (int transport : transports) Shadows.shadowOf(result).addTransportType(transport);
        return result;
    }

    @Test public void togglingEitherSettingsEntranceIsReflectedWhenReturningToTheOther() {
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        DownloadFragment download = new DownloadFragment();
        ForkFeaturesFragment fork = new ForkFeaturesFragment();
        host.getSupportFragmentManager().beginTransaction().add(download, "download")
                .add(fork, "fork").commitNow();
        TwoStatePreference downloadSwitch = download.findPreference(Settings.KEY_MOBILE_BROWSING_PRIORITY);
        TwoStatePreference forkSwitch = fork.findPreference(Settings.KEY_MOBILE_BROWSING_PRIORITY);
        assertFalse(downloadSwitch.isChecked());
        assertFalse(forkSwitch.isChecked());
        downloadSwitch.setChecked(true);
        assertTrue(Settings.getMobileBrowsingPriority());
        fork.onResume();
        assertTrue(forkSwitch.isChecked());
        forkSwitch.setChecked(false);
        assertFalse(Settings.getMobileBrowsingPriority());
        download.onResume();
        assertFalse(downloadSwitch.isChecked());
    }

    @Test public void cellularAndCellularVpnQualifyButMeteredWifiEthernetAndUnknownDoNot() {
        assertTrue(BrowsingPriority.isCellular(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)));
        assertTrue(BrowsingPriority.isCellular(capabilities(NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.TRANSPORT_CELLULAR)));
        assertFalse(BrowsingPriority.isCellular(capabilities(NetworkCapabilities.TRANSPORT_WIFI)));
        assertFalse(BrowsingPriority.isCellular(capabilities(NetworkCapabilities.TRANSPORT_ETHERNET)));
        assertFalse(BrowsingPriority.isCellular(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_WIFI)));
        assertFalse(BrowsingPriority.isCellular(capabilities(NetworkCapabilities.TRANSPORT_VPN)));
        assertFalse(BrowsingPriority.isCellular(null));
    }
}
