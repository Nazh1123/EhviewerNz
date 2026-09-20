package com.hippo.ehviewer;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.Looper;

import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DaoSession;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class EhDBCompatibilityTest {

    private SQLiteDatabase upstream(int version) throws Exception {
        return upstream(version, null);
    }

    private SQLiteDatabase upstream(int version, File file) throws Exception {
        SQLiteDatabase db = file == null ? SQLiteDatabase.create(null)
                : SQLiteDatabase.openOrCreateDatabase(file, null);
        try (InputStream input = getClass().getResourceAsStream("upstream-schema-8.sql")) {
            assertNotNull(input);
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (version == 7) {
                schema = schema.replace(",\"LOCATION\" TEXT", "");
            }
            for (String sql : schema.split(";")) {
                if (!sql.trim().isEmpty()) {
                    db.execSQL(sql);
                }
            }
        }
        db.setVersion(version);
        db.execSQL("INSERT INTO QUICK_SEARCH (_id, NAME, MODE, CATEGORY, ADVANCE_SEARCH, "
                + "MIN_RATING, PAGE_FROM, PAGE_TO, TIME) VALUES (1, 'test', 0, 0, 0, 0, -1, -1, 123)");
        db.execSQL("INSERT INTO DOWNLOADS (GID, TOKEN, TITLE, CATEGORY, RATING, STATE, LEGACY, "
                + "TIME, ARCHIVE_URI) VALUES (42, 'token', 'title', 0, 4.5, 0, 0, 123, 'file:///test.zip')");
        db.execSQL("INSERT INTO Gallery_Tags (GID, ARTIST) VALUES (42, 'artist')");
        return db;
    }

    private void assertImportedRows(SQLiteDatabase db, boolean subscribed, Long firstGid) {
        DaoSession session = new DaoMaster(db).newSession();
        assertEquals("test", session.getQuickSearchDao().load(1L).name);
        assertEquals(subscribed, session.getQuickSearchDao().load(1L).subscribed);
        assertEquals("title", session.getDownloadsDao().load(42L).title);
        assertEquals("file:///test.zip", session.getDownloadsDao().load(42L).getArchiveUri());
        assertEquals(firstGid, session.getDownloadsDao().load(42L).getFirstGid());
        assertEquals("artist", session.getGalleryTagsDao().load(42L).getArtist());
        assertEquals(DaoMaster.SCHEMA_VERSION, db.getVersion());
    }

    @Test
    public void importsUpstream7And8() throws Exception {
        for (int version : new int[] {7, 8}) {
            try (SQLiteDatabase db = upstream(version)) {
                assertTrue(EhDB.prepareImportDatabase(db));
                assertImportedRows(db, false, null);
                // Repeat preparation must neither add duplicate columns nor lose rows.
                assertFalse(EhDB.prepareImportDatabase(db));
                assertImportedRows(db, false, null);
            }
        }
    }

    @Test
    public void preservesLegacyFork8And9Data() throws Exception {
        for (int version : new int[] {8, 9}) {
            try (SQLiteDatabase db = upstream(7)) {
                db.execSQL("ALTER TABLE QUICK_SEARCH ADD COLUMN SUBSCRIBED INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE QUICK_SEARCH SET SUBSCRIBED = 1");
                if (version == 9) {
                    db.execSQL("ALTER TABLE DOWNLOADS ADD COLUMN FIRST_GID INTEGER");
                    db.execSQL("UPDATE DOWNLOADS SET FIRST_GID = 40");
                }
                db.setVersion(version);
                assertEquals(version == 8, EhDB.prepareImportDatabase(db));
                assertImportedRows(db, true, version == 9 ? 40L : null);
            }
        }
    }

    @Test
    public void repairsPreviouslyMislabelledUpstreamImport() throws Exception {
        try (SQLiteDatabase db = upstream(8)) {
            // The old importer upgraded upstream 8 to fork 9 but omitted SUBSCRIBED.
            db.execSQL("ALTER TABLE DOWNLOADS ADD COLUMN FIRST_GID INTEGER");
            db.setVersion(9);
            assertFalse(EhDB.prepareImportDatabase(db));
            assertImportedRows(db, false, null);
        }
    }

    @Test
    public void rejectsUnknownVersionsWithoutChangingThem() throws Exception {
        for (int version : new int[] {0, 1, 10, 1_000_010}) {
            try (SQLiteDatabase db = upstream(8)) {
                db.setVersion(version);
                try {
                    EhDB.prepareImportDatabase(db);
                    fail("Accepted unsupported version " + version);
                } catch (SQLiteException expected) {
                    assertEquals(version, db.getVersion());
                }
            }
        }
    }

    @Test
    public void rejectsMissingColumnsAndRollsBackMigration() throws Exception {
        try (SQLiteDatabase db = upstream(8)) {
            db.execSQL("ALTER TABLE HISTORY RENAME TO OLD_HISTORY");
            db.execSQL("CREATE TABLE HISTORY (GID INTEGER PRIMARY KEY)");
            try {
                EhDB.prepareImportDatabase(db);
                fail("Accepted a history table missing required columns");
            } catch (SQLiteException expected) {
                assertEquals(8, db.getVersion());
                try (Cursor cursor = db.rawQuery("PRAGMA table_info(QUICK_SEARCH)", null)) {
                    while (cursor.moveToNext()) {
                        assertNotEquals("SUBSCRIBED", cursor.getString(cursor.getColumnIndexOrThrow("name")));
                    }
                }
            }
        }
    }

    public static class ImportTestApplication extends EhApplication {
        @Override
        public void onCreate() {
            // Database import does not need the production network/native image initialization.
        }
    }

    private void initializePreferences(Context context) {
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("compatibility-test", Context.MODE_PRIVATE));
    }

    private void importBackup(int version) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        initializePreferences(context);
        File backup = new File(context.getCacheDir(), "upstream.db");
        try (SQLiteDatabase source = upstream(version, backup)) {
            assertEquals(version, source.getVersion());
        }
        byte[] original = Files.readAllBytes(backup.toPath());
        try (SQLiteDatabase destination = SQLiteDatabase.create(null)) {
            DaoMaster.createAllTables(new org.greenrobot.greendao.database.StandardDatabase(destination), false);
            DaoSession session = new DaoMaster(destination).newSession();
            ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", session);
            assertNull(EhDB.importDB(context, backup, new Handler(Looper.getMainLooper())));
            assertEquals("title", session.getDownloadsDao().load(42L).title);
            assertEquals("test", session.getQuickSearchDao().loadAll().get(0).name);
            assertFalse(session.getQuickSearchDao().loadAll().get(0).subscribed);
            assertEquals("artist", session.getGalleryTagsDao().load(42L).getArtist());
            assertTrue(Settings.isGalleryVersionUpdatePromptPending());
        } finally {
            ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        }
        assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
        assertEquals(0, context.getCacheDir().list((dir, name) -> name.startsWith("eh-import-")).length);
    }

    @Test
    @Config(application = ImportTestApplication.class)
    public void importsUpstream7WithoutChangingBackup() throws Exception {
        importBackup(7);
    }

    @Test
    @Config(application = ImportTestApplication.class)
    public void importsUpstream8WithoutChangingBackup() throws Exception {
        importBackup(8);
    }

    @Test
    public void rejectsMalformedBackupBeforeTouchingLiveData() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File backup = new File(context.getCacheDir(), "malformed.db");
        try (SQLiteDatabase source = upstream(8, backup)) {
            source.execSQL("DROP TABLE HISTORY");
        }
        byte[] original = Files.readAllBytes(backup.toPath());
        // No live DaoSession/DownloadManager: schema rejection must happen before accessing them.
        assertNotNull(EhDB.importDB(context, backup, null));
        assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
        assertEquals(0, context.getCacheDir().list((dir, name) -> name.startsWith("eh-import-")).length);
    }

    @Test
    public void upgradesInstalledLegacyForkDatabases() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        initializePreferences(context);
        for (int version : new int[] {8, 9}) {
            File installed = context.getDatabasePath("eh.db");
            installed.getParentFile().mkdirs();
            try (SQLiteDatabase source = upstream(7, installed)) {
                source.execSQL("ALTER TABLE QUICK_SEARCH ADD COLUMN SUBSCRIBED INTEGER NOT NULL DEFAULT 0");
                source.execSQL("UPDATE QUICK_SEARCH SET SUBSCRIBED = 1");
                if (version == 9) {
                    source.execSQL("ALTER TABLE DOWNLOADS ADD COLUMN FIRST_GID INTEGER");
                    source.execSQL("UPDATE DOWNLOADS SET FIRST_GID = 40");
                }
                source.setVersion(version);
            }
            Settings.setGalleryVersionUpdatePromptPending(false);
            EhDB.initialize(context);
            DaoSession session = ReflectionHelpers.getStaticField(EhDB.class, "sDaoSession");
            try {
                assertImportedRows((SQLiteDatabase) session.getDatabase().getRawDatabase(),
                        true, version == 9 ? 40L : null);
                assertEquals(version == 8, Settings.isGalleryVersionUpdatePromptPending());
            } finally {
                session.getDatabase().close();
                ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
                context.deleteDatabase("eh.db");
            }
        }
    }

    @Test
    public void compatibleExportKeepsVersion7ShapeAndCanBeReimported() throws Exception {
        try (SQLiteDatabase db = upstream(7)) {
            EhDB.prepareImportDatabase(db);
            db.execSQL("UPDATE QUICK_SEARCH SET SUBSCRIBED = 1");
            db.execSQL("UPDATE DOWNLOADS SET FIRST_GID = 40");
            Method export = EhDB.class.getDeclaredMethod("makeUpstreamCompatible", SQLiteDatabase.class);
            export.setAccessible(true);
            export.invoke(null, db);
            assertEquals(7, db.getVersion());
            try (Cursor cursor = db.rawQuery("SELECT * FROM QUICK_SEARCH", null)) {
                assertEquals(-1, cursor.getColumnIndex("SUBSCRIBED"));
            }
            try (Cursor cursor = db.rawQuery("SELECT * FROM DOWNLOADS", null)) {
                assertEquals(-1, cursor.getColumnIndex("FIRST_GID"));
            }
            // Current upstream can upgrade this compatible export to its schema 8.
            db.execSQL("ALTER TABLE Gallery_Tags ADD COLUMN LOCATION TEXT");
            db.setVersion(8);
            assertTrue(EhDB.prepareImportDatabase(db));
            assertImportedRows(db, false, null);
        }
    }
}
