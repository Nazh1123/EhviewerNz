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

package com.hippo.ehviewer.ui.scene.download;

import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.app.EditTextDialogBuilder;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadQuickOrganizer;
import com.hippo.ehviewer.download.DownloadLabelSearchQueryResolver;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.event.SomethingNeedRefresh;
import com.hippo.ehviewer.gallery.ImportedGalleryProgress;
import com.hippo.ehviewer.gallery.LocalFolderGalleryScanner;
import com.hippo.ehviewer.gallery.LocalFolderCoverStore;
import com.hippo.ehviewer.gallery.LocalFolderGallerySource;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.ui.scene.history.GalleryUpdateHistoryScene;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadArchiveImporter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAlbumImporter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadBatchActions;
import com.hippo.ehviewer.ui.scene.download.part.DownloadPaginationController;
import com.hippo.ehviewer.ui.scene.download.part.DownloadSearchController;
import com.hippo.ehviewer.ui.scene.download.part.DownloadChoiceListener;
import com.hippo.ehviewer.ui.scene.download.part.DownloadGuideHelper;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.util.DrawableManager;
import com.hippo.util.ExceptionUtils;
import com.hippo.view.ViewTransition;
import com.hippo.widget.FabLayout;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SearchBarMover;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.sxj.paginationlib.PaginationIndicator;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class DownloadsScene extends ToolbarScene
        implements DownloadManager.DownloadInfoListener, DownloadSearchCallback,
        MyEasyRecyclerView.OnItemClickListener,
        MyEasyRecyclerView.OnItemLongClickListener,
        FabLayout.OnClickFabListener, FabLayout.OnExpandListener, FastScroller.OnDragHandlerListener, SearchBar.Helper, SearchBarMover.Helper, SearchBar.OnStateChangeListener, DownloadAdapter.DownloadAdapterCallback,
        DownloadArchiveImporter.Host, DownloadAlbumImporter.Host, DownloadBatchActions.Host,
        DownloadPaginationController.Host, DownloadSearchController.Host,
        DownloadChoiceListener.Host, DownloadGuideHelper.Host {

    private static final String TAG = DownloadsScene.class.getSimpleName();
    private final DownloadArchiveImporter mArchiveImporter = new DownloadArchiveImporter(this);
    private final DownloadAlbumImporter mAlbumImporter = new DownloadAlbumImporter(this);
    private final DownloadBatchActions mBatchActions = new DownloadBatchActions(this);
    private final DownloadPaginationController mPaginationController = new DownloadPaginationController(this);
    private final DownloadSearchController mSearchController = new DownloadSearchController(this);
    private final DownloadGuideHelper mGuideHelper = new DownloadGuideHelper(this);

    public static final String KEY_GID = "gid";

    public static final String KEY_ACTION = "action";
    static final String KEY_LABEL = "label";
    static final String KEY_FORCE_SINGLE_LABEL_MODE = "force_single_label_mode";

    public static final String ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service";

    public static final int LOCAL_GALLERY_INFO_CHANGE = 909;

    private static final long ANIMATE_TIME = 300L;
    private static final int FAB_DELETE_TRANSLATIONS = 4;
    private static final int FAB_DRAG = 7;
    private static final int FAB_QUICK_ORGANIZE = 8;

    @Nullable
    private AddDeleteDrawable mActionFabDrawable;

    /*---------------
         Whole life cycle
         ---------------*/
    @Nullable
    private DownloadManager mDownloadManager;
    @Nullable
    public String mLabel;
    @Nullable
    private List<DownloadInfo> mList;
    @Nullable
    private List<DownloadInfo> mBackList;
    private boolean mContinuousLabelBrowse;
    private boolean mForceSingleLabelBrowse;
    private static final java.util.concurrent.atomic.AtomicBoolean sFolderScanRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    @Nullable
    private MenuItem mSyncFolderMenuItem;
    @Nullable
    private MenuItem mStartAllMenuItem;
    private final List<ContinuousDownloadItem> mContinuousItems = new ArrayList<>();
    private final Map<Long, Integer> mContinuousGalleryPositions = new HashMap<>();
    private final Map<String, Integer> mContinuousHeaderPositions = new HashMap<>();

    private static final class ContinuousDownloadItem {
        final boolean header;
        @Nullable
        final String label;
        @Nullable
        final Long labelId;
        @Nullable
        final String title;
        @Nullable
        final DownloadInfo downloadInfo;
        final int galleryIndex;
        final int galleryCount;
        final boolean collapsed;
        final long stableId;

        private ContinuousDownloadItem(boolean header, @Nullable String label,
                @Nullable Long labelId,
                @Nullable String title, @Nullable DownloadInfo downloadInfo,
                int galleryIndex, int galleryCount, boolean collapsed, long stableId) {
            this.header = header;
            this.label = label;
            this.labelId = labelId;
            this.title = title;
            this.downloadInfo = downloadInfo;
            this.galleryIndex = galleryIndex;
            this.galleryCount = galleryCount;
            this.collapsed = collapsed;
            this.stableId = stableId;
        }

        static ContinuousDownloadItem header(@Nullable String label, @Nullable Long labelId,
                String title,
                int galleryCount, boolean collapsed, long stableId) {
            return new ContinuousDownloadItem(true, label, labelId, title, null,
                    -1, galleryCount, collapsed, stableId);
        }

        static ContinuousDownloadItem gallery(DownloadInfo info, int galleryIndex) {
            return new ContinuousDownloadItem(false, info.label, null, null, info,
                    galleryIndex, 0, false, info.gid);
        }
    }

    /*---------------
     List pagination
     ---------------*/

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private MyEasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private FabLayout mFabLayout;
    @Nullable
    private RecyclerView.Adapter mAdapter;
    @Nullable
    private DownloadAdapter mOriginalAdapter;
    @Nullable
    private AutoStaggeredGridLayoutManager mLayoutManager;

    // 拖拽管理器
    @Nullable
    private RecyclerViewDragDropManager mDragDropManager;

    private ProgressView mProgressView;

    private DownloadLabelDraw downloadLabelDraw;
    public String searchKey = null;

    private int mInitPosition = -1;
    private int mContinuousRestorePosition = RecyclerView.NO_POSITION;
    private int mContinuousRestoreOffset;

    public boolean searching = false;

    @Nullable
    private Spinner mCategorySpinner;
    private int mSelectedCategory = EhUtils.ALL_CATEGORY;

    @NonNull
    private final ActivityResultLauncher<Intent> galleryActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            mPaginationController::updateReadProcess
    );

    @NonNull
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            mArchiveImporter::handleSelectedFile
    );

    @NonNull
    private final ActivityResultLauncher<Intent> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleSelectedFolder
    );

    private final ActivityResultLauncher<Intent> albumFolderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), mAlbumImporter::handleSelectedFolder);

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_downloads;
    }

    private boolean handleArguments(Bundle args) {
        if (null == args) {
            return false;
        }

        if (ACTION_CLEAR_DOWNLOAD_SERVICE.equals(args.getString(KEY_ACTION))) {
            DownloadService.Companion.clear();
        }

        if (args.containsKey(KEY_LABEL)) {
            mLabel = args.getString(KEY_LABEL);
            updateForLabel();
            updateView();
            return true;
        }

        long gid;
        if (null != mDownloadManager && -1L != (gid = args.getLong(KEY_GID, -1L))) {
            DownloadInfo info = mDownloadManager.getDownloadInfo(gid);
            if (null != info) {
                mLabel = info.getLabel();
                updateForLabel();
                updateView();

                // Get position
                if (null != mList) {
                    int position = mList.indexOf(info);
                    if (position >= 0 && null != mRecyclerView) {
                        initPage(position);
                    } else {
                        mInitPosition = position;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onNewArguments(@NonNull Bundle args) {
        handleArguments(args);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        mDownloadManager = EhApplication.getDownloadManager(context);
        mDownloadManager.addDownloadInfoListener(this);
        Bundle args = getArguments();
        mForceSingleLabelBrowse = args != null
                && args.getBoolean(KEY_FORCE_SINGLE_LABEL_MODE, false);
        mContinuousLabelBrowse = !mForceSingleLabelBrowse
                && Settings.getDownloadLabelContinuousBrowse();
        mPaginationController.canPagination = Settings.getDownloadPagination();
        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mList = null;

        DownloadManager manager = mDownloadManager;
        if (null == manager) {
            Context context = getEHContext();
            if (null != context) {
                manager = EhApplication.getDownloadManager(context);
            }
        } else {
            mDownloadManager = null;
        }

        if (null != manager) {
            manager.removeDownloadInfoListener(this);
        } else {
            Log.e(TAG, "Can't removeDownloadInfoListener");
        }
        mActionFabDrawable = null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateForLabel() {
        if (null == mDownloadManager) {
            return;
        }

        if (mContinuousLabelBrowse) {
            mLabel = null;
            List<DownloadInfo> allDownloads = new ArrayList<>(
                    mDownloadManager.getAllDownloadInfoList().size());
            allDownloads.addAll(mDownloadManager.getDefaultDownloadInfoList());
            for (DownloadLabel label : mDownloadManager.getLabelList()) {
                List<DownloadInfo> downloads =
                        mDownloadManager.getLabelDownloadInfoList(label.getLabel());
                if (downloads != null) {
                    allDownloads.addAll(downloads);
                }
            }
            rebuildContinuousItems(allDownloads, true);
        } else if (mLabel == null) {
            mList = mDownloadManager.getDefaultDownloadInfoList();
        } else {
            mList = mDownloadManager.getLabelDownloadInfoList(mLabel);
            if (mList == null) {
                mLabel = null;
                mList = mDownloadManager.getDefaultDownloadInfoList();
            }
        }

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        mBackList = mContinuousLabelBrowse && mList != null
                ? new ArrayList<>(mList) : mList;
//        filterByCategory();
        updateTitle();
        updatePaginationIndicator();
        updateFolderSyncMenu();
        if (!mContinuousLabelBrowse) {
            Settings.putRecentDownloadLabel(mLabel);
        }
        queryUnreadSpiderInfo();
    }

    private void rebuildContinuousItems(@NonNull List<DownloadInfo> source,
            boolean includeEmptyLabels) {
        if (!mContinuousLabelBrowse || mDownloadManager == null) {
            mList = source;
            return;
        }

        Map<String, List<DownloadInfo>> downloadsByLabel = new LinkedHashMap<>();
        downloadsByLabel.put(null, new ArrayList<>());
        List<DownloadLabel> labels = mDownloadManager.getLabelList();
        for (DownloadLabel label : labels) {
            downloadsByLabel.put(label.getLabel(), new ArrayList<>());
        }
        for (DownloadInfo info : source) {
            List<DownloadInfo> labelDownloads = downloadsByLabel.get(info.label);
            if (labelDownloads == null) {
                labelDownloads = new ArrayList<>();
                downloadsByLabel.put(info.label, labelDownloads);
            }
            labelDownloads.add(info);
        }

        mContinuousItems.clear();
        mContinuousGalleryPositions.clear();
        mContinuousHeaderPositions.clear();
        List<DownloadInfo> orderedDownloads = new ArrayList<>(source.size());

        List<DownloadInfo> defaultDownloads = downloadsByLabel.remove(null);
        if (defaultDownloads == null) {
            defaultDownloads = Collections.emptyList();
        }
        if (!defaultDownloads.isEmpty() || (includeEmptyLabels && !labels.isEmpty())) {
            appendContinuousSection(null, null,
                    getString(R.string.default_download_label_name),
                    Long.MIN_VALUE, defaultDownloads, orderedDownloads);
        }

        for (DownloadLabel label : labels) {
            String labelName = label.getLabel();
            List<DownloadInfo> downloads = downloadsByLabel.remove(labelName);
            if (downloads == null) {
                downloads = Collections.emptyList();
            }
            if (includeEmptyLabels || !downloads.isEmpty()) {
                Long labelId = label.getId();
                long stableId = labelId != null
                        ? Long.MIN_VALUE + labelId + 1L
                        : Long.MIN_VALUE / 2L + labelName.hashCode();
                appendContinuousSection(labelName, labelId, labelName, stableId,
                        downloads, orderedDownloads);
            }
        }

        for (Map.Entry<String, List<DownloadInfo>> entry : downloadsByLabel.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                String labelName = entry.getKey();
                String title = labelName != null ? labelName
                        : getString(R.string.default_download_label_name);
                long stableId = Long.MIN_VALUE / 2L + title.hashCode();
                appendContinuousSection(labelName, null, title, stableId,
                        entry.getValue(), orderedDownloads);
            }
        }
        mList = orderedDownloads;
    }

    private void appendContinuousSection(@Nullable String label, @Nullable Long labelId,
            @NonNull String title,
            long stableId, @NonNull List<DownloadInfo> downloads,
            @NonNull List<DownloadInfo> orderedDownloads) {
        int headerPosition = mContinuousItems.size();
        mContinuousHeaderPositions.put(label, headerPosition);
        boolean collapsed = !Settings.isDownloadLabelExpanded(labelId, label);
        mContinuousItems.add(ContinuousDownloadItem.header(
                label, labelId, title, downloads.size(), collapsed, stableId));
        for (DownloadInfo info : downloads) {
            int galleryIndex = orderedDownloads.size();
            orderedDownloads.add(info);
            if (collapsed) {
                continue;
            }
            int adapterPosition = mContinuousItems.size();
            mContinuousGalleryPositions.put(info.gid, adapterPosition);
            mContinuousItems.add(ContinuousDownloadItem.gallery(info, galleryIndex));
        }
    }

    private void updatePaginationIndicator() { mPaginationController.updatePaginationIndicator(); }

    @SuppressLint("StringFormatMatches")
    private void updateTitle() {
        if (mContinuousLabelBrowse) {
            setTitle(getString(R.string.scene_download_continuous_title,
                    mList == null ? 0 : mList.size()));
            return;
        }
        try {
            setTitle(getString(R.string.scene_download_title_new,
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name),
                    Integer.toString(mList == null ? 0 : mList.size())));
        } catch (Exception e) {
            Analytics.recordException(e);
            setTitle(getString(R.string.scene_download_title_new,
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name)));
        }
    }

    private void onInit() {
        if (!handleArguments(getArguments())) {
            mLabel = Settings.getRecentDownloadLabel();
            updateForLabel();
        }
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mLabel = savedInstanceState.getString(KEY_LABEL);
        updateForLabel();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_LABEL, mLabel);
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_download, container, false);

        Context context = getEHContext();
        assert context != null;

        mCategorySpinner = (Spinner) ViewUtils.$$(view, R.id.category_spinner);
        // Initialize category spinner
        List<String> categoryList = new ArrayList<>();
        categoryList.add(getString(R.string.category_all)); // Add "All" option
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.DOUJINSHI)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MANGA)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ARTIST_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.GAME_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.WESTERN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.NON_H)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.IMAGE_SET)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.COSPLAY)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ASIAN_PORN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MISC)).toUpperCase(Locale.ROOT));
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, categoryList);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(categoryAdapter);
        mCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedCategory;
                switch (position) {
                    case 0:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                    case 1:
                        selectedCategory = EhConfig.DOUJINSHI;
                        break;
                    case 2:
                        selectedCategory = EhConfig.MANGA;
                        break;
                    case 3:
                        selectedCategory = EhConfig.ARTIST_CG;
                        break;
                    case 4:
                        selectedCategory = EhConfig.GAME_CG;
                        break;
                    case 5:
                        selectedCategory = EhConfig.WESTERN;
                        break;
                    case 6:
                        selectedCategory = EhConfig.NON_H;
                        break;
                    case 7:
                        selectedCategory = EhConfig.IMAGE_SET;
                        break;
                    case 8:
                        selectedCategory = EhConfig.COSPLAY;
                        break;
                    case 9:
                        selectedCategory = EhConfig.ASIAN_PORN;
                        break;
                    case 10:
                        selectedCategory = EhConfig.MISC;
                        break;
                    default:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                }
                if (selectedCategory != mSelectedCategory) {
                    mSelectedCategory = selectedCategory;
                    filterByCategory();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        // Set default selection
        mCategorySpinner.setSelection(0);

        mProgressView = (ProgressView) ViewUtils.$$(view, R.id.download_progress_view);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (MyEasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        mPaginationController.mPaginationIndicator = (PaginationIndicator) ViewUtils.$$(view, R.id.indicator);

        mPaginationController.mPaginationIndicator.setPerPageCountChoices(mPaginationController.perPageCountChoices, getPageSizePos(mPaginationController.pageSize));

        mViewTransition = new ViewTransition(content, tip);

        Resources resources = context.getResources();

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);
        // 初始化拖拽管理器
        mDragDropManager = new RecyclerViewDragDropManager();
        try {
            mDragDropManager.setDraggingItemShadowDrawable(
                    (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drag shadow: " + e.getMessage());
        }

        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        mAdapter = mDragDropManager.createWrappedAdapter(mOriginalAdapter); // 包装适配器以支持拖拽
        mDragDropManager.setCheckCanDropEnabled(false);
        mRecyclerView.setAdapter(mAdapter);

        // 初始化分页监听器
        mPaginationController.bindPageChangeListener(mOriginalAdapter, mRecyclerView);
        mLayoutManager = new AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL);
        mLayoutManager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);

        // 设置拖拽动画器
        final GeneralItemAnimator animator = new DraggableItemAnimator();
        mRecyclerView.setItemAnimator(animator);

        // Keep a small pool of nearby cards. A large View/drawing cache retains too many
        // thumbnails in continuous mode and competes with the image cache for memory.
        mRecyclerView.setItemViewCacheSize(24);
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING
                        && mOriginalAdapter != null
                        && mOriginalAdapter.hasOpenLabelActions()) {
                    recyclerView.post(() -> {
                        if (mOriginalAdapter != null) {
                            mOriginalAdapter.dismissLabelActions();
                        }
                    });
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if ((dx != 0 || dy != 0) && mOriginalAdapter != null
                        && mOriginalAdapter.hasOpenLabelActions()) {
                    recyclerView.post(() -> {
                        if (mOriginalAdapter != null) {
                            mOriginalAdapter.dismissLabelActions();
                        }
                    });
                }
                if (mContinuousLabelBrowse) {
                    queryVisibleSpiderInfo();
                }
            }
        });
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme), new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        mRecyclerView.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        mRecyclerView.setCustomCheckedListener(new DownloadChoiceListener(this));
//        mRecyclerView.setOnGenericMotionListener(this::onGenericMotion);
        // Cancel change animation
        RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
        if (itemAnimator instanceof GeneralItemAnimator) {
            ((GeneralItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
        }
        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        // 将拖拽管理器附加到RecyclerView
        if (mDragDropManager != null) {
            try {
                mDragDropManager.attachRecyclerView(mRecyclerView);
            } catch (Exception e) {
                // 忽略硬件位图相关错误
                android.util.Log.w("DownloadsScene", "Error attaching drag manager: " + e.getMessage());
            }
        }

        if (mInitPosition >= 0) {
            if (!mContinuousLabelBrowse && mPaginationController.indexPage != 1) {
                initPage(mInitPosition);
            }
            int adapterPosition = listIndexInPage(mInitPosition);
            if (adapterPosition >= 0) {
                mRecyclerView.scrollToPosition(adapterPosition);
            }
            mInitPosition = -1;
        }

        fastScroller.attachToRecyclerView(mRecyclerView);
        HandlerDrawable handlerDrawable = new HandlerDrawable();
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent));
        fastScroller.setHandlerDrawable(handlerDrawable);
        fastScroller.setOnDragHandlerListener(this);
        mRecyclerView.post(this::queryVisibleSpiderInfo);

        mFabLayout.setExpanded(false, true);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setAutoCancel(false);
        mFabLayout.setOnClickFabListener(this);
        mFabLayout.setOnExpandListener(this);
        mActionFabDrawable = new AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null));
        mFabLayout.getPrimaryFab().setImageDrawable(mActionFabDrawable);
        FloatingActionButton fab = mFabLayout.getSecondaryFabAt(FAB_DRAG);
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
        TooltipCompat.setTooltipText(
                mFabLayout.getSecondaryFabAt(FAB_QUICK_ORGANIZE),
                getString(R.string.quick_organize));
        TooltipCompat.setTooltipText(
                mFabLayout.getSecondaryFabAt(FAB_DELETE_TRANSLATIONS),
                getString(R.string.translation_delete_results));
        addAboveSnackView(mFabLayout);

        updateView();

        guide();
        updatePaginationIndicator();
        return view;
    }

    private void guide() { mGuideHelper.guide(); }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateTitle();
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSyncFolderMenuItem = null;
        mStartAllMenuItem = null;

        mGuideHelper.destroy();
        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        if (null != mFabLayout) {
            removeAboveSnackView(mFabLayout);
            mFabLayout = null;
        }

        mRecyclerView = null;
        mViewTransition = null;
        mAdapter = null;
        mOriginalAdapter = null;
        mLayoutManager = null;
        mDragDropManager = null;
        mPaginationController.mPaginationIndicator = null;
        mPaginationController.myPageChangeListener = null;
        mPaginationController.needInitPage = false;
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_download;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_gallery_update_history) {
            startScene(new Announcer(GalleryUpdateHistoryScene.class));
            return true;
        }
        // Skip when in choice mode
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView || mRecyclerView.isInCustomChoice()) {
            return false;
        }

        int id = item.getItemId();
        switch (id) {
            case R.id.sync_local_folder:
                syncLocalFolderLabel(mLabel);
                return true;
            case R.id.action_start_all: {
                Intent intent = new Intent(activity, DownloadService.class);
                intent.setAction(DownloadService.ACTION_START_ALL);
                activity.startService(intent);
                return true;
            }
            case R.id.action_stop_all: {
                if (null != mDownloadManager) {
                    mDownloadManager.stopAllDownload();
                }
                return true;
            }
            case R.id.action_reset_reading_progress: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                if (searching) {
                    Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
                    return true;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.reset_reading_progress_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            resetReadingProgressInUi();
                            if (mDownloadManager != null) {
                                Toast.makeText(context, R.string.reset_reading_progress_processing,
                                        Toast.LENGTH_SHORT).show();
                                mDownloadManager.resetAllReadingProgress(() ->
                                        Toast.makeText(context, R.string.reset_reading_progress_done,
                                                Toast.LENGTH_SHORT).show());
                            }
                        }).show();
                return true;
            }
            case R.id.search_download_gallery: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                gotoSearch(context);
                return true;
            }
            case R.id.action_toggle_download_list_mode:
                if (mForceSingleLabelBrowse) {
                    Settings.setDownloadLabelContinuousBrowse(true);
                    onBackPressed();
                    return true;
                }
                boolean continuousLabelBrowse = !mContinuousLabelBrowse;
                Settings.setDownloadLabelContinuousBrowse(continuousLabelBrowse);
                applyDownloadListMode(continuousLabelBrowse);
                return true;
            case R.id.all:
            case R.id.sort_by_default:
            case R.id.download_done:
            case R.id.not_started:
            case R.id.waiting:
            case R.id.downloading:
            case R.id.failed:
            case R.id.sort_by_gallery_id_asc:
            case R.id.sort_by_gallery_id_desc:
            case R.id.sort_by_create_time_asc:
            case R.id.sort_by_create_time_desc:
            case R.id.sort_by_rating_asc:
            case R.id.sort_by_rating_desc:
            case R.id.sort_by_name_asc:
            case R.id.sort_by_name_desc:
            case R.id.sort_by_file_size_asc:
            case R.id.sort_by_file_size_desc:
            case R.id.all_kind:
            case R.id.misc:
            case R.id.doujinshi:
            case R.id.manga:
            case R.id.artist_cg:
            case R.id.game_cg:
            case R.id.image_set:
            case R.id.cosplay:
            case R.id.asian_porn:
            case R.id.non_h:
            case R.id.western:
            case R.id.unknown:
                gotoFilterAndSort(id);
                return true;
            case R.id.import_local_archive:
                mArchiveImporter.importLocalArchive(filePickerLauncher);
                return true;
            case R.id.import_local_album:
                mAlbumImporter.importLocalAlbum(albumFolderPickerLauncher);
                return true;
            case R.id.import_local_folder:
                importLocalFolder();
                return true;
