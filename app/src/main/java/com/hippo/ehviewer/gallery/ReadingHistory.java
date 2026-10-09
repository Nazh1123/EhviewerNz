package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.LocalViewerActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reader visits, independent of the gallery-details history and existing reading progress. */
public final class ReadingHistory {
    static final String PREFERENCES_NAME = "reading_history";
    public static final String KEY_SOURCE = "reading_history_source";
    public static final String DETAIL = "detail";
    public static final String DOWNLOAD = "download";
    public static final String LOCAL = "local";
    public static final String PREVIEW = "preview";
    public static final String LIST = "list";
    public static final String UPDATE = "update";

    private ReadingHistory() {}

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized void record(Context context, Intent intent) {
        if (!Settings.isReadingHistoryEnabled()) return;
        Entry entry = fromIntent(context, intent);
        if (entry == null) return;
        SharedPreferences.Editor editor = preferences(context).edit();
        List<Entry> entries = list(context);
        entries.removeIf(old -> old.key.equals(entry.key));
        // Use the same retention setting as the existing history screen.
        int limit = Settings.getHistoryInfoSize();
        for (int i = Math.max(0, limit - 1); i < entries.size(); i++) {
            editor.remove(entries.get(i).key);
        }
        editor.putString(entry.key, JSON.toJSONString(entry)).apply();
        retainUriPermission(context, intent);
    }

    private static void retainUriPermission(Context context, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null || !"content".equals(uri.getScheme())
                || (intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return;
        try {
            int flags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some external providers only grant access for the current activity.
        }
    }

    public static synchronized List<Entry> list(Context context) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> stored : preferences(context).getAll().entrySet()) {
            if (!(stored.getValue() instanceof String)) continue;
            try {
                Entry entry = JSON.parseObject((String) stored.getValue(), Entry.class);
                if (entry != null && entry.valid() && stored.getKey().equals(entry.key)) entries.add(entry);
            } catch (RuntimeException ignored) {
                // An invalid record must not hide other visits or prevent reading.
            }
        }
        entries.sort((left, right) -> Long.compare(right.readAt, left.readAt));
        return entries;
    }

    public static synchronized void remove(Context context, Entry entry) {
        preferences(context).edit().remove(entry.key).apply();
    }

    public static synchronized void clear(Context context) {
        preferences(context).edit().clear().apply();
    }

    @Nullable
    static Entry fromIntent(Context context, Intent intent) {
        Entry entry = new Entry();
        entry.action = intent.getAction();
        entry.filename = intent.getStringExtra(GalleryActivity.KEY_FILENAME);
        Uri uri = intent.getData();
        entry.uri = uri == null ? null : uri.toString();
        GalleryInfo gallery = intent.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO);
        if (gallery != null) {
            // Persist a small, stable snapshot rather than a DownloadInfo/GalleryDetail subclass.
            JSONObject saved = new JSONObject();
            saved.put("gid", gallery.gid);
            saved.put("token", gallery.token);
            saved.put("title", gallery.title);
            saved.put("titleJpn", gallery.titleJpn);
            saved.put("thumb", gallery.thumb);
            saved.put("pages", gallery.pages);
            entry.gallery = saved.toJavaObject(GalleryInfo.class);
        }
        entry.source = intent.getStringExtra(KEY_SOURCE);
        if (sourceLabel(entry.source) == 0) {
            entry.source = GalleryActivity.ACTION_EH.equals(entry.action) ? DETAIL : LOCAL;
        }
        entry.page = intent.getIntExtra(GalleryActivity.KEY_PAGE, -1);
        entry.updateGid = intent.getLongExtra(GalleryActivity.KEY_UPDATE_RECORD_GID, 0L);
        entry.updateTime = intent.getLongExtra(GalleryActivity.KEY_UPDATE_RECORD_TIME, 0L);
        entry.readAt = System.currentTimeMillis();
        String target = GalleryActivity.ACTION_EH.equals(entry.action) && entry.gallery != null
                ? Long.toString(entry.gallery.gid)
                : entry.uri != null ? entry.uri : entry.filename;
        // Keep separate visits through different entry points for the same gallery.
        entry.key = entry.source + ':' + entry.action + ':' + target;
        if (!entry.valid()) return null;
        entry.title = gallery != null ? EhUtils.getSuitableTitle(gallery) : null;
        if (TextUtils.isEmpty(entry.title)) entry.title = localTitle(context, entry);
        return entry;
    }

    private static String localTitle(Context context, Entry entry) {
        if (entry.uri != null) {
            Uri uri = Uri.parse(entry.uri);
            if ("content".equals(uri.getScheme())) {
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String name = cursor.getString(0);
                        if (!TextUtils.isEmpty(name)) return name;
                    }
                } catch (RuntimeException ignored) {}
            }
            String name = uri.getLastPathSegment();
            return TextUtils.isEmpty(name) ? entry.uri : name;
        }
        LocalFolderGallerySource folder = LocalFolderGallerySource.parse(entry.filename);
        String path = folder == null ? entry.filename : folder.relativePath;
        if (TextUtils.isEmpty(path)) path = folder.treeUri;
        String name = new File(path).getName();
        return TextUtils.isEmpty(name) ? path : name;
    }

    @StringRes
    public static int sourceLabel(@Nullable String source) {
        if (DETAIL.equals(source)) return R.string.reading_history_source_detail;
        if (DOWNLOAD.equals(source)) return R.string.reading_history_source_download;
        if (LOCAL.equals(source)) return R.string.reading_history_source_local;
        if (PREVIEW.equals(source)) return R.string.reading_history_source_preview;
        if (LIST.equals(source)) return R.string.reading_history_source_list;
        if (UPDATE.equals(source)) return R.string.reading_history_source_update;
        return 0;
    }

    public static final class Entry {
        public String key;
        public String title;
        public String source;
        public String action;
        public String filename;
        public String uri;
        public GalleryInfo gallery;
        public int page = -1;
        public long readAt;
        public long updateGid;
        public long updateTime;

        boolean valid() {
            if (key == null || sourceLabel(source) == 0 || readAt <= 0) return false;
            if (GalleryActivity.ACTION_EH.equals(action)) {
                return gallery != null && gallery.gid > 0 && !TextUtils.isEmpty(gallery.token);
            }
            if (GalleryActivity.ACTION_DIR.equals(action)) return !TextUtils.isEmpty(filename);
            if (GalleryActivity.ACTION_LOCAL_FOLDER.equals(action)) {
                return LocalFolderGallerySource.parse(filename) != null;
            }
            if (Intent.ACTION_VIEW.equals(action) && uri != null) {
                String scheme = Uri.parse(uri).getScheme();
                return "file".equals(scheme) || "content".equals(scheme);
            }
            return false;
        }

        public Intent createIntent(Context context) {
            Class<?> reader = LOCAL.equals(source) && Intent.ACTION_VIEW.equals(action)
                    ? LocalViewerActivity.class : GalleryActivity.class;
            Intent intent = new Intent(context, reader).setAction(action);
            intent.putExtra(KEY_SOURCE, source);
            if (filename != null) intent.putExtra(GalleryActivity.KEY_FILENAME, filename);
            if (uri != null) intent.setData(Uri.parse(uri));
            if (gallery != null) intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, gallery);
            intent.putExtra(GalleryActivity.KEY_PAGE, page);
            intent.putExtra(GalleryActivity.KEY_UPDATE_RECORD_GID, updateGid);
            intent.putExtra(GalleryActivity.KEY_UPDATE_RECORD_TIME, updateTime);
            return intent;
        }
    }
}
