package com.hippo.ehviewer.gallery;

import static org.junit.Assert.*;
import android.app.Application;
import android.net.Uri;
import com.hippo.unifile.UniFile;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class ArchiveGalleryProviderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] ORIGINAL = {71, 73, 70, 56, 57, 97, 1, 2, 3};
    private ArchiveGalleryProvider provider(AtomicInteger opens, AtomicInteger closes, boolean fail) throws Exception {
        File archive = temp.newFile("book.cbz");
        return new ArchiveGalleryProvider(RuntimeEnvironment.getApplication(), Uri.fromFile(archive), () -> {
            opens.incrementAndGet();
            return new ArchivePageSource() {
                @Override String[] names() { return new String[]{"Chapter 1/001.gif", "Chapter 2/001.webp"}; }
                @Override void extract(int page, OutputStream output) throws IOException {
                    output.write(ORIGINAL);
                    if (fail) throw new IOException("Damaged archive entry");
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
        });
    }
    @Test public void savesOriginalBytesWithExtensionAndReusesOnlyItsOwnHandle() throws Exception {
        AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        ArchiveGalleryProvider provider = provider(opens, closes, false);
        UniFile directory = UniFile.fromFile(temp.newFolder("output"));
        GalleryProvider2.SaveResult first = provider.saveWithResult(0, directory, "001");
        assertNotNull(first); assertFalse(first.overwritten);
        assertEquals("001.gif", first.file.getName());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(first.file.getUri().getPath()).toPath()));
        assertEquals("Chapter 1/001.gif", provider.getResultPageName(0));
        assertEquals("Chapter 1/001", provider.getTranslationFilename(0));
        assertEquals("001", provider.getImageFilename(0));
        assertEquals("", provider.getResultSourceStamp(0));
        assertTrue(provider.saveWithResult(0, directory, "001").overwritten);
        GalleryProvider2 copy = provider.createTranslationProvider(RuntimeEnvironment.getApplication());
        assertTrue(copy.save(1, UniFile.fromFile(temp.newFile("processing-source.image"))));
        assertEquals(2, opens.get());
        provider.stop(); copy.stop();
        assertEquals(2, closes.get());
        assertFalse(provider.save(0, first.file));
    }
    @Test public void failedExtractionRemovesNewFileAndInvalidPagesDoNotCreateFiles() throws Exception {
        ArchiveGalleryProvider provider = provider(new AtomicInteger(), new AtomicInteger(), true);
        File output = temp.newFolder("output");
        assertNull(provider.saveWithResult(0, UniFile.fromFile(output), "failed"));
        assertEquals(0, output.list().length);
        assertNull(provider.saveWithResult(-1, UniFile.fromFile(output), "invalid"));
        assertNull(provider.saveWithResult(2, UniFile.fromFile(output), "invalid"));
        assertEquals(0, output.list().length);
        provider.stop();
    }
    @Test public void rejectsAbsoluteAndTraversalPathsAndNormalizesRelativePaths() {
        assertEquals("chapter/001.jpg", ArchivePageSource.relativeName(".\\chapter\\001.jpg"));
        assertNull(ArchivePageSource.relativeName("../001.jpg"));
        assertNull(ArchivePageSource.relativeName("chapter/../001.jpg"));
        assertNull(ArchivePageSource.relativeName("/books/001.jpg"));
        assertNull(ArchivePageSource.relativeName("C:\\books\\001.jpg"));
    }
}
