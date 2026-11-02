package com.hippo.ehviewer.ui.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceManager;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = AppLanguagePreferencesTest.TestApplication.class, sdk = 28,
        manifest = Config.NONE)
public class AppLanguagePreferencesTest {
    private TestApplication app;
    private AdvancedFragment fragment;

    @Before public void setUp() {
        app = (TestApplication) RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        fragment = new AdvancedFragment();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "advanced").commitNow();
    }

    private void changeLanguage(String language, boolean expectedTranslations) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, !expectedTranslations).commit();
        int recreations = app.recreations;
        Preference preference = fragment.findPreference(Settings.KEY_APP_LANGUAGE);
        assertTrue(preference.callChangeListener(language));
        assertEquals(recreations + 1, app.recreations);
        // Both settings must already be saved when recreation begins.
        assertEquals(language, app.languageAtRecreation);
        assertEquals(expectedTranslations, app.translationsAtRecreation);
        Settings.initialize(app);
        assertEquals(language, Settings.getAppLanguage());
        assertEquals(expectedTranslations, Settings.getShowTagTranslations());
    }

    @Test public void allChineseChoicesEnableTagTranslations() {
        for (String language : new String[]{"zh-CN", "zh-HK", "zh-TW"}) {
            changeLanguage(language, true);
        }
    }

    @Test public void allOtherExplicitLanguagesDisableTagTranslations() {
        for (String language : app.getResources().getStringArray(R.array.app_language_entry_values)) {
            if (!language.equals("system") && !language.startsWith("zh-")) {
                changeLanguage(language, false);
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN")
    public void systemSimplifiedChineseEnablesTagTranslations() {
        changeLanguage("system", true);
    }

    @Test @Config(qualifiers = "zh-rTW")
    public void systemTraditionalChineseEnablesTagTranslations() {
        changeLanguage("system", true);
    }

    @Test @Config(qualifiers = "en")
    public void systemEnglishDisablesTagTranslations() {
        changeLanguage("system", false);
    }

    @Test public void manualOverrideSurvivesInitializationUntilNextLanguageChange() {
        changeLanguage("zh-CN", true);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false).commit();
        Settings.initialize(app);
        assertFalse(Settings.getShowTagTranslations());
        changeLanguage("zh-TW", true);
        changeLanguage("en", false);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true).commit();
        Settings.initialize(app);
        assertTrue(Settings.getShowTagTranslations());
    }

    public static class TestApplication extends EhApplication {
        int recreations;
        String languageAtRecreation;
        boolean translationsAtRecreation;

        @Override public void onCreate() {
            // Keep this preference test independent of application services and networking.
        }

        @Override public void recreate() {
            recreations++;
            languageAtRecreation = Settings.getAppLanguage();
            translationsAtRecreation = Settings.getShowTagTranslations();
        }
    }
}
