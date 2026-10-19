package com.hippo.ehviewer.util;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.xmlpull.v1.XmlPullParser;
import java.util.TimeZone;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class GalleryTimezonePreferenceTest {
    @Test public void defaultsToLocalAndRespondsToPreferenceAndDeviceZoneChanges() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("timezone-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", prefs);
        TimeZone previous = TimeZone.getDefault();
        try {
            assertTrue(Settings.getUseLocalTimezone());
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals("2026-01-02 00:00", GalleryTimeFormatter.format("2026-01-01 16:00"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertEquals("2026-01-01 11:00", GalleryTimeFormatter.format("2026-01-01 16:00"));
            Settings.putBoolean(Settings.KEY_USE_LOCAL_TIMEZONE, false);
            assertEquals("2026-01-01 16:00", GalleryTimeFormatter.format("2026-01-01 16:00"));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test public void switchIsImmediatelyBeforeJapaneseTitleAndEnabledByDefault() throws Exception {
        try (XmlResourceParser xml = RuntimeEnvironment.getApplication().getResources().getXml(R.xml.eh_settings)) {
            String previousKey = null;
            boolean found = false;
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.getEventType() != XmlPullParser.START_TAG) continue;
                String key = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "key");
                if (key == null) continue;
                if (Settings.KEY_USE_LOCAL_TIMEZONE.equals(key)) {
                    assertTrue(xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "defaultValue", false));
                    found = true;
                }
                if ("show_jpn_title".equals(key)) assertEquals(Settings.KEY_USE_LOCAL_TIMEZONE, previousKey);
                previousKey = key;
            }
            assertTrue(found);
        }
    }
}
