package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.util.SparseArray;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.download.GalleryUpdateRecord;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.unifile.UniFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        shadows = {UpdatedLocalGalleryProviderTest.LocalMetadata.class,
                UpdatedLocalGalleryProviderTest.LocalDirectory.class,
                UpdatedLocalGalleryProviderTest.DirectExecutor.class})
public class UpdatedLocalGalleryProviderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Context context;
    private GalleryUpdateRecordStore store;
    private GalleryInfo gallery;
    private long time;
    private static UniFile directory;
    private static SpiderInfo current;

    private static SpiderInfo info(long gid, String... tokens) {
        SpiderInfo info = new SpiderInfo(); info.gid = gid; info.pages = tokens.length;
        info.pTokenMap = new SparseArray<>();
        for (int i = 0; i < tokens.length; i++) info.pTokenMap.put(i, tokens[i]);
        return info;
    }

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("gallery_update_records.db");
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("local-updates", Context.MODE_PRIVATE));
        store = ReflectionHelpers.callConstructor(GalleryUpdateRecordStore.class,
                ReflectionHelpers.ClassParameter.from(Context.class, context));
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", store);
        directory = UniFile.fromFile(folder.newFolder());
        GalleryUpdateRecord record = ReflectionHelpers.callStaticMethod(GalleryUpdateRecord.class, "compare",
                ReflectionHelpers.ClassParameter.from(long.class, 200L),
                ReflectionHelpers.ClassParameter.from(long.class, 100L),
                ReflectionHelpers.ClassParameter.from(SpiderInfo.class, info(100, "A", "B")),
                ReflectionHelpers.ClassParameter.from(SpiderInfo.class, info(200, "A", "X", "Y", "B", "Z")));
        GalleryUpdateManager.UpdatePlan plan = ReflectionHelpers.callConstructor(GalleryUpdateManager.UpdatePlan.class,
                ReflectionHelpers.ClassParameter.from(long.class, 200L),
                ReflectionHelpers.ClassParameter.from(long.class, 100L),
                ReflectionHelpers.ClassParameter.from(List.class, List.of(100L)));
        assertTrue(store.stage(plan, () -> record.withFirstGid(100)));
        assertTrue(store.complete(200, 100));
        time = store.find(200).completedAt;
        gallery = new GalleryInfo(); gallery.gid = 400; gallery.token = "latest";
        current = info(400, "Z", "A", "X", "B", "Y", "NEW");
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8XcAAAAASUVORK5CYII=");
        Files.write(folder.getRoot().toPath().resolve(directory.getName()).resolve("00000001.png"), png);
        Files.write(folder.getRoot().toPath().resolve(directory.getName()).resolve("00000003.png"), png);
        Files.write(folder.getRoot().toPath().resolve(directory.getName()).resolve("00000005.png"), new byte[]{1});
    }

    @After public void cleanup() {
        store.close();
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", null);
    }

    @Test public void onlyReadableSurvivingLocalImagesAreSelectedAndProgressUsesHistoricalAnchor() {
        UpdatedGalleryProvider provider = new UpdatedGalleryProvider(context, gallery, 200, time);
        UniFile[] files = provider.loadImageFiles();
        assertNotNull(files);
        assertEquals(2, files.length);
        assertEquals("00000001.png", files[0].getName());
        assertEquals("00000003.png", files[1].getName());
        assertEquals(0, provider.getSourcePage(0));
        assertEquals(2, provider.getSourcePage(1));
        assertEquals(1, provider.getStartPage()); // The first historical addition X was reordered.
        assertEquals("400-latest-00000003", provider.getImageFilename(1));
        assertEquals("00000003", provider.getTranslationFilename(1));
        provider.putStartPage(0); // Z is the third original addition.
        assertEquals(2, store.find(200).readingPage);
        UpdatedGalleryProvider clone = (UpdatedGalleryProvider) provider.createTranslationProvider(context);
        assertNotNull(clone.loadImageFiles());
        assertEquals(0, clone.getStartPage());
        assertEquals(provider.getTranslationIdentity(), clone.getTranslationIdentity());
        assertEquals(directory, clone.getTranslationDirectory());
    }

    @Test public void noLocalPagesAndReplacedRecordsProduceClearErrors() {
        directory.findFile("00000001.png").delete();
        directory.findFile("00000003.png").delete();
        UpdatedGalleryProvider provider = new UpdatedGalleryProvider(context, gallery, 200, time);
        assertNull(provider.loadImageFiles());
        assertEquals(context.getString(R.string.gallery_update_history_no_pages), provider.getError());
        provider = new UpdatedGalleryProvider(context, gallery, 200, time + 1);
        assertNull(provider.loadImageFiles());
        assertEquals(context.getString(R.string.gallery_update_log_unavailable), provider.getError());
    }

    @Test public void deletedResumeAnchorMovesToNextOriginalAddition() {
        assertEquals(1, UpdatedGalleryProvider.resumePosition(1, new int[]{4, 2, 0}));
        assertEquals(2, UpdatedGalleryProvider.resumePosition(0, new int[]{4, 2, 0}));
        assertEquals(0, UpdatedGalleryProvider.resumePosition(5, new int[]{4, 2, 0}));
    }

    @Implements(GalleryUpdateManager.class)
    public static class LocalMetadata {
        @Implementation protected static SpiderInfo readDownloadedSpiderInfo(long gid) { return current; }
    }
    @Implements(SpiderDen.class)
    public static class LocalDirectory {
        @Implementation protected static UniFile getExistingGalleryDownloadDir(GalleryInfo info) { return directory; }
    }
    @Implements(EhApplication.class)
    public static class DirectExecutor {
        @Implementation protected static ExecutorService getExecutorService(Context context) {
            return new AbstractExecutorService() {
                public void shutdown() { }
                public List<Runnable> shutdownNow() { return List.of(); }
                public boolean isShutdown() { return false; }
                public boolean isTerminated() { return false; }
                public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
                public void execute(Runnable command) { command.run(); }
            };
        }
    }
}
