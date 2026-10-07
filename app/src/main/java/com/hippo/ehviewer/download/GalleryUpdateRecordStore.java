package com.hippo.ehviewer.download;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;

import org.json.JSONException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small app-private database: look up one gid without parsing the entire update history. */
public final class GalleryUpdateRecordStore extends SQLiteOpenHelper {
    private static GalleryUpdateRecordStore instance;

    public static synchronized GalleryUpdateRecordStore get(Context context) {
        if (instance == null) instance = new GalleryUpdateRecordStore(context.getApplicationContext());
        return instance;
    }

    GalleryUpdateRecordStore(Context context) {
        super(context, "gallery_update_records.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        for (String table : new String[]{"records", "pending"}) {
            db.execSQL("CREATE TABLE " + table + " (gid INTEGER PRIMARY KEY, source_gid INTEGER NOT NULL, "
                    + "completed_at INTEGER NOT NULL, reading_page INTEGER NOT NULL DEFAULT 0, "
                    + "payload TEXT NOT NULL, first_gid INTEGER NOT NULL DEFAULT 0)");
        }
        db.execSQL("CREATE INDEX records_completed ON records(completed_at, gid)");
        db.execSQL("CREATE INDEX records_family ON records(first_gid, completed_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN first_gid INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE pending ADD COLUMN first_gid INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX records_family ON records(first_gid, completed_at)");
        }
    }

    @Nullable public synchronized GalleryUpdateRecord find(long gid) {
        return read("records", gid);
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
        try {
            ContentValues values = new ContentValues();
            values.put("gid", record.targetGid);
            values.put("source_gid", record.sourceGid);
            values.put("first_gid", record.firstGid);
            values.put("completed_at", 0);
            values.put("reading_page", 0);
            values.put("payload", record.toJson());
            return getWritableDatabase().insertWithOnConflict("pending", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (JSONException e) {
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
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("gid", gid);
            values.put("source_gid", sourceGid);
            values.put("first_gid", record.firstGid);
            values.put("completed_at", System.currentTimeMillis());
            values.put("reading_page", 0);
            values.put("payload", record.toJson());
            if (db.insertWithOnConflict("records", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) == -1) return false;
            // Keep the draft until the update plan has been removed, allowing crash recovery.
            trim(db, Settings.getGalleryUpdateRecordLimit());
            db.setTransactionSuccessful();
            return true;
        } catch (JSONException e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void discardPending(long gid) {
        getWritableDatabase().delete("pending", "gid=?", new String[]{Long.toString(gid)});
    }

    /** A failed attempt is visible immediately; keep the draft and plan for a successful retry. */
    public synchronized boolean saveFailure(GalleryUpdateRecord record) {
        if (!record.isFailure()) throw new IllegalArgumentException("Expected failure record");
        if (record.firstGid == 0) {
            GalleryUpdateRecord previous = find(record.targetGid);
            if (previous == null) previous = find(record.sourceGid);
            if (previous != null) record = record.withFirstGid(previous.firstGid);
        }
        SQLiteDatabase db = getWritableDatabase();
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
            return true;
        } catch (JSONException e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void trim(int limit) {
        trim(getWritableDatabase(), limit);
    }

    private static void trim(SQLiteDatabase db, int limit) {
        db.execSQL("DELETE FROM records WHERE gid IN (SELECT gid FROM records "
                + "ORDER BY completed_at DESC, gid DESC LIMIT -1 OFFSET ?)",
                new Object[]{Math.max(1, limit)});
    }

    public synchronized void saveReadingPage(long gid, long completedAt, int page) {
        ContentValues values = new ContentValues();
        values.put("reading_page", Math.max(0, page));
        getWritableDatabase().update("records", values, "gid=? AND completed_at=?",
                new String[]{Long.toString(gid), Long.toString(completedAt)});
    }
}
