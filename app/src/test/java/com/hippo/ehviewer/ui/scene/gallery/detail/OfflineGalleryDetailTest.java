package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Parcel;
import android.util.SparseArray;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryPreview;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.unifile.UniFile;
import com.hippo.ehviewer.widget.LocalImageLoader;
import com.hippo.streampipe.InputStreamPipe;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class OfflineGalleryDetailTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private GalleryInfo source() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 123;
        info.token = "token";
        info.title = "Saved title";
        info.titleJpn = "保存的标题";
        info.uploader = "uploader";
        info.posted = "2026-10-01";
        info.rating = 4.5f;
        info.simpleLanguage = "ZH";
        return info;
    }

    private File image(String name) throws Exception {
        File file = new File(temporary.getRoot(), name);
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        return file;
    }

    private void metadata(String token) throws Exception {
        SpiderInfo info = new SpiderInfo();
        info.gid = 123;
        info.token = token;
        info.pages = 10;
        info.startPage = 4;
        info.previewPages = 1;
        info.previewPerPage = 20;
        info.pTokenMap = new SparseArray<>();
        try (FileOutputStream stream = new FileOutputStream(new File(temporary.getRoot(), ".ehviewer"))) {
            info.write(stream);
        }
    }

    @Test public void metadataAndLocalImagesRemainUsableAfterGalleryDisappears() throws Exception {
        metadata("token");
        image("00000001.jpg");
        image("00000003.png");
        File thumbnail = image(".thumb");
        byte[] before = Files.readAllBytes(new File(temporary.getRoot(), ".ehviewer").toPath());
        GalleryInfo source = source();
        OfflineGalleryDetail result = OfflineGalleryDetail.read(source, null, UniFile.fromFile(temporary.getRoot()));
        assertNotNull(result);
        assertEquals(10, result.info.pages);
        assertEquals(4, result.startPage);
        assertEquals(2, result.previews.size());
        assertEquals(0, result.previews.get(0).getPosition());
        assertEquals(2, result.previews.get(1).getPosition());
        assertEquals(UniFile.fromFile(thumbnail).getUri(), result.cover.getUri());
        assertEquals(6, result.imageBytes);
        assertArrayEquals(before, Files.readAllBytes(new File(temporary.getRoot(), ".ehviewer").toPath()));
        assertEquals(0, source.pages);
        assertNull(result.info.comments);
    }

    @Test public void mismatchedMetadataDoesNotInventPageCountOrReadingProgress() throws Exception {
        metadata("another-token");
        image("00000003.jpg");
        OfflineGalleryDetail result = OfflineGalleryDetail.read(source(), null, UniFile.fromFile(temporary.getRoot()));
        assertEquals(0, result.info.pages);
        assertEquals(0, result.startPage);
        assertEquals(2, result.previews.get(0).getPosition());
        assertEquals(result.previews.get(0).getLocalFile().getUri(), result.cover.getUri());
    }

    @Test public void savedTagsAndSimpleTagsKeepNamespacesAndRemoveDuplicates() {
        GalleryTags saved = new GalleryTags(123);
        saved.artist = "alice, bob";
        saved.language = "chinese";
        saved.location = "beach";
        GalleryTagGroup[] groups = OfflineGalleryDetail.tags(saved,
                new String[]{"artist:alice", "female:glasses", "miscellaneous", null, "male:"});
        assertEquals(5, groups.length);
        assertEquals("artist", groups[0].groupName);
        assertEquals(2, groups[0].size());
        assertEquals("bob", groups[0].getTagAt(1));
        OfflineGalleryDetail result = OfflineGalleryDetail.read(source(), saved, null);
        assertEquals("chinese", result.info.language);
        assertNull(result.directory);
    }

    @Test public void identifierAloneDoesNotQualifyAsAnOfflineDetail() {
        GalleryInfo empty = new GalleryInfo();
        empty.gid = 123;
        empty.token = "token";
        assertNull(OfflineGalleryDetail.read(empty, null, null));
        GalleryTags saved = new GalleryTags(123);
        saved.artist = "alice";
        assertNotNull(OfflineGalleryDetail.read(empty, saved, null));
    }

    @Test public void previewScanningRejectsSidecarsUnsupportedFilesAndInvalidPageNumbers() throws Exception {
        image("00000003.webp");
        image("00000001.JPG");
        image("00000001.png");
        image("cover.jpg");
        image("0.jpg");
        image("100001.jpg");
        image("00000002.txt");
        image(".thumb");
        ArrayList<GalleryPreview> result = OfflineGalleryDetail.readPreviews(UniFile.fromFile(temporary.getRoot()));
        assertEquals(2, result.size());
        assertEquals(0, result.get(0).getPosition());
        assertEquals(2, result.get(1).getPosition());
    }

    @Test public void localPreviewSurvivesParcelRoundTrip() throws Exception {
        GalleryPreview preview = GalleryPreview.fromLocalFile(2, UniFile.fromFile(image("00000003.jpg")));
        Parcel parcel = Parcel.obtain();
        preview.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        GalleryPreview restored = GalleryPreview.CREATOR.createFromParcel(parcel);
        assertEquals(2, restored.getPosition());
        // Uri.fromFile on the Windows Robolectric host differs from Android; verify the stored URI verbatim.
        assertEquals(org.robolectric.util.ReflectionHelpers.<String>getField(preview, "imageUrl"),
                org.robolectric.util.ReflectionHelpers.<String>getField(restored, "imageUrl"));
        parcel.recycle();
    }

    @Test public void largeLocalImagesProduceBoundedCachedThumbnailsWithoutChangingOriginals() throws Exception {
        File original = new File(temporary.getRoot(), "large.png");
        Bitmap bitmap = Bitmap.createBitmap(1536, 2048, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(original)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
        byte[] before = Files.readAllBytes(original.toPath());
        InputStreamPipe pipe = ReflectionHelpers.callStaticMethod(LocalImageLoader.class, "thumbnail",
                ReflectionHelpers.ClassParameter.from(Context.class, RuntimeEnvironment.getApplication()),
                ReflectionHelpers.ClassParameter.from(UniFile.class, UniFile.fromFile(original)),
                ReflectionHelpers.ClassParameter.from(String.class, "large-preview-test"));
        assertNotNull(pipe);
        try {
            pipe.obtain();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(pipe.open(), null, bounds);
            assertTrue(bounds.outWidth > 0 && bounds.outWidth <= 512);
            assertTrue(bounds.outHeight > 0 && bounds.outHeight <= 512);
        } finally {
            pipe.close();
            pipe.release();
        }
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
    }
}
