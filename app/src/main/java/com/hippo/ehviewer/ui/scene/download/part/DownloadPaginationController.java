/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.download.part;

import static com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo;
import static com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.gallery.ImportedGalleryProgress;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.sxj.paginationlib.PaginationIndicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载列表分页与阅读进度。
 */
public class DownloadPaginationController {

    public interface Host {
        @Nullable
        List<DownloadInfo> getList();
        Context getEHContext();
        AutoStaggeredGridLayoutManager getLayoutManager();
        boolean isContinuousLabelBrowse();
        int getDisplayItemCount();
        int positionInList(int position);
        int listIndexInPage(int position);

        @Nullable
        RecyclerView.Adapter getNotifyAdapter();

        @Nullable
        MyEasyRecyclerView getRecyclerView();
    }

    @NonNull
    private final Host mHost;

    public int indexPage = 1;
    public int pageSize = 1;
    public boolean canPagination = true;
    public final int paginationSize = 500;
    public final int[] perPageCountChoices = {50, 100, 200, 300, 500};

    public MyPageChangeListener myPageChangeListener;
    @Nullable
    public PaginationIndicator mPaginationIndicator;

    private final Map<Long, SpiderInfo> mSpiderInfoMap = new HashMap<>();
    private final Set<Long> mSpiderInfoRequested = new HashSet<>();

    public boolean doNotScroll = false;
    public boolean needInitPage = false;
    public boolean needInitPageSize = false;

    public DownloadPaginationController(@NonNull Host host) {
        mHost = host;
    }

    public int getIndexPage() {
        return indexPage;
    }

    public void setIndexPage(int indexPage) {
        this.indexPage = indexPage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isCanPagination() {
        return canPagination;
    }

    public void setCanPagination(boolean canPagination) {
        this.canPagination = canPagination;
    }

    public int getPaginationSize() {
        return paginationSize;
    }

    public int[] getPerPageCountChoices() {
        return perPageCountChoices;
    }

    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mSpiderInfoMap;
    }

    public boolean isNeedInitPage() {
        return needInitPage;
    }

    public void setNeedInitPage(boolean needInitPage) {
        this.needInitPage = needInitPage;
    }

    @Nullable
    public PaginationIndicator getPaginationIndicator() {
        return mPaginationIndicator;
    }

    public void setPaginationIndicator(@Nullable PaginationIndicator paginationIndicator) {
        mPaginationIndicator = paginationIndicator;
    }

    public void bindPageChangeListener(@Nullable RecyclerView.Adapter adapter,
                                       @Nullable MyEasyRecyclerView recyclerView) {
        myPageChangeListener = new MyPageChangeListener(indexPage, pageSize, needInitPage, doNotScroll, adapter, recyclerView);
        myPageChangeListener.setPageChangeCallback(new MyPageChangeListener.PageChangeCallback() {
            @Override
            public void onPageChanged(int newIndexPage) {
                indexPage = newIndexPage;
                queryUnreadSpiderInfo();
            }

            @Override
            public void onPageSizeChanged(int newPageSize) {
                pageSize = newPageSize;
                queryUnreadSpiderInfo();
            }
        });
    }

    public int positionInList(int position) {
        List<DownloadInfo> list = mHost.getList();
        if (list != null && list.size() > paginationSize && canPagination) {
            return position + pageSize * (indexPage - 1);
        }
        return position;
    }

    public int listIndexInPage(int position) {
        List<DownloadInfo> list = mHost.getList();
        if (list != null && list.size() > paginationSize && canPagination) {
            return position % pageSize;
        }
        return position;
    }

