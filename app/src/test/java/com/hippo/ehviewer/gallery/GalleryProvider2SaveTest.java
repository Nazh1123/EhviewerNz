package com.hippo.ehviewer.gallery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.unifile.UniFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class GalleryProvider2SaveTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void existingImageIsReportedAsOverwriteWithoutChangingItDuringDetection()
            throws Exception {
        File existing = temp.newFile("page.jpg");
        byte[] original = {1, 2, 3};
        Files.write(existing.toPath(), original);

        GalleryProvider2.SaveResult result = GalleryProvider2.prepareSaveDestination(
                UniFile.fromFile(temp.getRoot()), "page.jpg");

        assertNotNull(result);
        assertTrue(result.overwritten);
        assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
    }

    @Test
    public void newImageIsReportedAsNew() {
        GalleryProvider2.SaveResult result = GalleryProvider2.prepareSaveDestination(
                UniFile.fromFile(temp.getRoot()), "page.png");

        assertNotNull(result);
        assertFalse(result.overwritten);
        assertTrue(new File(temp.getRoot(), "page.png").isFile());
    }

    @Test
    public void directoryWithSameNameIsNotTreatedAsAnImage() throws Exception {
        temp.newFolder("page.webp");

        assertNull(GalleryProvider2.prepareSaveDestination(
                UniFile.fromFile(temp.getRoot()), "page.webp"));
    }

    @Test
    public void skippedSaveKeepsExistingFileAndCannotBeUndoneAsNew() throws Exception {
        File existing = temp.newFile("page.jpg");
        byte[] original = {4, 5, 6};
        Files.write(existing.toPath(), original);

        GalleryProvider2.SaveResult result = GalleryProvider2.prepareSaveDestination(
                UniFile.fromFile(temp.getRoot()), "page.jpg").skipped();

        assertTrue(result.overwritten);
        assertTrue(result.skipped);
        result.deleteIfCreated();
        assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
    }

    @Test
    public void concurrentRawCreationHasExactlyOneCreator() throws Exception {
        UniFile directory = UniFile.fromFile(temp.getRoot());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<UniFile.CreateFileResult> first = workers.submit(() -> {
                start.await();
                return directory.createFileWithStatus("page.gif");
            });
            Future<UniFile.CreateFileResult> second = workers.submit(() -> {
                start.await();
                return directory.createFileWithStatus("page.gif");
            });
            start.countDown();
            UniFile.CreateFileResult firstResult = first.get(5, TimeUnit.SECONDS);
            UniFile.CreateFileResult secondResult = second.get(5, TimeUnit.SECONDS);
            assertNotNull(firstResult);
            assertNotNull(secondResult);
            assertTrue(firstResult.created != secondResult.created);
        } finally {
            workers.shutdownNow();
        }
    }
}
