package com.hippo.ehviewer.widget;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.ListAdapter;
import android.widget.ListView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.ehviewer.R;

/** Download lists use the search page's thumb drawing and item-position seeking, with a
 * persistent 36dp thumb and a full-height touch target instead of a one-second grab window. */
public class DownloadFastScroller extends View {
    private final HandlerDrawable thumb = new HandlerDrawable();
    private final RectF bounds = new RectF();
    private RecyclerView recycler;
    private ListView list;
    private RecyclerView.Adapter<?> recyclerAdapter;
    private ListAdapter listAdapter;
    private FastScroller.OnDragHandlerListener listener;
    private boolean dragging;
    private boolean scrollable;
    private float progress;
    private float grabOffset;
    private final Runnable refresh = this::update;
    private final RecyclerView.AdapterDataObserver recyclerObserver = new RecyclerView.AdapterDataObserver() {
        @Override public void onChanged() { scheduleUpdate(); }
        @Override public void onItemRangeInserted(int start, int count) { scheduleUpdate(); }
        @Override public void onItemRangeRemoved(int start, int count) { scheduleUpdate(); }
        @Override public void onItemRangeMoved(int from, int to, int count) { scheduleUpdate(); }
    };
    private final DataSetObserver listObserver = new DataSetObserver() {
        @Override public void onChanged() { scheduleUpdate(); }
    };

    public DownloadFastScroller(Context context) { this(context, null); }
    public DownloadFastScroller(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public DownloadFastScroller(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        thumb.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent));
        setContentDescription(context.getString(R.string.download_fast_scroll));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
    }

    public void attachToRecyclerView(RecyclerView target) {
        recycler = target;
        target.setVerticalScrollBarEnabled(false);
        target.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { update(); }
        });
        target.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> update());
        scheduleUpdate();
    }

    public void attachToListView(ListView target) {
        list = target;
        target.setFastScrollEnabled(false);
        target.setVerticalScrollBarEnabled(false);
        target.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {}
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { update(); }
        });
        target.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> update());
        scheduleUpdate();
    }

    public void setOnDragHandlerListener(FastScroller.OnDragHandlerListener value) { listener = value; }

    private void scheduleUpdate() { removeCallbacks(refresh); post(refresh); }

    private void update() {
        if (recycler != null) {
            RecyclerView.Adapter<?> adapter = recycler.getAdapter();
            if (adapter != recyclerAdapter) {
                if (recyclerAdapter != null) recyclerAdapter.unregisterAdapterDataObserver(recyclerObserver);
                recyclerAdapter = adapter;
                if (adapter != null) adapter.registerAdapterDataObserver(recyclerObserver);
            }
            int range = recycler.computeVerticalScrollRange();
            int extent = recycler.computeVerticalScrollExtent();
            scrollable = recycler.canScrollVertically(-1) || recycler.canScrollVertically(1);
            if (!dragging) progress = range > extent
                    ? clamp(recycler.computeVerticalScrollOffset() / (float) (range - extent)) : 0;
        } else if (list != null) {
            ListAdapter adapter = list.getAdapter();
            if (adapter != listAdapter) {
                if (listAdapter != null) listAdapter.unregisterDataSetObserver(listObserver);
                listAdapter = adapter;
                if (adapter != null) adapter.registerDataSetObserver(listObserver);
            }
            scrollable = list.canScrollVertically(-1) || list.canScrollVertically(1);
            if (!dragging && list.getChildCount() > 0 && adapter != null) {
                View first = list.getChildAt(0), last = list.getChildAt(list.getChildCount() - 1);
                float offset = list.getFirstVisiblePosition()
                        + (list.getPaddingTop() - first.getTop()) / (float) Math.max(1, first.getHeight());
                float end = list.getLastVisiblePosition() + 1
                        - Math.max(0, last.getBottom() - list.getHeight() + list.getPaddingBottom())
                        / (float) Math.max(1, last.getHeight());
                float extent = end - offset;
                progress = clamp(offset / Math.max(1, adapter.getCount() - extent));
            }
        }
        updateBounds();
        invalidate();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private void updateBounds() {
        float top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
        float height = Math.min(dp(36), Math.max(0, bottom - top));
        float y = top + progress * Math.max(0, bottom - top - height);
        bounds.set(getWidth() - dp(10), y, getWidth() - dp(4), y + height);
    }
    public RectF getThumbBounds() { return new RectF(bounds); }
    public boolean isScrollable() { update(); return scrollable; }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { update(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); scheduleUpdate(); }
    @Override protected void onDetachedFromWindow() {
        finishDrag();
        removeCallbacks(refresh);
        if (recyclerAdapter != null) recyclerAdapter.unregisterAdapterDataObserver(recyclerObserver);
        if (listAdapter != null) listAdapter.unregisterDataSetObserver(listObserver);
        recyclerAdapter = null;
        listAdapter = null;
        super.onDetachedFromWindow();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!scrollable) return;
        updateBounds();
        // HandlerDrawable draws from (0, 0), as in the search page's FastScroller.
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        thumb.setBounds(0, 0, Math.round(bounds.width()), Math.round(bounds.height()));
        thumb.draw(canvas);
        canvas.restoreToCount(saved);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            update();
            if (!scrollable) return false;
            dragging = true;
            grabOffset = event.getY() >= bounds.top && event.getY() <= bounds.bottom
                    ? event.getY() - bounds.top : bounds.height() / 2;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (recycler != null) recycler.stopScroll();
            if (listener != null) listener.onStartDragHandler();
            seek(event.getY());
            return true;
        }
        if (!dragging) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) seek(event.getY());
        else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            seek(event.getY());
            performClick();
            finishDrag();
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) finishDrag();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setScrollable(scrollable);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 100, progress * 100));
        if (scrollable) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        float target;
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) target = progress + 0.1f;
        else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) target = progress - 0.1f;
        else if (action == android.R.id.accessibilityActionSetProgress && arguments != null)
            target = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) / 100;
        else return super.performAccessibilityAction(action, arguments);
        if (!scrollable) return false;
        grabOffset = bounds.height() / 2;
        seek(getPaddingTop() + clamp(target) * (getHeight() - getPaddingTop()
                - getPaddingBottom() - bounds.height()) + grabOffset);
        return true;
    }
    private void finishDrag() {
        if (!dragging) return;
        dragging = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (listener != null) listener.onEndDragHandler();
        scheduleUpdate();
    }
    private void seek(float y) {
        float track = getHeight() - getPaddingTop() - getPaddingBottom() - bounds.height();
        progress = clamp((y - getPaddingTop() - grabOffset) / Math.max(1, track));
        int count = recyclerAdapter != null ? recyclerAdapter.getItemCount()
                : listAdapter != null ? listAdapter.getCount() : 0;
        int position = Math.round(progress * Math.max(0, count - 1));
        if (recycler != null) {
            RecyclerView.LayoutManager layout = recycler.getLayoutManager();
            if (layout instanceof LinearLayoutManager) ((LinearLayoutManager) layout).scrollToPositionWithOffset(position, 0);
            else if (layout instanceof StaggeredGridLayoutManager) ((StaggeredGridLayoutManager) layout).scrollToPositionWithOffset(position, 0);
            else recycler.scrollToPosition(position);
        } else if (list != null) list.setSelectionFromTop(position, list.getPaddingTop());
        updateBounds();
        invalidate();
    }
}
