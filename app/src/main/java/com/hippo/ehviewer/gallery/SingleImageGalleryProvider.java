package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import com.hippo.unifile.UniFile;

/** Reads the granted image even when its provider exposes no filesystem path or directory. */
public final class SingleImageGalleryProvider extends DirGalleryProvider {
    private final Context context;
    private final Uri uri;
    private final String mimeType;
    private final String previewIdentity;
    private volatile String filename = "image";

    public SingleImageGalleryProvider(Context context, Uri uri) {
        this(context, uri, null);
    }

    public SingleImageGalleryProvider(Context context, Uri uri, String mimeType) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.mimeType = mimeType;
        previewIdentity = uri + ":" + java.util.UUID.randomUUID();
    }

    @Override protected UniFile[] loadImageFiles() {
        String name = ExternalImageFileResolver.queryDisplayName(context, uri);
        if (name == null || name.isEmpty()) {
            String extension = null;
            String type = mimeType;
            try {
                String providerType = context.getContentResolver().getType(uri);
                if (providerType != null) type = providerType;
            } catch (RuntimeException ignored) { }
            if (type != null) extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
            name = "image" + (extension == null ? "" : "." + extension);
        }
        filename = name;
        return new UniFile[] {"file".equals(uri.getScheme())
                ? UniFile.fromUri(context, uri) : UniFile.fromMediaUri(context, uri)};
    }

    @Override public String getFilename(int index) { return index == 0 ? filename : null; }

    @Override public String getImageFilename(int index) {
        String name = getFilename(index);
        if (name == null) return Integer.toString(index);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Override public ReaderPreviewSource createPreviewSource(Context context) {
        return (index, edge) -> index != 0 ? null : ReaderPreviewBitmapCache.load(context,
                previewIdentity, edge, uri, () -> this.context.getContentResolver().openInputStream(uri),
                () -> getCachedPreview(index, edge));
    }

    @Override public GalleryProvider2 createTranslationProvider(Context context) {
        return new SingleImageGalleryProvider(context, uri, mimeType);
    }

    @Override public String getTranslationIdentity() { return "image:" + uri; }
}
