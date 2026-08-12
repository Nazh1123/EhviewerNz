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

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.SwipeDismissItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter;
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder;
import com.hippo.app.EditTextDialogBuilder;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DownloadLabelsScene extends ToolbarScene {

    /*---------------
     Whole life cycle
     ---------------*/
    @Nullable
    public List<DownloadLabel> mList = null;

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private EasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private RecyclerView.Adapter mAdapter;
    @Nullable
    private MenuItem mAddItem;
    @Nullable
    private MenuItem mSelectAllItem;
    @Nullable
    private MenuItem mSelectRangeItem;
    @Nullable
    private MenuItem mCancelSelectionItem;
    @Nullable
    private MenuItem mClassifySelectionItem;
    @Nullable
    private MenuItem mDeleteEmptyLabelsItem;
    @Nullable
    private MenuItem mDeleteSelectionItem;

    private final Set<Long> mSelectedLabelIds = new LinkedHashSet<>();
    private boolean mRangeSelectionPending;
    @Nullable
    private Long mRangeSelectionAnchorId;
    @Nullable
    private List<Long> mDraggedSelectedIds;
    @Nullable
    private List<DownloadLabel> mGroupDragStartOrder;
    private long mGroupDragAnchorId = -1L;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mList = EhApplication.getDownloadManager(getEHContext()).getLabelList();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mList = null;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_label_list, container, false);

        mRecyclerView = (EasyRecyclerView) ViewUtils.$$(view, R.id.recycler_view);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        mViewTransition = new ViewTransition(mRecyclerView, tip);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_label);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);
        tip.setText(R.string.no_download_label);

        // drag & drop manager
        RecyclerViewDragDropManager dragDropManager = new RecyclerViewDragDropManager();
        dragDropManager.setDraggingItemShadowDrawable(
                (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));

        RecyclerView.Adapter adapter = new LabelAdapter();
        adapter.setHasStableIds(true);
        adapter = dragDropManager.createWrappedAdapter(adapter); // wrap for dragging
        mAdapter = adapter;
        final GeneralItemAnimator animator = new SwipeDismissItemAnimator();

        mRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setItemAnimator(animator);

        dragDropManager.attachRecyclerView(mRecyclerView);

        updateView();

        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.download_labels);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
        updateSelectionUi(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }

        mViewTransition = null;
        mAdapter = null;
        mAddItem = null;
        mSelectAllItem = null;
        mSelectRangeItem = null;
        mCancelSelectionItem = null;
        mClassifySelectionItem = null;
        mDeleteEmptyLabelsItem = null;
        mDeleteSelectionItem = null;
        mDraggedSelectedIds = null;
        mGroupDragStartOrder = null;
    }

    @Override
    public void onNavigationClick(View view) {
        if (hasSelectionContext()) {
            clearSelection();
        } else {
            onBackPressed();
        }
    }

    @Override
    public void onBackPressed() {
        if (hasSelectionContext()) {
            clearSelection();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_download_label;
    }

    @Override
    public void onMenuCreated(Menu menu) {
        mAddItem = menu.findItem(R.id.action_add);
        mSelectAllItem = menu.findItem(R.id.action_select_all_labels);
        mSelectRangeItem = menu.findItem(R.id.action_select_label_range);
        mCancelSelectionItem = menu.findItem(R.id.action_cancel_label_selection);
        mClassifySelectionItem = menu.findItem(R.id.action_classify_selected_labels);
        mDeleteEmptyLabelsItem = menu.findItem(R.id.action_delete_empty_labels);
        mDeleteSelectionItem = menu.findItem(R.id.action_delete_selected_labels);
        updateSelectionUi(false);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        Context context = getEHContext();
        if (null == context) {
            return false;
        }

        int id = item.getItemId();
        switch (id) {
            case R.id.action_add: {
                EditTextDialogBuilder builder = new EditTextDialogBuilder(context, null, getString(R.string.download_labels));
                builder.setTitle(R.string.new_label_title);
                builder.setPositiveButton(android.R.string.ok, null);
                AlertDialog dialog = builder.show();
                new NewLabelDialogHelper(builder, dialog);
                return true;
            }
            case R.id.action_select_all_labels:
                selectAllLabels();
                return true;
            case R.id.action_select_label_range:
                selectLabelRange();
                return true;
            case R.id.action_cancel_label_selection:
                clearSelection();
                return true;
            case R.id.action_classify_selected_labels:
                classifySelectedLabels();
                return true;
            case R.id.action_delete_empty_labels:
                confirmDeleteSelectedEmptyLabels();
                return true;
            case R.id.action_delete_selected_labels:
                confirmDeleteSelectedLabels();
                return true;
            default:
                return false;
        }
    }

    private boolean hasSelectionContext() {
        return !mSelectedLabelIds.isEmpty() || mRangeSelectionPending;
    }

    private void selectAllLabels() {
        if (mList == null) {
            return;
        }
        mRangeSelectionPending = false;
        mRangeSelectionAnchorId = null;
        for (DownloadLabel label : mList) {
            if (label.getId() != null) {
                mSelectedLabelIds.add(label.getId());
            }
        }
        updateSelectionUi(true);
    }

    private void selectLabelRange() {
        Context context = getEHContext();
        if (context == null || mList == null || mList.isEmpty()) {
            return;
        }

        List<Long> selectedIds = DownloadLabelListOperations.getSelectedIdsInOrder(
                mList, mSelectedLabelIds);
        if (selectedIds.size() >= 2) {
            mSelectedLabelIds.addAll(DownloadLabelListOperations.getRangeIds(
                    mList, selectedIds.get(0), selectedIds.get(selectedIds.size() - 1)));
            mRangeSelectionPending = false;
            mRangeSelectionAnchorId = null;
        } else {
            mRangeSelectionPending = true;
            mRangeSelectionAnchorId = selectedIds.isEmpty() ? null : selectedIds.get(0);
            Toast.makeText(context,
                    mRangeSelectionAnchorId == null
                            ? R.string.select_label_range_start
                            : R.string.select_label_range_end,
                    Toast.LENGTH_SHORT).show();
        }
        updateSelectionUi(true);
    }

    private void toggleLabelSelection(int position) {
        Context context = getEHContext();
        if (mList == null || position < 0 || position >= mList.size()) {
            return;
        }
        Long id = mList.get(position).getId();
        if (id == null) {
            return;
        }

        if (mRangeSelectionPending) {
            if (mRangeSelectionAnchorId == null) {
                mSelectedLabelIds.add(id);
                mRangeSelectionAnchorId = id;
                if (context != null) {
                    Toast.makeText(context, R.string.select_label_range_end,
                            Toast.LENGTH_SHORT).show();
                }
            } else {
                mSelectedLabelIds.addAll(DownloadLabelListOperations.getRangeIds(
                        mList, mRangeSelectionAnchorId, id));
                mRangeSelectionPending = false;
                mRangeSelectionAnchorId = null;
            }
        } else if (!mSelectedLabelIds.remove(id)) {
            mSelectedLabelIds.add(id);
        }
        updateSelectionUi(true);
    }

    private void clearSelection() {
        mSelectedLabelIds.clear();
        mRangeSelectionPending = false;
        mRangeSelectionAnchorId = null;
        updateSelectionUi(true);
    }

    private void classifySelectedLabels() {
        Context context = getEHContext();
        if (context == null || mList == null || mSelectedLabelIds.isEmpty()) {
            return;
        }

        List<DownloadLabel> newOrder =
                DownloadLabelListOperations.sortSelectedAtFirstPosition(
                        mList, mSelectedLabelIds);
        EhApplication.getDownloadManager(context).reorderLabels(newOrder);
        clearSelection();
    }

    private void confirmDeleteSelectedLabels() {
        Context context = getEHContext();
        if (context == null || mList == null || mSelectedLabelIds.isEmpty()) {
            return;
        }

        List<String> selectedLabels = new ArrayList<>();
        for (DownloadLabel label : mList) {
            if (mSelectedLabelIds.contains(label.getId()) && label.getLabel() != null) {
                selectedLabels.add(label.getLabel());
            }
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.delete_label_title)
                .setMessage(getString(R.string.delete_selected_labels_message,
                        selectedLabels.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    EhApplication.getDownloadManager(context).deleteLabels(selectedLabels);
                    clearSelection();
                    updateView();
                })
                .show();
    }

    private void confirmDeleteSelectedEmptyLabels() {
        Context context = getEHContext();
        if (context == null || mList == null || mSelectedLabelIds.isEmpty()) {
            return;
        }

        DownloadManager downloadManager = EhApplication.getDownloadManager(context);
        List<String> emptyLabels = new ArrayList<>();
        for (DownloadLabel label : mList) {
            if (!mSelectedLabelIds.contains(label.getId()) || label.getLabel() == null) {
                continue;
            }
            List<?> downloads = downloadManager.getLabelDownloadInfoList(label.getLabel());
            if (downloads == null || downloads.isEmpty()) {
                emptyLabels.add(label.getLabel());
            }
        }
        if (emptyLabels.isEmpty()) {
            Toast.makeText(context, R.string.download_delete_empty_labels_none,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.download_delete_empty_labels)
                .setMessage(getString(R.string.download_delete_empty_labels_message,
                        emptyLabels.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    downloadManager.deleteLabels(emptyLabels);
                    clearSelection();
                    updateView();
                    Toast.makeText(context,
                            getString(R.string.download_delete_empty_labels_done,
                                    emptyLabels.size()),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void updateSelectionUi(boolean notifyItems) {
        boolean hasSelection = !mSelectedLabelIds.isEmpty();
        boolean hasContext = hasSelectionContext();
        setTitle(hasContext ? "" : getString(R.string.download_labels));
        if (mAddItem != null) {
            mAddItem.setVisible(!hasContext);
        }
        if (mSelectAllItem != null) {
            mSelectAllItem.setVisible(true);
            mSelectAllItem.setEnabled(mList != null
                    && mSelectedLabelIds.size() < mList.size());
        }
        if (mSelectRangeItem != null) {
            mSelectRangeItem.setVisible(true);
            mSelectRangeItem.setEnabled(mList != null && !mList.isEmpty());
        }
        if (mCancelSelectionItem != null) {
            mCancelSelectionItem.setVisible(hasContext);
        }
        if (mClassifySelectionItem != null) {
            mClassifySelectionItem.setVisible(hasSelection);
        }
        if (mDeleteEmptyLabelsItem != null) {
            mDeleteEmptyLabelsItem.setVisible(hasSelection);
        }
        if (mDeleteSelectionItem != null) {
            mDeleteSelectionItem.setVisible(hasSelection);
        }
        if (notifyItems && mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void updateView() {
        if (mViewTransition != null) {
            if (mList != null && mList.size() > 0) {
                mViewTransition.showView(0);
            } else {
                mViewTransition.showView(1);
            }
        }
    }

    private class NewLabelDialogHelper implements View.OnClickListener {

        private final EditTextDialogBuilder mBuilder;
        private final AlertDialog mDialog;

        public NewLabelDialogHelper(EditTextDialogBuilder builder, AlertDialog dialog) {
            mBuilder = builder;
            mDialog = dialog;
            Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setOnClickListener(this);
            }
        }

        @Override
        public void onClick(View v) {
            Context context = getEHContext();
            if (null == context) {
                return;
            }

            String text = mBuilder.getText();
            if (TextUtils.isEmpty(text)) {
                mBuilder.setError(getString(R.string.label_text_is_empty));
            } else if (getString(R.string.default_download_label_name).equals(text)) {
                mBuilder.setError(getString(R.string.label_text_is_invalid));
            } else if (EhApplication.getDownloadManager(context).containLabel(text)) {
                mBuilder.setError(getString(R.string.label_text_exist));
            } else {
                mBuilder.setError(null);
                mDialog.dismiss();
                EhApplication.getDownloadManager(context).addLabel(text);
                if (mAdapter != null && mList != null) {
                    mAdapter.notifyItemInserted(mList.size() - 1);
                }
                if (mViewTransition != null) {
                    if (mList != null && mList.size() > 0) {
                        mViewTransition.showView(0);
                    } else {
                        mViewTransition.showView(1);
                    }
                }
            }
        }

    }

    private class RenameLabelDialogHelper implements View.OnClickListener {

        private final EditTextDialogBuilder mBuilder;
        private final AlertDialog mDialog;
        private final String mOriginalLabel;
        private final int mPosition;

        public RenameLabelDialogHelper(EditTextDialogBuilder builder, AlertDialog dialog,
                String originalLabel, int position) {
            mBuilder = builder;
            mDialog = dialog;
            mOriginalLabel = originalLabel;
            mPosition = position;
            Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setOnClickListener(this);
            }
        }

        @Override
        public void onClick(View v) {
            Context context = getEHContext();
            if (null == context) {
                return;
            }

            String text = mBuilder.getText();
            if (TextUtils.isEmpty(text)) {
                mBuilder.setError(getString(R.string.label_text_is_empty));
            } else if (getString(R.string.default_download_label_name).equals(text)) {
                mBuilder.setError(getString(R.string.label_text_is_invalid));
            } else if (mOriginalLabel.equals(text)) {
                mBuilder.setError(null);
                mDialog.dismiss();
            } else if (EhApplication.getDownloadManager(context).containLabel(text)) {
                showMergeLabelDialog(context, text);
            } else {
                mBuilder.setError(null);
                mDialog.dismiss();
                EhApplication.getDownloadManager(context).renameLabel(mOriginalLabel, text);
                if (mAdapter != null) {
                    mAdapter.notifyItemChanged(mPosition);
                }
            }
        }

        private void showMergeLabelDialog(Context context, String destinationLabel) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.label_text_exist)
                    .setMessage(getString(R.string.merge_download_label_message,
                            destinationLabel, mOriginalLabel))
                    .setPositiveButton(R.string.merge_download_label, (dialog, which) -> {
                        DownloadManager manager = EhApplication.getDownloadManager(context);
                        if (!manager.mergeLabel(mOriginalLabel, destinationLabel)) {
                            mBuilder.setError(getString(R.string.label_text_exist));
                            return;
                        }
                        mBuilder.setError(null);
                        mDialog.dismiss();
                        clearSelection();
                        updateView();
                    })
                    .setNegativeButton(R.string.rename_label_reenter, null)
                    .show();
        }
    }

    private class LabelHolder extends AbstractDraggableItemViewHolder
            implements View.OnClickListener {

        public final CheckBox selection;
        public final TextView label;
        public final View dragHandler;
        public final View delete;

        public LabelHolder(View itemView) {
            super(itemView);

            selection = (CheckBox) ViewUtils.$$(itemView, R.id.selection);
            label = (TextView) ViewUtils.$$(itemView, R.id.label);
            dragHandler = ViewUtils.$$(itemView, R.id.drag_handler);
            delete = ViewUtils.$$(itemView, R.id.delete);

            selection.setOnClickListener(this);
            label.setOnClickListener(this);
            delete.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int position = getAdapterPosition();
            Context context = getEHContext();
            if (position == RecyclerView.NO_POSITION || null == context
                    || null == mList || null == mRecyclerView) {
                return;
            }

            if (selection == v) {
                toggleLabelSelection(position);
            } else if (label == v) {
                DownloadLabel raw = mList.get(position);
                EditTextDialogBuilder builder = new EditTextDialogBuilder(
                        context, raw.getLabel(), getString(R.string.download_labels));
                builder.setTitle(R.string.rename_label_title);
                builder.setPositiveButton(android.R.string.ok, null);
                AlertDialog dialog = builder.show();
                new RenameLabelDialogHelper(builder, dialog, raw.getLabel(), position);
            } else if (delete == v) {
                final DownloadLabel label = mList.get(position);
                new AlertDialog.Builder(context)
                    .setTitle(R.string.delete_label_title)
                    .setMessage(getString(R.string.delete_label_message, label.getLabel()))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            EhApplication.getDownloadManager(context).deleteLabel(label.getLabel());
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            if (null != mAdapter) {
                                mAdapter.notifyDataSetChanged();
                            }
                            updateView();
                        }
                    }).show();
            }
        }
    }

    private class LabelAdapter extends RecyclerView.Adapter<LabelHolder>
            implements DraggableItemAdapter<LabelHolder> {

        private final LayoutInflater mInflater;

        public LabelAdapter() {
            mInflater = getLayoutInflater2();
            AssertUtils.assertNotNull(mInflater);
        }

        @Override
        public LabelHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new LabelHolder(mInflater.inflate(R.layout.item_download_label, parent, false));
        }

        @Override
        public void onBindViewHolder(LabelHolder holder, int position) {
            if (mList != null) {
                DownloadLabel label = mList.get(position);
                holder.label.setText(label.getLabel());
                holder.selection.setChecked(mSelectedLabelIds.contains(label.getId()));
                holder.selection.setContentDescription(getString(
                        R.string.select_download_label, label.getLabel()));
                holder.delete.setVisibility(hasSelectionContext()
                        ? View.GONE : View.VISIBLE);
            }
        }

        @Override
        public long getItemId(int position) {
            return mList != null ? mList.get(position).getId() : 0;
        }

        @Override
        public int getItemCount() {
            return mList != null ? mList.size() : 0;
        }

        @Override
        public boolean onCheckCanStartDrag(LabelHolder holder, int position, int x, int y) {
            if (!ViewUtils.isViewUnder(holder.dragHandler, x, y, 0)) {
                return false;
            }
            if (mSelectedLabelIds.isEmpty()) {
                return true;
            }
            return mList != null && position >= 0 && position < mList.size()
                    && mSelectedLabelIds.contains(mList.get(position).getId());
        }

        @Override
        public ItemDraggableRange onGetItemDraggableRange(LabelHolder holder, int position) {
            return null;
        }

        @Override
        public void onMoveItem(int fromPosition, int toPosition) {
            Context context = getEHContext();
            if (null == context || mList == null || fromPosition == toPosition
                    || fromPosition < 0 || fromPosition >= mList.size()
                    || toPosition < 0 || toPosition >= mList.size()) {
                return;
            }

            if (mDraggedSelectedIds != null) {
                DownloadLabel anchor = mList.remove(fromPosition);
                mList.add(toPosition, anchor);
            } else {
                EhApplication.getDownloadManager(context).moveLabel(fromPosition, toPosition);
            }
        }

        @Override
        public boolean onCheckCanDrop(int draggingPosition, int dropPosition) {
            return true;
        }

        @Override
        public void onItemDragStarted(int position) {
            if (mList == null || position < 0 || position >= mList.size()) {
                return;
            }
            DownloadLabel anchor = mList.get(position);
            Long anchorId = anchor.getId();
            if (anchorId != null && mSelectedLabelIds.contains(anchorId)) {
                mDraggedSelectedIds = DownloadLabelListOperations.getSelectedIdsInOrder(
                        mList, mSelectedLabelIds);
                mGroupDragStartOrder = new ArrayList<>(mList);
                mGroupDragAnchorId = anchorId;
            }
        }

        @Override
        public void onItemDragFinished(int fromPosition, int toPosition, boolean result) {
            Context context = getEHContext();
            if (context != null && mList != null && mDraggedSelectedIds != null
                    && mGroupDragStartOrder != null) {
                DownloadManager manager = EhApplication.getDownloadManager(context);
                if (result) {
                    manager.reorderLabels(
                            DownloadLabelListOperations.placeSelectedGroupAtAnchor(
                                    mList, mDraggedSelectedIds, mGroupDragAnchorId));
                } else {
                    manager.reorderLabels(mGroupDragStartOrder);
                }
                if (mAdapter != null) {
                    mAdapter.notifyDataSetChanged();
                }
            }
            mDraggedSelectedIds = null;
            mGroupDragStartOrder = null;
            mGroupDragAnchorId = -1L;
        }
    }
}