    public void updatePaginationIndicator() {
        List<DownloadInfo> list = mHost.getList();
        if (mPaginationIndicator == null || list == null) {
            return;
        }
        if (mHost.isContinuousLabelBrowse() || list.size() < paginationSize || !canPagination) {
            mPaginationIndicator.setVisibility(View.GONE);
            return;
        }
        mPaginationIndicator.setVisibility(View.VISIBLE);
        needInitPageSize = true;
        mPaginationIndicator.initPaginationIndicator(pageSize, perPageCountChoices, list.size(), indexPage);
//        mPaginationIndicator.setTotalCount();
        mPaginationIndicator.setListener(myPageChangeListener);

        // 同步分页监听器的状态
        if (myPageChangeListener != null) {
            myPageChangeListener.setIndexPage(indexPage);
            myPageChangeListener.setPageSize(pageSize);
            myPageChangeListener.setNeedInitPage(needInitPage);
            myPageChangeListener.setDoNotScroll(doNotScroll);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateReadProcess(ActivityResult result) {
        if (result.getResultCode() == LOCAL_GALLERY_INFO_CHANGE) {
            Intent data = result.getData();
            if (data != null) {
                GalleryInfo info = data.getParcelableExtra("info");

                if (info != null) {
                    mSpiderInfoMap.remove(info.gid);
                    Context context = mHost.getEHContext();
                    boolean imported = info instanceof DownloadInfo
                            && ImportedGalleryProgress.isImportedGallery((DownloadInfo) info);
                    SpiderInfo spiderInfo = imported
                            ? context == null ? null : ImportedGalleryProgress.toSpiderInfo(
                            context, (DownloadInfo) info)
                            : getSpiderInfo(info);
                    if (spiderInfo != null) {
                        mSpiderInfoMap.put(info.gid, spiderInfo);
                    }
                    trimSpiderInfoMapToCurrentPage();
                }

//                mSpiderInfoMap.remove(info.gid);
//                SpiderInfo spiderInfo = getSpiderInfo(info);
                int position = -1;
                if (mHost.getList() == null || mHost.getNotifyAdapter() == null || info == null) {
                    return;
                }
                for (int i = 0; i < mHost.getList().size(); i++) {
                    if (mHost.getList().get(i).gid == info.gid) {
                        position = mHost.listIndexInPage(i);
                        break;
                    }
                }
                if (position != -1) {
                    mHost.getNotifyAdapter().notifyItemChanged(position);
                } else {
                    mHost.getNotifyAdapter().notifyDataSetChanged();
                }

            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void resetReadingProgressInUi() {
        for (SpiderInfo spiderInfo : mSpiderInfoMap.values()) {
            if (spiderInfo != null) {
                spiderInfo.startPage = 0;
            }
        }
        RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @NonNull
    public List<DownloadInfo> getCurrentPageList() {
        List<DownloadInfo> list = mHost.getList();
        if (list == null) {
            return Collections.emptyList();
        }
        if (list.size() > paginationSize && canPagination) {
            int from = pageSize * (indexPage - 1);
            if (from < 0) {
                from = 0;
            }
            if (from >= list.size()) {
                return Collections.emptyList();
            }
            int to = Math.min(from + pageSize, list.size());
            return list.subList(from, to);
        }
        return list;
    }

    public void trimSpiderInfoMapToCurrentPage() {
        if (mHost.isContinuousLabelBrowse()) {
            return;
        }
        List<DownloadInfo> pageList = getCurrentPageList();
        Set<Long> keep = new HashSet<>(pageList.size());
        for (DownloadInfo info : pageList) {
            keep.add(info.gid);
        }
        mSpiderInfoMap.keySet().retainAll(keep);
        mSpiderInfoRequested.retainAll(keep);
    }

    public void queryUnreadSpiderInfo() {
        if (mHost.getList() == null) {
            return;
        }
        if (mHost.isContinuousLabelBrowse()) {
            List<DownloadInfo> initialWindow = new ArrayList<>(100);
            for (int position = 0; position < mHost.getDisplayItemCount(); position++) {
                int listPosition = mHost.positionInList(position);
                if (listPosition >= 0 && listPosition < mHost.getList().size()) {
                    initialWindow.add(mHost.getList().get(listPosition));
                    if (initialWindow.size() == 100) {
                        break;
                    }
                }
            }
            requestSpiderInfo(initialWindow);
            return;
        }
        trimSpiderInfoMapToCurrentPage();
        List<DownloadInfo> pageList = getCurrentPageList();
        List<DownloadInfo> requestList = new ArrayList<>();
        for (int i = 0; i < pageList.size(); i++) {
            DownloadInfo info = pageList.get(i);
            if (!mSpiderInfoMap.containsKey(info.gid) || mSpiderInfoMap.get(info.gid) == null) {
                requestList.add(info);
            }
        }
        requestSpiderInfo(requestList);
    }

    public void fetchSpiderInfo(List<DownloadInfo> infos) {
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(infos, this::spiderInfoResultCallBack);
        executor.execute();
    }

    public void spiderInfoResultCallBack(Map<Long, SpiderInfo> resultMap) {
        mSpiderInfoMap.putAll(resultMap);
        trimSpiderInfoMapToCurrentPage();
        if (mHost.getNotifyAdapter() == null || mHost.getLayoutManager() == null) {
            return;
        }
        int spanCount = mHost.getLayoutManager().getSpanCount();
        if (spanCount <= 0) {
            return;
        }
        int[] firstPositions = mHost.getLayoutManager().findFirstVisibleItemPositions(
                new int[spanCount]);
        int[] lastPositions = mHost.getLayoutManager().findLastVisibleItemPositions(
                new int[spanCount]);
        int first = Integer.MAX_VALUE;
        int last = RecyclerView.NO_POSITION;
        for (int position : firstPositions) {
            if (position != RecyclerView.NO_POSITION) {
                first = Math.min(first, position);
            }
        }
        for (int position : lastPositions) {
            last = Math.max(last, position);
        }
        if (first != Integer.MAX_VALUE && last >= first) {
            mHost.getNotifyAdapter().notifyItemRangeChanged(first, last - first + 1);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void initPage(int position) {
        List<DownloadInfo> list = mHost.getList();
        if (list != null && list.size() > paginationSize && canPagination) {
            indexPage = position / pageSize + 1;
        }
        doNotScroll = true;
        if (mPaginationIndicator != null) {
            mPaginationIndicator.skip2Pos(indexPage);
        }
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        recyclerView.scrollToPosition(listIndexInPage(position));
    }

    public int getPageSizePos(int pageSize) {
        int index = 0;
        for (int i = 0; i < perPageCountChoices.length; i++) {
            if (pageSize == perPageCountChoices[i]) {
                index = i;
                break;
            }
        }
        return index;
    }
    public void queryVisibleSpiderInfo() {
        if (!mHost.isContinuousLabelBrowse() || mHost.getLayoutManager() == null
                || mHost.getList() == null || mHost.getList().isEmpty()) {
            return;
        }
        int spanCount = mHost.getLayoutManager().getSpanCount();
        if (spanCount <= 0) {
            return;
        }
        int[] firstPositions = mHost.getLayoutManager().findFirstVisibleItemPositions(
                new int[spanCount]);
        int[] lastPositions = mHost.getLayoutManager().findLastVisibleItemPositions(
                new int[spanCount]);
        int first = Integer.MAX_VALUE;
        int last = RecyclerView.NO_POSITION;
        for (int position : firstPositions) {
            if (position != RecyclerView.NO_POSITION) {
                first = Math.min(first, position);
            }
        }
        for (int position : lastPositions) {
            last = Math.max(last, position);
        }
        if (first == Integer.MAX_VALUE || last < first) {
            return;
        }

        int preload = Math.max(24, spanCount * 8);
        int start = Math.max(0, first - preload / 2);
        int end = Math.min(mHost.getDisplayItemCount() - 1, last + preload);
        List<DownloadInfo> request = new ArrayList<>();
        for (int position = start; position <= end; position++) {
            int listPosition = mHost.positionInList(position);
            if (listPosition >= 0 && listPosition < mHost.getList().size()) {
                request.add(mHost.getList().get(listPosition));
            }
        }
        requestSpiderInfo(request);
    }
    public void requestSpiderInfo(@NonNull List<DownloadInfo> candidates) {
        Context context = mHost.getEHContext();
        List<DownloadInfo> request = new ArrayList<>();
        Map<Long, SpiderInfo> importedResult = new HashMap<>();
        for (DownloadInfo info : candidates) {
            if (info.state != DownloadInfo.STATE_FINISH
                    || !mSpiderInfoRequested.add(info.gid)) {
                continue;
            }
            if (ImportedGalleryProgress.isImportedGallery(info)) {
                if (context == null) {
                    mSpiderInfoRequested.remove(info.gid);
                    continue;
                }
                SpiderInfo spiderInfo = ImportedGalleryProgress.toSpiderInfo(context, info);
                if (spiderInfo != null) {
                    importedResult.put(info.gid, spiderInfo);
                }
            } else {
                request.add(info);
            }
        }
        if (!importedResult.isEmpty()) {
            spiderInfoResultCallBack(importedResult);
        }
        if (request.isEmpty()) {
            return;
        }
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(
                request, this::spiderInfoResultCallBack);
        executor.execute();
    }
}
