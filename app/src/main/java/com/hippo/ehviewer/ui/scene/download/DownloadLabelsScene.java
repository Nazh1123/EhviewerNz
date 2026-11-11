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
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.h6ah4i.android.widget.advrecyclerview.animator.SwipeDismissItemAnimator;
import com.hippo.android.resource.AttrResources;
import com.hippo.app.EditTextDialogBuilder;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.ehviewer.widget.DownloadFastScroller;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadLabelTree;
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
    private ItemTouchHelper mTouchHelper;
    private Set<Long> mDragIds;
    private Long mDropTargetId;
    private boolean mDropAfter;
    private boolean mDragCancelled;

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
        View view = inflater.inflate(R.layout.scene_download_labels, container, false);

        mRecyclerView = (EasyRecyclerView) ViewUtils.$$(view, R.id.recycler_view);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        mViewTransition = new ViewTransition(mRecyclerView, tip);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_label);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);
        tip.setText(R.string.no_download_label);

        RecyclerView.Adapter adapter = new LabelAdapter();
        adapter.setHasStableIds(true);
        mAdapter = adapter;
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setItemAnimator(new SwipeDismissItemAnimator());
        mTouchHelper = new ItemTouchHelper(new LabelDragCallback());
        mTouchHelper.attachToRecyclerView(mRecyclerView);
        ((com.hippo.ehviewer.widget.MyEasyRecyclerView) mRecyclerView)
                .setTouchCancelListener(() -> mDragCancelled = true);
        DownloadFastScroller scroller = view.findViewById(R.id.label_fast_scroller);
        scroller.attachToRecyclerView(mRecyclerView);

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
        mDragCancelled = true;
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
        if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
        mTouchHelper = null;
        mDragIds = null;
        mDropTargetId = null;
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
                builder.setTitle(R.string.download_label_new);
                final boolean[] folder = {false};
                builder.setSingleChoiceOptions(new String[]{getString(R.string.download_labels),
                        getString(R.string.download_label_folder)}, type -> {
                    folder[0] = type == 1;
                    builder.setHint(getString(folder[0] ? R.string.download_label_folder
                            : R.string.download_labels));
                });
                builder.setPositiveButton(android.R.string.ok, null);
                AlertDialog dialog = builder.show();
                new NewLabelDialogHelper(builder, dialog, folder);
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
                new AlertDialog.Builder(context).setTitle(R.string.download_labels_sort_name)
                        .setMessage(R.string.download_labels_sort_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> classifySelectedLabels())
                        .show();
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
                DownloadLabelTree.sortSelected(
                        mList, mSelectedLabelIds);
        EhApplication.getDownloadManager(context).reorderLabels(newOrder);
        clearSelection();
    }

    private void confirmDeleteSelectedLabels() {
        Context context = getEHContext();
        if (context == null || mList == null || mSelectedLabelIds.isEmpty()) {
            return;
        }

        boolean hasFolder = false;
        for (DownloadLabel label : mList) {
            if (mSelectedLabelIds.contains(label.getId()) && label.getFolder()) hasFolder = true;
        }
        final boolean[] deleteChildren = {false};
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.download_labels_delete_message, mSelectedLabelIds.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<Long> ids = deleteChildren[0]
                            ? DownloadLabelTree.withChildren(mList, mSelectedLabelIds)
                            : new LinkedHashSet<>(mSelectedLabelIds);
                    List<String> names = new ArrayList<>();
                    for (DownloadLabel label : mList) if (ids.contains(label.getId())) names.add(label.getLabel());
                    EhApplication.getDownloadManager(context).deleteLabels(names);
                    clearSelection();
                    updateView();
                });
        if (hasFolder) {
            CheckBox checkbox = new CheckBox(context);
            checkbox.setText(R.string.download_folder_delete_children);
            int inset = (int) (24 * getResources().getDisplayMetrics().density);
            checkbox.setPadding(inset, 0, inset, 0);
            checkbox.setOnCheckedChangeListener((button, checked) -> deleteChildren[0] = checked);
            builder.setView(checkbox);
        }
        builder.show();
    }

    private void confirmDeleteSelectedEmptyLabels() {
        Context context = getEHContext();
        if (context == null || mList == null || mSelectedLabelIds.isEmpty()) {
            return;
        }

        DownloadManager downloadManager = EhApplication.getDownloadManager(context);
        List<String> emptyLabels = new ArrayList<>();
        Set<Long> candidates = DownloadLabelTree.withChildren(mList, mSelectedLabelIds);
        for (DownloadLabel label : mList) {
            if (label.getFolder() || !candidates.contains(label.getId()) || label.getLabel() == null) {
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
        private final boolean[] mFolder;

        public NewLabelDialogHelper(EditTextDialogBuilder builder, AlertDialog dialog, boolean[] folder) {
            mBuilder = builder;
            mFolder = folder;
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
                mBuilder.dismiss(() -> {
                    DownloadManager manager = EhApplication.getDownloadManager(context);
                    if (mFolder[0]) manager.addLabelFolder(text); else manager.addLabel(text);
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
                });
            }
        }

    }

    private class RenameLabelDialogHelper implements View.OnClickListener {

        private final EditTextDialogBuilder mBuilder;
        private final String mOriginalLabel;
        private final int mPosition;

        public RenameLabelDialogHelper(EditTextDialogBuilder builder, AlertDialog dialog,
                String originalLabel, int position) {
            mBuilder = builder;
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
                mBuilder.dismiss();
            } else if (EhApplication.getDownloadManager(context).containLabel(text)) {
                DownloadManager manager = EhApplication.getDownloadManager(context);
                if (manager.findDownloadLabel(mOriginalLabel).getFolder()
                        || manager.findDownloadLabel(text).getFolder()) {
                    mBuilder.setError(getString(R.string.label_text_exist));
                } else showMergeLabelDialog(context, text);
            } else {
                mBuilder.setError(null);
                mBuilder.dismiss(() -> {
                    EhApplication.getDownloadManager(context).renameLabel(mOriginalLabel, text);
                    if (mAdapter != null) {
                        mAdapter.notifyItemChanged(mPosition);
                    }
                });
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
                        mBuilder.dismiss();
                        clearSelection();
                        updateView();
                    })
                    .setNegativeButton(R.string.rename_label_reenter, null)
                    .show();
        }
    }

    private class LabelHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener {

        public final CheckBox selection;
        public final TextView label;
        public final TextView count;
        public final View dragHandler;


        public LabelHolder(View itemView) {
            super(itemView);

            selection = (CheckBox) ViewUtils.$$(itemView, R.id.selection);
            label = (TextView) ViewUtils.$$(itemView, R.id.label);
            count = itemView.findViewById(R.id.count);
            dragHandler = ViewUtils.$$(itemView, R.id.drag_handler);


            selection.setOnClickListener(this);
            label.setOnClickListener(this);
            dragHandler.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() != MotionEvent.ACTION_DOWN || mTouchHelper == null
                        || mList == null || mDragIds != null) return false;
                int position = getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return false;
                Long id = mList.get(position).getId();
                if (!mSelectedLabelIds.isEmpty() && !mSelectedLabelIds.contains(id)) return false;
                mDragIds = mSelectedLabelIds.isEmpty()
                        ? new LinkedHashSet<>(java.util.Collections.singleton(id))
                        : new LinkedHashSet<>(mSelectedLabelIds);
                mDropTargetId = null;
                mDragCancelled = false;
                mTouchHelper.startDrag(this);
                return true;
            });
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
            }
        }
    }

    private class LabelAdapter extends RecyclerView.Adapter<LabelHolder> {

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
                holder.count.setText(label.getFolder()
                        ? EhApplication.getDownloadManager(getEHContext()).getFolderCount(label) : "");
                holder.selection.setChecked(mSelectedLabelIds.contains(label.getId()));
                holder.selection.setContentDescription(getString(
                        R.string.select_download_label, label.getLabel()));
                int indent = (int) ((label.getParentId() == null ? 0 : 24)
                        * getResources().getDisplayMetrics().density);
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.selection.getLayoutParams();
                params.setMarginStart(indent);
                holder.selection.setLayoutParams(params);
                holder.label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        label.getFolder() ? R.drawable.v_label_folder_x24 : 0, 0, 0, 0);
                holder.label.setCompoundDrawablePadding((int) (8 * getResources().getDisplayMetrics().density));
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

    }

    /** The model changes only on release: cancelling a drag leaves order and ownership intact. */
    private class LabelDragCallback extends ItemTouchHelper.SimpleCallback {
        LabelDragCallback() { super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0); }
        @Override public boolean isLongPressDragEnabled() { return false; }
        @Override public boolean onMove(RecyclerView recycler, RecyclerView.ViewHolder from,
                RecyclerView.ViewHolder to) { return false; }
        @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) {}

        @Override public void onChildDraw(Canvas canvas, RecyclerView recycler,
                RecyclerView.ViewHolder holder, float dx, float dy, int state, boolean active) {
            super.onChildDraw(canvas, recycler, holder, dx, dy, state, active);
            if (!active || mList == null || mDragIds == null) return;
            float center = holder.itemView.getTop() + dy + holder.itemView.getHeight() / 2f;
            mDropTargetId = null;
            Set<Long> carried = DownloadLabelTree.withChildren(mList, mDragIds);
            for (int i = 0; i < recycler.getChildCount(); i++) {
                View row = recycler.getChildAt(i);
                if (center < row.getTop() || center > row.getBottom()) continue;
                int position = recycler.getChildAdapterPosition(row);
                if (position < 0 || position >= mList.size()) continue;
                DownloadLabel target = mList.get(position);
                if (carried.contains(target.getId())) return;
                boolean hasFolder = false;
                for (DownloadLabel item : mList) {
                    if (mDragIds.contains(item.getId()) && item.getFolder()) hasFolder = true;
                }
                mDropAfter = center >= (row.getTop() + row.getBottom()) / 2f;
                if (hasFolder && target.getParentId() != null
                        && (!mDropAfter || !DownloadLabelTree.isLastChild(mList, target))) return;
                mDropTargetId = target.getId();
                // Show the exact insertion boundary; a folder header means its first child.
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(AttrResources.getAttrColor(recycler.getContext(), R.attr.widgetColorThemeAccent));
                paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
                float line = target.getFolder() && !hasFolder ? row.getBottom()
                        : mDropAfter ? row.getBottom() : row.getTop();
                canvas.drawLine(!hasFolder && (target.getParentId() != null || target.getFolder())
                        ? 24 * getResources().getDisplayMetrics().density : 0,
                        line, row.getRight(), line, paint);
                break;
            }
        }

        @Override public void clearView(RecyclerView recycler, RecyclerView.ViewHolder holder) {
            super.clearView(recycler, holder);
            if (!mDragCancelled && mDropTargetId != null && mDragIds != null && mList != null
                    && getEHContext() != null) {
                EhApplication.getDownloadManager(getEHContext()).reorderLabels(
                        DownloadLabelTree.move(mList, mDragIds, mDropTargetId, mDropAfter));
            }
            mDragIds = null;
            mDropTargetId = null;
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
        }
    }
}
