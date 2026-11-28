package com.hippo.ehviewer.download;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.util.JsonReader;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;

import com.hippo.ehviewer.spider.SpiderInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;

/** Small app-private database: look up one gid without parsing the entire update history. */
public final class GalleryUpdateRecordStore extends SQLiteOpenHelper {
    private static GalleryUpdateRecordStore instance;
    private final LruCache<Long, ActionSnapshot> actionSnapshots = new LruCache<>(256);
    private final Map<Long, Set<LongConsumer>> pendingSnapshots = new HashMap<>();

    public static synchronized GalleryUpdateRecordStore get(Context context) {
        if (instance == null) instance = new GalleryUpdateRecordStore(context.getApplicationContext());
        return instance;
    }

    GalleryUpdateRecordStore(Context context) {
        super(context, "gallery_update_records.db", null, 6);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createPreviews(db);
        for (String table : new String[]{"records", "pending"}) {
            db.execSQL("CREATE TABLE " + table + " (gid INTEGER PRIMARY KEY, source_gid INTEGER NOT NULL, "
                    + "completed_at INTEGER NOT NULL, reading_page INTEGER NOT NULL DEFAULT 0, "
                    + "payload TEXT NOT NULL, first_gid INTEGER NOT NULL DEFAULT 0)");
        }
        db.execSQL("CREATE INDEX records_completed ON records(completed_at, gid)");
        db.execSQL("CREATE INDEX records_family ON records(first_gid, completed_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) createPreviews(db);
        // Rebuild cached comparisons with each source's comparison page count.
        else if (oldVersion < 6) db.delete("previews", null, null);
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN first_gid INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE pending ADD COLUMN first_gid INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX records_family ON records(first_gid, completed_at)");
        }
    }

    private static void createPreviews(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE previews (gid INTEGER PRIMARY KEY, source_gid INTEGER NOT NULL, "
                + "source_token TEXT NOT NULL, target_token TEXT NOT NULL, checked_at INTEGER NOT NULL, payload TEXT NOT NULL, "
                + "source_pages TEXT NOT NULL)");
    }

    @Nullable public synchronized GalleryUpdateRecord findPreview(long gid, String token, long sourceGid,
                                                                  String sourceToken) {
        return findPreview(gid, token, sourceGid, sourceToken, null);
    }

    @Nullable synchronized GalleryUpdateRecord findPreview(long gid, String token, long sourceGid,
                                                             String sourceToken, String scope) {
        try (Cursor row = getReadableDatabase().query("previews", new String[]{"checked_at", "payload"},
                "gid=? AND source_gid=? AND source_token=? AND target_token=?", new String[]{Long.toString(gid),
                        Long.toString(sourceGid), sourceToken, token}, null, null, null)) {
            if (!row.moveToFirst()) return null;
            JSONObject payload = new JSONObject(row.getString(1));
            if (scope != null && !scope.equals(payload.optString("preview_scope"))) return null;
            GalleryUpdateRecord record = GalleryUpdateRecord.fromPayload(gid, sourceGid, row.getLong(0), 0, payload);
            return record.hasTokenSnapshot() && record.targetToken.equals(token) ? record : null;
        } catch (JSONException | android.database.SQLException e) {
            Log.w("GalleryUpdateRecords", "Unable to read preview for " + gid, e);
            return null;
        }
    }

    synchronized void savePreview(GalleryUpdateRecord record, SpiderInfo source) {
        savePreview(record, source, null);
    }

