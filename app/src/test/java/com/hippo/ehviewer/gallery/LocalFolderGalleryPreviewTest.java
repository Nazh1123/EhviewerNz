package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.hippo.unifile.UniFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30}, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalFolderGalleryPreviewTest {
    private Context context;
    private Uri tree;
    private FixtureProvider documents;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        String authority = "local.preview." + UUID.randomUUID();
        tree = DocumentsContract.buildTreeDocumentUri(authority, "folder");
        documents = new FixtureProvider();
        documents.first = image(Color.RED);
        documents.second = image(Color.BLUE);
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.exported = true;
        info.grantUriPermissions = true;
        documents.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(authority, documents);
        ResolveInfo resolve = new ResolveInfo();
        resolve.providerInfo = info;
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(DocumentsContract.PROVIDER_INTERFACE), resolve);
        ReflectionHelpers.setStaticField(ReaderPreviewBitmapCache.class, "cache", null);
    }

    @Test public void bareTreeStillResolvesToRootDocument() {
        assertEquals(document("folder"), UniFile.fromTreeUri(context, tree).getUri());
    }

    @Test public void treeDocumentWrappingPreservesOpaqueChildId() {
        Uri child = document("opaque:image/2");
        assertTrue(UniFile.isDocumentUri(context, child));
        assertEquals(child, UniFile.fromTreeUri(context, child).getUri());
        assertEquals(child, UniFile.fromUri(context, child).getUri());
        assertEquals(child, UniFile.fromSingleUri(context, child).getUri());
    }

    @Test public void importedPagesKeepDistinctPreviewsAfterDiskCacheReload() throws Exception {
        LocalFolderGallerySource folder = LocalFolderGallerySource.create(tree, "");
        LocalFolderGalleryProvider provider = new LocalFolderGalleryProvider(context, folder.encode());
        AtomicReference<List<LocalFolderGalleryScanner.ImageEntry>> images =
                ReflectionHelpers.getField(provider, "images");
        images.set(LocalFolderGalleryScanner.scan(context, folder).images);
        assertEquals(2, images.get().size());
        assertEquals(document("opaque:image/1"), provider.getPreviewFile(0).getUri());
        assertEquals(document("opaque:image/2"), provider.getPreviewFile(1).getUri());
        int queriesAfterScan = documents.queries;
        assertEquals(123L, images.get().get(0).lastModified);
        assertEquals(456L, images.get().get(0).size);
        try (ReaderPreviewSource source = provider.createPreviewSource(context)) {
            ReaderPreviewBitmapCache.Result first = source.load(0, 128);
            assertEquals(Color.RED, first.bitmap.getPixel(0, 0));
            first.persist(context);
            ReaderPreviewBitmapCache.Result second = source.load(1, 128);
            assertEquals(Color.BLUE, second.bitmap.getPixel(0, 0));
            second.persist(context);
        }
        // A new worker must read each page's own persistent poster, without opening originals.
        documents.rejectOpens = true;
        try (ReaderPreviewSource source = provider.createPreviewSource(context)) {
            assertEquals(Color.BLUE, source.load(1, 128).bitmap.getPixel(0, 0));
            assertEquals(Color.RED, source.load(0, 128).bitmap.getPixel(0, 0));
        }
        assertNull(provider.getPreviewFile(2));
        assertEquals("Previews must reuse scan metadata", queriesAfterScan, documents.queries);
    }

    @Test public void missingOptionalMetadataStillAllowsScanningAndPreview() throws Exception {
        documents.omitMetadata = true;
        LocalFolderGallerySource folder = LocalFolderGallerySource.create(tree, "");
        List<LocalFolderGalleryScanner.ImageEntry> images = LocalFolderGalleryScanner.scan(context, folder).images;
        assertEquals(2, images.size());
        assertEquals(-1L, images.get(0).lastModified);
        assertEquals(-1L, images.get(0).size);
        List<LocalFolderGalleryScanner.ImageEntry> rescanned = LocalFolderGalleryScanner.scan(context, folder).images;
        assertNotEquals(images.get(0).previewIdentity, rescanned.get(0).previewIdentity);
        LocalFolderGalleryProvider provider = new LocalFolderGalleryProvider(context, folder.encode());
        AtomicReference<List<LocalFolderGalleryScanner.ImageEntry>> current = ReflectionHelpers.getField(provider, "images");
        current.set(images);
        try (ReaderPreviewSource preview = provider.createPreviewSource(context)) {
            assertEquals(Color.RED, preview.load(0, 128).bitmap.getPixel(0, 0));
        }
    }

    @Test public void changedMetadataInvalidatesThePreviewIdentity() throws Exception {
        LocalFolderGallerySource folder = LocalFolderGallerySource.create(tree, "");
        String identity = LocalFolderGalleryScanner.scan(context, folder).images.get(0).previewIdentity;
        documents.modified++;
        assertNotEquals(identity, LocalFolderGalleryScanner.scan(context, folder).images.get(0).previewIdentity);
    }

    @Test public void directFolderScanSharesNaturalOrderingAndExcludesTranslatedPages() throws Exception {
        File root = java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(), "direct-gallery").toFile();
        java.nio.file.Files.copy(documents.first.toPath(), new File(root, "10.png").toPath());
        java.nio.file.Files.copy(documents.second.toPath(), new File(root, "2.png").toPath());
        File child = new File(root, "Nested");
        assertTrue(child.mkdir());
        java.nio.file.Files.copy(documents.first.toPath(), new File(child, "1.png").toPath());
        File translated = new File(root, "_translated");
        assertTrue(translated.mkdir());
        java.nio.file.Files.copy(documents.first.toPath(), new File(translated, "1.png").toPath());
        File enhanced = new File(root, "_super_resolution");
        assertTrue(enhanced.mkdir());
        java.nio.file.Files.copy(documents.first.toPath(), new File(enhanced, "1_sr.png").toPath());
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageDirectory(root.getParentFile().toPath());
        LocalFolderGallerySource folder = LocalFolderGallerySource.create(DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", "primary:" + root.getName()), "");
        int before = documents.queries;
        LocalFolderGalleryScanner.ScanResult result = LocalFolderGalleryScanner.scan(context, folder);
        assertEquals(3, result.images.size());
        assertEquals(2, result.directImageCount);
        assertEquals("2.png", result.images.get(0).relativePath);
        assertEquals("10.png", result.images.get(1).relativePath);
        assertEquals("Nested/1.png", result.images.get(2).relativePath);
        assertEquals("file", result.images.get(0).uri.getScheme());
        assertTrue(result.images.get(0).size > 0);
        assertEquals(before, documents.queries);
        assertEquals(2, LocalFolderGalleryScanner.scan(context, folder, false).images.size());
        LocalFolderGalleryProvider provider = new LocalFolderGalleryProvider(context, folder.encode());
        AtomicReference<List<LocalFolderGalleryScanner.ImageEntry>> current = ReflectionHelpers.getField(provider, "images");
        current.set(result.images);
        try (ReaderPreviewSource preview = provider.createPreviewSource(context)) {
            assertEquals(Color.BLUE, preview.load(0, 128).bitmap.getPixel(0, 0));
            assertEquals(Color.RED, preview.load(1, 128).bitmap.getPixel(0, 0));
        }
    }

    @Test public void directDirectoryFailureFallsBackToSaf() throws Exception {
        // The fixture authority has opaque IDs; it must never be treated as a filesystem path.
        assertNull(ExternalImageFileResolver.resolveDirectory(context, tree));
        assertEquals(2, LocalFolderGalleryScanner.scan(context, LocalFolderGallerySource.create(tree, "")).images.size());
    }

    @Test public void folderOpenedFromAnImageKeepsItsSelectedPage() throws Exception {
        LocalFolderGallerySource folder = LocalFolderGallerySource.create(tree, "");
        LocalFolderGalleryProvider provider = new LocalFolderGalleryProvider(context, folder.encode(),
                document("opaque:image/2"), "001.png");
        List<LocalFolderGalleryScanner.ImageEntry> pages = LocalFolderGalleryScanner.scan(context, folder, false).images;
        int selected = ReflectionHelpers.callInstanceMethod(provider, "findStartPage",
                ReflectionHelpers.ClassParameter.from(List.class, pages));
        assertEquals(1, selected); // Exact document identity takes precedence over a display name.
    }

    private Uri document(String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private File image(int color) throws Exception {
        File file = File.createTempFile("local-preview-", ".png", context.getCacheDir());
        Bitmap bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        return file;
    }

    private static final class FixtureProvider extends ContentProvider {
        File first;
        File second;
        boolean rejectOpens;
        boolean omitMetadata;
        long modified = 123L;
        int queries;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] args, String order) {
            queries++;
            if (omitMetadata && java.util.Arrays.asList(projection).contains(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED)) throw new IllegalArgumentException("Unsupported metadata");
            MatrixCursor cursor = new MatrixCursor(projection);
            String id = DocumentsContract.getDocumentId(uri);
            if ("children".equals(uri.getLastPathSegment())) {
                row(cursor, projection, "opaque:image/1");
                row(cursor, projection, "opaque:image/2");
            } else row(cursor, projection, id);
            return cursor;
        }
        private void row(MatrixCursor cursor, String[] projection, String id) {
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                values[i] = switch (projection[i]) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                            "folder".equals(id) ? "Folder" : id.endsWith("1") ? "001.png" : "002.png";
                    case DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            "folder".equals(id) ? DocumentsContract.Document.MIME_TYPE_DIR : "image/png";
                    // Both pages have identical metadata; only the document ID distinguishes them.
                    case DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modified;
                    case DocumentsContract.Document.COLUMN_SIZE -> 456L;
                    default -> null;
                };
            }
            cursor.addRow(values);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (rejectOpens) throw new AssertionError("Disk cache hit reopened original: " + uri);
            String id = DocumentsContract.getDocumentId(uri);
            if ("folder".equals(id)) throw new AssertionError("Preview opened tree root instead of image");
            return ParcelFileDescriptor.open(id.endsWith("1") ? first : second,
                    ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "image/png"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