//            case R.id.misc:
//            case R.id.doujinshi:
//            case R.id.manga:
//            case R.id.artist_cg:
//            case R.id.game_cg:
//            case R.id.image_set:
//            case R.id.cosplay:
//            case R.id.asian_porn:
//            case R.id.non_h:
//            case R.id.western:
//            case R.id.unknown:
//
//                return true;
        }
        return false;
    }

    private void gotoSearch(Context context) { mSearchController.gotoSearch(context, this, this); }

    public void updateView() {
        if (mViewTransition != null) {
            boolean empty = mContinuousLabelBrowse
                    ? mContinuousItems.isEmpty()
                    : mList == null || mList.isEmpty();
            if (empty) {
                mViewTransition.showView(1);
            } else {
                mViewTransition.showView(0);
            }
        }
    }

    @Override
    public View onCreateDrawerView(LayoutInflater inflater,
                                   @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (downloadLabelDraw == null) {
            downloadLabelDraw = new DownloadLabelDraw(inflater, container, this);
        }

        return downloadLabelDraw.createView();
    }

    @Override
    public void onBackPressed() {
        if (mGuideHelper.isShowing()) {
            return;
        }

        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    public void onEndDragHandler() {
        // Restore right drawer
        if (null != mRecyclerView && !mRecyclerView.isInCustomChoice()) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
        }
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == activity || null == recyclerView) {
            return false;
        }

        if (mContinuousLabelBrowse && isLabelHeaderPosition(position)) {
            return true;
        }

        if (recyclerView.isInCustomChoice()) {
            recyclerView.toggleItemChecked(position);
            return true;
        } else {
            List<DownloadInfo> list = mList;
            if (list == null) {
                return false;
            }
            int listPosition = positionInList(position);
            if (listPosition < 0 || listPosition >= list.size()) {
                return false;
            }

            DownloadInfo downloadInfo = list.get(listPosition);
            Intent intent = new Intent(activity, GalleryActivity.class);
            intent.putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD);
            LocalFolderGallerySource folderSource =
                    LocalFolderGallerySource.parse(downloadInfo.archiveUri);
            if (folderSource != null) {
                intent.setAction(GalleryActivity.ACTION_LOCAL_FOLDER);
                intent.putExtra(GalleryActivity.KEY_FILENAME, folderSource.encode());
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            } else if (downloadInfo.archiveUri != null
                    && downloadInfo.archiveUri.startsWith("content://")) {
                // This is an imported archive, ensure URI permission is available
                Uri archiveUri = Uri.parse(downloadInfo.archiveUri);
                try {
                    // Test if we can access the URI
                    try (InputStream testStream = getEHContext().getContentResolver().openInputStream(archiveUri)) {
                        if (testStream == null) {
                            Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    }
                } catch (SecurityException e) {
                    // Try to restore permission
                    try {
                        getEHContext().getContentResolver().takePersistableUriPermission(archiveUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ex) {
                        Toast.makeText(getEHContext(), R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
                        Analytics.recordException(ex);
                        return true;
                    }
                } catch (Exception e) {
                    Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                    return true;
                }
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(archiveUri);
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            } else {
                // This is a normal download, use ACTION_EH
                intent.setAction(GalleryActivity.ACTION_EH);
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            }
//            startActivity(intent);
            galleryActivityLauncher.launch(intent);
            return true;
        }
    }

    @Override
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (recyclerView == null) {
            return false;
        }

        if (mContinuousLabelBrowse && isLabelHeaderPosition(position)) {
            return true;
        }

        if (!recyclerView.isInCustomChoice()) {
            recyclerView.intoCustomChoiceMode();
        }
        recyclerView.toggleItemChecked(position);

        return true;
    }

    private void showRenameContinuousLabelDialog(@Nullable String originalLabel) {
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        if (originalLabel == null) {
            Toast.makeText(context, R.string.default_download_label_cannot_rename,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        EditTextDialogBuilder builder = new EditTextDialogBuilder(
                context, originalLabel, getString(R.string.download_labels));
        builder.setTitle(R.string.rename_label_title);
        builder.setPositiveButton(android.R.string.ok, null);
        AlertDialog dialog = builder.show();
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive == null) {
            return;
        }
        positive.setOnClickListener(view -> {
            String text = builder.getText();
            if (TextUtils.isEmpty(text)) {
                builder.setError(getString(R.string.label_text_is_empty));
            } else if (getString(R.string.default_download_label_name).equals(text)) {
                builder.setError(getString(R.string.label_text_is_invalid));
            } else if (originalLabel.equals(text)) {
                builder.dismiss();
            } else if (mDownloadManager != null && mDownloadManager.containLabel(text)) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.label_text_exist)
                        .setMessage(getString(R.string.merge_download_label_message,
                                text, originalLabel))
                        .setPositiveButton(R.string.merge_download_label,
                                (confirmDialog, which) -> {
                                    if (mDownloadManager != null
                                            && mDownloadManager.mergeLabel(originalLabel, text)) {
                                        builder.setError(null);
                                        builder.dismiss();
                                    } else {
                                        builder.setError(getString(R.string.label_text_exist));
                                    }
                                })
                        .setNegativeButton(R.string.rename_label_reenter, null)
                        .show();
            } else if (mDownloadManager != null) {
                builder.setError(null);
                builder.dismiss(() -> {
                    if (mDownloadManager != null) {
                        mDownloadManager.renameLabel(originalLabel, text);
                    }
                });
            }
        });
    }

    @Override
    public void onLabelHeaderClick(int position) {
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (!mContinuousLabelBrowse || recyclerView == null
                || recyclerView.isInCustomChoice() || !isLabelHeaderPosition(position)) {
            return;
        }
        ContinuousDownloadItem header = mContinuousItems.get(position);
        View headerView = mLayoutManager != null
                ? mLayoutManager.findViewByPosition(position) : null;
        int headerTop = headerView != null ? headerView.getTop() : 0;
        Settings.setDownloadLabelExpanded(header.labelId, header.label, header.collapsed);
        if (mList != null) {
            boolean includeEmptyLabels = TextUtils.isEmpty(searchKey)
                    && mSelectedCategory == EhUtils.ALL_CATEGORY;
            rebuildContinuousItems(new ArrayList<>(mList), includeEmptyLabels);
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            if (mLayoutManager != null) {
                mLayoutManager.scrollToPositionWithOffset(position, headerTop);
            }
            queryVisibleSpiderInfo();
        }
    }

    @Override
    public void onLabelHeaderRename(int position) {
        if (mContinuousLabelBrowse && isLabelHeaderPosition(position)
                && mRecyclerView != null && !mRecyclerView.isInCustomChoice()) {
            showRenameContinuousLabelDialog(mContinuousItems.get(position).label);
        }
    }

    @Override
    public void onLabelHeaderSearchOrSync(int position) {
        if (!mContinuousLabelBrowse || !isLabelHeaderPosition(position)
                || mRecyclerView == null || mRecyclerView.isInCustomChoice()) {
            return;
        }
        String label = mContinuousItems.get(position).label;
        if (isLabelHeaderSyncable(position)) {
            syncLocalFolderLabel(label);
            return;
        }
        String query = DownloadLabelSearchQueryResolver.resolve(
                label);
        if (query == null) {
            Context context = getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.download_label_search_unsupported,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        rememberContinuousScrollPosition();
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword(query);
        GalleryListScene.startScene(this, builder);
    }

    @Override
    public void onLabelHeaderEnter(int position) {
        if (!mContinuousLabelBrowse || !isLabelHeaderPosition(position)
                || mRecyclerView == null || mRecyclerView.isInCustomChoice()) {
            return;
        }
        rememberContinuousScrollPosition();
        Bundle args = new Bundle();
        args.putBoolean(KEY_FORCE_SINGLE_LABEL_MODE, true);
        args.putString(KEY_LABEL, mContinuousItems.get(position).label);
        startScene(new Announcer(SingleLabelDownloadsScene.class).setArgs(args));
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onExpand(boolean expanded) {
        if (null == mActionFabDrawable) {
            return;
        }

        if (expanded) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
            mActionFabDrawable.setDelete(ANIMATE_TIME);
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
            mActionFabDrawable.setAdd(ANIMATE_TIME);
        }
    }

    @Override
    public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
            return;
        }
        if (mRecyclerView != null && !mRecyclerView.isInCustomChoice()) {
            mRecyclerView.intoCustomChoiceMode();
            return;
        }
        view.toggle();
    }

    @Override
    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        mBatchActions.onClickSecondaryFab(view, fab, position);
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean continuousLabelBrowse = !mForceSingleLabelBrowse
                && Settings.getDownloadLabelContinuousBrowse();
        if (continuousLabelBrowse != mContinuousLabelBrowse) {
            applyDownloadListMode(continuousLabelBrowse);
        }
        restoreContinuousScrollPosition();
    }

    private void applyDownloadListMode(boolean continuousLabelBrowse) {
        if (mOriginalAdapter != null) {
            mOriginalAdapter.dismissLabelActions();
        }
        // Resolve before detaching the old rows; null is a valid (default) label.
        String normalLabel = Settings.getRecentDownloadLabel();
        if (mContinuousLabelBrowse && !continuousLabelBrowse) {
            int position = findCenteredContinuousPosition();
            if (position != RecyclerView.NO_POSITION) {
                normalLabel = mContinuousItems.get(position).label;
            }
        }
        // Finish operations using the old position mapping before switching between
        // section headers and the single-label gallery list.
        if (mDragDropManager != null) {
            mDragDropManager.cancelDrag();
        }
        if (mRecyclerView != null) {
            mRecyclerView.stopScroll();
            if (mRecyclerView.isInCustomChoice()) {
                mRecyclerView.outOfCustomChoiceMode();
            }
            RecyclerView.ItemAnimator animator = mRecyclerView.getItemAnimator();
            if (animator != null) {
                animator.endAnimations();
            }
            // A mode change replaces the row structure. Drop attached/cached rows and
            // RecyclerView's old layout state instead of treating it as a content update.
            mRecyclerView.setAdapter(null);
        }
        mContinuousLabelBrowse = continuousLabelBrowse;
        mLabel = continuousLabelBrowse ? null : normalLabel;
        mContinuousItems.clear();
        mContinuousGalleryPositions.clear();
        mContinuousHeaderPositions.clear();
        mContinuousRestorePosition = RecyclerView.NO_POSITION;
        mContinuousRestoreOffset = 0;
        searchKey = null;
        mSelectedCategory = EhUtils.ALL_CATEGORY;
        mPaginationController.indexPage = 1;
        mPaginationController.needInitPage = false;
        mPaginationController.doNotScroll = false;
        if (mPaginationController.myPageChangeListener != null) {
            mPaginationController.myPageChangeListener.setIndexPage(mPaginationController.indexPage);
            mPaginationController.myPageChangeListener.setNeedInitPage(false);
            mPaginationController.myPageChangeListener.setDoNotScroll(false);
        }
        if (mCategorySpinner != null) {
            mCategorySpinner.setSelection(0);
        }
        updateForLabel();
        if (mLayoutManager != null) {
            mLayoutManager.invalidateSpanAssignments();
            mLayoutManager.scrollToPositionWithOffset(0, 0);
        }
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mAdapter);
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        updateView();
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }

    /** Only inspect attached, visible rows when switching modes, never on every scroll. */
    private int findCenteredContinuousPosition() {
        if (mRecyclerView == null || mRecyclerView.getVisibility() != View.VISIBLE) {
            return RecyclerView.NO_POSITION;
        }
        int left = mRecyclerView.getPaddingLeft();
        int top = mRecyclerView.getPaddingTop();
        int right = mRecyclerView.getWidth() - mRecyclerView.getPaddingRight();
        int bottom = mRecyclerView.getHeight() - mRecyclerView.getPaddingBottom();
        if (right <= left || bottom <= top) {
            return RecyclerView.NO_POSITION;
        }
        float centerX = (left + right) / 2f;
        float centerY = (top + bottom) / 2f;
        float nearestDistance = Float.MAX_VALUE;
        int nearestPosition = RecyclerView.NO_POSITION;
        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
            View child = mRecyclerView.getChildAt(i);
            int position = mRecyclerView.getChildAdapterPosition(child);
            if (position < 0 || position >= mContinuousItems.size()
                    || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            float childLeft = child.getX();
            float childTop = child.getY();
            float childRight = childLeft + child.getWidth();
            float childBottom = childTop + child.getHeight();
            if (childRight <= left || childLeft >= right
                    || childBottom <= top || childTop >= bottom) {
                continue;
            }
            // Use distance to the row rectangle so tall cards containing the center
            // win; gaps between cards select the nearest visible row deterministically.
            float dx = Math.max(0, Math.max(childLeft - centerX, centerX - childRight));
            float dy = Math.max(0, Math.max(childTop - centerY, centerY - childBottom));
            float distance = dx * dx + dy * dy;
            if (distance < nearestDistance
                    || (distance == nearestDistance && position < nearestPosition)) {
                nearestDistance = distance;
                nearestPosition = position;
            }
        }
        return nearestPosition;
    }

    public void quickOrganizeDownloads(@NonNull Context context,
                                        @NonNull List<DownloadInfo> downloadInfos) {
        Context applicationContext = context.getApplicationContext();
        EhApplication.getExecutorService(applicationContext).execute(() -> {
            Map<Long, GalleryTags> storedTags = new HashMap<>();
            List<GalleryInfo> missingMetadata = new ArrayList<>();
            for (DownloadInfo info : downloadInfos) {
                GalleryTags tags = EhDB.queryGalleryTags(info.gid);
                storedTags.put(info.gid, tags);
                if (!DownloadManager.isImportedGallery(info)
                        && !DownloadQuickOrganizer.hasKnownTags(info, tags)
                        && info.gid > 0L && info.token != null && !info.token.isEmpty()) {
                    missingMetadata.add(info);
                }
            }

            if (!missingMetadata.isEmpty()) {
                try {
                    EhEngine.fillGalleryListByApi(null,
                            EhApplication.getOkHttpClient(applicationContext),
                            missingMetadata, EhUrl.getReferer());
                } catch (Throwable e) {
                    ExceptionUtils.throwIfFatal(e);
                    Log.w(TAG, "Unable to fill metadata for quick organization", e);
                }
            }

            Map<String, List<DownloadInfo>> assignments = new LinkedHashMap<>();
            int skipped = 0;
            for (DownloadInfo info : downloadInfos) {
                String label = DownloadQuickOrganizer.resolveLabel(
                        info, storedTags.get(info.gid));
                if (label == null) {
                    skipped++;
                    continue;
                }
                List<DownloadInfo> labelAssignments = assignments.get(label);
                if (labelAssignments == null) {
                    labelAssignments = new ArrayList<>();
                    assignments.put(label, labelAssignments);
                }
                labelAssignments.add(info);
            }

            int skippedCount = skipped;
            runOnUiThread(() -> applyQuickOrganization(
                    applicationContext, assignments, skippedCount));
        });
    }

    private void applyQuickOrganization(
            @NonNull Context context,
            @NonNull Map<String, List<DownloadInfo>> assignments,
            int skippedCount) {
        DownloadManager manager = mDownloadManager;
        if (manager == null) {
            return;
        }

        List<String> existingLabels = new ArrayList<>();
        for (DownloadLabel label : manager.getLabelList()) {
            existingLabels.add(label.getLabel());
        }

        int organizedCount = 0;
        Set<Long> newLabelIds = new HashSet<>();
        for (Map.Entry<String, List<DownloadInfo>> assignment : assignments.entrySet()) {
            String desiredLabel = assignment.getKey();
            String concreteLabel = DownloadQuickOrganizer.findEquivalentLabel(
                    existingLabels, desiredLabel);
            if (concreteLabel == null) {
                manager.addLabel(desiredLabel);
                existingLabels.add(desiredLabel);
                concreteLabel = desiredLabel;
                List<DownloadLabel> labels = manager.getLabelList();
                if (!labels.isEmpty()) {
                    DownloadLabel addedLabel = labels.get(labels.size() - 1);
                    if (desiredLabel.equals(addedLabel.getLabel())
                            && addedLabel.getId() != null) {
                        newLabelIds.add(addedLabel.getId());
                    }
                }
            }
            List<DownloadInfo> infos = assignment.getValue();
            manager.changeLabel(infos, concreteLabel);
            organizedCount += infos.size();
        }

        if (!newLabelIds.isEmpty()) {
            manager.reorderLabels(
                    DownloadLabelListOperations.placeSelectedBeforeFirstNonUnderscore(
                            manager.getLabelList(), newLabelIds));
        }

        updateTitle();
        updatePaginationIndicator();
        updateView();
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
        Toast.makeText(context, getString(R.string.quick_organize_result,
                organizedCount, skippedCount), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mContinuousLabelBrowse) {
            refreshContinuousStructure();
            return;
        }
        if (mList != list) {
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyItemInserted(position);
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
        updateView();
    }

    @Override
    public void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo) {
        if (mList == null) {
            return;
        }
        if (mContinuousLabelBrowse) {
            mList = new ArrayList<>(mList);
            for (int i = 0; i < mList.size(); i++) {
                if (mList.get(i).gid == oldInfo.gid) {
                    mList.set(i, newInfo);
                    break;
                }
            }
            refreshContinuousStructure();
            return;
        }
        updateForLabel();
        updateView();

        int index = mList.indexOf(newInfo);
        if (index >= 0 && mAdapter != null) {
//            mPaginationController.getSpiderInfoMap().put(info.gid,getSpiderInfo(info));
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
        List<DownloadInfo> infos = new ArrayList<>();
        infos.add(newInfo);
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(infos, this::spiderInfoResultCallBack);
        executor.execute();
    }

    @Override
    public void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList) {
        if (mList == null || (mList != list && !mList.contains(info))) {
            return;
        }
        if (ImportedGalleryProgress.isImportedGallery(info) && getEHContext() != null) {
            mPaginationController.getSpiderInfoMap().put(info.gid, ImportedGalleryProgress.toSpiderInfo(getEHContext(), info));
        } else if (mContinuousLabelBrowse && info.state == DownloadInfo.STATE_FINISH) {
            mPaginationController.requestSpiderInfo(Collections.singletonList(info));
        }
        int index = mList.indexOf(info);
        if (index >= 0 && mAdapter != null) {
            int adapterPosition = listIndexInPage(index);
            if (adapterPosition >= 0) {
                mAdapter.notifyItemChanged(adapterPosition);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onUpdateAll() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onReload() {
        if (mContinuousLabelBrowse) {
            refreshContinuousStructure();
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
        updateView();
    }

    @Override
    public void onChange() {
        if (mContinuousLabelBrowse) {
            refreshContinuousStructure();
            return;
        }
        mLabel = null;
        updateForLabel();
        updateView();
    }

    @Override
    public void onRenameLabel(String from, String to) {
        if (mContinuousLabelBrowse) {
            refreshContinuousStructure();
            if (downloadLabelDraw != null) {
                downloadLabelDraw.updateDownloadLabels();
            }
            return;
        }
        if (!ObjectUtils.equal(mLabel, from)) {
            return;
        }

        mLabel = to;
        updateForLabel();
        updateView();
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }

    @Override
    public void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mContinuousLabelBrowse) {
            if (mList != null) {
                mList = new ArrayList<>(mList);
                mList.remove(info);
            }
            refreshContinuousStructure();
            return;
        }
        if (mList != list) {
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyItemRemoved(listIndexInPage(position));
        }
        updateView();
    }

    @Override
    public void onUpdateLabels() {
        updateFolderSyncMenu();
        if (mContinuousLabelBrowse) {
            refreshContinuousStructure();
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }

    private void refreshContinuousStructure() {
        if (!mContinuousLabelBrowse || mDownloadManager == null) {
            return;
        }
        boolean unfiltered = TextUtils.isEmpty(searchKey)
                && mSelectedCategory == EhUtils.ALL_CATEGORY;
        if (unfiltered) {
            updateForLabel();
        } else {
            if (mList == null) {
                mList = new ArrayList<>();
            } else {
                mList = new ArrayList<>(mList);
                for (int i = mList.size() - 1; i >= 0; i--) {
                    if (mDownloadManager.getDownloadInfo(mList.get(i).gid) == null) {
                        mList.remove(i);
                    }
                }
            }
            rebuildContinuousItems(mList, false);
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            updateTitle();
            updateView();
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }

    @Nullable
    public DownloadManager getMDownloadManager() {
        return mDownloadManager;
    }

    // DownloadAdapterCallback 接口实现
    @Override
    public int getIndexPage() {
        return mPaginationController.indexPage;
    }

    @Override
    public int getPageSize() {
        return mPaginationController.pageSize;
    }

    @Override
    public int getPaginationSize() {
        return mPaginationController.paginationSize;
    }

    @Override
    public boolean isCanPagination() {
        return mPaginationController.canPagination && !mContinuousLabelBrowse;
    }

    @Override
    public int positionInList(int position) {
        if (mContinuousLabelBrowse) {
            if (position < 0 || position >= mContinuousItems.size()) {
                return -1;
            }
            ContinuousDownloadItem item = mContinuousItems.get(position);
            return item.header ? -1 : item.galleryIndex;
        }
        if (mList != null && mList.size() > mPaginationController.paginationSize && mPaginationController.canPagination) {
            return position + mPaginationController.pageSize * (mPaginationController.indexPage - 1);
        }
        return position;
    }

    @Override
    public int listIndexInPage(int position) {
        if (mContinuousLabelBrowse) {
            if (mList == null || position < 0 || position >= mList.size()) {
                return -1;
            }
            Integer adapterPosition =
                    mContinuousGalleryPositions.get(mList.get(position).gid);
            return adapterPosition != null ? adapterPosition : -1;
        }
        if (mList != null && mList.size() > mPaginationController.paginationSize && mPaginationController.canPagination) {
            return position % mPaginationController.pageSize;
        }
        return position;
    }

    @Override
    public List<DownloadInfo> getList() {
        return mList;
    }

    @Override
    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mPaginationController.getSpiderInfoMap();
    }

    @Override
    public DownloadManager getDownloadManager() {
        return mDownloadManager;
    }

    @Override
    public MyEasyRecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    @Override
    public boolean isContinuousLabelBrowse() {
        return mContinuousLabelBrowse;
    }

    @Override
    public int getDisplayItemCount() {
        return mContinuousItems.size();
    }

    @Override
    public boolean isLabelHeaderPosition(int position) {
        return mContinuousLabelBrowse && position >= 0
                && position < mContinuousItems.size()
                && mContinuousItems.get(position).header;
    }

    @Override
    public String getLabelHeaderTitle(int position) {
        if (!isLabelHeaderPosition(position)) {
            return "";
        }
        String title = mContinuousItems.get(position).title;
        return title != null ? title : "";
    }

    @Override
    public int getLabelHeaderGalleryCount(int position) {
        return isLabelHeaderPosition(position)
                ? mContinuousItems.get(position).galleryCount : 0;
    }

    @Override
    public boolean isLabelHeaderCollapsed(int position) {
        return isLabelHeaderPosition(position)
                && mContinuousItems.get(position).collapsed;
    }

    @Override
    public boolean isLabelHeaderSyncable(int position) {
        return isLabelHeaderPosition(position) && mDownloadManager != null
                && !mDownloadManager.getLocalFolderImportTrees(
                mContinuousItems.get(position).label).isEmpty();
    }

    @Override
    public long getDisplayItemId(int position) {
        return position >= 0 && position < mContinuousItems.size()
                ? mContinuousItems.get(position).stableId : RecyclerView.NO_ID;
    }

    @Override
    public void onGroupedDownloadOrderChanged() {
        updateForLabel();
        updateView();
    }

    @Override
    public boolean canReorderCurrentList() {
        return !mContinuousLabelBrowse || (TextUtils.isEmpty(searchKey)
                && mSelectedCategory == EhUtils.ALL_CATEGORY);
    }

    @Override
    public int getAdapterPositionForGallery(long gid) {
        if (mContinuousLabelBrowse) {
            Integer position = mContinuousGalleryPositions.get(gid);
            return position != null ? position : -1;
        }
        if (mList == null) {
            return -1;
        }
        for (int i = 0; i < mList.size(); i++) {
            if (mList.get(i).gid == gid) {
                if (mList.size() > mPaginationController.paginationSize && mPaginationController.canPagination) {
                    int first = mPaginationController.pageSize * (mPaginationController.indexPage - 1);
                    int last = Math.min(first + mPaginationController.pageSize, mList.size());
                    return i >= first && i < last ? i - first : -1;
                }
                return i;
            }
        }
        return -1;
    }

    boolean scrollToDownloadLabel(@Nullable String label) {
        if (!mContinuousLabelBrowse || mRecyclerView == null) {
            return false;
        }
        Integer position = mContinuousHeaderPositions.get(label);
        if (position == null) {
            return false;
        }
        mRecyclerView.stopScroll();
        if (mLayoutManager != null) {
            mLayoutManager.scrollToPositionWithOffset(position, 0);
        } else {
            mRecyclerView.scrollToPosition(position);
        }
        return true;
    }

    boolean expandAndScrollToDownloadLabel(@Nullable String label) {
        if (!mContinuousLabelBrowse || mList == null) {
            return false;
        }
        Integer position = mContinuousHeaderPositions.get(label);
        if (position == null) {
            return false;
        }
        ContinuousDownloadItem header = mContinuousItems.get(position);
        if (header.collapsed) {
            Settings.setDownloadLabelExpanded(header.labelId, header.label, true);
            boolean includeEmptyLabels = TextUtils.isEmpty(searchKey)
                    && mSelectedCategory == EhUtils.ALL_CATEGORY;
            rebuildContinuousItems(new ArrayList<>(mList), includeEmptyLabels);
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            queryVisibleSpiderInfo();
        }
        return scrollToDownloadLabel(label);
    }

    private void rememberContinuousScrollPosition() {
        if (!mContinuousLabelBrowse || mLayoutManager == null) {
            return;
        }
        int[] positions = mLayoutManager.findFirstVisibleItemPositions(null);
        int first = Integer.MAX_VALUE;
        for (int position : positions) {
            if (position != RecyclerView.NO_POSITION) {
                first = Math.min(first, position);
            }
        }
        if (first == Integer.MAX_VALUE) {
            return;
        }
        View firstView = mLayoutManager.findViewByPosition(first);
        mContinuousRestorePosition = first;
        mContinuousRestoreOffset = firstView != null
                ? mLayoutManager.getDecoratedTop(firstView) : 0;
    }

    private void restoreContinuousScrollPosition() {
        if (!mContinuousLabelBrowse || mRecyclerView == null || mLayoutManager == null
                || mContinuousRestorePosition == RecyclerView.NO_POSITION) {
            return;
        }
        int position = Math.min(mContinuousRestorePosition,
                Math.max(0, mContinuousItems.size() - 1));
        int offset = mContinuousRestoreOffset;
        mContinuousRestorePosition = RecyclerView.NO_POSITION;
        mRecyclerView.post(() -> {
            if (mLayoutManager != null && !mContinuousItems.isEmpty()) {
                mLayoutManager.scrollToPositionWithOffset(position, offset);
            }
        });
    }

    @Override
    public void onClickTitle() {
        mSearchController.onClickTitle();
    }

    @Override
    public void onClickLeftIcon() {
        mSearchController.onClickLeftIcon();
    }

    @Override
    public void onClickRightIcon() {
        mSearchController.onClickRightIcon();
    }

    @Override
    public void onSearchEditTextClick() {
        mSearchController.onSearchEditTextClick();
    }

    @Override
    public void onApplySearch(String query) {
        mSearchController.onApplySearch(query);
    }

    protected void startSearching() {
        mSearchController.startSearching();
    }

    private void gotoFilterAndSort(int id) {
        mProgressView.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.GONE);
        }

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, mDownloadManager);

        executor.setDownloadSearchingListener(this);

        executor.executeFilterAndSort(id);
    }

    private void updateAdapter() {
        // 检查 Fragment 是否已附加，如果未附加则延迟创建适配器
        if (!isAdded()) {
            return;
        }
        if (mContinuousLabelBrowse && mList != null) {
            boolean includeEmptyLabels = TextUtils.isEmpty(searchKey)
                    && mSelectedCategory == EhUtils.ALL_CATEGORY;
            rebuildContinuousItems(mList, includeEmptyLabels);
        }
        if (mOriginalAdapter != null) {
            mOriginalAdapter.notifyDataSetChanged();
            updateTitle();
            updatePaginationIndicator();
            updateView();
            return;
        }
        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        // 避免重复创建包装适配器，直接使用原始适配器
        mAdapter = mOriginalAdapter;
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mAdapter);
        }
    }

    @Override
    public void onSearchEditTextBackPressed() {
        mSearchController.onSearchEditTextBackPressed();
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {
        mSearchController.onStateChange(searchBar, newState, oldState, animation);
    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return mSearchController.isValidView(recyclerView);
    }

    @Nullable
    @Override
    public RecyclerView getValidRecyclerView() {
        return mSearchController.getValidRecyclerView();
    }

    @Override
    public boolean forceShowSearchBar() {
        return mSearchController.forceShowSearchBar();
    }

    @Override
    public void onDownloadSearchSuccess(List<DownloadInfo> list) {
        // 检查 Fragment 是否已附加，如果未附加则忽略回调
        if (!isAdded()) {
            return;
        }
        mList = list;
        updateAdapter();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        searching = false;
        queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadListHandleSuccess(List<DownloadInfo> list) {
        // 检查 Fragment 是否已附加，如果未附加则忽略回调
        if (!isAdded()) {
            return;
        }
        mList = list;
        updateAdapter();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadSearchFailed(List<DownloadInfo> list) {
        Toast.makeText(getEHContext(), R.string.download_searching_failed, Toast.LENGTH_LONG).show();
        mList = list;
        updateAdapter();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        searching = false;
        queryUnreadSpiderInfo();
    }

    private void resetReadingProgressInUi() { mPaginationController.resetReadingProgressInUi(); }

    private void queryUnreadSpiderInfo() { mPaginationController.queryUnreadSpiderInfo(); }

    private void queryVisibleSpiderInfo() { mPaginationController.queryVisibleSpiderInfo(); }

    private void spiderInfoResultCallBack(Map<Long, SpiderInfo> result) { mPaginationController.spiderInfoResultCallBack(result); }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateDownloadLabels(SomethingNeedRefresh somethingNeedRefresh) {
        if (somethingNeedRefresh.isDownloadLabelDrawNeed()) {
            if (downloadLabelDraw != null) {
                downloadLabelDraw.updateDownloadLabels();
            }
            if (mContinuousLabelBrowse) {
                refreshContinuousStructure();
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void initPage(int position) {
        if (mContinuousLabelBrowse) {
            int adapterPosition = listIndexInPage(position);
            if (mRecyclerView != null && adapterPosition >= 0) {
                mRecyclerView.scrollToPosition(adapterPosition);
            }
            return;
        }
        if (mList != null && mList.size() > mPaginationController.paginationSize && mPaginationController.canPagination) {
            mPaginationController.indexPage = position / mPaginationController.pageSize + 1;
        }
        mPaginationController.doNotScroll = true;
        if (mPaginationController.mPaginationIndicator != null) {
            mPaginationController.mPaginationIndicator.skip2Pos(mPaginationController.indexPage);
        }
        mRecyclerView.scrollToPosition(listIndexInPage(position));
    }

    private int getPageSizePos(int requestedSize) { return mPaginationController.getPageSizePos(requestedSize); }

    private void importLocalFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            folderPickerLauncher.launch(Intent.createChooser(
                    intent, getString(R.string.import_folder_title)));
        } catch (RuntimeException e) {
            Context context = getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.import_folder_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onMenuCreated(Menu menu) {
        super.onMenuCreated(menu);
        mSyncFolderMenuItem = menu.findItem(R.id.sync_local_folder);
        mStartAllMenuItem = menu.findItem(R.id.action_start_all);
        updateFolderSyncMenu();
    }

    private void updateFolderSyncMenu() {
        boolean canSync = !mContinuousLabelBrowse && mDownloadManager != null
                && !mDownloadManager.getLocalFolderImportTrees(mLabel).isEmpty();
        if (mSyncFolderMenuItem != null) {
            mSyncFolderMenuItem.setVisible(canSync);
        }
        if (mStartAllMenuItem != null) {
            mStartAllMenuItem.setVisible(!canSync);
        }
    }

    private void syncLocalFolderLabel(@Nullable String label) {
        Context sceneContext = getEHContext();
        DownloadManager manager = mDownloadManager;
        if (sceneContext == null || manager == null) {
            return;
        }
        DownloadLabel target = manager.findDownloadLabel(label);
        Set<String> trees = manager.getLocalFolderImportTrees(label);
        if (target == null || trees.isEmpty()) {
            return;
        }
        Context context = sceneContext.getApplicationContext();
        if (!sFolderScanRunning.compareAndSet(false, true)) {
            Toast.makeText(context, R.string.sync_folder_processing, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, R.string.sync_folder_processing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                for (String tree : trees) {
                    processLocalFolder(context, manager, Uri.parse(tree), target);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Local folder synchronization failed", e);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                        context, R.string.sync_folder_failed, Toast.LENGTH_LONG).show());
            } finally {
                new Handler(Looper.getMainLooper()).post(() -> sFolderScanRunning.set(false));
            }
        }, "LocalFolderSync").start();
    }

    private void handleSelectedFolder(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        Uri treeUri = result.getData().getData();
        Context sceneContext = getEHContext();
        DownloadManager downloadManager = mDownloadManager;
        if (treeUri == null || sceneContext == null || downloadManager == null) {
            return;
        }
        Context context = sceneContext.getApplicationContext();
        if (LocalFolderGalleryScanner.isUnsafeSelection(treeUri)) {
            Toast.makeText(context, R.string.import_folder_unsafe_selection,
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to persist local folder permission", e);
            Toast.makeText(context, R.string.import_folder_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(context, R.string.import_folder_processing, Toast.LENGTH_LONG).show();
        if (!sFolderScanRunning.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                processLocalFolder(context, downloadManager, treeUri, null);
            } catch (RuntimeException e) {
                Log.e(TAG, "Local folder import failed", e);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                        context, R.string.import_folder_failed, Toast.LENGTH_LONG).show());
            } finally {
                new Handler(Looper.getMainLooper()).post(() -> sFolderScanRunning.set(false));
            }
        }, "LocalFolderImport").start();
    }

    private void processLocalFolder(
            @NonNull Context context,
            @NonNull DownloadManager downloadManager,
            @NonNull Uri treeUri,
            @Nullable DownloadLabel syncLabel) {
        LocalFolderGallerySource rootSource =
                LocalFolderGallerySource.create(treeUri, "");
        LocalFolderGalleryScanner.ScanResult scanResult;
        try {
            scanResult = LocalFolderGalleryScanner.scan(context, rootSource);
        } catch (LocalFolderGalleryScanner.ScanException e) {
            if (e.reason != LocalFolderGalleryScanner.Reason.CANCELLED) {
                int message = e.reason == LocalFolderGalleryScanner.Reason.TOO_LARGE
                        ? R.string.local_folder_scan_too_large
                        : R.string.local_folder_not_accessible;
                runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
            }
            return;
        }
        if (scanResult.images.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(
                    context, R.string.local_folder_no_images, Toast.LENGTH_LONG).show());
            return;
        }

        List<FolderImportCandidate> candidates = new ArrayList<>();
        // Keep an aggregate label's original grouping even if root-level images appear later.
        boolean aggregateChildren = syncLabel != null || scanResult.directImageCount == 0;
        if (!aggregateChildren) {
            candidates.add(new FolderImportCandidate(
                    scanResult.rootName, rootSource, scanResult.images));
        } else {
            LinkedHashMap<String, List<LocalFolderGalleryScanner.ImageEntry>> childImages =
                    new LinkedHashMap<>();
            for (LocalFolderGalleryScanner.ImageEntry image : scanResult.images) {
                int separator = image.relativePath.indexOf('/');
                if (separator <= 0) {
                    continue;
                }
                String childName = image.relativePath.substring(0, separator);
                List<LocalFolderGalleryScanner.ImageEntry> images =
                        childImages.get(childName);
                if (images == null) {
                    images = new ArrayList<>();
                    childImages.put(childName, images);
                }
                images.add(image);
            }
            for (Map.Entry<String, List<LocalFolderGalleryScanner.ImageEntry>> entry
                    : childImages.entrySet()) {
                candidates.add(new FolderImportCandidate(
                        entry.getKey(),
                        LocalFolderGallerySource.create(treeUri, entry.getKey()),
                        entry.getValue()));
            }
        }

        String label = aggregateChildren
                ? '/' + scanResult.rootName + "/..."
                : (Settings.getHasDefaultDownloadLabel()
                ? Settings.getDefaultDownloadLabel() : null);
        List<DownloadInfo> imports = new ArrayList<>();
        long importTime = System.currentTimeMillis();
        for (int i = 0; i < candidates.size(); i++) {
            FolderImportCandidate candidate = candidates.get(i);
            DownloadInfo info = createLocalFolderDownloadInfo(
                    candidate, label, importTime - i);
            LocalFolderCoverStore.ensure(context, info.gid, info.thumb);
            imports.add(info);
        }
        // Commit on the main thread: download lists and their UI listeners are shared.
        // This also completes if the scene was closed while the folder was being scanned.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (syncLabel != null && !downloadManager.getLabelList().contains(syncLabel)) {
                Toast.makeText(context, R.string.sync_folder_label_removed, Toast.LENGTH_LONG).show();
                return;
            }
            String targetLabel = syncLabel == null ? label : syncLabel.getLabel();
            DownloadManager.FolderSyncResult result = downloadManager.applyLocalFolderScan(
                    imports, targetLabel, syncLabel != null);
            if (aggregateChildren) {
                if (syncLabel == null && result.added > 0) {
                    downloadManager.placeLocalFolderImportLabel(targetLabel);
                }
                downloadManager.rememberLocalFolderImport(targetLabel, treeUri.toString());
            }
            String message = syncLabel != null
                    ? context.getString(R.string.sync_folder_success, result.added, result.updated)
                    : result.added == 0 ? context.getString(R.string.import_folder_already_imported)
                    : context.getString(R.string.import_folder_success, result.added);
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            if (mRecyclerView != null) {
                updateForLabel();
                updateView();
            }
        });
    }

    @NonNull
    private static DownloadInfo createLocalFolderDownloadInfo(
            @NonNull FolderImportCandidate candidate,
            @Nullable String label,
            long importTime) {
        DownloadInfo info = new DownloadInfo();
        info.gid = candidate.source.stableGalleryId();
        info.token = "";
        info.title = candidate.title;
        info.titleJpn = null;
        info.thumb = candidate.images.get(0).uri.toString();
        info.category = EhUtils.UNKNOWN;
        info.posted = null;
        info.uploader = "Local Folder";
        info.rating = -1.0f;
        info.state = DownloadInfo.STATE_FINISH;
        info.legacy = 0;
        info.time = importTime;
        info.label = label;
        info.pages = candidate.images.size();
        info.total = candidate.images.size();
        info.finished = candidate.images.size();
        info.downloaded = candidate.images.size();
        info.archiveUri = candidate.source.encode();
        return info;
    }

    private static final class FolderImportCandidate {
        @NonNull
        final String title;
        @NonNull
        final LocalFolderGallerySource source;
        @NonNull
        final List<LocalFolderGalleryScanner.ImageEntry> images;

        FolderImportCandidate(
                @NonNull String title,
                @NonNull LocalFolderGallerySource source,
                @NonNull List<LocalFolderGalleryScanner.ImageEntry> images) {
            this.title = title;
            this.source = source;
            this.images = images;
        }
    }

    public void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity2();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

//    /**
//     * 更新thumb的可见性（拖拽功能已直接附加到thumb上）
//     * @param isSelectionMode 是否处于选择模式
//     */
//    private void updateThumbVisibility(boolean isSelectionMode) {
//        if (mRecyclerView == null) {
//            return;
//        }
//
//        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
//            RecyclerView.ViewHolder holder = mRecyclerView.getChildViewHolder(mRecyclerView.getChildAt(i));
//            if (holder instanceof DownloadAdapter.DownloadHolder) {
//                DownloadAdapter.DownloadHolder downloadHolder = (DownloadAdapter.DownloadHolder) holder;
//                // thumb 始终可见，拖拽功能已直接附加到thumb上
//                downloadHolder.thumb.setVisibility(View.VISIBLE);
//            }
//        }
//    }

    private void filterByCategory() {
        if (mBackList == null) {
            return;
        }
        if (mSelectedCategory == EhUtils.ALL_CATEGORY) {
            mList = new ArrayList<>(mBackList);
        } else {
            mList = new ArrayList<>();
            for (DownloadInfo info : mBackList) {
                if (info.category == mSelectedCategory) {
                    mList.add(info);
                }
            }
        }
        if (mContinuousLabelBrowse) {
            rebuildContinuousItems(mList, false);
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        updateTitle();
        updatePaginationIndicator();
        updateView();
        queryUnreadSpiderInfo();
    }
    @Nullable
    @Override
    public FabLayout getFabLayout() {
        return mFabLayout;
    }
    @Nullable
    @Override
    public AutoStaggeredGridLayoutManager getLayoutManager() {
        return mLayoutManager;
    }
    @Override
    public ProgressView getProgressView() {
        return mProgressView;
    }
    @Override
    public List<DownloadInfo> getBackList() {
        return mBackList;
    }
    @Override
    public DownloadSearchCallback getDownloadSearchCallback() {
        return this;
    }
    @Override
    public String getSearchKey() {
        return searchKey;
    }
    @Override
    public void setSearchKey(String searchKey) {
        this.searchKey = searchKey;
    }
    @Override
    public void setSearching(boolean searching) {
        this.searching = searching;
    }
    @Override
    public MyEasyRecyclerView.OnItemLongClickListener getItemLongClickListener() {
        return this;
    }
    @Override
    public void launchGalleryActivity(Intent intent) {
        galleryActivityLauncher.launch(intent);
    }
    @Nullable
    @Override
    public String getLabel() {
        return mLabel;
    }
    @Override
    public RecyclerView.Adapter getNotifyAdapter() {
        return mAdapter;
    }
}
