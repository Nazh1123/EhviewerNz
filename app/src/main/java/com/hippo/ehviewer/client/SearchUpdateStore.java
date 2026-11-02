package com.hippo.ehviewer.client;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Query-local checkpoints and one shared identity ledger. No per-check history is retained. */
public final class SearchUpdateStore extends SQLiteOpenHelper {
    public static final String DATABASE = "search-updates.db";
    public static final int HISTORY_LIMIT = 1000;
    private static Context defaultContext;

    public static void initialize(Context context) { defaultContext = context.getApplicationContext(); }

    public SearchUpdateStore(Context context) {
        super(context.getApplicationContext(), DATABASE, null, 1);
    }

    public static SearchUpdateStore open() {
        return new SearchUpdateStore(defaultContext != null ? defaultContext : Settings.getApplicationContext());
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE queries (id TEXT PRIMARY KEY, scope TEXT NOT NULL, "
                + "version INTEGER NOT NULL DEFAULT 0, checked INTEGER NOT NULL DEFAULT 0, viewed INTEGER NOT NULL DEFAULT 0, "
                + "initialized INTEGER NOT NULL DEFAULT 0, head INTEGER NOT NULL DEFAULT 0, "
                + "floor INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0, "
                + "exhausted INTEGER NOT NULL DEFAULT 0, touched INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE galleries (query_id TEXT NOT NULL, gid INTEGER NOT NULL, "
                + "posted INTEGER NOT NULL, qualified INTEGER NOT NULL DEFAULT 0, "
                + "active INTEGER NOT NULL DEFAULT 0, "
                + "detected INTEGER NOT NULL DEFAULT 0, acknowledged INTEGER NOT NULL DEFAULT 0, "
                + "snapshot INTEGER NOT NULL DEFAULT 0, touched INTEGER NOT NULL, "
                + "PRIMARY KEY(query_id,gid)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX galleries_identity ON galleries(gid,acknowledged)");
        db.execSQL("CREATE INDEX queries_scope ON queries(scope)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported search state version: " + oldVersion);
    }

