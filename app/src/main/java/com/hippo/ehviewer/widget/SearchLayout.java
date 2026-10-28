/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.RatingBar;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SearchPresetQuery;
import com.hippo.ehviewer.client.SearchRating;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ripple.Ripple;
import com.hippo.widget.RadioGridGroup;
import com.hippo.lib.yorozuya.ViewUtils;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class SearchLayout extends EasyRecyclerView implements CompoundButton.OnCheckedChangeListener,
        View.OnClickListener, ImageSearchLayout.Helper {

    @IntDef({SEARCH_MODE_NORMAL, SEARCH_MODE_IMAGE})
    @Retention(RetentionPolicy.SOURCE)
    private @interface SearchMode {}

    private static final String STATE_KEY_SUPER = "super";
    private static final String STATE_KEY_SEARCH_MODE = "search_mode";
    private static final String STATE_KEY_ENABLE_ADVANCE = "enable_advance";
    private static final String STATE_KEY_RATING = "minimum_rating_hundredths";
    private static final String STATE_KEY_RATING_OVERRIDE = "rating_override";

    public static final int SEARCH_MODE_NORMAL = 0;
    public static final int SEARCH_MODE_IMAGE = 1;

    private static final int ITEM_TYPE_NORMAL = 0;
    private static final int ITEM_TYPE_NORMAL_ADVANCE = 1;
    private static final int ITEM_TYPE_IMAGE = 2;
    private static final int ITEM_TYPE_ACTION = 3;

    private static final int[] SEARCH_ITEM_COUNT_ARRAY = {
            3, 2
    };

    private static final int[][] SEARCH_ITEM_TYPE = {
            {ITEM_TYPE_NORMAL, ITEM_TYPE_NORMAL_ADVANCE, ITEM_TYPE_ACTION}, // SEARCH_MODE_NORMAL
            {ITEM_TYPE_IMAGE, ITEM_TYPE_ACTION}, // SEARCH_MODE_IMAGE
    };

    private LayoutInflater mInflater;

    private int mSearchMode = SEARCH_MODE_NORMAL;
    private boolean mEnableAdvance = false;

    private View mNormalView;
    private CategoryTable mCategoryTable;
    private RadioGridGroup mNormalSearchMode;
    private ImageView mNormalSearchModeHelp;
    private SwitchCompat mEnableAdvanceSwitch;
    private SearchPresetSwitch mPresetTag1;
    private SearchPresetSwitch mPresetTag2;
    private String mSearchQuery = "";
    private int mMinRatingHundredths;
    private boolean mRatingOverride;
    private int mSourceMode = ListUrlBuilder.MODE_NORMAL;
    private RatingBar mRatingStars;
    private TextView mRatingValue;

    private View mAdvanceView;
    private AdvanceSearchTable mTableAdvanceSearch;

    private ImageSearchLayout mImageView;

    private View mActionView;
    // --- [修正] 使用新的 TextView 变量，删除旧的 mAction ---
    private TextView mTagSearchEntrance;
    private TextView mImageSearchToggle;

    private LinearLayoutManager mLayoutManager;
    private SearchAdapter mAdapter;

    private Helper mHelper;
    private int mSearchBarExtraHeight;

    public SearchLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SearchLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    @SuppressLint("InflateParams")
    private void init(Context context) {
        Resources resources = context.getResources();
        mInflater = LayoutInflater.from(context);

        mLayoutManager = new LinearLayoutManager(context);
        mAdapter = new SearchAdapter();
        setLayoutManager(mLayoutManager);
        setAdapter(mAdapter);
        setHasFixedSize(true);
        setClipToPadding(false);
        int interval = resources.getDimensionPixelOffset(R.dimen.search_layout_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.search_layout_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.search_layout_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(
                interval, paddingH, paddingV, paddingH, paddingV);
        addItemDecoration(decoration);
        decoration.applyPaddings(this);

        // Create normal view
        View normalView = mInflater.inflate(R.layout.search_normal, null);
        mNormalView = normalView;
        mRatingStars = normalView.findViewById(R.id.search_rating_stars);
        mRatingStars.setStepSize(0.01f);
        mRatingStars.setKeyProgressIncrement(50);
        mRatingValue = normalView.findViewById(R.id.search_rating_value);
        mRatingValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, mRatingValue.getTextSize()
                + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 2, resources.getDisplayMetrics()));
        mRatingValue.setPaintFlags(mRatingValue.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        mRatingValue.setOnClickListener(v -> showRatingInput());
        mRatingStars.setOnRatingBarChangeListener((bar, rating, fromUser) -> {
            if (fromUser) setMinimumRating(Math.round(rating * 2) * 50);
        });
        setMinimumRating(0);
        mRatingOverride = false;
        updateRatingDisplay();
        mCategoryTable = (CategoryTable) normalView.findViewById(R.id.search_category_table);
        mPresetTag1 = normalView.findViewById(R.id.search_preset_tag_1);
        mPresetTag2 = normalView.findViewById(R.id.search_preset_tag_2);
        mPresetTag1.setOnStateChangedListener(state -> changePreset(Settings.getSearchPresetTag1(), state));
        mPresetTag2.setOnStateChangedListener(state -> changePreset(Settings.getSearchPresetTag2(), state));
        syncPresetTags("");
        mNormalSearchMode = (RadioGridGroup) normalView.findViewById(R.id.normal_search_mode);
        mNormalSearchModeHelp = (ImageView) normalView.findViewById(R.id.normal_search_mode_help);
        mEnableAdvanceSwitch = (SwitchCompat) normalView.findViewById(R.id.search_enable_advance);
        mNormalSearchModeHelp.setOnClickListener(this);
        Ripple.addRipple(mNormalSearchModeHelp, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme));
        mEnableAdvanceSwitch.setOnCheckedChangeListener(SearchLayout.this);
        mEnableAdvanceSwitch.setSwitchPadding(resources.getDimensionPixelSize(R.dimen.switch_padding));

        // Create advance view
        mAdvanceView = mInflater.inflate(R.layout.search_advance, null);
        mTableAdvanceSearch = (AdvanceSearchTable) mAdvanceView.findViewById(R.id.search_advance_search_table);
        syncSearchOptionVisibility();

        // Create image view
        mImageView = (ImageSearchLayout) mInflater.inflate(R.layout.search_image, null);
        mImageView.setHelper(this);

        // Create action view (New Structure)
        mActionView = mInflater.inflate(R.layout.search_action, null);

        // 1. 绑定“图片搜索”切换
        mImageSearchToggle = mActionView.findViewById(R.id.search_image_toggle);
        mImageSearchToggle.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点击左边 -> 切换模式（文字会变）
                toggleSearchMode();
            }
        });

        // 2. 绑定“标签检索”跳转
        mTagSearchEntrance = mActionView.findViewById(R.id.tv_tag_search_entrance);
        mTagSearchEntrance.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点击右边 -> 跳转页面（文字不变）
                if (mHelper != null) {
                    mHelper.onOpenTagSelector();
                }
            }
        });
    }

    public void setHelper(Helper helper) {
        mHelper = helper;
    }

    public void setSearchBarExtraHeight(int extraHeight) {
        int height = Math.max(0, extraHeight);
        int delta = height - mSearchBarExtraHeight;
        if (delta == 0) return;
        mSearchBarExtraHeight = height;
        setPadding(getPaddingLeft(), getPaddingTop() + delta, getPaddingRight(), getPaddingBottom());
        // Preserve the viewed conditions' offset relative to the new top inset.
        offsetChildrenVertical(delta);
    }

    public void setMinimumRating(int hundredths) {
        mMinRatingHundredths = Math.max(0, Math.min(500, hundredths));
        mRatingOverride = true;
        updateRatingDisplay();
    }

    public void setRatingFromBuilder(ListUrlBuilder builder) {
        mRatingOverride = builder.hasRatingOverride() || builder.getSearchMinRatingHundredths() > 0;
        mMinRatingHundredths = builder.getSearchMinRatingHundredths();
        mTableAdvanceSearch.setMinRating(builder.getMinRating());
        updateRatingDisplay();
    }

    private void updateRatingDisplay() {
        int rating = mRatingOverride ? mMinRatingHundredths : Settings.getPersistentMinRatingHundredths();
        // RatingBar draws clipped stars; render exact values, but retain half-star touch steps.
        mRatingStars.setRating(rating / 100f);
        mRatingValue.setText(SearchRating.format(rating));
    }

    public void setSourceMode(int mode) {
        mSourceMode = mode;
    }

    private void showRatingInput() {
        EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setHint(R.string.search_rating_hint);
        input.setText(mRatingValue.getText());
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.search_rating_title)
                .setMessage(R.string.search_rating_hint)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            View.OnClickListener confirm = v -> {
                try {
                    setMinimumRating(SearchRating.parse(input.getText().toString()));
                    dialog.dismiss();
                } catch (IllegalArgumentException e) {
                    input.setError(getContext().getString(R.string.search_rating_invalid));
                }
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(confirm);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                confirm.onClick(v);
                return true;
            });
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }

    public void syncPresetTags(String query) {
        syncSearchOptionVisibility();
        mSearchQuery = query == null ? "" : query;
        syncPreset(mPresetTag1, Settings.getSearchPresetTag1());
        syncPreset(mPresetTag2, Settings.getSearchPresetTag2());
    }

    private void syncSearchOptionVisibility() {
        mNormalView.findViewById(R.id.search_preset_row).setVisibility(
                Settings.getEnableSearchPresetTags() ? View.VISIBLE : View.GONE);
        mNormalView.findViewById(R.id.search_rating_row).setVisibility(
                Settings.getEnablePreciseRatingFilter() ? View.VISIBLE : View.GONE);
        if (mTableAdvanceSearch != null) {
            mTableAdvanceSearch.setPreciseRatingEnabled(Settings.getEnablePreciseRatingFilter());
        }
        updateRatingDisplay();
    }

    private void syncPreset(SearchPresetSwitch button, String preset) {
        button.setLabel(SearchPresetSwitch.labelFor(getContext(), preset));
        button.setState(SearchPresetQuery.getState(mSearchQuery, preset));
    }

    private void changePreset(String preset, int state) {
        String updated = SearchPresetQuery.setState(mSearchQuery, preset, state);
        syncPresetTags(updated);
        if (mHelper != null) mHelper.onSearchPresetQueryChanged(updated);
    }

    public void scrollSearchContainerToTop() {
        mLayoutManager.scrollToPositionWithOffset(0, 0);
    }

    public void setImageUri(Uri imageUri) {
        mImageView.setImageUri(imageUri);
    }

    public void setNormalSearchMode(int id) {
        mNormalSearchMode.check(id);
    }

    @Override
    public void onSelectImage() {
        if (mHelper != null) {
            mHelper.onSelectImage();
        }
    }

    @Override
    protected void dispatchSaveInstanceState(@NonNull SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);

        mNormalView.saveHierarchyState(container);
        mAdvanceView.saveHierarchyState(container);
        mImageView.saveHierarchyState(container);
        mActionView.saveHierarchyState(container);
    }

    @Override
    protected void dispatchRestoreInstanceState(@NonNull SparseArray<Parcelable> container) {
        super.dispatchRestoreInstanceState(container);

        mNormalView.restoreHierarchyState(container);
        mAdvanceView.restoreHierarchyState(container);
        mImageView.restoreHierarchyState(container);
        mActionView.restoreHierarchyState(container);
        updateRatingDisplay();
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle state = new Bundle();
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState());
        state.putInt(STATE_KEY_SEARCH_MODE, mSearchMode);
        state.putBoolean(STATE_KEY_ENABLE_ADVANCE, mEnableAdvance);
        state.putInt(STATE_KEY_RATING, mMinRatingHundredths);
        state.putBoolean(STATE_KEY_RATING_OVERRIDE, mRatingOverride);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle savedState = (Bundle) state;
            super.onRestoreInstanceState(savedState.getParcelable(STATE_KEY_SUPER));
            mSearchMode = savedState.getInt(STATE_KEY_SEARCH_MODE);
            mEnableAdvance = savedState.getBoolean(STATE_KEY_ENABLE_ADVANCE);
            mMinRatingHundredths = savedState.getInt(STATE_KEY_RATING);
            mRatingOverride = savedState.getBoolean(STATE_KEY_RATING_OVERRIDE);
            updateRatingDisplay();
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == mEnableAdvanceSwitch) {
            mEnableAdvance = isChecked;
            if (mSearchMode == SEARCH_MODE_NORMAL) {
                if (isChecked) {
                    mAdapter.notifyItemInserted(1);
                } else {
                    mAdapter.notifyItemRemoved(1);
                }

                if (mHelper != null) {
                    mHelper.onChangeSearchMode();
                }
            }
        }
    }

    public void formatListUrlBuilder(ListUrlBuilder urlBuilder, String query) throws EhException {
        syncSearchOptionVisibility();
        urlBuilder.reset();

        // Editing only the rating on these pages keeps the current feed.
        // A keyword or another search condition still starts an ordinary search.
        if (mSearchMode == SEARCH_MODE_NORMAL && SearchRating.isClientOnlyMode(mSourceMode)
                && mNormalSearchMode.getCheckedRadioButtonId() == R.id.search_normal_search
                && (query == null || query.trim().isEmpty())
                && mCategoryTable.getCategory() == EhConfig.ALL_CATEGORY
                && !mEnableAdvance) {
            urlBuilder.setMode(mSourceMode);
            if (Settings.getEnablePreciseRatingFilter() && mRatingOverride) {
                urlBuilder.setMinRatingHundredths(mMinRatingHundredths);
            }
            return;
        }

        switch (mSearchMode) {
            case SEARCH_MODE_NORMAL:
                int nsMode = mNormalSearchMode.getCheckedRadioButtonId();
                switch (nsMode) {
                    default:
                    case R.id.search_normal_search:
                        urlBuilder.setMode(ListUrlBuilder.MODE_NORMAL);
                        break;
                    case R.id.search_subscription_search:
                        urlBuilder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
                        break;
                    case R.id.search_specify_uploader:
                        urlBuilder.setMode(ListUrlBuilder.MODE_UPLOADER);
                        break;
                    case R.id.search_specify_tag:
                        urlBuilder.setMode(ListUrlBuilder.MODE_TAG);
                        break;
                }
                urlBuilder.setKeyword(query);
                urlBuilder.setCategory(mCategoryTable.getCategory());
                if (mEnableAdvance) {
                    urlBuilder.setAdvanceSearch(mTableAdvanceSearch.getAdvanceSearch());
                    if (!Settings.getEnablePreciseRatingFilter()) {
                        urlBuilder.setMinRating(mTableAdvanceSearch.getMinRating());
                    }
                    urlBuilder.setPageFrom(mTableAdvanceSearch.getPageFrom());
                    urlBuilder.setPageTo(mTableAdvanceSearch.getPageTo());
                }
                if (Settings.getEnablePreciseRatingFilter() && mRatingOverride) {
                    urlBuilder.setMinRatingHundredths(mMinRatingHundredths);
                }
                break;
            case SEARCH_MODE_IMAGE:
                urlBuilder.setMode(ListUrlBuilder.MODE_IMAGE_SEARCH);
                mImageView.formatListUrlBuilder(urlBuilder);
                break;
        }
    }

    public void setSearchMode(@SearchMode int searchMode, boolean animation) {
        if (mSearchMode != searchMode) {
            int oldItemCount = mAdapter.getItemCount();
            mSearchMode = searchMode;
            int newItemCount = mAdapter.getItemCount();

            if (animation) {
                mAdapter.notifyItemRangeRemoved(0, oldItemCount - 1);
                mAdapter.notifyItemRangeInserted(0, newItemCount - 1);
            } else {
                mAdapter.notifyDataSetChanged();
            }

            if (mHelper != null) {
                mHelper.onChangeSearchMode();
            }
        }
    }

    public void toggleSearchMode() {
        int oldItemCount = mAdapter.getItemCount();

        mSearchMode++;
        if (mSearchMode > SEARCH_MODE_IMAGE) {
            mSearchMode = SEARCH_MODE_NORMAL;
        }

        int newItemCount = mAdapter.getItemCount();

        mAdapter.notifyItemRangeRemoved(0, oldItemCount - 1);
        mAdapter.notifyItemRangeInserted(0, newItemCount - 1);

        // Update action text
        int resId;
        switch (mSearchMode) {
            default:
            case SEARCH_MODE_NORMAL:
                resId = R.string.image_search;
                break;
            case SEARCH_MODE_IMAGE:
                resId = R.string.keyword_search;
                break;
        }

        // --- [修正] 确保将文字设置给 mImageSearchToggle ---
        if (mImageSearchToggle != null) {
            mImageSearchToggle.setText(resId);
        }

        if (mHelper != null) {
            mHelper.onChangeSearchMode();
        }
    }

    @Override
    public void onClick(View v) {
        if (mNormalSearchModeHelp == v) {
            new AlertDialog.Builder(getContext())
                    .setMessage(R.string.search_tip)
                    .show();
        }
        // 移除了 mAction 和 mAdvancedTagSearchBtn 的判断，因为现在使用匿名监听器
    }

    private class SimpleHolder extends ViewHolder {
        public SimpleHolder(View itemView) {
            super(itemView);
        }
    }

    private class SearchAdapter extends Adapter<ViewHolder> {

        @Override
        public int getItemCount() {
            int count = SEARCH_ITEM_COUNT_ARRAY[mSearchMode];
            if (mSearchMode == SEARCH_MODE_NORMAL && !mEnableAdvance) {
                count--;
            }
            return count;
        }

        @Override
        public int getItemViewType(int position) {
            int type = SEARCH_ITEM_TYPE[mSearchMode][position];
            if (mSearchMode == SEARCH_MODE_NORMAL && position == 1 && !mEnableAdvance) {
                type = ITEM_TYPE_ACTION;
            }
            return type;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;

            if (viewType == ITEM_TYPE_ACTION) {
                ViewUtils.removeFromParent(mActionView);
                mActionView.setLayoutParams(new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                int resId;
                switch (mSearchMode) {
                    default:
                    case SEARCH_MODE_NORMAL:
                        resId = R.string.image_search;
                        break;
                    case SEARCH_MODE_IMAGE:
                        resId = R.string.keyword_search;
                        break;
                }

                // --- [核心修正] 防止空指针异常 ---
                if (mImageSearchToggle != null) {
                    mImageSearchToggle.setText(resId);
                }

                view = mActionView;
            } else {
                view = mInflater.inflate(R.layout.search_category, parent, false);
                TextView title = (TextView) view.findViewById(R.id.category_title);
                FrameLayout content = (FrameLayout) view.findViewById(R.id.category_content);
                switch (viewType) {
                    case ITEM_TYPE_NORMAL:
                        title.setText(R.string.search_normal);
                        ViewUtils.removeFromParent(mNormalView);
                        content.addView(mNormalView);
                        break;
                    case ITEM_TYPE_NORMAL_ADVANCE:
                        title.setText(R.string.search_advance);
                        ViewUtils.removeFromParent(mAdvanceView);
                        content.addView(mAdvanceView);
                        break;
                    case ITEM_TYPE_IMAGE:
                        title.setText(R.string.search_image);
                        ViewUtils.removeFromParent(mImageView);
                        content.addView(mImageView);
                        break;
                }
            }

            return new SimpleHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            // Empty, bind view in create view
        }
    }

    public interface Helper {
        void onSearchPresetQueryChanged(String query);
        void onChangeSearchMode();
        void onSelectImage();
        void onOpenTagSelector();
    }
}
