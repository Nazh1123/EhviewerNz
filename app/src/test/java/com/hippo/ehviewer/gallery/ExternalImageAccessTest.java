package com.hippo.ehviewer.gallery;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {28, 30})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ExternalImageAccessTest {
    private Context context;
    private FixtureProvider documents;
    private Uri uri;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("external-image", Context.MODE_PRIVATE));
        ReadingHistory.clear(context);
        documents = new FixtureProvider();
        File directory = java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(), "external-images").toFile();
        documents.image = new File(directory, "2.png");
        Bitmap bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        try (FileOutputStream output = new FileOutputStream(documents.image)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        java.nio.file.Files.copy(documents.image.toPath(), new File(directory, "10.png").toPath());
        String authority = "external.image." + UUID.randomUUID();
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        documents.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(authority, documents);
        uri = Uri.parse("content://" + authority + "/opaque-id");
        Image.Companion.setScreenWidth(128);
        Image.Companion.setScreenHeight(128);
    }

    @Test public void pathlessImageUsesOriginalUriForReadingPreviewAndSaving() throws Exception {
        assertNull(ExternalImageFileResolver.resolve(context, uri));
        SingleImageGalleryProvider provider = new SingleImageGalleryProvider(context, uri);
        UniFile[] files = provider.loadImageFiles();
        assertEquals(1, files.length);
        assertEquals(uri, files[0].getUri());
        assertEquals("2.png", provider.getFilename(0));
        Image decoded = provider.decodeImage(files[0], 0);
        assertNotNull(decoded);
        assertEquals(32, decoded.getWidth());
        assertEquals(48, decoded.getHeight());
        decoded.recycle();
        try (ReaderPreviewSource preview = provider.createPreviewSource(context)) {
            assertEquals(Color.BLUE, preview.load(0, 128).bitmap.getPixel(0, 0));
            assertNull(preview.load(1, 128));
        }
        java.util.concurrent.atomic.AtomicReference<UniFile[]> current = ReflectionHelpers.getField(provider, "mFileList");
        current.set(files);
        File destination = new File(context.getCacheDir(), "saved-" + UUID.randomUUID());
        GalleryProvider2.SaveResult saved = provider.saveWithResult(0, UniFile.fromFile(context.getCacheDir()), destination.getName());
        assertNotNull(saved);
        assertTrue(saved.file.getName().endsWith(".png"));
        assertArrayEquals(java.nio.file.Files.readAllBytes(documents.image.toPath()),
                java.nio.file.Files.readAllBytes(new File(saved.file.getUri().getPath()).toPath()));
    }

    @Test public void externalReaderFallsBackToSingleImageInsteadOfRejectingTheIntent() {
        GalleryActivity activity = reader();
        assertTrue(ReflectionHelpers.getField(activity, "mGalleryProvider") instanceof SingleImageGalleryProvider);
        assertFalse(activity.isFinishing());
        ReadingHistory.Entry visit = ReadingHistory.list(context).get(0);
        assertEquals(Intent.ACTION_VIEW, visit.action);
        assertEquals(uri, visit.createIntent(context).getData());
    }

    @Test public void incomingMimeTypeWorksWhenProviderExposesNoNameOrType() {
        documents.omitMetadata = true;
        Intent incoming = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png");
        GalleryActivity activity = Robolectric.buildActivity(GalleryActivity.class, incoming).get();
        ReflectionHelpers.callInstanceMethod(activity, "onInit");
        SingleImageGalleryProvider provider = ReflectionHelpers.getField(activity, "mGalleryProvider");
        assertEquals(1, provider.loadImageFiles().length);
        assertEquals("image.png", provider.getFilename(0));
        Intent reopened = ReadingHistory.list(context).get(0).createIntent(context);
        assertEquals("image/png", reopened.getType());
        assertEquals(uri, reopened.getData());
    }

    @Test public void nonSeekableProviderStillDecodesTheGrantedImage() throws Exception {
        documents.usePipe = true;
        SingleImageGalleryProvider provider = new SingleImageGalleryProvider(context, uri);
        Image decoded = provider.decodeImage(provider.loadImageFiles()[0], 0);
        assertNotNull(decoded);
        assertEquals(32, decoded.getWidth());
        assertEquals(48, decoded.getHeight());
        decoded.recycle();
    }

    @Test public void directlyAccessibleImageRetainsNaturallySortedSiblings() {
        documents.exposePath = true;
        GalleryActivity activity = reader();
        DirGalleryProvider provider = ReflectionHelpers.getField(activity, "mGalleryProvider");
        assertFalse(provider instanceof SingleImageGalleryProvider);
        UniFile[] files = provider.loadImageFiles();
        assertEquals(2, files.length);
        assertEquals("2.png", files[0].getName());
        assertEquals("10.png", files[1].getName());
        assertEquals(documents.image, ExternalImageFileResolver.resolve(context, uri));
    }

    @Test public void directoryCheckRequiresActualEnumeration() {
        File inaccessible = new File(context.getCacheDir(), "denied") {
            @Override public boolean isDirectory() { return true; }
            @Override public boolean canRead() { return true; }
            @Override public String[] list() { return null; }
        };
        assertFalse(ExternalImageFileResolver.isReadableDirectory(inaccessible));
    }

    @Test public void folderPickerCancellationKeepsSingleImageReader() {
        GalleryActivity activity = reader();
        int request = ReflectionHelpers.getStaticField(GalleryActivity.class, "IMAGE_FOLDER_REQUEST_CODE");
        activity.onActivityResult(request, Activity.RESULT_CANCELED, null);
        assertFalse(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void folderSelectionPreservesSelectedImageAndDirectoryModeInHistory() {
        GalleryActivity activity = reader();
        Uri tree = android.provider.DocumentsContract.buildTreeDocumentUri("folder.provider", "folder");
        int request = ReflectionHelpers.getStaticField(GalleryActivity.class, "IMAGE_FOLDER_REQUEST_CODE");
        activity.onActivityResult(request, Activity.RESULT_OK,
                new Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        Intent open = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(GalleryActivity.ACTION_LOCAL_FOLDER, open.getAction());
        assertEquals(uri, open.getParcelableExtra(GalleryActivity.KEY_LOCAL_SELECTED_IMAGE_URI));
        assertEquals("2.png", open.getStringExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME));
        assertEquals(tree, LocalFolderGallerySource.parse(open.getStringExtra(GalleryActivity.KEY_FILENAME)).getTreeUri());
        ReadingHistory.Entry visit = ReadingHistory.fromIntent(context, open);
        assertEquals(uri, visit.createIntent(context).getParcelableExtra(GalleryActivity.KEY_LOCAL_SELECTED_IMAGE_URI));
    }

    private GalleryActivity reader() {
        GalleryActivity activity = Robolectric.buildActivity(GalleryActivity.class,
                new Intent(Intent.ACTION_VIEW).setData(uri)).get();
        ReflectionHelpers.callInstanceMethod(activity, "onInit");
        return activity;
    }

    private static final class FixtureProvider extends ContentProvider {
        File image;
        boolean exposePath;
        boolean usePipe;
        boolean omitMetadata;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor result = new MatrixCursor(projection);
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) values[i] = switch (projection[i]) {
                case OpenableColumns.DISPLAY_NAME -> omitMetadata ? null : image.getName();
                case MediaStore.MediaColumns.DATA -> exposePath ? image.getPath() : null;
                case OpenableColumns.SIZE -> image.length();
                default -> null;
            };
            result.addRow(values);
            return result;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (usePipe) {
                try {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    Thread writer = new Thread(() -> {
                        try (java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                            output.write(java.nio.file.Files.readAllBytes(image.toPath()));
                        } catch (java.io.IOException ignored) { }
                    }, "Image-fixture-pipe");
                    writer.setDaemon(true);
                    writer.start();
                    return pipe[0];
                } catch (java.io.IOException failure) { throw new FileNotFoundException(failure.toString()); }
            }
            return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return omitMetadata ? null : "image/png"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
