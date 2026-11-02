/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

/** Tracks preview scroll position for the optional jump past already displayed previews. */
public class GalleryDetailScrollView extends ScrollView {

    private static final float BOTTOM_ACTIVATION_RANGE_DP = 120f;

    private final float mBottomActivationRange;
    private final Rect mVisibleRect = new Rect();
    private final GalleryDetailPreviewPosition mPreviewPosition = new GalleryDetailPreviewPosition();

    @Nullable
    private View mPreviewEndView;

    public GalleryDetailScrollView(Context context) {
        this(context, null);
    }

    public GalleryDetailScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GalleryDetailScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mBottomActivationRange = BOTTOM_ACTIVATION_RANGE_DP
                * getResources().getDisplayMetrics().density;
    }

    public void setPreviewEndView(@Nullable View view) {
        if (mPreviewEndView != view) {
            mPreviewPosition.resetActivationViewSeen();
        }
        mPreviewEndView = view;
        updatePreviewPosition();
    }

    public boolean canJumpToNewContent() {
        updatePreviewPosition();
        return mPreviewPosition.canJumpToNewContent(isNearContentBottom());
    }

    private void updatePreviewPosition() {
        View activationView = mPreviewEndView;
        boolean activationViewVisible = activationView != null && activationView.isShown()
                && activationView.getGlobalVisibleRect(mVisibleRect);
        mPreviewPosition.observeActivationView(activationViewVisible);
    }

    private boolean isNearContentBottom() {
        View content = getChildCount() > 0 ? getChildAt(0) : null;
        if (!isLaidOut() || getHeight() <= 0 || content == null || content.getHeight() <= 0) {
            return false;
        }
        int viewportHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        return GalleryDetailPreviewPosition.isWithinBottomRange(
                getScrollY(), viewportHeight, content.getHeight(), mBottomActivationRange);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        updatePreviewPosition();
    }

}
