package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DaoSession;
import com.hippo.ehviewer.dao.QuickSearch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(application = BookmarkNamesTest.TestApplication.class, sdk = 28)
public class BookmarkNamesTest {
    private SQLiteDatabase database;
    private DaoSession session;
    private Activity activity;
    private EhTagDatabase tags;

    @Before public void setUp() throws Exception {
        TestApplication app = (TestApplication) RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new org.greenrobot.greendao.database.StandardDatabase(database), false);
        session = new DaoMaster(database).newSession();
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", session);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Main);
        // A deterministic translation source without network or downloaded tag data.
        tags = new EhTagDatabase("test", new Buffer().writeInt(0)) {
            @Override public String getTranslation(String tag) {
                if ("n:language".equals(tag)) return "语言";
                if ("l:chinese".equals(tag)) return "中文";
                return null;
            }
        };
    }

    @After public void tearDown() {
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        database.close();
        activity.finish();
    }

    @Test public void disabledTranslationsPreserveAllCustomNamesInMemoryAndDatabase() {
        String[] names = {"我的收藏", "My favorites", "language:chinese", "分类:收藏", "译名", "a:b:c"};
        for (String name : names) insert(name, "artist:someone");
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        ListView drawer = openDrawer();
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], label(drawer, i));
            assertEquals(names[i], ((QuickSearch) drawer.getAdapter().getItem(i)).name);
        }
        assertStoredNames(names);
    }

    @Test public void enabledTranslationsPreserveCustomNamesEvenIfTheyLookLikeTags() {
        insert("language:chinese", "artist:someone");
        insert("分类:收藏", "language:chinese");
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        ListView drawer = openDrawer();
        assertEquals("language:chinese", label(drawer, 0));
        assertEquals("分类:收藏", label(drawer, 1));
        assertStoredNames("language:chinese", "分类:收藏");
    }

    @Test public void languageChangesAndRepeatedDrawerCreationDoNotRewriteNames() {
        insert("我的收藏", "language:chinese");
        insert("分类:收藏", "artist:someone");
        for (String language : new String[]{"en", "zh-CN", "ja", "zh-TW", "en"}) {
            Settings.putAppLanguage(language);
            ListView drawer = openDrawer();
            assertEquals("我的收藏", label(drawer, 0));
            assertEquals("分类:收藏", label(drawer, 1));
            assertStoredNames("我的收藏", "分类:收藏");
        }
    }

    @Test public void defaultTagNameIsTranslatedOnlyForDisplayAndCanSwitchBack() {
        insert("language:chinese", "language:chinese");
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        ListView drawer = openDrawer();
        assertEquals("语言:中文", label(drawer, 0));
        assertEquals("language:chinese", ((QuickSearch) drawer.getAdapter().getItem(0)).name);
        assertStoredNames("language:chinese");
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        assertEquals("language:chinese", label(drawer, 0));
        assertEquals("language:chinese", label(openDrawer(), 0));
        assertStoredNames("language:chinese");
    }

    @Test public void missingTranslationDataLeavesDefaultNameIntact() {
        insert("language:chinese", "language:chinese");
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        tags = null;
        ReflectionHelpers.setStaticField(EhTagDatabase.class, "instance", null);
        assertEquals("language:chinese", label(openDrawer(), 0));
        assertStoredNames("language:chinese");
    }

    @Test public void quotedDefaultTagNamesKeepTheirSearchSyntaxInStorage() {
        String keyword = "language:\"chinese$\"  l:\"chinese$\"";
        insert(keyword, keyword);
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        assertEquals("语言:中文  语言:中文", label(openDrawer(), 0));
        assertStoredNames(keyword);
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, false);
        assertEquals(keyword, label(openDrawer(), 0));
    }

    @Test public void addingBookmarkPreservesEnteredNameWithTranslationEnabled() {
        Settings.putBoolean(Settings.KEY_SHOW_TAG_TRANSLATIONS, true);
        TestScene scene = new TestScene(activity);
        scene.mUrlBuilder = new ListUrlBuilder();
        scene.mUrlBuilder.setKeyword("artist:someone");
        ReflectionHelpers.setField(scene, "ehTags", tags);
        List<QuickSearch> list = new ArrayList<>();
        ArrayAdapter<QuickSearch> adapter = new ArrayAdapter<>(activity, R.layout.item_simple_list, list);
        scene.showAddQuickSearchDialog(list, adapter, new ListView(activity), new TextView(activity));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        String name = "language:chinese  \"我的$收藏\"";
        ((EditText) dialog.findViewById(R.id.edit_text)).setText(name);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(1, list.size());
        assertEquals(name, list.get(0).name);
        assertStoredNames(name);
        assertEquals(name, label(openDrawer(), 0));
    }

    private void insert(String name, String keyword) {
        QuickSearch search = new QuickSearch();
        search.name = name;
        search.keyword = keyword;
        search.time = session.getQuickSearchDao().count() + 1;
        EhDB.insertQuickSearch(search);
    }

    private ListView openDrawer() {
        View view = new BookmarksDraw(activity, LayoutInflater.from(activity), tags)
                .onCreate(new GalleryListScene());
        return view.findViewById(R.id.list_view);
    }

    private String label(ListView drawer, int index) {
        return ((TextView) drawer.getAdapter().getView(index, null, drawer)).getText().toString();
    }

    private void assertStoredNames(String... names) {
        // Bypass greenDAO's identity cache to check the values actually written to SQLite.
        try (android.database.Cursor cursor = database.rawQuery("SELECT NAME FROM QUICK_SEARCH ORDER BY TIME", null)) {
            for (String name : names) {
                assertTrue(cursor.moveToNext());
                assertEquals(name, cursor.getString(0));
            }
            assertFalse(cursor.moveToNext());
        }
    }

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() { }
    }

    private static class TestScene extends GalleryListScene {
        private final Context context;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
    }
}
