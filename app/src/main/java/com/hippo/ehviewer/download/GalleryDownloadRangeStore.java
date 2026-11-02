package com.hippo.ehviewer.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import org.json.JSONObject;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Scope/progress supplement to the existing gid-keyed download record. */
public final class GalleryDownloadRangeStore {
    public static final String FILENAME = ".ehviewer.range";
    private static volatile GalleryDownloadRangeStore instance;
    private static final Map<Long, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    private static final Set<Long> editing = ConcurrentHashMap.newKeySet();
    private static final Map<Long, java.util.concurrent.atomic.AtomicLong> versions = new ConcurrentHashMap<>();
    private final SharedPreferences preferences;
    private final Context application;
    private final Map<Long, Entry> ranges = new ConcurrentHashMap<>();

    public record Entry(GalleryPageRange range, int readingPage, boolean pending) { }

    private GalleryDownloadRangeStore(Context context) {
        application = context.getApplicationContext();
        preferences = context.getApplicationContext().getSharedPreferences("download_page_ranges", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> value : preferences.getAll().entrySet()) {
            try { ranges.put(Long.parseLong(value.getKey()), decode(new JSONObject((String) value.getValue()))); }
            catch (Exception e) { Log.w("DownloadRange", "Ignoring invalid range metadata", e); }
        }
    }

