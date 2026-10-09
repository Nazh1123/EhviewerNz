package com.hippo.ehviewer.ui.scene.history;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.gallery.A7ZipArchive;
import com.hippo.ehviewer.gallery.ExternalImageFileResolver;
import com.hippo.ehviewer.gallery.LocalFolderCoverStore;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UniRandomAccessFile;
import com.hippo.util.NaturalComparator;
import com.hippo.widget.LoadImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reuses online thumbnail caches and decodes local covers on bounded background workers. */
final class ReadingHistoryCoverLoader {
    static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };

    private ReadingHistoryCoverLoader() {}

    static void clear(LoadImageView view) {
        Object old = view.getTag(R.id.reading_cover);
        if (old instanceof Request) ((Request) old).cancelled = true;
        view.setTag(R.id.reading_cover, null);
        view.unload();
    }

    static void load(LoadImageView view, ReadingHistory.Entry entry) {
        clear(view);
        view.load(R.drawable.v_book_open_primary_x24);
        String thumb = entry.gallery == null ? null : entry.gallery.thumb;
        if (!TextUtils.isEmpty(thumb)) {
            String scheme = Uri.parse(thumb).getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                view.load(EhCacheKeyFactory.getThumbKey(entry.gallery.gid), thumb);
                return;
            }
        }
        String key = entry.key + ':' + thumb + ':' + entry.readAt;
        Request request = new Request();
        view.setTag(R.id.reading_cover, request);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.load(new BitmapDrawable(view.getResources(), cached));
            return;
        }
        Context context = view.getContext().getApplicationContext();
        IO.execute(() -> {
            if (request.cancelled) return;
            Bitmap bitmap = null;
            try {
                bitmap = localCover(context, entry);
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                Log.w("ReadingHistoryCover", "Unable to load local reading cover", e);
            }
            if (bitmap == null) return;
            Bitmap result = bitmap;
            CACHE.put(key, result);
            MAIN.post(() -> {
                if (!request.cancelled && view.getTag(R.id.reading_cover) == request) {
                    view.load(new BitmapDrawable(view.getResources(), result));
                }
            });
        });
    }

    static Bitmap localCover(Context context, ReadingHistory.Entry entry) throws Exception {
        if (entry.gallery != null) {
            File cover = LocalFolderCoverStore.find(context, entry.gallery.gid);
            if (cover != null) return decode(UniFile.fromFile(cover));
            String thumb = entry.gallery.thumb;
            if (!TextUtils.isEmpty(thumb)) {
                Uri uri = Uri.parse(thumb);
                if ("file".equals(uri.getScheme()) || "content".equals(uri.getScheme())) {
                    return decode(UniFile.fromUri(context, uri));
                }
            }
        }
        if (Intent.ACTION_VIEW.equals(entry.action) && entry.uri != null) {
            Uri uri = Uri.parse(entry.uri);
            if (ExternalImageFileResolver.isImageUri(context, uri)) {
                File file = ExternalImageFileResolver.resolve(context, uri);
                return decode(file != null ? UniFile.fromFile(file) : UniFile.fromUri(context, uri));
            }
            return archiveCover(context, uri);
        }
        if (GalleryActivity.ACTION_DIR.equals(entry.action) && entry.filename != null) {
            UniFile[] files = UniFile.fromFile(new File(entry.filename)).listFiles();
            NaturalComparator comparator = new NaturalComparator();
            Arrays.sort(files, (left, right) -> comparator.compare(left.getName(), right.getName()));
            for (UniFile file : files) {
                if (file.isFile() && ExternalImageFileResolver.isSupportedImageName(file.getName())) {
                    return decode(file);
                }
            }
        }
        if (GalleryActivity.ACTION_EH.equals(entry.action) && entry.gallery != null) {
            UniFile directory = SpiderDen.getExistingGalleryDownloadDir(entry.gallery);
            if (directory != null) {
                UniFile cover = directory.findFile(".thumb");
                if (cover == null) cover = SpiderDen.findImageFile(directory, 0);
                return decode(cover);
            }
        }
        return null;
    }

    private static Bitmap decode(UniFile file) throws IOException {
        if (file == null) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = file.openInputStream()) { BitmapFactory.decodeStream(input, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 480) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        try (InputStream input = file.openInputStream()) { return BitmapFactory.decodeStream(input, null, options); }
    }

    private static Bitmap archiveCover(Context context, Uri uri) throws Exception {
        UniFile file = UniFile.fromUri(context, uri);
        if (file == null) return null;
        UniRandomAccessFile input = file.createRandomAccessFile("r");
        if (input == null) return null;
        try {
            try (A7ZipArchive archive = A7ZipArchive.create(input)) {
                if (archive == null) return null;
                List<A7ZipArchive.A7ZipArchiveEntry> entries = archive.getArchiveEntries();
                NaturalComparator comparator = new NaturalComparator();
                entries.sort((left, right) -> comparator.compare(left.getPath(), right.getPath()));
                if (entries.isEmpty()) return null;
                File temporary = File.createTempFile("reading-cover-", ".image", context.getCacheDir());
                try {
                    // Extract one image with a size cap, rather than decoding an entire archive.
                    try (OutputStream output = new LimitedOutput(new FileOutputStream(temporary))) {
                        entries.get(0).extract(output);
                    }
                    return decode(UniFile.fromFile(temporary));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    temporary.delete();
                }
            }
        } finally {
            input.close();
        }
    }

    private static class Request { volatile boolean cancelled; }

    private static class LimitedOutput extends FilterOutputStream {
        private long size;
        LimitedOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException {
            checkSize(1);
            out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            checkSize(length);
            out.write(bytes, offset, length);
        }
        private void checkSize(int length) throws IOException {
            size += length;
            if (size > 32L * 1024 * 1024) throw new IOException("Archive cover is too large");
        }
    }
}
