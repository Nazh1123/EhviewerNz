package com.hippo.ehviewer.ui.scene.history;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import androidx.appcompat.view.ContextThemeWrapper;

import com.hippo.a7zip.InArchive;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.A7ZipArchive;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.unifile.UniRandomAccessFile;
import com.hippo.widget.LoadImageView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application.class, sdk = 28, shadows = {
        ReadingHistoryUiTest.AppShadow.class, ReadingHistoryUiTest.ImageShadow.class,
        ReadingHistoryCoverTest.ArchiveShadow.class, ReadingHistoryCoverTest.EntryShadow.class})
public class ReadingHistoryCoverTest {
    private Context context;
    private static byte[] image;
    private static List<A7ZipArchive.A7ZipArchiveEntry> archiveEntries;
    private static final List<String> extracted = new ArrayList<>();
    private static CountDownLatch started;
    private static CountDownLatch release;

    @Before public void setUp() throws Exception {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        Bitmap bitmap = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        image = output.toByteArray();
        archiveEntries = new ArrayList<>(Arrays.asList(archiveEntry("10.png"), archiveEntry("2.png")));
        extracted.clear();
        started = null;
        release = null;
    }

    @Test public void loadsOnlineCoverAndClearsItWhenRowIsRecycled() {
        LoadImageView view = new LoadImageView(context);
        ReadingHistoryCoverLoader.load(view, online());
        ReadingHistoryUiTest.ImageShadow shadow = org.robolectric.shadow.api.Shadow.extract(view);
        assertEquals("https://example.com/cover.jpg", shadow.url);
        ReadingHistoryCoverLoader.clear(view);
        assertNull(shadow.url);
        assertNull(view.getDrawable());
    }

    @Test public void decodesExternalImagesAndLocalFolderThumbsWithBoundedDimensions() throws Exception {
        File file = imageFile();
        ReadingHistory.Entry entry = local(file);
        Bitmap cover = ReadingHistoryCoverLoader.localCover(context, entry);
        assertTrue(Math.max(cover.getWidth(), cover.getHeight()) <= 480);
        assertEquals(Color.BLUE, cover.getPixel(0, 0));
        entry.gallery = new GalleryInfo();
        entry.gallery.gid = -123;
        entry.gallery.thumb = entry.uri;
        entry.uri = null;
        Bitmap folderCover = ReadingHistoryCoverLoader.localCover(context, entry);
        assertEquals(Color.BLUE, folderCover.getPixel(0, 0));
    }

    @Test public void archiveCoverExtractsOnlyFirstImageInNaturalOrderAndRemovesTemporaryFile() throws Exception {
        ReadingHistoryCoverLoader.localCover(context, local(archiveFile()));
        assertEquals(Arrays.asList("2.png"), extracted);
        File[] remaining = context.getCacheDir().listFiles((directory, name) -> name.startsWith("reading-cover-"));
        assertEquals(0, remaining.length);
    }

    @Test public void delayedLocalCoverCannotReplaceReboundOnlineCover() throws Exception {
        LoadImageView view = new LoadImageView(context);
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
        ReadingHistoryCoverLoader.load(view, local(archiveFile()));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        ReadingHistoryCoverLoader.load(view, online());
        release.countDown();
        CountDownLatch workersIdle = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) ReadingHistoryCoverLoader.IO.execute(() -> {
                workersIdle.countDown();
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(workersIdle.await(5, TimeUnit.SECONDS));
        } finally { gate.countDown(); }
        shadowOf(Looper.getMainLooper()).idle();
        ReadingHistoryUiTest.ImageShadow shadow = org.robolectric.shadow.api.Shadow.extract(view);
        assertEquals("https://example.com/cover.jpg", shadow.url);
        assertFalse(view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
    }

    private ReadingHistory.Entry online() {
        ReadingHistory.Entry entry = new ReadingHistory.Entry();
        entry.gallery = new GalleryInfo();
        entry.gallery.gid = 1;
        entry.gallery.thumb = "https://example.com/cover.jpg";
        return entry;
    }
    private ReadingHistory.Entry local(File file) {
        ReadingHistory.Entry entry = new ReadingHistory.Entry();
        entry.action = Intent.ACTION_VIEW;
        // Preserve the host path in Robolectric, including Windows drive letters.
        entry.uri = new Uri.Builder().scheme("file").path(file.getAbsolutePath()).build().toString();
        entry.key = entry.uri;
        entry.readAt = System.nanoTime();
        return entry;
    }
    private File imageFile() throws Exception {
        File file = File.createTempFile("source-cover-", ".png", context.getCacheDir());
        try (OutputStream output = new FileOutputStream(file)) { output.write(image); }
        return file;
    }
    private File archiveFile() throws Exception {
        return File.createTempFile("source-archive-", ".cbz", context.getCacheDir());
    }
    private A7ZipArchive.A7ZipArchiveEntry archiveEntry(String path) {
        return ReflectionHelpers.callConstructor(A7ZipArchive.A7ZipArchiveEntry.class,
                ClassParameter.from(InArchive.class, null), ClassParameter.from(int.class, 0),
                ClassParameter.from(String.class, path));
    }
    @Implements(A7ZipArchive.class)
    public static class ArchiveShadow {
        @Implementation protected static A7ZipArchive create(UniRandomAccessFile input) {
            return ReflectionHelpers.callConstructor(A7ZipArchive.class, ClassParameter.from(InArchive.class, null));
        }
        @Implementation protected List<A7ZipArchive.A7ZipArchiveEntry> getArchiveEntries() { return archiveEntries; }
        @Implementation protected void close() {}
    }
    @Implements(A7ZipArchive.A7ZipArchiveEntry.class)
    public static class EntryShadow {
        @RealObject private A7ZipArchive.A7ZipArchiveEntry entry;
        @Implementation protected void extract(OutputStream output) throws Exception {
            if (started != null) {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Cover worker timed out");
            }
            extracted.add(entry.getPath());
            output.write(image);
        }
    }
}
