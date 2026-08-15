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

import static com.hippo.ehviewer.spider.SpiderDen.getExistingGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo;
import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;
import static com.hippo.util.FileUtils.getFileName;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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

import com.github.amlcurran.showcaseview.ShowcaseView;
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener;
import com.github.amlcurran.showcaseview.targets.PointTarget;
import com.github.amlcurran.showcaseview.targets.ViewTarget;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.app.CheckBoxDialogBuilder;
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
import com.hippo.ehviewer.gallery.A7ZipArchive;
import com.hippo.ehviewer.gallery.ImportedGalleryProgress;
import com.hippo.ehviewer.gallery.LocalFolderGalleryScanner;
import com.hippo.ehviewer.gallery.LocalFolderCoverStore;
import com.hippo.ehviewer.gallery.LocalFolderGallerySource;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.annotation.ViewLifeCircle;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UniRandomAccessFile;
import com.hippo.util.DrawableManager;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.view.ViewTransition;
import com.hippo.widget.FabLayout;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SearchBarMover;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.sxj.paginationlib.PaginationIndicator;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.IOException;
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
        FabLayout.OnClickFabListener, FabLayout.OnExpandListener, FastScroller.OnDragHandlerListener, SearchBar.Helper, SearchBarMover.Helper, SearchBar.OnStateChangeListener, DownloadAdapter.DownloadAdapterCallback {

    private static final String TAG = DownloadsScene.class.getSimpleName();

    public static final String KEY_GID = "gid";

    public static final String KEY_ACTION = "action";
    static final String KEY_LABEL = "label";
    static final String KEY_FORCE_SINGLE_LABEL_MODE = "force_single_label_mode";

    public static final String ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service";

    public static final int LOCAL_GALLERY_INFO_CHANGE = 909;

    private static final long ANIMATE_TIME = 300L;
    private static final int FAB_QUICK_ORGANIZE = 7;
    private static final int CONTINUOUS_LABEL_EXPAND_LIMIT = 50;

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
    private final List<ContinuousDownloadItem> mContinuousItems = new ArrayList<>();
    private final Map<Long, Integer> mContinuousGalleryPositions = new HashMap<>();
    private final Map<String, Integer> mContinuousHeaderPositions = new HashMap<>();

    private static final class ContinuousDownloadItem {
        final boolean header;
        @Nullable
        final String label;
        @Nullable
        final String title;
        @Nullable
        final DownloadInfo downloadInfo;
        final int galleryIndex;
        final int galleryCount;
        final boolean collapsed;
        final long stableId;

        private ContinuousDownloadItem(boolean header, @Nullable String label,
                @Nullable String title, @Nullable DownloadInfo downloadInfo,
                int galleryIndex, int galleryCount, boolean collapsed, long stableId) {
            this.header = header;
            this.label = label;
            this.title = title;
            this.downloadInfo = downloadInfo;
            this.galleryIndex = galleryIndex;
            this.galleryCount = galleryCount;
            this.collapsed = collapsed;
            this.stableId = stableId;
        }

        static ContinuousDownloadItem header(@Nullable String label, String title,
                int galleryCount, boolean collapsed, long stableId) {
            return new ContinuousDownloadItem(true, label, title, null,
                    -1, galleryCount, collapsed, stableId);
        }

        static ContinuousDownloadItem gallery(DownloadInfo info, int galleryIndex) {
            return new ContinuousDownloadItem(false, info.label, null, info,
                    galleryIndex, 0, false, info.gid);
        }
    }

    /*---------------
     List pagination
     ---------------*/
    private int indexPage = 1;
    private int pageSize = 1;
    private boolean canPagination = true;
    private final int paginationSize = 500;
    //    private final int paginationSize = 5;
    private final int[] perPageCountChoices = {50, 100, 200, 300, 500};
//    private final int[] perPageCountChoices = {1, 2, 3, 4, 5};

    private MyPageChangeListener myPageChangeListener;

    private final Map<Long, SpiderInfo> mSpiderInfoMap = new HashMap<>();
    private final Set<Long> mSpiderInfoRequested = new HashSet<>();

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

    private ShowcaseView mShowcaseView;

    private ProgressView mProgressView;

    private AlertDialog mSearchDialog;
    private SearchBar mSearchBar;
    @Nullable
    private PaginationIndicator mPaginationIndicator;

    private DownloadLabelDraw downloadLabelDraw;
    @Nullable
    @ViewLifeCircle
    private SearchBarMover mSearchBarMover;
    private boolean mSearchMode = false;
    public String searchKey = null;

    private int mInitPosition = -1;
    private int mContinuousRestorePosition = RecyclerView.NO_POSITION;
    private int mContinuousRestoreOffset;

    public boolean searching = false;
    private boolean doNotScroll = false;

    private boolean needInitPage = false;
    private boolean needInitPageSize = false;

    @Nullable
    private Spinner mCategorySpinner;
    private int mSelectedCategory = EhUtils.ALL_CATEGORY;

    @NonNull
    private final ActivityResultLauncher<Intent> galleryActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::updateReadProcess
    );

    @NonNull
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleSelectedFile
    );

    @NonNull
    private final ActivityResultLauncher<Intent> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleSelectedFolder
    );

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
        canPagination = Settings.getDownloadPagination();
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
            appendContinuousSection(null,
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
                appendContinuousSection(labelName, labelName, stableId,
                        downloads, orderedDownloads);
            }
        }

        for (Map.Entry<String, List<DownloadInfo>> entry : downloadsByLabel.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                String labelName = entry.getKey();
                String title = labelName != null ? labelName
                        : getString(R.string.default_download_label_name);
                long stableId = Long.MIN_VALUE / 2L + title.hashCode();
                appendContinuousSection(labelName, title, stableId,
                        entry.getValue(), orderedDownloads);
            }
        }
        mList = orderedDownloads;
    }

    private void appendContinuousSection(@Nullable String label, @NonNull String title,
            long stableId, @NonNull List<DownloadInfo> downloads,
            @NonNull List<DownloadInfo> orderedDownloads) {
        int headerPosition = mContinuousItems.size();
        mContinuousHeaderPositions.put(label, headerPosition);
        boolean collapsed = downloads.size() > CONTINUOUS_LABEL_EXPAND_LIMIT;
        mContinuousItems.add(ContinuousDownloadItem.header(
                label, title, downloads.size(), collapsed, stableId));
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

    private void updatePaginationIndicator() {
        if (mPaginationIndicator == null || mList == null) {
            return;
        }
        if (mContinuousLabelBrowse || mList.size() < paginationSize || !canPagination) {
            mPaginationIndicator.setVisibility(View.GONE);
            return;
        }
        mPaginationIndicator.setVisibility(View.VISIBLE);
        needInitPageSize = true;
        mPaginationIndicator.initPaginationIndicator(pageSize, perPageCountChoices, mList.size(), indexPage);
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
        mPaginationIndicator = (PaginationIndicator) ViewUtils.$$(view, R.id.indicator);

        mPaginationIndicator.setPerPageCountChoices(perPageCountChoices, getPageSizePos(pageSize));

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
        myPageChangeListener = new MyPageChangeListener(indexPage, pageSize, needInitPage, doNotScroll, mOriginalAdapter, mRecyclerView);

        // 设置分页监听器的回调
        myPageChangeListener.setPageChangeCallback(new MyPageChangeListener.PageChangeCallback() {
            @Override
            public void onPageChanged(int newIndexPage) {
                indexPage = newIndexPage;
            }

            @Override
            public void onPageSizeChanged(int newPageSize) {
                pageSize = newPageSize;
            }
        });
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
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
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
        mRecyclerView.setCustomCheckedListener(new DownloadChoiceListener());
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
            if (!mContinuousLabelBrowse && indexPage != 1) {
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
        FloatingActionButton fab = mFabLayout.getSecondaryFabAt(6);
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
        TooltipCompat.setTooltipText(
                mFabLayout.getSecondaryFabAt(FAB_QUICK_ORGANIZE),
                getString(R.string.quick_organize));
        addAboveSnackView(mFabLayout);

        updateView();

        guide();
        updatePaginationIndicator();
        return view;
    }

    private void guide() {
        if (Settings.getGuideDownloadThumb() && null != mRecyclerView) {
            mRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (Settings.getGuideDownloadThumb()) {
                        guideDownloadThumb();
                    }
                    if (null != mRecyclerView) {
                        ViewUtils.removeOnGlobalLayoutListener(mRecyclerView.getViewTreeObserver(), this);
                    }
                }
            });
        } else {
            guideDownloadLabels();
        }
    }

    private void guideDownloadThumb() {
        MainActivity activity = getActivity2();
        if (null == activity || !Settings.getGuideDownloadThumb() || null == mLayoutManager || null == mRecyclerView) {
            guideDownloadLabels();
            return;
        }
        int position = mLayoutManager.findFirstCompletelyVisibleItemPositions(null)[0];
        if (position < 0) {
            guideDownloadLabels();
            return;
        }
        RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForAdapterPosition(position);
        if (null == holder) {
            guideDownloadLabels();
            return;
        }

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new ViewTarget(((DownloadAdapter.DownloadHolder) holder).thumb))
                .blockAllTouches()
                .setContentTitle(R.string.guide_download_thumb_title)
                .setContentText(R.string.guide_download_thumb_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.putGuideDownloadThumb(false);
                        guideDownloadLabels();
                    }
                }).build();
    }

    private void guideDownloadLabels() {
        MainActivity activity = getActivity2();
        if (null == activity || !Settings.getGuideDownloadLabels()) {
            return;
        }

        Display display = activity.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new PointTarget(point.x, point.y / 3))
                .blockAllTouches()
                .setContentTitle(R.string.guide_download_labels_title)
                .setContentText(R.string.guide_download_labels_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.puttGuideDownloadLabels(false);
                        openDrawer(Gravity.RIGHT);
                    }
                }).build();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateTitle();
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mShowcaseView) {
            ViewUtils.removeFromParent(mShowcaseView);
            mShowcaseView = null;
        }
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
        mPaginationIndicator = null;
        myPageChangeListener = null;
        needInitPage = false;
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
        // Skip when in choice mode
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView || mRecyclerView.isInCustomChoice()) {
            return false;
        }

        int id = item.getItemId();
        switch (id) {
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
                                mDownloadManager.resetAllReadingProgress();
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
                importLocalArchive();
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

    private void gotoSearch(Context context) {
        if (mSearchDialog != null) {
            mSearchDialog.show();
            return;
        }
        LayoutInflater layoutInflater = LayoutInflater.from(context);

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);

        LinearLayout linearLayout = (LinearLayout) layoutInflater.inflate(R.layout.download_search_dialog, null);
        mSearchBar = linearLayout.findViewById(R.id.download_search_bar);
        mSearchBar.setHelper(this);
        mSearchBar.setIsComeFromDownload(true);
        mSearchBar.setEditTextHint(R.string.download_search_hint);
        mSearchBar.setLeftDrawable(drawable);
        mSearchBar.setText(searchKey);
        if (searchKey != null && !searchKey.isEmpty()) {
            mSearchBar.setTitle(searchKey);
            mSearchBar.cursorToEnd();
        } else {
            mSearchBar.setTitle(R.string.download_search_hint);
        }

        mSearchBar.setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24));
        mSearchBarMover = new SearchBarMover(this, mSearchBar);
        mSearchDialog = new AlertDialog.Builder(context)
                .setMessage(R.string.download_search_gallery)
                .setView(linearLayout)
                .setCancelable(true)
                .setOnDismissListener(this::onSearchDialogDismiss)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    searchKey = null;
                    mSearchBar.setText(null);
                    mSearchBar.setTitle(null);
                    mSearchBar.applySearch(true);
                    dialog.dismiss();
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mSearchBar.applySearch(true);
                    dialog.dismiss();
                }).show();
    }

    private void onSearchDialogDismiss(DialogInterface dialog) {
        mSearchMode = false;
    }

    private void enterSearchMode(boolean animation) {
        if (mSearchMode || mSearchBar == null || mSearchBarMover == null) {
            return;
        }
        mSearchMode = true;
        mSearchBar.setState(SearchBar.STATE_SEARCH_LIST, animation);

        mSearchBarMover.returnSearchBarPosition(animation);

    }

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
        if (null != mShowcaseView) {
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
        showRenameContinuousLabelDialog(mContinuousItems.get(position).label);
    }

    @Override
    public boolean onLabelHeaderLongClick(int position) {
        if (!mContinuousLabelBrowse || !isLabelHeaderPosition(position)) {
            return false;
        }
        String query = DownloadLabelSearchQueryResolver.resolve(
                mContinuousItems.get(position).label);
        if (query == null) {
            Context context = getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.download_label_search_unsupported,
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        rememberContinuousScrollPosition();
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword(query);
        GalleryListScene.startScene(this, builder);
        return true;
    }

    @Override
    public void onCollapsedLabelClick(int position) {
        if (!mContinuousLabelBrowse || !isLabelHeaderCollapsed(position)) {
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
        Context context = getEHContext();
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == context || null == activity || null == recyclerView) {
            return;
        }

        if (0 == position) {
            recyclerView.checkAll();
            if (mContinuousLabelBrowse) {
                SparseBooleanArray checked = recyclerView.getCheckedItemPositions();
                for (int i = checked.size() - 1; i >= 0; i--) {
                    int adapterPosition = checked.keyAt(i);
                    if (checked.valueAt(i) && isLabelHeaderPosition(adapterPosition)) {
                        recyclerView.toggleItemChecked(adapterPosition);
                    }
                }
            }
        } else {
            List<DownloadInfo> list = mList;
            if (list == null) {
                return;
            }

            LongList gidList = null;
            List<DownloadInfo> downloadInfoList = null;
            boolean collectGid = position == 1 || position == 2 || position == 3; // Start, Stop, Delete
            boolean collectDownloadInfo = position == 3 || position == 4
                    || position == FAB_QUICK_ORGANIZE; // Delete, Move, or Quick organize
            if (collectGid) {
                gidList = new LongList();
            }
            if (collectDownloadInfo) {
                downloadInfoList = new LinkedList<>();
            }

            SparseBooleanArray stateArray = recyclerView.getCheckedItemPositions();
            for (int i = 0, n = stateArray.size(); i < n; i++) {
                if (stateArray.valueAt(i)) {
                    int listPosition = positionInList(stateArray.keyAt(i));
                    if (listPosition < 0 || listPosition >= list.size()) {
                        continue;
                    }
                    DownloadInfo info = list.get(listPosition);
                    if (collectDownloadInfo) {
                        downloadInfoList.add(info);
                    }
                    if (collectGid) {
                        gidList.add(info.gid);
                    }
                }
            }

            switch (position) {
                case 1: { // Start
                    if (gidList.isEmpty()) {
                        break;
                    }
                    Intent intent = new Intent(activity, DownloadService.class);
                    intent.setAction(DownloadService.ACTION_START_RANGE);
                    intent.putExtra(DownloadService.KEY_GID_LIST, gidList);
                    activity.startService(intent);
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 2: { // Stop
                    if (gidList.isEmpty()) {
                        break;
                    }
                    if (null != mDownloadManager) {
                        mDownloadManager.stopRangeDownload(gidList);
                    }
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 3: { // Delete
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    boolean containsRegularGallery = false;
                    for (DownloadInfo info : downloadInfoList) {
                        if (!isImportedGallery(info)) {
                            containsRegularGallery = true;
                            break;
                        }
                    }
                    CheckBoxDialogBuilder builder = new CheckBoxDialogBuilder(context,
                            getString(R.string.download_remove_dialog_message_2, gidList.size()),
                            getString(R.string.download_remove_dialog_check_text),
                            Settings.getRemoveImageFiles());
                    builder.setCheckBoxVisible(containsRegularGallery);
                    DeleteRangeDialogHelper helper = new DeleteRangeDialogHelper(
                            downloadInfoList, gidList, builder);
                    builder.setTitle(R.string.download_remove_dialog_title)
                            .setPositiveButton(android.R.string.ok, helper)
                            .show();
                    break;
                }
                case 4: {// Move
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    List<DownloadLabel> labelRawList = EhApplication.getDownloadManager(context).getLabelList();
                    List<String> labelList = new ArrayList<>(labelRawList.size() + 1);
                    labelList.add(getString(R.string.default_download_label_name));
                    for (int i = 0, n = labelRawList.size(); i < n; i++) {
                        labelList.add(labelRawList.get(i).getLabel());
                    }
                    String[] labels = labelList.toArray(new String[labelList.size()]);

                    MoveDialogHelper helper = new MoveDialogHelper(labels, downloadInfoList);

                    new AlertDialog.Builder(context)
                            .setTitle(R.string.download_move_dialog_title)
                            .setItems(labels, helper)
                            .show();
                    break;
                }
                case 5:
                    if (mList == null || mList.isEmpty()) {
                        return;
                    }
                    onClickPrimaryFab(mFabLayout, null);
                    viewRandom();
                    break;
                case 6:
                    setDragEnable(fab);
                    break;
                case FAB_QUICK_ORGANIZE:
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    List<DownloadInfo> selectedDownloads =
                            new ArrayList<>(downloadInfoList);
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.quick_organize)
                            .setMessage(getString(R.string.quick_organize_confirm,
                                    selectedDownloads.size()))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                recyclerView.outOfCustomChoiceMode();
                                Toast.makeText(context,
                                        R.string.quick_organize_processing,
                                        Toast.LENGTH_SHORT).show();
                                quickOrganizeDownloads(context, selectedDownloads);
                            })
                            .show();
                    break;
            }
        }
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
        mContinuousLabelBrowse = continuousLabelBrowse;
        mLabel = null;
        searchKey = null;
        mSelectedCategory = EhUtils.ALL_CATEGORY;
        indexPage = 1;
        if (mCategorySpinner != null) {
            mCategorySpinner.setSelection(0);
        }
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        }
        updateForLabel();
        updateView();
        if (mLayoutManager != null) {
            mLayoutManager.scrollToPositionWithOffset(0, 0);
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }

    private void quickOrganizeDownloads(@NonNull Context context,
                                        @NonNull List<DownloadInfo> downloadInfos) {
        Context applicationContext = context.getApplicationContext();
        EhApplication.getExecutorService(applicationContext).execute(() -> {
            Map<Long, GalleryTags> storedTags = new HashMap<>();
            List<GalleryInfo> missingMetadata = new ArrayList<>();
            for (DownloadInfo info : downloadInfos) {
                GalleryTags tags = EhDB.queryGalleryTags(info.gid);
                storedTags.put(info.gid, tags);
                if (!DownloadQuickOrganizer.hasKnownTags(info, tags)
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

    private void setDragEnable(FloatingActionButton fab) {
        DRAG_ENABLE = !DRAG_ENABLE;
        Settings.setDragDownloadGallery(DRAG_ENABLE);
        Context context = getEHContext();
        if (null == context) return;
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
//        mDragDropManager.cancelDrag(dragEnable);
    }

    private void viewRandom() {
        List<DownloadInfo> list = mList;
        if (list == null) {
            return;
        }
        int position = (int) (Math.random() * list.size());
        if (position < 0 || position >= list.size()) {
            return;
        }
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView) {
            return;
        }

        Intent intent = new Intent(activity, GalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list.get(position));
        galleryActivityLauncher.launch(intent);
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
//            mSpiderInfoMap.put(info.gid,getSpiderInfo(info));
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
        if (mContinuousLabelBrowse && info.state == DownloadInfo.STATE_FINISH) {
            requestSpiderInfo(Collections.singletonList(info));
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
        return indexPage;
    }

    @Override
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public int getPaginationSize() {
        return paginationSize;
    }

    @Override
    public boolean isCanPagination() {
        return canPagination && !mContinuousLabelBrowse;
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
        if (mList != null && mList.size() > paginationSize && canPagination) {
            return position + pageSize * (indexPage - 1);
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
        if (mList != null && mList.size() > paginationSize && canPagination) {
            return position % pageSize;
        }
        return position;
    }

    @Override
    public List<DownloadInfo> getList() {
        return mList;
    }

    @Override
    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mSpiderInfoMap;
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
                if (mList.size() > paginationSize && canPagination) {
                    int first = pageSize * (indexPage - 1);
                    int last = Math.min(first + pageSize, mList.size());
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


    private static void deleteFileAsync(UniFile... files) {
        new AsyncTask<UniFile, Void, Void>() {
            @Override
            protected Void doInBackground(UniFile... params) {
                for (UniFile file : params) {
                    if (file != null) {
                        file.delete();
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance(), files);
    }

    private static boolean isImportedGallery(@Nullable GalleryInfo info) {
        if (!(info instanceof DownloadInfo)) {
            return false;
        }
        String archiveUri = ((DownloadInfo) info).archiveUri;
        return LocalFolderGallerySource.isLocalFolderGallery(archiveUri)
                || archiveUri != null && archiveUri.startsWith("content://");
    }

    private static void deleteGalleryFilesAsync(List<? extends GalleryInfo> galleryInfoList) {
        new AsyncTask<List<? extends GalleryInfo>, Void, Void>() {
            @Override
            protected Void doInBackground(List<? extends GalleryInfo>... params) {
                for (GalleryInfo info : params[0]) {
                    if (isImportedGallery(info)) {
                        continue;
                    }
                    UniFile file = getGalleryDownloadDir(info);
                    EhDB.removeDownloadDirname(info.gid);
                    if (file != null) {
                        file.delete();
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance(), galleryInfoList);
    }

    @Override
    public void onClickTitle() {
        if (!mSearchMode) {
            enterSearchMode(true);
        }
    }

    @Override
    public void onClickLeftIcon() {

    }

    @Override
    public void onClickRightIcon() {
        mSearchBar.applySearch(true);
    }

    @Override
    public void onSearchEditTextClick() {

    }


    @Override
    public void onApplySearch(String query) {
        searchKey = query;
        mSearchBar.hideKeyBoard();
        searching = true;
        startSearching();
    }

    protected void startSearching() {
        mProgressView.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.GONE);
        }

        if (mSearchMode) {
            mSearchMode = false;
            mSearchBar.setTitle(searchKey);
            mSearchBar.setState(SearchBar.STATE_NORMAL);
        }

        mSearchDialog.dismiss();

        updateForLabel();

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mList, searchKey);

        executor.setDownloadSearchingListener(this);

        executor.executeSearching();
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
        if (mSearchMode) {
            mSearchMode = false;
        }
        mSearchBar.setState(SearchBar.STATE_NORMAL, true);
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {

    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return false;
    }

    @Nullable
    @Override
    public RecyclerView getValidRecyclerView() {
        return mRecyclerView;
    }

    @Override
    public boolean forceShowSearchBar() {
        return false;
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

    @SuppressLint("NotifyDataSetChanged")
    private void updateReadProcess(ActivityResult result) {
        if (result.getResultCode() == LOCAL_GALLERY_INFO_CHANGE) {
            Intent data = result.getData();
            if (data != null) {
                GalleryInfo info = data.getParcelableExtra("info");

                if (info != null) {
                    mSpiderInfoMap.remove(info.gid);
                    Context context = getEHContext();
                    boolean imported = info instanceof DownloadInfo
                            && ImportedGalleryProgress.isImportedGallery((DownloadInfo) info);
                    SpiderInfo spiderInfo = imported
                            ? context == null ? null : ImportedGalleryProgress.toSpiderInfo(
                            context, (DownloadInfo) info)
                            : getSpiderInfo(info);
                    if (spiderInfo != null) {
                        mSpiderInfoMap.put(info.gid, spiderInfo);
                    }
                }

//                mSpiderInfoMap.remove(info.gid);
//                SpiderInfo spiderInfo = getSpiderInfo(info);
                int position = -1;
                if (mList == null || mAdapter == null || info == null) {
                    return;
                }
                for (int i = 0; i < mList.size(); i++) {
                    if (mList.get(i).gid == info.gid) {
                        position = listIndexInPage(i);
                        break;
                    }
                }
                if (position != -1) {
                    mAdapter.notifyItemChanged(position);
                } else {
                    mAdapter.notifyDataSetChanged();
                }

            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void resetReadingProgressInUi() {
        for (SpiderInfo spiderInfo : mSpiderInfoMap.values()) {
            if (spiderInfo != null) {
                spiderInfo.startPage = 0;
            }
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void queryUnreadSpiderInfo() {
        if (mList == null) {
            return;
        }
        if (mContinuousLabelBrowse) {
            List<DownloadInfo> initialWindow = new ArrayList<>(100);
            for (ContinuousDownloadItem item : mContinuousItems) {
                if (!item.header && item.downloadInfo != null) {
                    initialWindow.add(item.downloadInfo);
                    if (initialWindow.size() == 100) {
                        break;
                    }
                }
            }
            requestSpiderInfo(initialWindow);
            return;
        }
        List<DownloadInfo> requestList = new ArrayList<>();
        for (int i = 0; i < mList.size(); i++) {
            DownloadInfo info = mList.get(i);
            if (!mSpiderInfoMap.containsKey(info.gid) || mSpiderInfoMap.get(info.gid) == null) {
                requestList.add(info);
            }
        }
        requestSpiderInfo(requestList);
    }

    private void queryVisibleSpiderInfo() {
        if (!mContinuousLabelBrowse || mLayoutManager == null
                || mList == null || mList.isEmpty()) {
            return;
        }
        int spanCount = mLayoutManager.getSpanCount();
        if (spanCount <= 0) {
            return;
        }
        int[] firstPositions = mLayoutManager.findFirstVisibleItemPositions(
                new int[spanCount]);
        int[] lastPositions = mLayoutManager.findLastVisibleItemPositions(
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
        int end = Math.min(mContinuousItems.size() - 1, last + preload);
        List<DownloadInfo> request = new ArrayList<>();
        for (int position = start; position <= end; position++) {
            int listPosition = positionInList(position);
            if (listPosition >= 0 && listPosition < mList.size()) {
                request.add(mList.get(listPosition));
            }
        }
        requestSpiderInfo(request);
    }

    private void requestSpiderInfo(@NonNull List<DownloadInfo> candidates) {
        Context context = getEHContext();
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

    private void spiderInfoResultCallBack(Map<Long, SpiderInfo> resultMap) {
        mSpiderInfoMap.putAll(resultMap);
        if (mAdapter == null || mLayoutManager == null) {
            return;
        }
        int spanCount = mLayoutManager.getSpanCount();
        if (spanCount <= 0) {
            return;
        }
        int[] firstPositions = mLayoutManager.findFirstVisibleItemPositions(
                new int[spanCount]);
        int[] lastPositions = mLayoutManager.findLastVisibleItemPositions(
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
            mAdapter.notifyItemRangeChanged(first, last - first + 1);
        }
    }

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
        if (mList != null && mList.size() > paginationSize && canPagination) {
            indexPage = position / pageSize + 1;
        }
        doNotScroll = true;
        if (mPaginationIndicator != null) {
            mPaginationIndicator.skip2Pos(indexPage);
        }
        mRecyclerView.scrollToPosition(listIndexInPage(position));
    }


    private int getPageSizePos(int pageSize) {
        int index = 0;
        for (int i = 0; i < perPageCountChoices.length; i++) {
            if (pageSize == perPageCountChoices[i]) {
                index = i;
                break;
            }
        }
        return index;
    }

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
        new Thread(() -> processLocalFolder(context, downloadManager, treeUri),
                "LocalFolderImport").start();
    }

    private void processLocalFolder(
            @NonNull Context context,
            @NonNull DownloadManager downloadManager,
            @NonNull Uri treeUri) {
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
        boolean aggregateChildren = scanResult.directImageCount == 0;
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
            if (!downloadManager.containDownloadInfo(info.gid)) {
                // Keep the source URI in the database, but render future list rows from a
                // small app-owned file. This avoids relying on a document provider every
                // time RecyclerView binds a cover.
                LocalFolderCoverStore.ensure(context, info.gid, info.thumb);
                imports.add(info);
            }
        }
        if (imports.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(context,
                    R.string.import_folder_already_imported, Toast.LENGTH_SHORT).show());
            return;
        }

        if (aggregateChildren) {
            downloadManager.placeLocalFolderImportLabel(label);
        }
        downloadManager.addDownload(imports);
        int importedCount = imports.size();
        runOnUiThread(() -> {
            Toast.makeText(context, context.getString(
                    R.string.import_folder_success, importedCount), Toast.LENGTH_SHORT).show();
            updateForLabel();
            updateView();
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

    private void importLocalArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-rar",
                "application/rar",
                "application/x-cbz",
                "application/x-cbr"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // CRITICAL: Add flags to enable persistent URI permissions
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.import_archive_title)));
        } catch (Exception e) {
            Context context = getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleSelectedFile(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }

        Context context = getEHContext();
        if (context == null) {
            return;
        }

        // CRITICAL: Request persistent URI permission IMMEDIATELY when file is selected
        // This is the key to solving the permission loss issue after app restart
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.d(TAG, "Successfully obtained persistent URI permission for: " + uri);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: " + uri, e);
            Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: " + uri, e);
            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Show processing dialog
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show();

        // Process the archive file in background
        new Thread(() -> processArchiveFile(uri)).start();
    }

    private void processArchiveFile(Uri uri) {
        Context context = getEHContext();
        if (context == null) {
            return;
        }

        try {
            // Verify URI accessibility (permission should already be granted)
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    runOnUiThread(() ->
                            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot access file even with persistent permission", e);
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Get file name
            String fileName = getFileName(context, uri);
            if (fileName == null) {
                fileName = "imported_archive_" + System.currentTimeMillis();
            }

            // Validate file format
            if (!isValidArchiveFormat(fileName)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_invalid_format, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Create DownloadInfo for the archive
            DownloadInfo downloadInfo = createArchiveDownloadInfo(context, uri, fileName);
            if (downloadInfo == null) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Check if already imported
            if (mDownloadManager != null && mDownloadManager.containDownloadInfo(downloadInfo.gid)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_already_imported, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Add to download manager
            if (mDownloadManager != null) {
                List<DownloadInfo> downloadList = new ArrayList<>();
                downloadList.add(downloadInfo);
                mDownloadManager.addDownload(downloadList);
                runOnUiThread(() -> {
                    Toast.makeText(context, R.string.import_archive_success, Toast.LENGTH_SHORT).show();
                    updateForLabel();
                    updateView();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process archive file", e);
            runOnUiThread(() ->
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private boolean isValidArchiveFormat(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr");
    }


    public void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity2();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private DownloadInfo createArchiveDownloadInfo(Context context, Uri uri, String fileName) {
        try {
            DownloadInfo downloadInfo = new DownloadInfo();
            downloadInfo.gid = System.currentTimeMillis(); // Use timestamp as unique ID
            downloadInfo.token = "";
            downloadInfo.title = fileName.replaceAll("\\.[^.]*$", ""); // Remove extension
            downloadInfo.titleJpn = null;
            downloadInfo.thumb = null; // No thumbnail for imported archives
            downloadInfo.category = EhUtils.UNKNOWN; // Keep as UNKNOWN, will be handled in display logic
            downloadInfo.posted = null;
            downloadInfo.uploader = "Local Archive";
            downloadInfo.rating = -1.0f; // Keep default rating to not affect other downloads
            downloadInfo.state = DownloadInfo.STATE_FINISH;
            downloadInfo.legacy = 0;
            downloadInfo.time = System.currentTimeMillis();
            downloadInfo.label = null;
            int pageCount = countArchiveImages(context, uri);
            downloadInfo.pages = pageCount;
            downloadInfo.total = pageCount;
            downloadInfo.finished = pageCount;
            downloadInfo.downloaded = pageCount;

            // Store the URI in the archiveUri field - this is the key identifier
            downloadInfo.archiveUri = uri.toString();

            return downloadInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DownloadInfo", e);
            return null;
        }
    }

    private int countArchiveImages(@NonNull Context context, @NonNull Uri uri) {
        UniRandomAccessFile randomAccessFile = null;
        A7ZipArchive archive = null;
        try {
            UniFile file = UniFile.fromUri(context, uri);
            if (file == null || !file.exists()) {
                return 0;
            }
            randomAccessFile = file.createRandomAccessFile("r");
            if (randomAccessFile == null) {
                return 0;
            }
            archive = A7ZipArchive.create(randomAccessFile);
            return archive == null ? 0 : archive.getArchiveEntries().size();
        } catch (Exception e) {
            Log.w(TAG, "Failed to count imported archive pages: " + uri, e);
            return 0;
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to close imported archive", e);
                }
            }
            if (randomAccessFile != null) {
                try {
                    randomAccessFile.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close imported archive file", e);
                }
            }
        }
    }

    private class DeleteDialogHelper implements DialogInterface.OnClickListener {

        private final GalleryInfo mGalleryInfo;
        private final CheckBoxDialogBuilder mBuilder;

        public DeleteDialogHelper(GalleryInfo galleryInfo, CheckBoxDialogBuilder builder) {
            mGalleryInfo = galleryInfo;
            mBuilder = builder;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }

            // Delete
            if (null != mDownloadManager) {
                mDownloadManager.deleteDownload(mGalleryInfo.gid);
            }

            // Delete image files
            boolean importedGallery = isImportedGallery(mGalleryInfo);
            boolean checked = !importedGallery && mBuilder.isChecked();
            if (!importedGallery) {
                Settings.putRemoveImageFiles(checked);
            }
            if (checked) {
                UniFile file = getExistingGalleryDownloadDir(mGalleryInfo);
                EhDB.removeDownloadDirname(mGalleryInfo.gid);
                if (file != null) {
                    deleteFileAsync(file);
                } else {
                    deleteGalleryFilesAsync(Collections.singletonList(mGalleryInfo));
                }
            }
        }
    }

    private class DeleteRangeDialogHelper implements DialogInterface.OnClickListener {

        private final List<DownloadInfo> mDownloadInfoList;
        private final LongList mGidList;
        private final CheckBoxDialogBuilder mBuilder;

        public DeleteRangeDialogHelper(List<DownloadInfo> downloadInfoList,
                                       LongList gidList, CheckBoxDialogBuilder builder) {
            mDownloadInfoList = downloadInfoList;
            mGidList = gidList;
            mBuilder = builder;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }

            // Cancel check mode
            if (mRecyclerView != null) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            // Delete
            if (null != mDownloadManager) {
                mDownloadManager.deleteRangeDownload(mGidList);
            }

            // Delete image files
            boolean containsRegularGallery = false;
            for (DownloadInfo info : mDownloadInfoList) {
                if (!isImportedGallery(info)) {
                    containsRegularGallery = true;
                    break;
                }
            }
            boolean checked = containsRegularGallery && mBuilder.isChecked();
            if (containsRegularGallery) {
                Settings.putRemoveImageFiles(checked);
            }
            if (checked) {
                deleteGalleryFilesAsync(mDownloadInfoList);
            }
        }
    }

    private class MoveDialogHelper implements DialogInterface.OnClickListener {

        private final String[] mLabels;
        private final List<DownloadInfo> mDownloadInfoList;

        public MoveDialogHelper(String[] labels, List<DownloadInfo> downloadInfoList) {
            mLabels = labels;
            mDownloadInfoList = downloadInfoList;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            // Cancel check mode
            Context context = getEHContext();
            if (null == context) {
                return;
            }
            if (null != mRecyclerView) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            String label;
            if (which == 0) {
                label = null;
            } else {
                label = mLabels[which];
            }
            EhApplication.getDownloadManager(context).changeLabel(mDownloadInfoList, label);
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

    private class DownloadChoiceListener implements MyEasyRecyclerView.CustomChoiceListener {

        @Override
        public void onIntoCustomChoice(EasyRecyclerView view) {
            if (mRecyclerView != null) {
                mRecyclerView.setOnItemLongClickListener(null);
                mRecyclerView.setLongClickable(false);
            }
            if (mFabLayout != null) {
                mFabLayout.setExpanded(true);
            }
            // Lock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);

//            // 进入选择模式时，thumb保持可见（拖拽功能已直接附加到thumb上）
//            updateThumbVisibility(true);
        }

        @Override
        public void onOutOfCustomChoice(EasyRecyclerView view) {
            if (mRecyclerView != null) {
                mRecyclerView.setOnItemLongClickListener(DownloadsScene.this);
            }
            if (mFabLayout != null) {
                mFabLayout.setExpanded(false);
            }
            // Unlock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);

//            // 退出选择模式时，thumb保持可见（拖拽功能已直接附加到thumb上）
//            updateThumbVisibility(false);
        }

        @Override
        public void onItemCheckedStateChanged(EasyRecyclerView view, int position, long id, boolean checked) {
            if (view.getCheckedItemCount() == 0) {
                view.outOfCustomChoiceMode();
            }
        }
    }

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
}