    public static synchronized GalleryDownloadRangeStore get(Context context) {
        if (instance == null) instance = new GalleryDownloadRangeStore(context);
        return instance;
    }
    @Nullable public static Entry peek(long gid) { return instance != null ? instance.ranges.get(gid) : null; }
    public static boolean isRange(long gid) { return peek(gid) != null; }
    public static long version(long gid) { return versions.computeIfAbsent(gid, ignored -> new java.util.concurrent.atomic.AtomicLong()).get(); }
    public static void changed(long gid) { versions.computeIfAbsent(gid, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet(); }
    public static boolean isEditing(long gid) { return editing.contains(gid); }
    public static boolean beginEdit(long gid) { return editing.add(gid); }
    public static void endEdit(long gid) { editing.remove(gid); }
    public static ReentrantReadWriteLock lock(long gid) {
        return locks.computeIfAbsent(gid, ignored -> new ReentrantReadWriteLock(true));
    }
    public static boolean includes(long gid, int page) {
        Entry entry = peek(gid);
        return entry == null || entry.range.contains(page);
    }
    public static int downloadCount(long gid, int sourcePages) {
        Entry entry = peek(gid);
        return entry == null ? sourcePages : entry.range.size();
    }
    public static boolean canPersist(long gid, int page) {
        Entry entry = peek(gid);
        return !isEditing(gid) && (entry == null || (!entry.pending && entry.range.contains(page)));
    }

    private static Entry decode(JSONObject json) throws Exception {
        return new Entry(GalleryPageRange.parse(json.getString("pages"), json.getInt("total")),
                Math.max(0, json.optInt("reading_page", 0)), json.optBoolean("pending", false));
    }
    private static JSONObject encode(long gid, Entry entry) throws Exception {
        return new JSONObject().put("gid", gid).put("total", entry.range.totalPages)
                .put("pages", entry.range.toString()).put("reading_page", entry.readingPage)
                .put("pending", entry.pending);
    }
    private synchronized void save(long gid, Entry entry) throws Exception {
        if (!preferences.edit().putString(Long.toString(gid), encode(gid, entry).toString()).commit())
            throw new IOException("Unable to save download range");
        ranges.put(gid, entry);
    }
    public synchronized void remove(long gid) {
        if (!preferences.edit().remove(Long.toString(gid)).commit())
            throw new IllegalStateException("Unable to remove download range");
        ranges.remove(gid);
        changed(gid);
    }
    public synchronized void saveReadingPage(long gid, int sourcePage) {
        Entry entry = ranges.get(gid);
        if (entry == null || !entry.range.contains(sourcePage)) return;
        Entry next = new Entry(entry.range, sourcePage, entry.pending);
        try { save(gid, next); } catch (Exception e) { Log.w("DownloadRange", "Unable to save range progress", e); }
    }

    /** Preserve scope when the app's database backup is moved without its image folders. */
    public void exportBackup(android.database.sqlite.SQLiteDatabase database, Set<Long> downloadedIds) {
        database.execSQL("CREATE TABLE IF NOT EXISTS EH_RANGE_DOWNLOADS (gid INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
        for (Map.Entry<Long, Entry> value : ranges.entrySet()) if (downloadedIds.contains(value.getKey())) {
            try {
                android.content.ContentValues row = new android.content.ContentValues();
                row.put("gid", value.getKey()); row.put("payload", encode(value.getKey(), value.getValue()).toString());
                database.insertOrThrow("EH_RANGE_DOWNLOADS", null, row);
            } catch (Exception error) { throw new IllegalStateException("Unable to back up download range", error); }
        }
    }
    public void importBackup(android.database.sqlite.SQLiteDatabase database, Set<Long> acceptedIds) {
        try (android.database.Cursor table = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='EH_RANGE_DOWNLOADS'", null)) {
            if (!table.moveToFirst()) return;
        }
        try (android.database.Cursor rows = database.query("EH_RANGE_DOWNLOADS", new String[]{"gid", "payload"},
                null, null, null, null, null)) {
            while (rows.moveToNext()) if (acceptedIds.contains(rows.getLong(0))) {
                try { save(rows.getLong(0), decode(new JSONObject(rows.getString(1)))); }
                catch (Exception error) { throw new IllegalStateException("Unable to restore download range", error); }
            }
        }
    }

    /** Worker only. Recover explicit range metadata when importing an existing directory. */
    public void restore(long gid, UniFile directory) {
        if (isRange(gid) || directory == null) return;
        UniFile file = directory.findFile(FILENAME);
        if (file == null) file = directory.findFile(FILENAME + ".bak");
        if (file == null) return;
        try (java.io.InputStream input = file.openInputStream()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            com.hippo.lib.yorozuya.IOUtils.copy(input, bytes);
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (json.getLong("gid") == gid) save(gid, decode(json));
        } catch (Exception e) { Log.w("DownloadRange", "Unable to restore range metadata", e); }
    }

    /** Worker only; selected files retain their original source-page filenames. */
    public void apply(GalleryInfo gallery, @Nullable GalleryPageRange range) throws Exception {
        ReentrantReadWriteLock.WriteLock write = lock(gallery.gid).writeLock();
        write.lockInterruptibly();
        try {
            UniFile directory = SpiderDen.getGalleryDownloadDir(gallery);
            if (directory == null || !directory.ensureDir()) throw new IOException("Download directory unavailable");
            if (range == null) {
                for (String name : new String[]{FILENAME, FILENAME + ".bak", FILENAME + ".tmp"}) {
                    UniFile sidecar = directory.findFile(name);
                    if (sidecar != null && !sidecar.delete()) throw new IOException("Unable to remove range metadata");
                }
                remove(gallery.gid);
                return;
            }
            Entry previous = peek(gallery.gid);
            Entry next = new Entry(range, previous != null ? previous.readingPage : 0, true);
            save(gallery.gid, next);
            writeSidecar(directory, gallery.gid, next);
            UniFile[] files = directory.listFiles();
            if (files == null) throw new IOException("Unable to list download directory");
            for (UniFile file : files) {
                String name = file.getName();
                if (name == null || !name.matches("[0-9]{8}\\.(?i:jpg|jpeg|png|gif|webp)")) continue;
                int page = Integer.parseInt(name.substring(0, 8)) - 1;
                if ((!range.contains(page) || !SpiderDen.isReadableImage(file)) && !file.delete())
                    throw new IOException("Unable to remove page " + (page + 1));
            }
            com.hippo.ehviewer.gallery.RangeDownloadProcessing.prune(application, gallery.gid, directory, range.pages());
            next = new Entry(range, next.readingPage, false);
            writeSidecar(directory, gallery.gid, next);
            save(gallery.gid, next);
            changed(gallery.gid);
        } finally { write.unlock(); }
    }
    private void writeSidecar(UniFile directory, long gid, Entry entry) throws Exception {
        UniFile file = directory.createFile(FILENAME + ".tmp");
        if (file == null) throw new IOException("Unable to create range metadata");
        try (OutputStream output = file.openOutputStream()) {
            output.write(encode(gid, entry).toString().getBytes(StandardCharsets.UTF_8));
        }
        UniFile backup = null;
        try {
            UniFile previous = directory.findFile(FILENAME);
            if (previous != null) {
                UniFile stale = directory.findFile(FILENAME + ".bak");
                if (stale != null && !stale.delete()) throw new IOException("Unable to replace range backup");
                if (!previous.renameTo(FILENAME + ".bak")) throw new IOException("Unable to back up range metadata");
                backup = previous;
            }
            if (!file.renameTo(FILENAME)) throw new IOException("Unable to publish range metadata");
            if (backup != null) backup.delete();
        } catch (Exception error) {
            if (backup != null) backup.renameTo(FILENAME);
            file.delete();
            throw error;
        }
    }

    /** Hold the shared file lock until the writer closes, so edits cannot race old workers. */
    public static OutputStream openImageOutput(long gid, int page, UniFile directory, String filename) throws IOException {
        ReentrantReadWriteLock.ReadLock read = lock(gid).readLock();
        try { read.lockInterruptibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        try {
            if (!canPersist(gid, page)) throw new IOException("Page is outside the current download range");
            UniFile file = directory.createFile(filename);
            if (file == null) throw new IOException("Unable to create image");
            OutputStream output = file.openOutputStream();
            return new FilterOutputStream(output) {
                private boolean closed;
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
                @Override public void write(byte[] bytes) throws IOException { out.write(bytes); }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try { super.close(); } finally { read.unlock(); }
                }
            };
        } catch (IOException | RuntimeException e) { read.unlock(); throw e; }
    }
}
