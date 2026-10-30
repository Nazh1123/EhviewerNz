package com.hippo.ehviewer.ui.fragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Limits download maintenance to inactive, app-owned gallery originals. */
final class GalleryDownloadCleanup {
    private GalleryDownloadCleanup() {}

    static boolean isProtected(@NonNull DownloadInfo info) {
        return DownloadManager.isImportedGallery(info)
                || info.state == DownloadInfo.STATE_WAIT
                || info.state == DownloadInfo.STATE_DOWNLOAD
                || info.state == DownloadInfo.STATE_UPDATE;
    }

    /** Returns the live download to reset on the main thread, or null if left untouched. */
    @Nullable
    static DownloadInfo cleanInvalidDirectory(@NonNull UniFile directory,
            @NonNull DownloadManager manager, @NonNull List<String> logs) {
        if (!directory.isDirectory() || !directory.canRead()) return null;
        UniFile[] files = directory.listFiles();
        if (files == null) return null; // Unavailable storage is not an empty gallery.
        SpiderInfo metadata = SpiderInfo.readHeader(
                directory.findFile(SpiderQueen.SPIDER_INFO_FILENAME));
        if (metadata == null) {
            logs.add("Missing or invalid .ehviewer file: " + directory.getName());
            return null;
        }
        DownloadInfo info = manager.getDownloadInfo(metadata.gid);
        if (info == null || isProtected(info)) return null;
        UniFile ownedDirectory = SpiderDen.getExistingGalleryDownloadDir(info);
        if (ownedDirectory == null || !ownedDirectory.getUri().equals(directory.getUri())) {
            // SAF providers may use opaque document IDs that subFile cannot construct.
            UniFile root = Settings.getDownloadLocation();
            String dirname = EhDB.getDownloadDirname(info.gid);
            ownedDirectory = root != null && dirname != null ? root.findFile(dirname) : null;
            if (ownedDirectory == null || !ownedDirectory.getUri().equals(directory.getUri())) {
                return null;
            }
        }

        List<UniFile> originals = new ArrayList<>();
        Set<Integer> presentPages = new HashSet<>();
        for (UniFile file : files) {
            int page = originalPage(file);
            if (page < 0) continue;
            if (!file.canRead()) return null;
            originals.add(file);
            if (page < metadata.pages && file.length() > 0) presentPages.add(page);
        }
        // Check page positions, not the number of directory entries or image extensions.
        if (presentPages.size() == metadata.pages) return null;

        logs.add("Incomplete gallery: " + directory.getName() + ", expected: "
                + metadata.pages + ", actual: " + presentPages.size());
        for (UniFile original : originals) {
            if (manager.getDownloadInfo(info.gid) != info || isProtected(info)) return null;
            if (!original.delete()) logs.add("Unable to delete original: " + original.getUri());
        }
        return info;
    }

    /** Only the numbered original files written by SpiderDen; never result subdirectories. */
    private static int originalPage(@NonNull UniFile file) {
        if (!file.isFile()) return -1;
        String name = file.getName();
        if (name == null || name.length() < 12 || name.charAt(8) != '.') return -1;
        String extension = name.substring(8);
        boolean supported = false;
        for (String value : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (value.equals(extension)) {
                supported = true;
                break;
            }
        }
        if (!supported) return -1;
        int page = 0;
        for (int i = 0; i < 8; i++) {
            char digit = name.charAt(i);
            if (digit < '0' || digit > '9') return -1;
            page = page * 10 + digit - '0';
        }
        return page - 1;
    }
}