    /** Pagination and presentation do not define the identity of a logical search. */
    public static String queryKey(ListUrlBuilder builder) {
        int rating = Math.max(builder.getEffectiveMinRatingHundredths(), builder.getSubscriptionMinRatingHundredths());
        if (builder.getMode() == ListUrlBuilder.MODE_SUBSCRIPTION) {
            int defaultRating = Settings.getEnablePreciseRatingFilter()
                    ? Settings.getPersistentMinRatingHundredths() : 0;
            return rating == defaultRating ? "eh-subscription" : "eh-subscription#rating=" + rating;
        }
        ListUrlBuilder first = builder.clone();
        first.setPageIndex(0);
        URI url = URI.create(first.buildWithoutRatingFilter());
        return SubscriptionProgressStore.key("query-v3", url.getRawPath()
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery())
                + "#rating=" + rating);
    }

    private static String id(String scope, String query) {
        return SubscriptionProgressStore.key(scope, query);
    }

    public boolean isInitializedEmpty(String scope, String query) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT initialized,floor FROM queries WHERE id=?", new String[]{id(scope, query)})) {
            return c.moveToFirst() && c.getInt(0) != 0 && c.getLong(1) == 0;
        }
    }

    private record Entry(long posted, boolean qualified, long detected, boolean acknowledged, long snapshot, boolean active) { }

    public static final class Snapshot {
        public final String scope;
        public final String query;
        public final boolean initialized;
        /** version is an optimistic revision; checked/viewed are independent committed frontiers. */
        public final long version, checked, viewed, head, floor;
        public final boolean complete, exhausted;
        private final Map<Long, Entry> entries;
        private final Set<Long> confirmed;

        private Snapshot(String scope, String query, boolean initialized, long version,
                         long checked, long viewed, long head, long floor, boolean complete, boolean exhausted,
                         Map<Long, Entry> entries, Set<Long> confirmed) {
            this.scope = scope; this.query = query; this.initialized = initialized;
            this.version = version; this.checked = checked; this.viewed = viewed; this.head = head; this.floor = floor;
            this.complete = complete; this.exhausted = exhausted;
            this.entries = entries; this.confirmed = confirmed;
        }

        public boolean isNew(GalleryInfo gallery) {
            if (!initialized || gallery.gid <= 0 || confirmed.contains(gallery.gid)) return false;
            Entry entry = entries.get(gallery.gid);
            if (entry != null && entry.qualified) return false;
            long posted = GallerySeenState.postedMinute(gallery.posted);
            // An initialized empty query has already covered its entire (empty) result set.
            return posted == 0 || floor == 0 || posted > floor || (complete && posted == floor);
        }

        public Set<Long> unread() {
            Set<Long> result = new HashSet<>();
            entries.forEach((gid, row) -> {
                if (row.active && row.detected > 0 && !row.acknowledged && !confirmed.contains(gid)) result.add(gid);
            });
            return result;
        }

        public boolean isUnconfirmed(GalleryInfo gallery) {
            Entry entry = entries.get(gallery.gid);
            return entry != null && entry.detected > 0 && !entry.acknowledged
                    && !confirmed.contains(gallery.gid) || isNew(gallery);
        }

        /** Legacy UI bundle adapter only; durable membership lives in the SQLite ledger. */
        public GallerySeenState displayWatermark() {
            if (!initialized) return GallerySeenState.empty();
            List<GalleryInfo> galleries = new ArrayList<>();
            entries.forEach((gid, row) -> {
                if (row.posted > 0) {
                    GalleryInfo gallery = new GalleryInfo(); gallery.gid = gid;
                    gallery.posted = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", java.util.Locale.US)
                            .withZone(java.time.ZoneOffset.UTC)
                            .format(java.time.Instant.ofEpochSecond(row.posted * 60));
                    galleries.add(gallery);
                }
            });
            return GallerySeenState.empty().observe(galleries);
        }
    }

    public synchronized Snapshot read(String scope, String query) {
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransactionNonExclusive();
        try {
            Snapshot result = readSnapshot(scope, query);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    private Snapshot readSnapshot(String scope, String query) {
        SQLiteDatabase db = getReadableDatabase();
        String key = id(scope, query);
        long version = 0, checked = 0, viewed = 0, head = 0, floor = 0;
        boolean initialized = false, complete = false, exhausted = false;
        try (Cursor c = db.rawQuery("SELECT version,viewed,initialized,head,floor,complete,exhausted,checked "
                + "FROM queries WHERE id=?", new String[]{key})) {
            if (c.moveToFirst()) {
                version = c.getLong(0); viewed = c.getLong(1); initialized = c.getInt(2) != 0;
                head = c.getLong(3); floor = c.getLong(4); complete = c.getInt(5) != 0;
                exhausted = c.getInt(6) != 0;
                checked = c.getLong(7);
            }
        }
        Map<Long, Entry> entries = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT gid,posted,qualified,detected,acknowledged,snapshot,active "
                + "FROM galleries WHERE query_id=?", new String[]{key})) {
            while (c.moveToNext()) entries.put(c.getLong(0), new Entry(c.getLong(1),
                    c.getInt(2) != 0, c.getLong(3), c.getInt(4) != 0, c.getLong(5), c.getInt(6) != 0));
        }
        Set<Long> confirmed = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT DISTINCT g.gid FROM galleries g JOIN queries q "
                + "ON q.id=g.query_id WHERE q.scope=? AND g.acknowledged=1 "
                + "AND g.gid IN (SELECT gid FROM galleries WHERE query_id=?)", new String[]{scope,key})) {
            while (c.moveToNext()) confirmed.add(c.getLong(0));
        }
        return new Snapshot(scope, query, initialized, version, checked, viewed, head, floor,
                complete, exhausted, entries, confirmed);
    }

    /** A pass owns the immutable baseline captured before its first request. */
    public static final class Pass {
        public final Snapshot baseline;
        private final Map<Long, GalleryInfo> raw = new LinkedHashMap<>();
        private final Map<Long, GalleryInfo> eligible = new LinkedHashMap<>();
        private long floor, head, scannedFloor;
        private boolean first = true, done, exhausted;
        private long expectedVersion;

        public Pass(Snapshot baseline) { this.baseline = baseline; expectedVersion = baseline.version; }

        public void observe(List<GalleryInfo> rawPage, List<GalleryInfo> qualifiedPage,
                            String rawTailPosted, boolean hasMore) {
            for (GalleryInfo gallery : rawPage) {
                if (gallery.gid <= 0) continue;
                raw.put(gallery.gid, gallery);
                head = Math.max(head, GallerySeenState.postedMinute(gallery.posted));
            }
            for (GalleryInfo gallery : qualifiedPage) if (gallery.gid > 0) {
                eligible.put(gallery.gid, gallery);
                raw.putIfAbsent(gallery.gid, gallery);
            }
            // Resolve cross-query confirmations in one bounded SQL lookup, not one query per GID
            // and not a copy of every confirmed gallery in the account for every search condition.
            Set<Long> ids = new HashSet<>();
            for (GalleryInfo gallery : rawPage) ids.add(gallery.gid);
            for (GalleryInfo gallery : qualifiedPage) ids.add(gallery.gid);
            try (SearchUpdateStore store = SearchUpdateStore.open()) {
                baseline.confirmed.addAll(store.confirmed(baseline.scope, ids));
            }
            long tail = GallerySeenState.postedMinute(rawTailPosted);
            if (tail > 0) scannedFloor = tail;
            if (first) { floor = tail; first = false; }
            if (floor == 0 && tail > 0) floor = tail;
            exhausted = !hasMore;
            long required = baseline.initialized && baseline.floor > 0
                    ? Math.min(baseline.floor, floor) : floor;
            done = !hasMore || (tail > 0 && required > 0 && tail < required);
            // An empty baseline needs to cover every newly matching old result, not just page one.
            if (baseline.initialized && baseline.floor == 0 && hasMore) done = false;
        }

        public boolean complete() { return done; }

        public Set<Long> newGids() {
            Set<Long> result = new HashSet<>();
            for (GalleryInfo gallery : eligible.values()) if (baseline.isNew(gallery)) result.add(gallery.gid);
            return result;
        }

        public Set<Long> displayNewGids() {
            Set<Long> result = baseline.unread();
            for (GalleryInfo gallery : eligible.values()) {
                if (baseline.isUnconfirmed(gallery)) result.add(gallery.gid);
            }
            return result;
        }
    }

    /** A completed source advances atomically. Browsing may confirm a partial page without
     * advancing a newer check checkpoint past results which it has not actually scanned. */
    public synchronized void commit(Pass pass, boolean browsing) {
        if (!browsing && !pass.complete()) throw new IllegalStateException("Incomplete check");
        SQLiteDatabase db = getWritableDatabase();
        String key = id(pass.baseline.scope, pass.baseline.query);
        db.beginTransaction();
        try {
            Snapshot current = read(pass.baseline.scope, pass.baseline.query);
            current.confirmed.addAll(confirmed(pass.baseline.scope, pass.raw.keySet()));
            long version = current.version + 1;
            boolean replaceCheckpoint = (pass.complete() || !current.initialized)
                    && current.version == pass.expectedVersion;
            Set<Long> detected = pass.newGids();
            ContentValues state = new ContentValues();
            state.put("id", key); state.put("scope", pass.baseline.scope);
            state.put("touched", System.currentTimeMillis());
            db.insertWithOnConflict("queries", null, state, SQLiteDatabase.CONFLICT_IGNORE);
            if (replaceCheckpoint && pass.complete()) {
                // Only absence inside a fully scanned interval proves that an old match vanished.
                // Keep qualified/discovery history so restoring a tag cannot trigger a new reminder.
                if (pass.exhausted) db.execSQL("UPDATE galleries SET active=0 WHERE query_id=?", new Object[]{key});
                else if (pass.scannedFloor > 0) db.execSQL(
                        "UPDATE galleries SET active=0 WHERE query_id=? AND posted>=?",
                        new Object[]{key, pass.scannedFloor});
            }
            for (GalleryInfo gallery : pass.raw.values()) {
                Entry old = current.entries.get(gallery.gid);
                boolean qualified = pass.eligible.containsKey(gallery.gid);
                long firstDetected = old == null ? 0 : old.detected;
                if (detected.contains(gallery.gid) && firstDetected == 0
                        && (old == null || !old.qualified)) firstDetected = version;
                boolean acknowledged = current.confirmed.contains(gallery.gid)
                        || old != null && old.acknowledged;
                // Only this browsing pass's results are confirmed; unrelated newer checks survive.
                if (browsing && qualified) acknowledged = true;
                ContentValues row = new ContentValues();
                row.put("query_id", key); row.put("gid", gallery.gid);
                row.put("posted", GallerySeenState.postedMinute(gallery.posted));
                row.put("qualified", qualified || old != null && old.qualified ? 1 : 0);
                row.put("active", qualified || current.version != pass.expectedVersion
                        && old != null && old.active ? 1 : 0);
                row.put("detected", firstDetected); row.put("acknowledged", acknowledged ? 1 : 0);
                boolean inSnapshot = pass.floor == 0 || GallerySeenState.postedMinute(gallery.posted) >= pass.floor;
                row.put("snapshot", replaceCheckpoint ? (inSnapshot ? version : 0)
                        : old != null ? old.snapshot : 0);
                row.put("touched", System.currentTimeMillis());
                db.insertWithOnConflict("galleries", null, row, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (browsing) {
                // Overall acknowledgement includes captured unread entries outside the display batch.
                List<Long> captured = new ArrayList<>(pass.baseline.unread());
                for (int start = 0; start < captured.size(); start += 400) {
                    List<Object> args = new ArrayList<>(); args.add(key); args.add(pass.baseline.version);
                    args.addAll(captured.subList(start, Math.min(start + 400, captured.size())));
                    String placeholders = String.join(",", java.util.Collections.nCopies(args.size() - 2, "?"));
                    db.execSQL("UPDATE galleries SET acknowledged=1 WHERE query_id=? AND detected<=? "
                            + "AND gid IN (" + placeholders + ")", args.toArray());
                }
                db.execSQL("UPDATE galleries SET acknowledged=1 WHERE gid IN (SELECT gid FROM galleries "
                                + "WHERE query_id=? AND acknowledged=1) AND query_id IN "
                                + "(SELECT id FROM queries WHERE scope=?)",
                        new Object[]{key, pass.baseline.scope});
            }
            state.clear();
            state.put("version", version);
            if (browsing) state.put("viewed", version);
            // Never replace a completed newer checkpoint with a stale browsing response.
            if (replaceCheckpoint) {
                if (pass.complete()) state.put("checked", version);
                state.put("initialized", 1); state.put("head", Math.max(current.head, pass.head));
                // In a merged request, a child with no matches at EOF is an initialized empty
                // query, even when other children supplied a nonzero raw tail timestamp.
                state.put("floor", pass.exhausted && pass.raw.isEmpty() ? 0 : pass.floor);
                state.put("complete", pass.complete() ? 1 : 0);
                state.put("exhausted", pass.exhausted ? 1 : 0);
                db.execSQL("UPDATE galleries SET snapshot=0 WHERE query_id=? AND snapshot<>?",
                        new Object[]{key, version});
            }
            db.update("queries", state, "id=?", new String[]{key});
            db.execSQL("DELETE FROM galleries WHERE query_id=? AND snapshot=0 "
                            + "AND (acknowledged=1 OR detected=0 OR active=0) AND gid NOT IN "
                            + "(SELECT gid FROM galleries WHERE query_id=? AND snapshot=0 "
                            + "AND (acknowledged=1 OR detected=0 OR active=0) ORDER BY touched DESC,gid DESC LIMIT ?)",
                    new Object[]{key, key, HISTORY_LIMIT});
            db.setTransactionSuccessful();
            pass.expectedVersion = version;
        } finally { db.endTransaction(); }
    }

    public Set<Long> unread(String scope, List<String> queries) {
        Set<Long> result = new HashSet<>();
        for (int start = 0; start < queries.size(); start += 400) {
            List<String> keys = new ArrayList<>();
            for (String query : queries.subList(start, Math.min(start + 400, queries.size()))) keys.add(id(scope, query));
            String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
            keys.add(scope);
            try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT g.gid FROM galleries g "
                    + "WHERE g.query_id IN (" + placeholders + ") AND g.active=1 AND g.detected>0 AND g.acknowledged=0 "
                    + "AND NOT EXISTS (SELECT 1 FROM galleries a JOIN queries q ON q.id=a.query_id "
                    + "WHERE a.gid=g.gid AND a.acknowledged=1 AND q.scope=?)", keys.toArray(new String[0]))) {
                while (c.moveToNext()) result.add(c.getLong(0));
            }
        }
        return result;
    }

    private Set<Long> confirmed(String scope, Set<Long> gids) {
        List<Long> ids = new ArrayList<>(gids);
        Set<Long> result = new HashSet<>();
        for (int start = 0; start < ids.size(); start += 400) {
            List<String> args = new ArrayList<>(); args.add(scope);
            for (long gid : ids.subList(start, Math.min(start + 400, ids.size()))) args.add(Long.toString(gid));
            String placeholders = String.join(",", java.util.Collections.nCopies(args.size() - 1, "?"));
            try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT g.gid FROM galleries g "
                    + "JOIN queries q ON q.id=g.query_id WHERE q.scope=? AND g.acknowledged=1 "
                    + "AND g.gid IN (" + placeholders + ")", args.toArray(new String[0]))) {
                while (c.moveToNext()) result.add(c.getLong(0));
            }
        }
        return result;
    }

    public void acknowledgeLegacy(String scope, String group) {
        Pass pass = new Pass(read(scope, "legacy-unread:" + group));
        pass.observe(List.of(), List.of(), null, false);
        commit(pass, true);
    }

    /** Ordinary queries use the existing history LRU budget; bookmarks are retained. */
    public synchronized void trim(String scope, Set<String> bookmarkQueries, int limit) {
        SQLiteDatabase db = getWritableDatabase();
        List<String> remove = new ArrayList<>();
        int ordinary = 0;
        try (Cursor c = db.rawQuery("SELECT id FROM queries WHERE scope=? ORDER BY touched DESC,id",
                new String[]{scope})) {
            Set<String> retained = new HashSet<>();
            for (String query : bookmarkQueries) retained.add(id(scope, query));
            retained.add(id(scope, "eh-subscription"));
            retained.add(id(scope, "legacy-unread:eh"));
            retained.add(id(scope, "legacy-unread:bookmark"));
            while (c.moveToNext()) {
                String key = c.getString(0);
                if (!retained.contains(key) && ++ordinary > limit) remove.add(key);
            }
        }
        db.beginTransaction();
        try {
            for (String key : remove) {
                db.delete("galleries", "query_id=?", new String[]{key});
                db.delete("queries", "id=?", new String[]{key});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Legacy unread IDs have no exact bookmark attribution: retain them once per scope/group. */
    public synchronized void migrateUnread(String scope, String group, Set<Long> gids) {
        String query = "legacy-unread:" + group;
        if (read(scope, query).initialized) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues state = new ContentValues();
            state.put("id", id(scope, query)); state.put("scope", scope);
            state.put("initialized", 1); state.put("version", 1);
            db.insertOrThrow("queries", null, state);
            for (long gid : gids) {
                ContentValues row = new ContentValues();
                row.put("query_id", id(scope, query)); row.put("gid", gid); row.put("posted", 0);
                row.put("qualified", 1); row.put("active", 1); row.put("detected", 1); row.put("touched", 0);
                db.insertOrThrow("galleries", null, row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
