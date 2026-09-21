package com.hippo.ehviewer.download;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.SparseArray;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.unifile.UniFile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Offline examples: real image validation/copy, with the already-loaded source index injected.
 * Does not simulate the server, source metadata discovery, or SpiderQueen's network worker. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryUpdateExamplesTest {
    private static final long TARGET = 200;
    private static final String A = "1111111111", B = "2222222222", C = "3333333333";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Map<String, UniFile> source = new HashMap<>();
    private File targetDir;
    private SpiderDen den;

    @Before public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("gallery-update-examples", Context.MODE_PRIVATE));
        cache("PLAN_CACHE").clear();
        cache("SOURCE_CACHE").clear();
        cache("PLAN_CACHE").put(TARGET,
                new GalleryUpdateManager.UpdatePlan(TARGET, 100, List.of(100L)));
        Class<?> type = Class.forName(GalleryUpdateManager.class.getName() + "$SourcePages");
        Constructor<?> constructor = type.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        cache("SOURCE_CACHE").put(TARGET, constructor.newInstance(source));
        targetDir = temporary.newFolder("target");
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = TARGET;
        den = new SpiderDen(gallery);
        ReflectionHelpers.setField(den, "mDownloadDir", UniFile.fromFile(targetDir));
        den.setMode(SpiderQueen.MODE_DOWNLOAD);
        image(A, 0xff112233);
        image(B, 0xff445566);
        image(C, 0xff778899);
    }

    private Map<Long, Object> cache(String field) {
        return ReflectionHelpers.getStaticField(GalleryUpdateManager.class, field);
    }

    private void image(String token, int color) throws Exception {
        File file = temporary.newFile(token + ".png");
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
        source.put(token, UniFile.fromFile(file));
    }

    private boolean reuse(int page, String token) {
        return GalleryUpdateManager.tryReuse(TARGET, page, token, den);
    }

    private void copied(int page, String token) throws Exception {
        assertTrue(reuse(page, token));
        assertArrayEquals(Files.readAllBytes(new File(source.get(token).getUri().getPath()).toPath()),
                Files.readAllBytes(new File(targetDir,
                        SpiderDen.generateImageFilename(page, ".png")).toPath()));
    }

    @Test public void appendDownloadsOnlyNewToken() throws Exception {
        copied(0, A); copied(1, B); copied(2, C);
        assertFalse(reuse(3, "4444444444"));
        assertEquals(3, targetDir.list().length);
    }

    @Test public void insertionDoesNotShiftTheWrongImageIntoPlace() throws Exception {
        copied(0, A); assertFalse(reuse(1, "4444444444"));
        copied(2, B); copied(3, C);
    }

    @Test public void deletionAndReorderUseContentIdentity() throws Exception {
        copied(0, C); copied(1, A);
        assertEquals(2, targetDir.list().length);
    }

    @Test public void replacementAtSamePageRequiresDownload() {
        assertFalse(reuse(1, "4444444444"));
        assertEquals(0, targetDir.list().length);
    }

    @Test public void repeatedTargetImageCanBeCopiedMoreThanOnce() throws Exception {
        copied(0, A); copied(1, A); copied(2, A);
    }

    @Test public void invalidAndUnknownTokensCannotReuse() {
        for (String token : new String[]{null, "", "failed", "4444444444"}) {
            assertFalse(reuse(0, token));
        }
    }

    @Test public void missingEmptyAndNonImageSourcesRequireDownload() throws Exception {
        assertTrue(source.get(A).delete());
        Files.write(new File(source.get(B).getUri().getPath()).toPath(), new byte[0]);
        Files.write(new File(source.get(C).getUri().getPath()).toPath(), "not an image".getBytes());
        assertFalse(reuse(0, A)); assertFalse(reuse(1, B)); assertFalse(reuse(2, C));
    }

    @Test public void unavailableDestinationFallsBackToDownload() throws Exception {
        ReflectionHelpers.setField(den, "mDownloadDir", UniFile.fromFile(temporary.newFile()));
        assertFalse(reuse(0, A));
    }

    @Test public void existingCorruptTargetStillSatisfiesWorkerExistenceCheck() throws Exception {
        File broken = new File(targetDir, SpiderDen.generateImageFilename(0, ".png"));
        Files.write(broken.toPath(), "interrupted download".getBytes());
        assertFalse(SpiderDen.isReadableImage(UniFile.fromFile(broken)));
        // SpiderQueen checks contain() before resolving pToken or attempting parent reuse.
        assertTrue(den.contain(0));
    }

    @Test public void readingModeDoesNotCopy() {
        den.setMode(SpiderQueen.MODE_READ);
        assertFalse(reuse(0, A));
    }

    @Test public void noPlanDoesNotReuse() {
        assertFalse(GalleryUpdateManager.tryReuse(999, 0, A, den));
    }

    @Test public void originalQualitySettingDoesNotInvalidateAnExistingResample() throws Exception {
        // Characterizes a limitation: pToken does not encode the locally downloaded quality.
        Settings.putDownloadOriginImage(true);
        copied(0, A);
    }

    private SpiderInfo info(int position, String... tokens) {
        SpiderInfo result = new SpiderInfo();
        result.pages = tokens.length;
        result.startPage = position;
        result.pTokenMap = new SparseArray<>();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i] != null) result.pTokenMap.put(i, tokens[i]);
        }
        return result;
    }

    @Test public void readingPositionFollowsInsertedAndReorderedPages() {
        assertEquals(2, GalleryUpdateManager.findMappedStartPage(info(1, A, B, C),
                info(0, A, "new", B, C)));
        assertEquals(0, GalleryUpdateManager.findMappedStartPage(info(2, A, B, C),
                info(0, C, A, B)));
    }

    @Test public void deletedReadingPagePrefersNextSurvivor() {
        assertEquals(1, GalleryUpdateManager.findMappedStartPage(info(1, A, B, C),
                info(0, A, C)));
    }

    @Test public void allPagesReplacedHaveNoReadingAnchor() {
        assertEquals(-1, GalleryUpdateManager.findMappedStartPage(info(1, A, B, C),
                info(0, "new1", "new2")));
    }

    @Test public void repeatedReadingTokenUsesProportionalPosition() {
        assertEquals(3, GalleryUpdateManager.findMappedStartPage(info(2, A, B, A),
                info(0, A, B, C, A)));
    }

    @Test public void incompleteTargetCanChooseNeighborBeforeExactAnchorIsKnown() {
        // Characterizes premature migration after a partial download: C is not yet resolved.
        assertEquals(1, GalleryUpdateManager.findMappedStartPage(info(2, A, B, C),
                info(0, A, B, null)));
        assertEquals(2, GalleryUpdateManager.findMappedStartPage(info(2, A, B, C),
                info(0, A, B, C)));
    }
}
