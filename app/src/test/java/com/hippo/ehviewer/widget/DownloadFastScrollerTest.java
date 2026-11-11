package com.hippo.ehviewer.widget;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.graphics.RectF;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hippo.ehviewer.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class DownloadFastScrollerTest {
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
    private final FrameLayout root = new FrameLayout(context);
    private final DownloadFastScroller scroller = new DownloadFastScroller(context);
    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 600, 800);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void add(View view) {
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        root.addView(scroller, new FrameLayout.LayoutParams(30, -1));
    }
    private void touch(int action, float y) {
        MotionEvent event = MotionEvent.obtain(0, 100, action, 15, y, 0);
        assertTrue(scroller.onTouchEvent(event));
        event.recycle();
    }
    private void dragTo(float y) {
        RectF thumb = scroller.getThumbBounds();
        touch(MotionEvent.ACTION_DOWN, thumb.centerY());
        touch(MotionEvent.ACTION_MOVE, y);
        layout();
        touch(MotionEvent.ACTION_UP, y);
        layout();
    }
    @Test public void recyclerThumbRemainsShortAndDraggableAfterIdleAndAdapterReplacement() {
        RecyclerView recycler = new RecyclerView(context);
        LinearLayoutManager manager = new LinearLayoutManager(context);
        recycler.setLayoutManager(manager);
        recycler.setAdapter(adapter(2));
        add(recycler);
        scroller.attachToRecyclerView(recycler);
        layout();
        assertFalse(scroller.isScrollable());
        recycler.setAdapter(adapter(240));
        layout();
        assertTrue(scroller.isScrollable());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(View.VISIBLE, scroller.getVisibility());
        assertEquals(1f, scroller.getAlpha(), 0f);
        assertTrue(scroller.getThumbBounds().height() <= 36 * context.getResources().getDisplayMetrics().density);
        dragTo(790);
        assertTrue(manager.findFirstVisibleItemPosition() > 200);
        assertThumbDrawnAtItsReportedPosition();
        dragTo(8);
        assertTrue(manager.findFirstVisibleItemPosition() < 10);
    }
    @Test public void drawerListSupportsSeekingAndRebindingItsAdapter() {
        ListView list = new ListView(context);
        ArrayList<String> items = new ArrayList<>();
        for (int i = 0; i < 240; i++) items.add("Label " + i);
        list.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, items));
        add(list);
        scroller.attachToListView(list);
        layout();
        assertTrue(scroller.isScrollable());
        dragTo(790);
        assertTrue(list.getFirstVisiblePosition() > 200);
        assertThumbDrawnAtItsReportedPosition();
        list.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, items));
        layout();
        dragTo(790);
        assertTrue(list.getFirstVisiblePosition() > 200);
        dragTo(8);
        assertTrue(list.getFirstVisiblePosition() < 10);
    }
    private RecyclerView.Adapter<?> adapter(int count) {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                TextView text = new TextView(context);
                text.setLayoutParams(new RecyclerView.LayoutParams(-1, 48));
                return new RecyclerView.ViewHolder(text) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int index) {}
            @Override public int getItemCount() { return count; }
        };
    }
    private void assertThumbDrawnAtItsReportedPosition() {
        RectF thumb = scroller.getThumbBounds();
        assertTrue(thumb.top > scroller.getHeight() * 0.8f);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                scroller.getWidth(), scroller.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        scroller.draw(new android.graphics.Canvas(bitmap));
        assertNotEquals("The thumb must be drawn at the same position as its touch bounds", 0,
                bitmap.getPixel(Math.round(thumb.centerX()), Math.round(thumb.centerY())));
        bitmap.recycle();
    }
}