    synchronized void savePreview(GalleryUpdateRecord record, SpiderInfo source, String scope) {
        if (source == null || !record.hasTokenSnapshot()) return;
        try {
            ContentValues values = new ContentValues();
            values.put("gid", record.targetGid);
            values.put("source_gid", record.sourceGid);
            values.put("source_token", source.token);
            values.put("target_token", record.targetToken);
            values.put("checked_at", System.currentTimeMillis());
            values.put("source_pages", sourceTokens(source).toString());
            values.put("payload", record.toPayload().put("preview_scope", scope).toString());
            SQLiteDatabase db = getWritableDatabase();
            db.insertWithOnConflict("previews", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM previews WHERE gid IN (SELECT gid FROM previews "
                    + "ORDER BY checked_at DESC, gid DESC LIMIT -1 OFFSET 64)");
        } catch (JSONException | android.database.SQLException e) {
            Log.w("GalleryUpdateRecords", "Unable to cache preview for " + record.targetGid, e);
        }
    }

    synchronized void fillPreviewSource(long targetGid, String targetToken, SpiderInfo source) {
        try (Cursor row = getReadableDatabase().query("previews", new String[]{"source_pages"},
                "gid=? AND source_gid=? AND source_token=? AND target_token=?", new String[]{Long.toString(targetGid),
                        Long.toString(source.gid), source.token, targetToken}, null, null, null)) {
            if (!row.moveToFirst()) return;
            fillSource(source, new JSONArray(row.getString(0)));
        } catch (JSONException | android.database.SQLException e) {
            Log.w("GalleryUpdateRecords", "Unable to restore source page identities for " + targetGid, e);
        }
    }

    private static JSONArray sourceTokens(SpiderInfo source) {
        JSONArray pages = new JSONArray();
        for (int i = 0; i < source.pages; i++) pages.put(source.pTokenMap.get(i));
        return pages;
    }

    private static void fillSource(SpiderInfo source, JSONArray pages) throws JSONException {
        if (pages == null || pages.length() != source.pages) return;
        for (int i = 0; i < source.pages; i++) {
            if (pages.isNull(i)) continue;
            String saved = pages.getString(i);
            String local = source.pTokenMap.get(i);
            if (GalleryUpdateRecord.usableToken(saved) && GalleryUpdateRecord.usableToken(local)
                    && !local.equals(saved)) return;
        }
        for (int i = 0; i < source.pages; i++) {
            if (pages.isNull(i)) continue;
            String saved = pages.getString(i);
            if (GalleryUpdateRecord.usableToken(saved)) source.pTokenMap.put(i, saved);
        }
    }

    synchronized boolean fillConfirmedSource(long targetGid, String targetToken, SpiderInfo source) {
        try (Cursor row = getReadableDatabase().query("pending", new String[]{"payload"},
                "gid=? AND source_gid=?", new String[]{Long.toString(targetGid), Long.toString(source.gid)},
                null, null, null)) {
            if (!row.moveToFirst()) return false;
            JSONObject payload = new JSONObject(row.getString(0));
            if (!payload.has("source_deletions")) return false;
            if (targetToken.equals(payload.optString("target_token"))
                    && source.token.equals(payload.optString("confirmed_source_token"))) {
                fillSource(source, payload.optJSONArray("confirmed_source_pages"));
            }
            return true;
        } catch (JSONException | android.database.SQLException e) {
            Log.w("GalleryUpdateRecords", "Unable to read confirmed source " + source.gid, e);
            return false;
        }
    }

    @Nullable public synchronized GalleryUpdateRecord find(long gid) {
        GalleryUpdateRecord record = read("records", gid);
        actionSnapshots.put(gid, snapshot(record, record != null ? record.completedAt : 0));
        return record;
    }

    /** Card metadata only; skip page-token snapshots without constructing their arrays. */
    public record Summary(long targetGid, long completedAt, int readingPage,
                          int addedPages, int deletedPages, boolean complete,
                          boolean failure, String targetToken, boolean localCoverage) {
        public Summary(long targetGid, long completedAt, int readingPage, int addedPages, int deletedPages,
                       boolean complete, boolean failure, String targetToken) {
            this(targetGid, completedAt, readingPage, addedPages, deletedPages, complete, failure, targetToken, false);
        }
    }

    /** UI-only metadata; page-token arrays are never retained by this cache. */
    public record ActionSnapshot(@Nullable Summary summary, boolean readable) {}

    @Nullable public ActionSnapshot peekActionSnapshot(long gid) { return actionSnapshots.get(gid); }

    public void prefetchActionSnapshot(long gid, Executor executor) {
        prefetchActionSnapshot(gid, executor, null);
    }

    /** Completion listeners run on the executor, once the shared snapshot is available. */
    public void prefetchActionSnapshot(long gid, Executor executor, @Nullable LongConsumer onLoaded) {
        if (actionSnapshots.get(gid) != null) return;
        synchronized (pendingSnapshots) {
            Set<LongConsumer> listeners = pendingSnapshots.get(gid);
            if (listeners != null) {
                if (onLoaded != null) listeners.add(onLoaded);
                return;
            }
            if (pendingSnapshots.size() >= 64) return;
            listeners = new HashSet<>();
            if (onLoaded != null) listeners.add(onLoaded);
            pendingSnapshots.put(gid, listeners);
        }
        try {
            executor.execute(() -> {
                try {
                    synchronized (this) {
                        if (actionSnapshots.get(gid) != null) return;
                        try (Cursor cursor = getReadableDatabase().query("records",
                                new String[]{"gid", "completed_at", "reading_page", "payload"},
                                "gid=?", new String[]{Long.toString(gid)}, null, null, null)) {
                            actionSnapshots.put(gid, cursor.moveToFirst()
                                    ? readActionSnapshot(cursor) : new ActionSnapshot(null, false));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    Log.w("GalleryUpdateRecords", "Unable to prefetch update actions for " + gid, e);
                } finally {
                    Set<LongConsumer> listeners;
                    synchronized (pendingSnapshots) { listeners = pendingSnapshots.remove(gid); }
                    if (actionSnapshots.get(gid) != null && listeners != null) {
                        for (LongConsumer listener : listeners) listener.accept(gid);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (pendingSnapshots) { pendingSnapshots.remove(gid); }
        }
    }

    private static ActionSnapshot snapshot(@Nullable GalleryUpdateRecord record, long completedAt) {
        return record == null ? new ActionSnapshot(null, false) : new ActionSnapshot(new Summary(
                record.targetGid, completedAt, record.readingPage,
                record.complete && !record.hasKnownAdditions() ? -1 : record.addedPages.length,
                record.complete && !record.hasKnownDeletions() ? -1 : record.deletedPages.length,
                record.complete, record.isFailure(), record.targetToken, record.showsLocalCoverage()),
                record.complete && !record.isFailure() && record.hasTokenSnapshot() && record.addedPages.length > 0);
    }

    private void cacheWrittenRecord(GalleryUpdateRecord record, long completedAt) {
        try (Cursor cursor = getReadableDatabase().query("records", new String[]{"gid"}, "gid=?",
                new String[]{Long.toString(record.targetGid)}, null, null, null)) {
            actionSnapshots.put(record.targetGid, cursor.moveToFirst()
                    ? snapshot(record, completedAt) : new ActionSnapshot(null, false));
        }
    }

    private static ActionSnapshot readActionSnapshot(Cursor cursor) throws IOException {
        try (JsonReader json = new JsonReader(new StringReader(cursor.getString(3)))) {
            int added = 0, deleted = 0, pages = 0, tokens = 0;
            boolean complete = false, failure = false, additionsKnown = true, deletionsKnown = true, localCoverage = false;
            String token = "";
            json.beginObject();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case "added" -> added = countIndexes(json);
                    case "deleted" -> deleted = countIndexes(json);
                    case "new_pages" -> pages = json.nextInt();
                    case "target_tokens" -> {
                        json.beginArray();
                        while (json.hasNext()) { json.skipValue(); tokens++; }
                        json.endArray();
                    }
                    case "complete" -> complete = json.nextBoolean();
                    case "additions_known" -> additionsKnown = json.nextBoolean();
                    case "deletions_known" -> deletionsKnown = json.nextBoolean();
                    case "local_coverage" -> localCoverage = json.nextBoolean();
                    case "error_reason" -> failure = !json.nextString().isEmpty();
                    case "target_token" -> token = json.nextString();
                    default -> json.skipValue();
                }
            }
            json.endObject();
            return new ActionSnapshot(new Summary(cursor.getLong(0), cursor.getLong(1),
                    Math.max(0, Math.min(cursor.getInt(2), added - 1)), additionsKnown ? added : -1,
                    deletionsKnown ? deleted : -1, complete, failure, token, localCoverage),
                    complete && !failure && additionsKnown && added > 0 && tokens > 0 && tokens == pages);
        }
    }

    public synchronized List<Summary> listSummaries() {
        ArrayList<Summary> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("records",
                new String[]{"gid", "completed_at", "reading_page", "payload"},
                null, null, null, null, "completed_at DESC, gid DESC")) {
            while (cursor.moveToNext()) {
                try {
                    ActionSnapshot snapshot = readActionSnapshot(cursor);
                    result.add(snapshot.summary());
                    actionSnapshots.put(cursor.getLong(0), snapshot);
                } catch (IOException | IllegalStateException | NumberFormatException e) {
                    Log.w("GalleryUpdateRecords", "Invalid history card for " + cursor.getLong(0), e);
                }
            }
        }
        return result;
    }

    private static int countIndexes(JsonReader json) throws IOException {
        int count = 0, previous = -1;
        json.beginArray();
        while (json.hasNext()) {
            int page = json.nextInt();
            if (page < 0 || page <= previous) throw new IOException("Invalid page index");
            previous = page;
            count++;
        }
        json.endArray();
        return count;
    }

    @Nullable private GalleryUpdateRecord read(String table, long gid) {
        try (Cursor cursor = getReadableDatabase().query(table, null, "gid=?",
                new String[]{Long.toString(gid)}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return decode(cursor);
        } catch (JSONException e) {
            Log.w("GalleryUpdateRecords", "Invalid record for " + gid, e);
            return null;
        }
    }

    private static GalleryUpdateRecord decode(Cursor cursor) throws JSONException {
        return GalleryUpdateRecord.fromJson(cursor.getLong(cursor.getColumnIndexOrThrow("gid")),
                cursor.getLong(cursor.getColumnIndexOrThrow("source_gid")),
                cursor.getLong(cursor.getColumnIndexOrThrow("completed_at")),
                cursor.getInt(cursor.getColumnIndexOrThrow("reading_page")),
                cursor.getString(cursor.getColumnIndexOrThrow("payload")))
                .withFirstGid(cursor.getLong(cursor.getColumnIndexOrThrow("first_gid")));
    }

    /** Local history, descending by target gid then time. Include connected pre-migration records. */
    public synchronized List<GalleryUpdateRecord> findHistory(long gid, long firstGid) {
        record Link(long targetGid, long sourceGid, long firstGid) {}
        ArrayList<Link> all = new ArrayList<>();
        // Unrelated galleries may have large token snapshots; inspect only their chain keys.
        try (Cursor cursor = getReadableDatabase().query("records",
                new String[]{"gid", "source_gid", "first_gid"}, null, null,
                null, null, "gid DESC, completed_at DESC")) {
            while (cursor.moveToNext()) {
                all.add(new Link(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)));
            }
        }
        Set<Long> family = new HashSet<>();
        Set<Long> roots = new HashSet<>();
        family.add(gid);
        if (firstGid > 0) {
            family.add(firstGid);
            roots.add(firstGid);
        }
        boolean changed;
        do {
            changed = false;
            for (Link record : all) {
                if (roots.contains(record.firstGid)
                        || family.contains(record.targetGid)
                        || (record.sourceGid > 0 && family.contains(record.sourceGid))) {
                    changed |= family.add(record.targetGid);
                    if (record.sourceGid > 0) changed |= family.add(record.sourceGid);
                    if (record.firstGid > 0) {
                        changed |= roots.add(record.firstGid);
                        changed |= family.add(record.firstGid);
                    }
                }
            }
        } while (changed);
        ArrayList<GalleryUpdateRecord> result = new ArrayList<>();
        for (Link link : all) {
            if (family.contains(link.targetGid)) {
                GalleryUpdateRecord record = find(link.targetGid);
                if (record != null) result.add(record);
            }
        }
        return result;
    }

    /** Persist before any source files are removed. A retry retains the original snapshot. */
    public synchronized boolean stage(GalleryUpdateManager.UpdatePlan plan,
                                      java.util.function.Supplier<GalleryUpdateRecord> snapshot) {
        GalleryUpdateRecord existing = read("pending", plan.targetGid);
        if (existing != null && existing.sourceGid == plan.sourceGid
                && (existing.complete || plan.progressMigrated)) return true;
        GalleryUpdateRecord record = snapshot.get();
        if (record.firstGid == 0) {
            GalleryUpdateRecord source = find(record.sourceGid);
            if (source != null && source.firstGid > 0) record = record.withFirstGid(source.firstGid);
        }
        return writePending(record, null);
    }

    @Nullable synchronized GalleryUpdateRecord findConfirmed(long gid, long sourceGid) {
        GalleryUpdateRecord record = read("pending", gid);
        return record != null && record.sourceGid == sourceGid && !record.sourceDeletions.isEmpty()
                ? record : null;
    }

    synchronized boolean saveConfirmed(GalleryUpdateRecord record, SpiderInfo source) {
        return !record.sourceDeletions.isEmpty() && record.hasTokenSnapshot() && writePending(record, source);
    }

    private boolean writePending(GalleryUpdateRecord record, SpiderInfo source) {
        try {
            ContentValues values = new ContentValues();
            values.put("gid", record.targetGid);
            values.put("source_gid", record.sourceGid);
            values.put("first_gid", record.firstGid);
            values.put("completed_at", 0);
            values.put("reading_page", 0);
            JSONObject payload = record.toPayload();
            if (source != null && source.pTokenMap != null) {
                payload.put("confirmed_source_token", source.token);
                payload.put("confirmed_source_pages", sourceTokens(source));
            }
            values.put("payload", payload.toString());
            return getWritableDatabase().insertWithOnConflict("pending", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (JSONException | android.database.SQLException e) {
            Log.w("GalleryUpdateRecords", "Unable to save update draft for " + record.targetGid, e);
            return false;
        }
    }

    /** Publish only after update validation and parent cleanup both succeed. */
    public synchronized boolean complete(long gid, long sourceGid) {
        GalleryUpdateRecord record = read("pending", gid);
        if (record == null || record.sourceGid != sourceGid) return false;
        GalleryUpdateRecord published = read("records", gid);
        try {
            if (published != null && published.sourceGid == sourceGid
                    && published.toJson().equals(record.toJson())) return true;
        } catch (JSONException e) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        long completedAt = System.currentTimeMillis();
        boolean committed = false;
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("gid", gid);
            values.put("source_gid", sourceGid);
            values.put("first_gid", record.firstGid);
            values.put("completed_at", completedAt);
            values.put("reading_page", 0);
            values.put("payload", record.toJson());
            if (db.insertWithOnConflict("records", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) == -1) return false;
            db.delete("previews", "gid=?", new String[]{Long.toString(gid)});
            // Keep the draft until the update plan has been removed, allowing crash recovery.
            trim(db, Settings.getGalleryUpdateRecordLimit());
            db.setTransactionSuccessful();
            committed = true;
            return true;
        } catch (JSONException e) {
            return false;
        } finally {
            db.endTransaction();
            if (committed) cacheWrittenRecord(record, completedAt);
        }
    }

    public synchronized void discardPending(long gid) {
        getWritableDatabase().delete("pending", "gid=?", new String[]{Long.toString(gid)});
    }

    /** A failed attempt is visible immediately; keep the draft and plan for a successful retry. */
    public synchronized boolean saveFailure(GalleryUpdateRecord record) {
        if (!record.isFailure()) throw new IllegalArgumentException("Expected failure record");
        if (record.targetToken.isEmpty()) {
            GalleryUpdateRecord previous = read("pending", record.targetGid);
            if (previous == null || previous.targetToken.isEmpty()) previous = find(record.targetGid);
            if (previous != null) record = record.withTargetToken(previous.targetToken);
        }
        if (record.firstGid == 0) {
            GalleryUpdateRecord previous = find(record.targetGid);
            if (previous == null) previous = find(record.sourceGid);
            if (previous != null) record = record.withFirstGid(previous.firstGid);
        }
        SQLiteDatabase db = getWritableDatabase();
        boolean committed = false;
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("gid", record.targetGid);
            values.put("source_gid", record.sourceGid);
            values.put("first_gid", record.firstGid);
            values.put("completed_at", record.completedAt);
            values.put("reading_page", 0);
            values.put("payload", record.toJson());
            if (db.insertWithOnConflict("records", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) == -1) return false;
            trim(db, Settings.getGalleryUpdateRecordLimit());
            db.setTransactionSuccessful();
            committed = true;
            return true;
        } catch (JSONException e) {
            return false;
        } finally {
            db.endTransaction();
            if (committed) cacheWrittenRecord(record, record.completedAt);
        }
    }

    public synchronized void trim(int limit) {
        trim(getWritableDatabase(), limit);
    }

    private void trim(SQLiteDatabase db, int limit) {
        db.execSQL("DELETE FROM records WHERE gid IN (SELECT gid FROM records "
                + "ORDER BY completed_at DESC, gid DESC LIMIT -1 OFFSET ?)",
                new Object[]{Math.max(1, limit)});
        Set<Long> retained = new HashSet<>();
        try (Cursor cursor = db.query("records", new String[]{"gid"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) retained.add(cursor.getLong(0));
        }
        for (var entry : actionSnapshots.snapshot().entrySet()) {
            if (entry.getValue().summary() != null && !retained.contains(entry.getKey())) {
                actionSnapshots.put(entry.getKey(), new ActionSnapshot(null, false));
            }
        }
    }

    public synchronized void saveReadingPage(long gid, long completedAt, int page) {
        ContentValues values = new ContentValues();
        values.put("reading_page", Math.max(0, page));
        getWritableDatabase().update("records", values, "gid=? AND completed_at=?",
                new String[]{Long.toString(gid), Long.toString(completedAt)});
    }
}
