package com.hippo.ehviewer.ui.fragment;

import android.app.Application;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UriHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowEnvironment;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE, qualifiers = "zh-rCN")
public class ManualImageSaveLocationPreferencesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<Uri, UniFile> locations = new HashMap<>();
    private UriHandler uriHandler;
    private SharedPreferences preferences;
    private Harness fragment;
    private FragmentActivity host;

    @Before public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        Settings.initialize(context);
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        // Preserve drive-letter file paths when running the Android URI resolver on Windows.
        uriHandler = (app, uri) -> locations.get(uri);
        UniFile.addUriHandler(uriHandler);
        host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        fragment = new Harness();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "save-path").commitNow();
    }

    @After public void tearDown() {
        host.finish();
        UniFile.removeUriHandler(uriHandler);
    }

    private UniFile location(File directory) {
        UniFile file = UniFile.fromFile(directory);
        locations.put(file.getUri(), file);
        return file;
    }

    private Preference preference() {
        return fragment.findPreference(DownloadFragment.KEY_MANUAL_IMAGE_SAVE_LOCATION);
    }

    private AlertDialog showChoices() {
        ManualImageSaveLocationPreferences.selectLocation(fragment, 3, 4,
                () -> ManualImageSaveLocationPreferences.updateSummary(fragment, preference()));
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private void choose(int mode) {
        showChoices().getListView().performItemClick(null, mode, mode);
    }

    @Test public void choicesShowCurrentModeAndDefaultRestoresAppDirectory() throws Exception {
        UniFile custom = location(temp.newFolder("custom"));
        Settings.putManualImageSaveLocation(custom);
        AlertDialog choices = showChoices();
        assertEquals(3, choices.getListView().getCount());
        assertEquals("自定义", choices.getListView().getAdapter().getItem(0));
        assertEquals("默认", choices.getListView().getAdapter().getItem(1));
        assertEquals("跟随\"下载路径\"", choices.getListView().getAdapter().getItem(2));
        assertEquals(Settings.MANUAL_IMAGE_SAVE_CUSTOM,
                choices.getListView().getCheckedItemPosition());
        choices.getListView().performItemClick(null, Settings.MANUAL_IMAGE_SAVE_DEFAULT, 1);
        assertFalse(choices.isShowing());
        assertEquals(Settings.MANUAL_IMAGE_SAVE_DEFAULT, Settings.getManualImageSaveMode());
        assertEquals(Uri.fromFile(AppConfig.getExternalImageDir()),
                Settings.getManualImageSaveLocation().getUri());
        assertTrue(preference().getSummary().toString().startsWith("默认\n"));
        // Switching modes preserves the custom directory for the next custom selection.
        assertEquals(custom.getUri(), Settings.getManualImageSaveLocationUri());
    }

    @Test public void oldCustomSelectionAndNewModeSurviveReinitialization() throws Exception {
        UniFile custom = location(temp.newFolder("legacy"));
        preferences.edit().putString("manual_image_save_location", custom.getUri().toString())
                .commit();
        assertEquals(Settings.MANUAL_IMAGE_SAVE_CUSTOM, Settings.getManualImageSaveMode());
        assertEquals(custom.getUri(), Settings.getManualImageSaveLocation().getUri());
        Settings.putManualImageSaveMode(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD);
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertEquals(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD, Settings.getManualImageSaveMode());
        Settings.putManualImageSaveLocation(custom);
        assertEquals(Settings.MANUAL_IMAGE_SAVE_CUSTOM, Settings.getManualImageSaveMode());
    }

    @Test public void followingDownloadsCreatesAndReusesImageChildAndTracksPathChanges()
            throws Exception {
        File first = temp.newFolder("downloads-first");
        UniFile download = location(first);
        Settings.putDownloadLocation(download);
        choose(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD);
        File images = new File(first, "_image");
        assertFalse("The settings summary must not create directories", images.exists());
        assertTrue(preference().getSummary().toString(),
                preference().getSummary().toString().endsWith("/_image"));
        assertEquals(Uri.fromFile(images), Settings.getManualImageSaveLocation().getUri());
        assertTrue(images.isDirectory());
        assertEquals(download.getUri(), Settings.getDownloadLocation().getUri());
        File marker = new File(images, "saved.jpg");
        assertTrue(marker.createNewFile());
        assertEquals(Uri.fromFile(images), Settings.getManualImageSaveLocation().getUri());
        assertTrue(marker.exists());
        File second = temp.newFolder("downloads-second");
        Settings.putDownloadLocation(location(second));
        assertEquals(Uri.fromFile(new File(second, "_image")),
                Settings.getManualImageSaveLocation().getUri());
        assertTrue(marker.exists());
    }

    @Test public void cancellingModeOrCustomPickerPreservesPreviousChoice() {
        Settings.putManualImageSaveMode(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD);
        AlertDialog choices = showChoices();
        choices.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD, Settings.getManualImageSaveMode());
        choose(Settings.MANUAL_IMAGE_SAVE_CUSTOM);
        AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNull(picker.getListView());
        assertTrue(picker.isShowing());
        picker.cancel();
        assertEquals(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD, Settings.getManualImageSaveMode());
    }

    @Test public void blockedImageChildOrMissingDownloadRootDoesNotFallBack() throws Exception {
        File root = temp.newFolder("blocked");
        File images = new File(root, "_image");
        assertTrue(images.createNewFile());
        Settings.putDownloadLocation(location(root));
        Settings.putManualImageSaveMode(Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD);
        assertNull(Settings.getManualImageSaveLocation());
        assertTrue(images.isFile());
        Settings.putDownloadLocation(location(new File(temp.getRoot(), "missing")));
        assertNull(Settings.getManualImageSaveLocation());
    }

    @Test public void missingCustomDirectoryDoesNotFallBackAndClearRestoresDefault() throws Exception {
        File directory = temp.newFolder("removed-custom");
        Settings.putManualImageSaveLocation(location(directory));
        assertTrue(directory.delete());
        assertNull(Settings.getManualImageSaveLocation());
        Settings.clearManualImageSaveLocation();
        assertEquals(Settings.MANUAL_IMAGE_SAVE_DEFAULT, Settings.getManualImageSaveMode());
        assertNull(Settings.getManualImageSaveLocationUri());
        assertNotNull(Settings.getManualImageSaveLocation());
    }

    public static class Harness extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.download_settings, rootKey);
        }
    }
}
