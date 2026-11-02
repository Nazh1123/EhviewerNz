package com.hippo.ehviewer.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.unifile.UniFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the upstream metadata fallbacks together with fork range restoration and DB writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = RestoreDownloadCompatibilityTest.TestApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RestoreDownloadCompatibilityTest {
    private static final long GID = 123456L;
    private static final String TOKEN = "0123456789";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private SQLiteDatabase database;
    private DownloadManager manager;
    private ActivityController<Activity> activity;
    private Object task;
    private File directory;

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {
            ReflectionHelpers.setStaticField(EhApplication.class, "instance", this);
        }
    }

    @Before public void setup() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sContext", application);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                application.getSharedPreferences("restore-tests", Context.MODE_PRIVATE));
        application.getSharedPreferences("download_page_ranges", Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        manager = new DownloadManager(application);
        ReflectionHelpers.setField(application, "mDownloadManager", manager);
        ReflectionHelpers.setField(application, "mOkHttpClient", new okhttp3.OkHttpClient());
        File root = temporary.newFolder("downloads");
        directory = new File(root, "restored-gallery");
        assertTrue(directory.mkdir());
        Settings.putDownloadLocation(UniFile.fromFile(root));
        activity = Robolectric.buildActivity(Activity.class);
        Activity themed = activity.get();
        themed.setTheme(R.style.AppTheme);
        activity.create();
        RestoreDownloadPreference preference = new RestoreDownloadPreference(themed, null);
        Class<?> taskClass = Class.forName(RestoreDownloadPreference.class.getName() + "$RestoreTask");
        Constructor<?> constructor = taskClass.getDeclaredConstructor(RestoreDownloadPreference.class, Context.class);
        constructor.setAccessible(true);
        task = constructor.newInstance(preference, themed);
    }

    @After public void cleanup() {
        if (activity != null) activity.destroy();
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        ReflectionHelpers.setStaticField(EhApplication.class, "instance", null);
        database.close();
    }

    @Test public void textMetadataAndCompleteRangeRestoreWithoutApiTitle() throws Exception {
        textMetadata();
        range(false);
        image(2); image(4);
        assertRestored(DownloadInfo.STATE_FINISH, 2);
    }

    @Test public void cborMetadataAndCompleteRangeRestoreWithoutApiTitle() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{(byte) 0xA2, 0x63, 'g', 'i', 'd', 0x1A, 0, 1, (byte) 0xE2, 0x40,
                0x65, 't', 'o', 'k', 'e', 'n', 0x6A});
        bytes.writeBytes(TOKEN.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, ".ehviewer").toPath(), bytes.toByteArray());
        range(false);
        image(2); image(4);
        assertRestored(DownloadInfo.STATE_FINISH, 2);
    }

    @Test public void comicInfoWithoutSpiderFileAlsoRestoresRange() throws Exception {
        comicInfo();
        range(false);
        image(2); image(4);
        assertRestored(DownloadInfo.STATE_FINISH, 2);
    }

    @Test public void corruptSpiderFileFallsBackToComicInfoAndKeepsPartialRange() throws Exception {
        writeText(".ehviewer", "INVALID_FORMAT");
        comicInfo();
        range(false);
        image(2);
        assertRestored(DownloadInfo.STATE_NONE, 1);
    }

    @Test public void pendingRangeIsNotMarkedCompleteEvenWhenAllImagesExist() throws Exception {
        textMetadata();
        range(true);
        image(2); image(4);
        assertRestored(DownloadInfo.STATE_NONE, 2);
    }

    @Test public void ordinaryGalleryKeepsFallbackTitleWithoutBecomingARange() throws Exception {
        textMetadata();
        GalleryInfo item = restoreItem();
        publish(item);
        DownloadInfo restored = manager.getDownloadInfo(GID);
        assertNotNull(restored);
        assertEquals(directory.getName(), restored.title);
        assertEquals(DownloadInfo.STATE_NONE, restored.state);
        assertFalse(GalleryDownloadRangeStore.isRange(GID));
        assertEquals(1, EhDB.getAllDownloadInfo().size());
    }

    @Test public void directoryWithoutGalleryIdentityIsIgnored() throws Exception {
        range(false);
        assertNull(restoreItem());
        assertFalse(GalleryDownloadRangeStore.isRange(GID));
        assertTrue(EhDB.getAllDownloadInfo().isEmpty());
    }

    private void assertRestored(int state, int finished) throws Exception {
        GalleryInfo item = restoreItem();
        assertNotNull(item);
        assertEquals(GID, item.gid);
        assertEquals(TOKEN, item.token);
        publish(item); // No API title: exercise the offline/removed-gallery fallback.
        DownloadInfo restored = manager.getDownloadInfo(GID);
        assertNotNull(restored);
        assertEquals(directory.getName(), restored.title);
        assertEquals(state, restored.state);
        assertEquals(2, restored.total);
        assertEquals(finished, restored.finished);
        assertEquals(finished, restored.downloaded);
        assertEquals(2 - finished, restored.legacy);
        assertEquals(directory.getName(), EhDB.getDownloadDirname(GID));
        assertEquals(1, EhDB.getAllDownloadInfo().size());
        assertEquals(finished, EhDB.getAllDownloadInfo().get(0).finished);
        assertEquals("2,4", GalleryDownloadRangeStore.peek(GID).range().toString());
        assertNull(restoreItem()); // A second scan must not add the same download again.
    }

    private GalleryInfo restoreItem() throws Exception {
        Method method = task.getClass().getDeclaredMethod("getRestoreItem", UniFile.class);
        method.setAccessible(true);
        return (GalleryInfo) method.invoke(task, UniFile.fromFile(directory));
    }

    private void publish(GalleryInfo item) throws Exception {
        ArrayList<GalleryInfo> items = new ArrayList<>();
        items.add(item);
        Method method = task.getClass().getDeclaredMethod("onPostExecute", Object.class);
        method.setAccessible(true);
        method.invoke(task, items);
    }

    private void textMetadata() throws Exception {
        writeText(".ehviewer",
                "VERSION2\n00000000\n" + GID + "\n" + TOKEN + "\n1\n1\n20\n5\n");
    }

    private void comicInfo() throws Exception {
        writeText("ComicInfo.xml",
                "<ComicInfo><Web>https://e-hentai.org/g/" + GID + "/" + TOKEN + "/</Web></ComicInfo>");
    }

    private void range(boolean pending) throws Exception {
        writeText(GalleryDownloadRangeStore.FILENAME,
                "{\"gid\":" + GID + ",\"total\":5,\"pages\":\"2,4\",\"pending\":" + pending + "}");
    }

    private void image(int page) throws Exception {
        Files.write(new File(directory, String.format(java.util.Locale.ROOT, "%08d.png", page)).toPath(),
                Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8XcAAAAASUVORK5CYII="));
    }

    private void writeText(String name, String content) throws Exception {
        Files.write(new File(directory, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
