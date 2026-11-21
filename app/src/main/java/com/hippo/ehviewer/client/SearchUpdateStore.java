package com.hippo.ehviewer.client;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.hippo.ehviewer.EhApplication;

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

/** Query-local checkpoints/browsing and shared notification confirmations. No per-check history is retained. */
public final class SearchUpdateStore extends SQLiteOpenHelper {
    public static final String DATABASE = "search-updates.db";
    public static final int HISTORY_LIMIT = 1000;
    private static Context defaultContext;

    public static void initialize(Context context) { defaultContext = context.getApplicationContext(); }

    public SearchUpdateStore(Context context) {
        super(context.getApplicationContext(), DATABASE, null, 2);
    }

    public static SearchUpdateStore open() {
        return new SearchUpdateStore(defaultContext != null ? defaultContext : Settings.getApplicationContext());
    }

    public static void saveBrowsingInBackground(Runnable save) {
        EhApplication.getSearchHistoryExecutor().execute(() -> {
            try {
                save.run();
            } catch (RuntimeException error) {
                Log.w("SearchUpdateStore", "Unable to save browsing state", error);
            }
        });
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
                + "browsed INTEGER NOT NULL DEFAULT 0, "
                + "snapshot INTEGER NOT NULL DEFAULT 0, touched INTEGER NOT NULL, "
                + "PRIMARY KEY(query_id,gid)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX galleries_identity ON galleries(gid,acknowledged)");
        db.execSQL("CREATE INDEX queries_scope ON queries(scope)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE galleries ADD COLUMN browsed INTEGER NOT NULL DEFAULT 0");
            // Recover query-local browsing from its own frontier, never from shared acknowledgements.
            db.execSQL("UPDATE galleries SET browsed=1 WHERE EXISTS (SELECT 1 FROM queries q "
                    + "WHERE q.id=galleries.query_id AND q.viewed>0 AND galleries.detected<=q.viewed)");
        } else {
            throw new IllegalStateException("Unsupported search state version: " + oldVersion);
        }
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

    private record Entry(long posted, boolean qualified, long detected, boolean acknowledged,
                         long snapshot, boolean active, boolean browsed) { }

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
            return !confirmed.contains(gallery.gid) && isNewInQuery(gallery);
        }

        private boolean isNewInQuery(GalleryInfo gallery) {
            if (!initialized || gallery.gid <= 0) return false;
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

        // Shared acknowledgements suppress duplicate alerts, but cannot advance another query's divider.
        private Set<Long> unviewedInQuery() {
            Set<Long> result = new HashSet<>();
            entries.forEach((gid, row) -> {
                if (row.active && row.detected > 0 && !row.browsed) result.add(gid);
            });
            return result;
        }

        private boolean isUnviewedInQuery(GalleryInfo gallery) {
            Entry entry = entries.get(gallery.gid);
            return (entry != null && entry.detected > 0 && !entry.browsed) || isNewInQuery(gallery);
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
        try (Cursor c = db.rawQuery("SELECT gid,posted,qualified,detected,acknowledged,snapshot,active,browsed "
                + "FROM galleries WHERE query_id=?", new String[]{key})) {
            while (c.moveToNext()) entries.put(c.getLong(0), new Entry(c.getLong(1),
                    c.getInt(2) != 0, c.getLong(3), c.getInt(4) != 0, c.getLong(5), c.getInt(6) != 0,
                    c.getInt(7) != 0));
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
        private final Set<Long> pendingGids = new HashSet<>();
        private PageChanges pageChanges;
        private long floor, head, scannedFloor;
        private boolean first = true, done, exhausted;
        // Successive queued snapshots share the last successful write of this browsing pass.
        private final CommitVersion commitVersion;

        private record PageChanges(int number, PageChanges previous, Set<Long> gids) { }

        private static final class CommitVersion {
            long expected;
            PageChanges savedPage;
            boolean superseded;
            CommitVersion(long expected) { this.expected = expected; }
        }

        public Pass(Snapshot baseline) {
            this(baseline, new CommitVersion(baseline.version));
        }

        private Pass(Snapshot baseline, CommitVersion commitVersion) {
            this.baseline = baseline;
            this.commitVersion = commitVersion;
        }

        Pass snapshotForCommit() {
            int number = pageChanges == null ? 1 : pageChanges.number + 1;
            pageChanges = new PageChanges(number, pageChanges, Set.copyOf(pendingGids));
            pendingGids.clear();
            Pass copy = new Pass(new Snapshot(baseline.scope, baseline.query, baseline.initialized,
                    baseline.version, baseline.checked, baseline.viewed, baseline.head, baseline.floor,
                    baseline.complete, baseline.exhausted, baseline.entries, new HashSet<>(baseline.confirmed)),
                    commitVersion);
            copy.raw.putAll(raw);
            copy.eligible.putAll(eligible);
            copy.pageChanges = pageChanges;
            copy.floor = floor;
            copy.head = head;
            copy.scannedFloor = scannedFloor;
            copy.done = done;
            copy.exhausted = exhausted;
            return copy;
        }

        public void observe(List<GalleryInfo> rawPage, List<GalleryInfo> qualifiedPage,
                            String rawTailPosted, boolean hasMore) {
            Set<Long> ids = new HashSet<>();
            for (GalleryInfo gallery : rawPage) ids.add(gallery.gid);
            for (GalleryInfo gallery : qualifiedPage) ids.add(gallery.gid);
            try (SearchUpdateStore store = SearchUpdateStore.open()) {
                observe(rawPage, qualifiedPage, rawTailPosted, hasMore, store.confirmed(baseline.scope, ids));
            }
        }

        void observe(List<GalleryInfo> rawPage, List<GalleryInfo> qualifiedPage,
                     String rawTailPosted, boolean hasMore, Set<Long> confirmed) {
            for (GalleryInfo gallery : rawPage) {
                if (gallery.gid <= 0) continue;
                raw.put(gallery.gid, gallery);
                pendingGids.add(gallery.gid);
                head = Math.max(head, GallerySeenState.postedMinute(gallery.posted));
            }
            for (GalleryInfo gallery : qualifiedPage) if (gallery.gid > 0) {
                eligible.put(gallery.gid, gallery);
                raw.putIfAbsent(gallery.gid, gallery);
                pendingGids.add(gallery.gid);
            }
            baseline.confirmed.addAll(confirmed);
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

        private Set<Long> unsavedGids() {
            Set<Long> gids = new HashSet<>();
            for (PageChanges page = pageChanges; page != commitVersion.savedPage && page != null;
                    page = page.previous) {
                gids.addAll(page.gids);
            }
            return gids;
        }

        private boolean isInCheckpoint(GalleryInfo gallery) {
            return floor == 0 || GallerySeenState.postedMinute(gallery.posted) >= floor;
        }

        /** The divider describes this query's last visit; notification deduplication is separate. */
        public Set<Long> queryDisplayNewGids() {
            Set<Long> result = baseline.unviewedInQuery();
            for (GalleryInfo gallery : eligible.values()) {
                if (baseline.isUnviewedInQuery(gallery)) result.add(gallery.gid);
            }
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
        if (browsing) {
            commitCapturedPage(pass.snapshotForCommit());
        } else {
            if (!pass.complete()) throw new IllegalStateException("Incomplete check");
            commitPage(pass, false);
        }
    }

    synchronized void commitCapturedPage(Pass page) {
        synchronized (page.commitVersion) {
            commitPage(page, true);
        }
    }

    private void commitPage(Pass pass, boolean browsing) {
        Pass.CommitVersion progress = pass.commitVersion;
        if (browsing && progress.savedPage != null && pass.pageChanges.number <= progress.savedPage.number) return;
        SQLiteDatabase db = getWritableDatabase();
        String key = id(pass.baseline.scope, pass.baseline.query);
        long version;
        boolean stale;
        db.beginTransaction();
        try {
            Snapshot current = readSnapshot(pass.baseline.scope, pass.baseline.query);
            version = current.version + 1;
            boolean conflict = current.version != progress.expected;
            // Later pages of a superseded pass can acknowledge what they show but cannot
            // replace the newer check's coverage or deactivate its results.
            stale = conflict || browsing && progress.superseded;
            boolean replaceCheckpoint = (pass.complete() || !current.initialized) && !stale;
            Set<Long> gidsToSave = !browsing || conflict ? pass.raw.keySet() : pass.unsavedGids();
            if (browsing && replaceCheckpoint) {
                // Reuse checkpoint membership when its meaning is unchanged, regardless of its revision.
                for (GalleryInfo gallery : pass.raw.values()) {
                    Entry old = current.entries.get(gallery.gid);
                    if (pass.isInCheckpoint(gallery) != (old != null && old.snapshot != 0)) {
                        gidsToSave.add(gallery.gid);
                    }
                }
            }
            current.confirmed.addAll(confirmed(pass.baseline.scope, gidsToSave));
            ContentValues state = new ContentValues();
            state.put("id", key); state.put("scope", pass.baseline.scope);
            state.put("touched", System.currentTimeMillis());
            db.insertWithOnConflict("queries", null, state, SQLiteDatabase.CONFLICT_IGNORE);
            if (browsing && replaceCheckpoint) {
                reconcileMissingGalleries(db, key, pass, current);
            } else if (replaceCheckpoint && pass.complete()) {
                // Only absence inside a fully scanned interval proves that an old match vanished.
                // Keep qualified/discovery history so restoring a tag cannot trigger a new reminder.
                if (pass.exhausted) db.execSQL("UPDATE galleries SET active=0 WHERE query_id=? AND active=1", new Object[]{key});
                else if (pass.scannedFloor > 0) db.execSQL(
                        "UPDATE galleries SET active=0 WHERE query_id=? AND active=1 AND posted>=?",
                        new Object[]{key, pass.scannedFloor});
            }
            for (long gid : gidsToSave) {
                GalleryInfo gallery = pass.raw.get(gid);
                Entry old = current.entries.get(gid);
                GalleryInfo eligible = pass.eligible.get(gid);
                boolean qualified = eligible != null;
                long firstDetected = old == null ? 0 : old.detected;
                if (qualified && firstDetected == 0 && (old == null || !old.qualified)
                        && pass.baseline.isNewInQuery(eligible)) firstDetected = version;
                // Only this browsing pass's results are confirmed; unrelated newer checks survive.
                boolean acknowledged = browsing && qualified || current.confirmed.contains(gid)
                        || old != null && old.acknowledged;
                long snapshot = old == null ? 0 : old.snapshot;
                if (replaceCheckpoint) {
                    if (!pass.isInCheckpoint(gallery)) snapshot = 0;
                    else if (!browsing || snapshot == 0) snapshot = version;
                }
                Entry next = new Entry(GallerySeenState.postedMinute(gallery.posted),
                        qualified || old != null && old.qualified, firstDetected, acknowledged, snapshot,
                        qualified || stale && old != null && old.active, browsing && qualified || old != null && old.browsed);
                if (!browsing || !next.equals(old)) writeGallery(db, key, gid, next);
            }
            if (browsing) {
                // Overall acknowledgement includes captured unread entries outside the display batch.
                List<Long> captured = new ArrayList<>(pass.baseline.unread());
                for (int start = 0; start < captured.size(); start += 400) {
                    List<Object> args = new ArrayList<>(); args.add(key); args.add(pass.baseline.version);
                    args.addAll(captured.subList(start, Math.min(start + 400, captured.size())));
                    String placeholders = String.join(",", java.util.Collections.nCopies(args.size() - 2, "?"));
                    db.execSQL("UPDATE galleries SET acknowledged=1 WHERE query_id=? AND acknowledged=0 AND detected<=? "
                            + "AND gid IN (" + placeholders + ")", args.toArray());
                }
                List<Long> unviewed = new ArrayList<>(pass.baseline.unviewedInQuery());
                for (int start = 0; start < unviewed.size(); start += 400) {
                    List<Object> args = new ArrayList<>(); args.add(key); args.add(pass.baseline.version);
                    args.addAll(unviewed.subList(start, Math.min(start + 400, unviewed.size())));
                    String placeholders = String.join(",", java.util.Collections.nCopies(args.size() - 2, "?"));
                    db.execSQL("UPDATE galleries SET browsed=1 WHERE query_id=? AND browsed=0 AND detected<=? "
                            + "AND gid IN (" + placeholders + ")", args.toArray());
                }
                db.execSQL("UPDATE galleries SET acknowledged=1 WHERE acknowledged=0 AND gid IN (SELECT gid FROM galleries "
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
                if (!browsing) db.execSQL("UPDATE galleries SET snapshot=0 WHERE query_id=? AND snapshot<>0 AND snapshot<>?",
                        new Object[]{key, version});
            }
            db.update("queries", state, "id=?", new String[]{key});
            db.execSQL("DELETE FROM galleries WHERE query_id=? AND snapshot=0 "
                            + "AND (acknowledged=1 OR detected=0 OR active=0) AND gid NOT IN "
                            + "(SELECT gid FROM galleries WHERE query_id=? AND active=1 AND detected>0 AND browsed=0) "
                            + "AND gid NOT IN "
                            + "(SELECT gid FROM galleries WHERE query_id=? AND snapshot=0 "
                            + "AND (acknowledged=1 OR detected=0 OR active=0) ORDER BY touched DESC,gid DESC LIMIT ?)",
                    new Object[]{key, key, key, HISTORY_LIMIT});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        progress.expected = version;
        if (browsing) {
            progress.superseded = stale;
            progress.savedPage = pass.pageChanges;
        }
    }

    private static void reconcileMissingGalleries(SQLiteDatabase db, String key, Pass pass, Snapshot current) {
        for (Map.Entry<Long, Entry> row : current.entries.entrySet()) {
            long gid = row.getKey();
            if (pass.raw.containsKey(gid)) continue;
            Entry old = row.getValue();
            boolean covered = pass.exhausted || pass.scannedFloor > 0 && old.posted >= pass.scannedFloor;
            boolean deactivate = old.active && pass.complete() && covered;
            if (!deactivate && old.snapshot == 0) continue;
            ContentValues values = new ContentValues();
            if (deactivate) values.put("active", 0);
            if (old.snapshot != 0) values.put("snapshot", 0);
            db.update("galleries", values, "query_id=? AND gid=?", new String[]{key, Long.toString(gid)});
        }
    }

    private static void writeGallery(SQLiteDatabase db, String key, long gid, Entry entry) {
        ContentValues row = new ContentValues();
        row.put("query_id", key); row.put("gid", gid);
        row.put("posted", entry.posted); row.put("qualified", entry.qualified ? 1 : 0);
        row.put("active", entry.active ? 1 : 0); row.put("detected", entry.detected);
        row.put("acknowledged", entry.acknowledged ? 1 : 0); row.put("browsed", entry.browsed ? 1 : 0);
        row.put("snapshot", entry.snapshot); row.put("touched", System.currentTimeMillis());
        if (db.insertWithOnConflict("galleries", null, row, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new SQLiteException("Unable to save gallery browsing state");
        }
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

    Set<Long> confirmed(String scope, Set<Long> gids) {
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
        Snapshot baseline = read(scope, "legacy-unread:" + group);
        if (baseline.initialized && baseline.unread().isEmpty() && baseline.unviewedInQuery().isEmpty()) return;
        Pass pass = new Pass(baseline);
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
