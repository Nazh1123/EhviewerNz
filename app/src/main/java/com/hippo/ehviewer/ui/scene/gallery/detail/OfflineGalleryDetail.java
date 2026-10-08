package com.hippo.ehviewer.ui.scene.gallery.detail;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryPreview;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A local snapshot. It is never added to the online GalleryDetail cache or saved over a download. */
public final class OfflineGalleryDetail {
    final GalleryDetail info;
    final UniFile directory;
    final UniFile cover;
    final ArrayList<GalleryPreview> previews;
    final int startPage;
    final long imageBytes;

    private OfflineGalleryDetail(GalleryDetail info, UniFile directory, UniFile cover,
                                 ArrayList<GalleryPreview> previews, int startPage, long imageBytes) {
        this.info = info;
        this.directory = directory;
        this.cover = cover;
        this.previews = previews;
        this.startPage = startPage;
        this.imageBytes = imageBytes;
    }

    @Nullable
    static OfflineGalleryDetail read(GalleryInfo source, GalleryTags savedTags, UniFile directory) {
        GalleryDetail info = new GalleryDetail();
        info.gid = source.gid;
        info.token = source.token;
        info.title = source.title;
        info.titleJpn = source.titleJpn;
        info.thumb = source.thumb;
        info.category = source.category;
        info.uploader = source.uploader;
        info.posted = source.posted;
        info.rating = source.rating;
        info.firstGid = source.firstGid;
        info.simpleLanguage = source.simpleLanguage;
        info.simpleTags = source.simpleTags;
        info.tags = tags(savedTags, source.simpleTags);
        info.generateSLang();
        info.language = language(info.tags, info.simpleLanguage);

        SpiderInfo spider = directory == null ? null
                : SpiderInfo.readHeader(directory.findFile(SpiderQueen.SPIDER_INFO_FILENAME));
        boolean validSpider = spider != null && spider.gid == source.gid
                && java.util.Objects.equals(spider.token, source.token);
        int startPage = validSpider ? Math.max(0, Math.min(spider.startPage, spider.pages - 1)) : 0;
        ArrayList<GalleryPreview> previews = readPreviews(directory);
        info.pages = validSpider ? spider.pages : Math.max(0, source.pages);
        UniFile cover = directory == null ? null : directory.findFile(".thumb");
        if (cover != null && (!cover.isFile() || cover.length() <= 0)) cover = null;
        if (cover == null && !previews.isEmpty()) {
            cover = previews.get(0).getLocalFile();
        }
        long bytes = 0;
        for (GalleryPreview preview : previews) bytes += Math.max(0, preview.getLocalFile().length());
        if (isEmpty(info.title) && isEmpty(info.titleJpn) && isEmpty(info.uploader)
                && isEmpty(info.posted) && isEmpty(info.thumb) && info.tags.length == 0
                && previews.isEmpty() && !validSpider) return null;
        return new OfflineGalleryDetail(info, directory, cover, previews, startPage, bytes);
    }

    /** Download filenames are one-based page numbers. Preserve gaps in incomplete downloads. */
    public static ArrayList<GalleryPreview> readPreviews(@Nullable UniFile directory) {
        TreeMap<Integer, UniFile> files = new TreeMap<>();
        UniFile[] children = directory == null ? null : directory.listFiles();
        if (children != null) for (UniFile file : children) {
            String name = file.getName();
            int page = pageIndex(name);
            if (page >= 0 && file.isFile() && file.length() > 0) files.putIfAbsent(page, file);
        }
        ArrayList<GalleryPreview> previews = new ArrayList<>(files.size());
        for (Map.Entry<Integer, UniFile> entry : files.entrySet()) {
            previews.add(GalleryPreview.fromLocalFile(entry.getKey(), entry.getValue()));
        }
        return previews;
    }

    static int pageIndex(@Nullable String name) {
        if (name == null) return -1;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return -1;
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        boolean supported = false;
        for (String candidate : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (candidate.equals(extension)) supported = true;
        }
        if (!supported) return -1;
        String number = name.substring(0, dot);
        if (!number.matches("[0-9]+")) return -1;
        try {
            int page = Integer.parseInt(number);
            return page > 0 && page <= 100_000 ? page - 1 : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    static GalleryTagGroup[] tags(@Nullable GalleryTags saved, @Nullable String[] simpleTags) {
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
        if (saved != null) {
            String[] names = {"rows", "artist", "cosplayer", "character", "female", "group",
                    "language", "male", "misc", "mixed", "other", "parody", "reclass", "location"};
            String[] values = {saved.rows, saved.artist, saved.cosplayer, saved.character,
                    saved.female, saved.group, saved.language, saved.male, saved.misc, saved.mixed,
                    saved.other, saved.parody, saved.reclass, saved.location};
            for (int i = 0; i < names.length; i++) {
                if (!isEmpty(values[i])) for (String tag : values[i].split(",")) addTag(groups, names[i], tag);
            }
        }
        if (simpleTags != null) for (String tag : simpleTags) {
            if (tag == null) continue;
            int colon = tag.indexOf(':');
            addTag(groups, colon > 0 ? tag.substring(0, colon) : "misc",
                    colon > 0 ? tag.substring(colon + 1) : tag);
        }
        ArrayList<GalleryTagGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            GalleryTagGroup group = new GalleryTagGroup();
            group.groupName = entry.getKey();
            for (String tag : entry.getValue()) group.addTag(tag);
            result.add(group);
        }
        return result.toArray(new GalleryTagGroup[0]);
    }

    private static void addTag(Map<String, List<String>> groups, String namespace, String tag) {
        tag = tag.trim();
        if (tag.isEmpty()) return;
        List<String> values = groups.computeIfAbsent(namespace, key -> new ArrayList<>());
        if (!values.contains(tag)) values.add(tag);
    }

    private static String language(GalleryTagGroup[] groups, String fallback) {
        for (GalleryTagGroup group : groups) if ("language".equals(group.groupName)) {
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) values.add(group.getTagAt(i));
            return String.join(", ", values);
        }
        if (isEmpty(fallback)) return null;
        Locale locale = Locale.forLanguageTag(fallback.toLowerCase(Locale.ROOT));
        return locale.getDisplayLanguage(Locale.getDefault());
    }

    private static boolean isEmpty(String value) { return value == null || value.trim().isEmpty(); }
}
