package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.text.TextUtils;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.SubscriptionGallerySnapshot;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.spider.SpiderQueen;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Metadata/header reads only. Never enumerate gallery images for the pending detail screen. */
final class LocalGallerySummary {
    record Snapshot(GalleryInfo info, int startPage) {}

    static Snapshot read(Context context, long gid, String token, GalleryInfo entry) {
        GalleryInfo result = entry == null ? new GalleryInfo() : copy(entry);
        result.gid = gid; result.token = token;
        if (entry == null) result.rating = -1;
        fillMissing(result, EhApplication.getDownloadManager(context).getDownloadInfo(gid));
        fillMissing(result, EhDB.getHistoryInfo(gid));
        GalleryInfo favorite = EhDB.searchLocalFavorites(gid);
        fillMissing(result, favorite);
        if (favorite != null && Objects.equals(result.token, favorite.token) && result.favoriteSlot == -2) result.favoriteSlot = -1;
        if (result.simpleTags == null && (result.tgList == null || result.tgList.isEmpty())) {
            List<String> tags = new ArrayList<>();
            for (GalleryTagGroup group : OfflineGalleryDetail.tags(EhDB.queryGalleryTags(gid), null))
                for (int i = 0; i < group.size(); i++) tags.add(group.groupName + ":" + group.getTagAt(i));
            if (!tags.isEmpty()) result.simpleTags = tags.toArray(new String[0]);
        }
        int startPage = SpiderQueen.findStartPage(context, gid);
        if (result.pages <= 0) result.pages = SpiderQueen.peekPageCount(gid);
        return new Snapshot(result, startPage);
    }

    static GalleryInfo copy(GalleryInfo info) { return SubscriptionGallerySnapshot.copy(List.of(info)).get(0); }

    static void fillMissing(GalleryInfo target, GalleryInfo source) {
        if (source == null || source.gid != target.gid || (!TextUtils.isEmpty(target.token)
                && !Objects.equals(target.token, source.token))) return;
        if (TextUtils.isEmpty(target.token)) target.token = source.token;
        if (TextUtils.isEmpty(target.title)) target.title = source.title;
        if (TextUtils.isEmpty(target.titleJpn)) target.titleJpn = source.titleJpn;
        if (TextUtils.isEmpty(target.uploader)) target.uploader = source.uploader;
        if (TextUtils.isEmpty(target.thumb)) {
            target.thumb = source.thumb; target.thumbWidth = source.thumbWidth; target.thumbHeight = source.thumbHeight;
        }
        if (TextUtils.isEmpty(target.posted)) target.posted = source.posted;
        if (TextUtils.isEmpty(target.simpleLanguage)) target.simpleLanguage = source.simpleLanguage;
        if (target.category == 0) target.category = source.category;
        if (target.pages <= 0) target.pages = source.pages;
        if (target.rating < 0 || !Float.isFinite(target.rating)) target.rating = source.rating;
        if (target.firstGid == null) target.firstGid = source.firstGid;
        if (target.simpleTags == null && source.simpleTags != null) target.simpleTags = source.simpleTags.clone();
        if (target.tgList == null && source.tgList != null) target.tgList = new ArrayList<>(source.tgList);
    }
}
