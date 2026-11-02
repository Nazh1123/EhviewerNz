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

import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.util.SparseBooleanArray;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.ui.DownloadRemovalDialog;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.widget.FabLayout;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 下载页 FAB 多选操作及删除/移动对话框。
 */
public class DownloadBatchActions {

    public interface Host {
        @Nullable
        Context getEHContext();

        @Nullable
        MainActivity getActivity2();

        @Nullable
        MyEasyRecyclerView getRecyclerView();

        @Nullable
        List<DownloadInfo> getList();

        @Nullable
        DownloadManager getDownloadManager();

        int positionInList(int position);

        boolean isContinuousLabelBrowse();
        boolean isLabelHeaderPosition(int position);
        void quickOrganizeDownloads(Context context, List<DownloadInfo> infos);

        @Nullable
        FabLayout getFabLayout();

        void onClickPrimaryFab(FabLayout view, FloatingActionButton fab);

        void launchGalleryActivity(Intent intent);

        Resources getResources();

        String getString(int resId);

        String getString(int resId, Object... formatArgs);
    }

    @NonNull
    private final Host mHost;
    private static final int FAB_DELETE_TRANSLATIONS = 4;
    private static final int FAB_MOVE = 5;
    private static final int FAB_RANDOM = 6;
    private static final int FAB_DRAG = 7;
    private static final int FAB_QUICK_ORGANIZE = 8;

    public DownloadBatchActions(@NonNull Host host) {
        mHost = host;
    }

    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        Context context = mHost.getEHContext();
        Activity activity = mHost.getActivity2();
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (null == context || null == activity || null == recyclerView) {
            return;
        }

        if (0 == position) {
            recyclerView.checkAll();
            if (mHost.isContinuousLabelBrowse()) {
                SparseBooleanArray checked = recyclerView.getCheckedItemPositions();
                for (int i = checked.size() - 1; i >= 0; i--) {
                    int adapterPosition = checked.keyAt(i);
                    if (checked.valueAt(i) && mHost.isLabelHeaderPosition(adapterPosition)) {
                        recyclerView.toggleItemChecked(adapterPosition);
                    }
                }
            }
        } else {
            List<DownloadInfo> list = mHost.getList();
            if (list == null) {
                return;
            }

            LongList gidList = null;
            List<DownloadInfo> downloadInfoList = null;
            boolean collectGid = position == 1 || position == 2; // Start, Stop
            boolean collectDownloadInfo = position == 3 || position == FAB_MOVE
                    || position == FAB_QUICK_ORGANIZE || position == FAB_DELETE_TRANSLATIONS;
            if (collectGid) {
                gidList = new LongList();
            }
            if (collectDownloadInfo) {
                downloadInfoList = new LinkedList<>();
            }

            SparseBooleanArray stateArray = recyclerView.getCheckedItemPositions();
            for (int i = 0, n = stateArray.size(); i < n; i++) {
                if (stateArray.valueAt(i)) {
                    int listPosition = mHost.positionInList(stateArray.keyAt(i));
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
                    if (null != mHost.getDownloadManager()) {
                        mHost.getDownloadManager().stopRangeDownload(gidList);
                    }
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 3: { // Delete
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    DownloadManager manager = mHost.getDownloadManager();
                    if (manager != null) {
                        DownloadRemovalDialog.show(context, downloadInfoList, manager,
                                recyclerView::outOfCustomChoiceMode);
                    }
                    break;
                }
                case FAB_MOVE: {// Move
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    List<DownloadLabel> labelRawList = EhApplication.getDownloadManager(context).getLabelList();
                    List<String> labelList = new ArrayList<>(labelRawList.size() + 1);
                    labelList.add(mHost.getString(R.string.default_download_label_name));
                    for (int i = 0, n = labelRawList.size(); i < n; i++) {
                        labelList.add(labelRawList.get(i).getLabel());
                    }
                    String[] labels = labelList.toArray(new String[labelList.size()]);

                    MoveDialogHelper helper = new MoveDialogHelper(labels, downloadInfoList, recyclerView, mHost);

                    new AlertDialog.Builder(context)
                            .setTitle(R.string.download_move_dialog_title)
                            .setItems(labels, helper)
                            .show();
                    break;
                }
                case FAB_RANDOM:
                    if (mHost.getList() == null || mHost.getList().isEmpty()) {
                        return;
                    }
                    mHost.onClickPrimaryFab(mHost.getFabLayout(), null);
                    viewRandom();
                    break;
                case FAB_DRAG:
                    setDragEnable(fab);
                    break;
                case FAB_DELETE_TRANSLATIONS: {
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    com.hippo.ehviewer.gallery.GalleryResultStorage.showDeleteDialog(context,
                            new ArrayList<>(downloadInfoList), recyclerView::outOfCustomChoiceMode);
                    break;
                }
                case FAB_QUICK_ORGANIZE:
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    List<DownloadInfo> selectedDownloads =
                            new ArrayList<>(downloadInfoList);
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.quick_organize)
                            .setMessage(mHost.getString(R.string.quick_organize_confirm,
                                    selectedDownloads.size()))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                recyclerView.outOfCustomChoiceMode();
                                Toast.makeText(context,
                                        R.string.quick_organize_processing,
                                        Toast.LENGTH_SHORT).show();
                                mHost.quickOrganizeDownloads(context, selectedDownloads);
                            })
                            .show();
                    break;
            }
        }
    }

    public void setDragEnable(FloatingActionButton fab) {
        DRAG_ENABLE = !DRAG_ENABLE;
        Settings.setDragDownloadGallery(DRAG_ENABLE);
        Context context = mHost.getEHContext();
        if (null == context) return;
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(mHost.getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(mHost.getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
//        mDragDropManager.cancelDrag(dragEnable);
    }

    public void viewRandom() {
        List<DownloadInfo> list = mHost.getList();
        if (list == null) {
            return;
        }
        int position = (int) (Math.random() * list.size());
        if (position < 0 || position >= list.size()) {
            return;
        }
        Activity activity = mHost.getActivity2();
        if (null == activity || null == mHost.getRecyclerView()) {
            return;
        }

        Intent intent = new Intent(activity, GalleryActivity.class);
        intent.putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list.get(position));
        mHost.launchGalleryActivity(intent);
    }

    public static class MoveDialogHelper implements DialogInterface.OnClickListener {

        private final String[] mLabels;
        private final List<DownloadInfo> mDownloadInfoList;
        @Nullable
        private final MyEasyRecyclerView mRecyclerView;
        @NonNull
        private final Host mHost;

        public MoveDialogHelper(String[] labels, List<DownloadInfo> downloadInfoList,
                                @Nullable MyEasyRecyclerView recyclerView, @NonNull Host host) {
            mLabels = labels;
            mDownloadInfoList = downloadInfoList;
            mRecyclerView = recyclerView;
            mHost = host;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            // Cancel check mode
            Context context = mHost.getEHContext();
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
}
