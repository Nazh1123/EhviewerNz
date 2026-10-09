package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.LocalViewerActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        List<Entry> entries = list(context);
        for (Entry old : entries) {
            if (old.key.equals(entry.key) && entry.resumeFilename == null) {
                entry.resumeFilename = old.resumeFilename;
                entry.progressAt = old.progressAt;
            }
        }
        entries.removeIf(old -> old.key.equals(entry.key));
        SharedPreferences.Editor editor = preferences(context).edit();
        int limit = Settings.getReadingHistorySize();
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
        // Migrate earlier per-image visits and merge them into the newest directory visit.
        Map<String, Entry> directories = new LinkedHashMap<>();
        SharedPreferences.Editor editor = preferences(context).edit();
        boolean changed = false;
        for (Entry entry : entries) {
            String oldKey = entry.key;
            String oldTitle = entry.title;
            normalizeLocal(context, entry);
            if (!oldKey.equals(entry.key) || !Objects.equals(oldTitle, entry.title)) {
                editor.remove(oldKey);
                changed = true;
            }
            if (directories.putIfAbsent(entry.key, entry) != null) changed = true;
        }
        List<Entry> result = new ArrayList<>(directories.values());
        int limit = Settings.getReadingHistorySize();
        for (int i = limit; i < result.size(); i++) {
            editor.remove(result.get(i).key);
            changed = true;
        }
        if (result.size() > limit) result = new ArrayList<>(result.subList(0, limit));
        if (changed) {
            for (Entry entry : result) editor.putString(entry.key, JSON.toJSONString(entry));
            editor.apply();
        }
        return result;
    }

    /** Update retained local progress without changing the visit order or recreating a removed visit. */
    public static synchronized void saveLocalProgress(Context context, Intent intent, int page,
            @Nullable String filename) {
        if (!Settings.isReadingHistoryEnabled() || page < 0) return;
        Entry visit = fromIntent(context, intent);
        if (visit == null || GalleryActivity.ACTION_EH.equals(visit.action)) return;
        String saved = preferences(context).getString(visit.key, null);
        if (saved == null) return;
        try {
            Entry entry = JSON.parseObject(saved, Entry.class);
            if (entry == null || !entry.valid()) return;
            // Another file in this directory may have been opened since this reader started.
            if (!Objects.equals(entry.uri, visit.uri) || !entry.action.equals(visit.action)) return;
            entry.page = page;
            if (filename != null) entry.resumeFilename = filename;
            entry.progressAt = System.currentTimeMillis();
            preferences(context).edit().putString(entry.key, JSON.toJSONString(entry)).apply();
        } catch (RuntimeException ignored) {}
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
        if (GalleryActivity.ACTION_DIR.equals(entry.action)) {
            entry.resumeFilename = intent.getStringExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME);
        }
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
        normalizeLocal(context, entry);
        if (TextUtils.isEmpty(entry.title)) entry.title = entry.uri != null ? entry.uri : entry.filename;
        return entry;
    }

    private static void normalizeLocal(Context context, Entry entry) {
        LocalFolderGallerySource folder = LocalFolderGallerySource.parse(entry.filename);
        if (GalleryActivity.ACTION_LOCAL_FOLDER.equals(entry.action) && folder != null) {
            String root;
            File directory = null;
            try {
                root = DocumentsContract.getTreeDocumentId(folder.getTreeUri());
                if (root.startsWith("primary:")) {
                    directory = new File(Environment.getExternalStorageDirectory(), root.substring(8));
                } else if (root.contains(":")) {
                    directory = new File("/storage", root.replace(':', '/'));
                } else {
                    root = folder.treeUri;
                }
            } catch (RuntimeException ignored) {
                root = folder.treeUri;
            }
            if (directory != null) {
                directory = new File(directory, folder.relativePath);
                entry.title = canonicalPath(directory);
                entry.key = "directory:" + entry.title;
            } else {
                entry.title = root + (folder.relativePath.isEmpty() ? "" : "/" + folder.relativePath);
                entry.key = "directory:" + folder.encode();
            }
            return;
        }
        if (!LOCAL.equals(entry.source) && !GalleryActivity.ACTION_DIR.equals(entry.action)) return;
        File directory = null;
        if (GalleryActivity.ACTION_DIR.equals(entry.action) && entry.filename != null) {
            directory = new File(entry.filename);
        } else if (Intent.ACTION_VIEW.equals(entry.action) && entry.uri != null) {
            Uri uri = Uri.parse(entry.uri);
            File file = "file".equals(uri.getScheme()) && uri.getPath() != null
                    ? new File(uri.getPath()) : ExternalImageFileResolver.resolve(context, uri);
            // SAF may grant archive access without allowing direct filesystem enumeration.
            if (file == null && "com.android.externalstorage.documents".equals(uri.getAuthority())) {
                try {
                    String[] document = DocumentsContract.getDocumentId(uri).split(":", 2);
                    if (document.length == 2) {
                        file = "primary".equals(document[0])
                                ? new File(Environment.getExternalStorageDirectory(), document[1])
                                : new File("/storage/" + document[0], document[1]);
                    }
                } catch (RuntimeException ignored) {}
            }
            if (file != null) {
                directory = file.getParentFile();
                if (ExternalImageFileResolver.isImageUri(context, uri) && directory != null) {
                    entry.action = GalleryActivity.ACTION_DIR;
                    entry.resumeFilename = file.getName();
                    entry.progressAt = Math.max(entry.progressAt, entry.readAt);
                    entry.uri = null;
                }
            } else if (uri.getPath() != null) {
                int separator = uri.getPath().lastIndexOf('/');
                if (separator >= 0) {
                    entry.title = uri.buildUpon().path(uri.getPath().substring(0, separator))
                            .clearQuery().fragment(null).build().toString();
                }
            }
        }
        if (directory == null) return;
        String path = canonicalPath(directory);
        if (GalleryActivity.ACTION_DIR.equals(entry.action)) entry.filename = path;
        entry.title = path;
        entry.key = "directory:" + path;
    }

    private static String canonicalPath(File directory) {
        try {
            return directory.getCanonicalPath();
        } catch (IOException ignored) {
            return directory.getAbsolutePath();
        }
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
        public String resumeFilename;
        public long progressAt;
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
            if (gallery != null) {
                GalleryInfo info = gallery;
                if (GalleryActivity.ACTION_LOCAL_FOLDER.equals(action)
                        || (gallery.gid < 0 && Intent.ACTION_VIEW.equals(action))) {
                    DownloadInfo imported = JSON.parseObject(JSON.toJSONString(gallery), DownloadInfo.class);
                    imported.archiveUri = GalleryActivity.ACTION_LOCAL_FOLDER.equals(action) ? filename : uri;
                    info = imported;
                }
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, info);
            }
            int startPage = page;
            if (GalleryActivity.ACTION_DIR.equals(action)) {
                LocalGalleryHistory.Entry progress = LocalGalleryHistory.get(context, filename);
                String startFilename = progress != null && progress.updatedAt > Math.max(readAt, progressAt)
                        ? progress.filename : resumeFilename;
                if (startFilename == null && progress != null) startFilename = progress.filename;
                intent.putExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME, startFilename);
                // Resolve a filename after directory enumeration, including deleted-image fallback.
                if (startFilename != null) startPage = -1;
            } else if (gallery != null && (GalleryActivity.ACTION_LOCAL_FOLDER.equals(action)
                    || (gallery.gid < 0 && Intent.ACTION_VIEW.equals(action)))) {
                startPage = -1;
            }
            intent.putExtra(GalleryActivity.KEY_PAGE, startPage);
            intent.putExtra(GalleryActivity.KEY_UPDATE_RECORD_GID, updateGid);
            intent.putExtra(GalleryActivity.KEY_UPDATE_RECORD_TIME, updateTime);
            return intent;
        }
    }
}
