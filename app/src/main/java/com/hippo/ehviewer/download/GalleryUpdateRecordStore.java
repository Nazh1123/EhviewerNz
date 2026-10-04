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

/** Small app-private database: look up one gid without parsing the entire update history. */
public final class GalleryUpdateRecordStore extends SQLiteOpenHelper {
    private static GalleryUpdateRecordStore instance;

    public static synchronized GalleryUpdateRecordStore get(Context context) {
        if (instance == null) instance = new GalleryUpdateRecordStore(context.getApplicationContext());
        return instance;
    }

    GalleryUpdateRecordStore(Context context) {
        super(context, "gallery_update_records.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        for (String table : new String[]{"records", "pending"}) {
            db.execSQL("CREATE TABLE " + table + " (gid INTEGER PRIMARY KEY, source_gid INTEGER NOT NULL, "
                    + "completed_at INTEGER NOT NULL, reading_page INTEGER NOT NULL DEFAULT 0, "
                    + "payload TEXT NOT NULL)");
        }
        db.execSQL("CREATE INDEX records_completed ON records(completed_at, gid)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported update record database version");
    }

    @Nullable public synchronized GalleryUpdateRecord find(long gid) {
        return read("records", gid);
    }

    @Nullable private GalleryUpdateRecord read(String table, long gid) {
        try (Cursor cursor = getReadableDatabase().query(table, null, "gid=?",
                new String[]{Long.toString(gid)}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return GalleryUpdateRecord.fromJson(gid,
                    cursor.getLong(cursor.getColumnIndexOrThrow("source_gid")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("completed_at")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("reading_page")),
                    cursor.getString(cursor.getColumnIndexOrThrow("payload")));
        } catch (JSONException e) {
            Log.w("GalleryUpdateRecords", "Invalid record for " + gid, e);
            return null;
        }
    }

    /** Persist before any source files are removed. A retry retains the original snapshot. */
    public synchronized boolean stage(GalleryUpdateManager.UpdatePlan plan,
                                      java.util.function.Supplier<GalleryUpdateRecord> snapshot) {
        GalleryUpdateRecord existing = read("pending", plan.targetGid);
        if (existing != null && existing.sourceGid == plan.sourceGid) return true;
        GalleryUpdateRecord record = snapshot.get();
        try {
            ContentValues values = new ContentValues();
            values.put("gid", record.targetGid);
            values.put("source_gid", record.sourceGid);
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
