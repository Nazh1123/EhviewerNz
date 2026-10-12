package com.hippo.widget;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.hippo.widget.recyclerview.AutoGridLayoutManager;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        qualifiers = "w360dp-h800dp-mdpi")
public class ThumbnailGridLayoutTest {
    @Test public void previewGridKeepsPortraitWidthAndCanExceedEightColumnsInLandscape() {
        Context context = RuntimeEnvironment.getApplication();
        for (int columns = 1; columns <= 8; columns++) {
            RecyclerView view = recycler(context);
            view.setPadding(4, 0, 4, 0);
            AutoGridLayoutManager manager = new AutoGridLayoutManager(context, -1);
            manager.setStrategy(AutoGridLayoutManager.STRATEGY_SUITABLE_SIZE);
            manager.setPortraitSpanCount(context.getResources(), columns);
            view.setLayoutManager(manager);
            layout(view, 360);
            assertEquals(columns, manager.getSpanCount());
            int width = manager.getColumnSize();
            layout(view, 800);
            assertEquals(width, manager.getColumnSize());
            assertTrue(manager.getSpanCount() > columns);
            layout(view, 360);
            assertEquals(columns, manager.getSpanCount());
        }
    }

    @Test public void coverGridAndDetailPreviewsReflowWithTheirFixedPortraitWidths() {
        Context context = RuntimeEnvironment.getApplication();
        for (int columns = 1; columns <= 8; columns++) {
            if (columns <= 5) {
                RecyclerView view = recycler(context);
                view.setPadding(8, 0, 8, 0);
                AutoStaggeredGridLayoutManager manager = new AutoStaggeredGridLayoutManager(
                        120, RecyclerView.VERTICAL);
                manager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_SUITABLE_SIZE);
                manager.setPortraitSpanCount(context.getResources(), columns);
                view.setLayoutManager(manager);
                layout(view, 360);
                assertEquals(columns, manager.getSpanCount());
                layout(view, 800);
                assertTrue(manager.getSpanCount() > columns);
                layout(view, 360);
                assertEquals(columns, manager.getSpanCount());
            }
            SimpleGridAutoSpanLayout detail = new SimpleGridAutoSpanLayout(context);
            detail.setPadding(12, 0, 12, 0);
            detail.setStrategy(SimpleGridAutoSpanLayout.STRATEGY_SUITABLE_SIZE);
            detail.setPortraitSpanCount(columns);
            layout(detail, 360);
            assertEquals(columns, (int) ReflectionHelpers.getField(detail, "mColumnCount"));
            layout(detail, 800);
            assertTrue((int) ReflectionHelpers.getField(detail, "mColumnCount") > columns);
            layout(detail, 360);
            assertEquals(columns, (int) ReflectionHelpers.getField(detail, "mColumnCount"));
        }
    }

    @Test @Config(qualifiers = "sw360dp-w800dp-h336dp-land-mdpi")
    public void openingInLandscapeUsesShortEdgeAndPageMargins() {
        Context context = RuntimeEnvironment.getApplication();
        PortraitColumnSize size = new PortraitColumnSize();
        size.setColumns(4);
        // 800-wide window, 24px outer page margins, 16px grid padding.
        assertEquals(80, size.resolve(context.getResources(), 776, 16));
        assertEquals(80, size.resolve(context.getResources(), 336, 16));
    }

    @Test public void changingBackToAPresetDisablesNumericSizing() {
        Context context = RuntimeEnvironment.getApplication();
        RecyclerView view = recycler(context);
        AutoGridLayoutManager manager = new AutoGridLayoutManager(context, 120);
        manager.setStrategy(AutoGridLayoutManager.STRATEGY_SUITABLE_SIZE);
        manager.setPortraitSpanCount(context.getResources(), 1);
        view.setLayoutManager(manager);
        layout(view, 360);
        assertEquals(1, manager.getSpanCount());
        manager.setColumnSize(120);
        view.requestLayout();
        layout(view, 360);
        assertEquals(3, manager.getSpanCount());
        layout(view, 800);
        assertEquals(7, manager.getSpanCount());
    }

    private static RecyclerView recycler(Context context) {
        RecyclerView view = new RecyclerView(context);
        view.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                View item = new View(parent.getContext());
                item.setLayoutParams(new RecyclerView.LayoutParams(-1, 100));
                return new RecyclerView.ViewHolder(item) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 32; }
        });
        return view;
    }

    private static void layout(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, 800);
    }
}
