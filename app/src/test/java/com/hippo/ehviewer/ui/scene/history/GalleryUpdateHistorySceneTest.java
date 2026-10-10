package com.hippo.ehviewer.ui.scene.history;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.conaco.Conaco;
import com.hippo.conaco.DataContainer;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore.Summary;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.lib.image.Image;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.widget.LoadImageView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN", shadows = {
        GalleryUpdateHistorySceneTest.AppShadow.class, GalleryUpdateHistorySceneTest.ImageShadow.class})
public class GalleryUpdateHistorySceneTest {
    private Context context;

    @Before public void setup() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("update-history-ui", Context.MODE_PRIVATE));
    }

    @Test public void downloadOverflowOpensGlobalHistory() {
        PopupMenu menu = new PopupMenu(context, new View(context));
        menu.inflate(R.menu.scene_download);
        TestDownloads scene = new TestDownloads();
        android.view.MenuItem item = menu.getMenu().findItem(R.id.action_gallery_update_history);
        assertEquals("画廊更新历史", item.getTitle().toString());
        assertTrue(scene.onMenuItemClick(item));
        assertEquals(GalleryUpdateHistoryScene.class, scene.launched.getClazz());
        // Exercise the real navigation registry: an unregistered page crashes before inflation.
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        assertEquals(SceneFragment.LAUNCH_MODE_SINGLE_TOP,
                host.getSceneLaunchMode(scene.launched.getClazz()));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void cardsShowUpdatesAndRecycledMissingGalleryClearsMetadataAndOpensTarget() throws Exception {
        TestScene scene = new TestScene(context);
        scene.rows = List.of(new Summary(200, 1791076310000L, 2, 5, 2, true, false, "token200"),
                new Summary(150, 1791076300000L, 0, 0, 0, false, true, "token150"));
        scene.local = new DownloadInfo(200);
        scene.local.token = "token200";
        scene.local.title = "本地画廊标题";
        scene.local.thumb = "https://example.com/cover.jpg";
        scene.local.category = 2;
        View root = scene.onCreateView3(LayoutInflater.from(context), null, null);
        ShadowLooper.idleMainLooper();
        RecyclerView recycler = root.findViewById(R.id.recycler_view);
        RecyclerView.Adapter adapter = recycler.getAdapter();
        assertEquals(2, adapter.getItemCount());
        RecyclerView.ViewHolder row = adapter.createViewHolder(recycler, 0);
        adapter.bindViewHolder(row, 0);
        assertEquals("本地画廊标题", text(row, R.id.title));
        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(scene.rows.get(0).completedAt())), text(row, R.id.update_time));
        assertEquals("+5  -2", text(row, R.id.update_counts));
        Spanned counts = (Spanned) ((TextView) row.itemView.findViewById(R.id.update_counts)).getText();
        ForegroundColorSpan[] colors = counts.getSpans(0, counts.length(), ForegroundColorSpan.class);
        assertEquals(2, colors.length);
        assertEquals(context.getColor(R.color.deep_green_600), colors[0].getForegroundColor());
        assertEquals(context.getColor(R.color.red_500), colors[1].getForegroundColor());
        assertEquals("已完成", text(row, R.id.state));
        assertEquals("3/5", text(row, R.id.read_progress));
        assertNull(row.itemView.findViewById(R.id.uploader));
        assertNull(row.itemView.findViewById(R.id.rating));
        row.itemView.performClick();
        assertEquals(GalleryDetailScene.class, scene.launched.getClazz());
        assertEquals(GalleryDetailScene.ACTION_DOWNLOAD_GALLERY_INFO,
                scene.launched.getArgs().getString(GalleryDetailScene.KEY_ACTION));
        assertEquals(200, ((DownloadInfo) scene.launched.getArgs()
                .getParcelable(GalleryDetailScene.KEY_GALLERY_INFO)).gid);
        int compactHeight = preview(row.itemView, "success", "本地画廊标题");
        int longTitleHeight = preview(row.itemView, "long-title", "第一行标题\n第二行标题\n第三行标题\n第四行标题\n第五行标题");
        assertEquals("History bounds: title=" + bounds(row.itemView, R.id.title)
                + ", time=" + bounds(row.itemView, R.id.update_time)
                + ", counts=" + bounds(row.itemView, R.id.update_counts_row)
                + ", footer=" + bounds(row.itemView, R.id.update_footer),
                downloadCardHeight(row.itemView), longTitleHeight);
        assertEquals(compactHeight, longTitleHeight);
        TextView title = row.itemView.findViewById(R.id.title);
        assertEquals(2, title.getLineCount());
        assertTrue(title.getLayout().getEllipsisCount(1) > 0);
        ((TextView) row.itemView.findViewById(R.id.title)).setTextSize(24);
        preview(row.itemView, "large-text", "较大的标题文字\n第二行标题\n第三行标题\n第四行标题");

        adapter.bindViewHolder(row, 1);
        assertEquals("150", text(row, R.id.title));
        assertEquals(View.GONE, row.itemView.findViewById(R.id.category).getVisibility());
        assertNull(((LoadImageView) row.itemView.findViewById(R.id.thumb)).getDrawable());
        assertEquals("+—  -—", text(row, R.id.update_counts));
        assertEquals("失败", text(row, R.id.state));
        assertEquals("—", text(row, R.id.read_progress));
        row.itemView.performClick();
        Bundle args = scene.launched.getArgs();
        assertEquals(GalleryDetailScene.ACTION_GID_TOKEN, args.getString(GalleryDetailScene.KEY_ACTION));
        assertEquals(150, args.getLong(GalleryDetailScene.KEY_GID));
        assertEquals("token150", args.getString(GalleryDetailScene.KEY_TOKEN));
        preview(row.itemView, "missing-gallery", "150");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void incompleteComparisonAndEmptyHistoryAreExplicit() throws Exception {
        TestScene scene = new TestScene(context);
        scene.rows = List.of(new Summary(250, 1, 0, 0, 0, false, false, "t"));
        View root = scene.onCreateView3(LayoutInflater.from(context), null, null);
        ShadowLooper.idleMainLooper();
        RecyclerView recycler = root.findViewById(R.id.recycler_view);
        RecyclerView.Adapter adapter = recycler.getAdapter();
        RecyclerView.ViewHolder row = adapter.createViewHolder(recycler, 0);
        adapter.bindViewHolder(row, 0);
        assertEquals("已完成 · 页面信息不完整", text(row, R.id.state));
        assertEquals("+—  -—", text(row, R.id.update_counts));
        preview(row.itemView, "incomplete", "第一行标题\n第二行标题\n第三行标题");
        assertEquals(1, ((TextView) row.itemView.findViewById(R.id.state)).getLineCount());
        assertEquals(1, ((TextView) row.itemView.findViewById(R.id.update_time)).getLineCount());
        scene.rows = List.of();
        ReflectionHelpers.callInstanceMethod(scene, "reload");
        ShadowLooper.idleMainLooper();
        assertEquals(0, adapter.getItemCount());
        assertEquals(View.VISIBLE, root.findViewById(R.id.tip).getVisibility());
        assertEquals("暂无保存的画廊更新记录", ((TextView) root.findViewById(R.id.tip)).getText().toString());
    }

    private String text(RecyclerView.ViewHolder holder, int id) {
        return ((TextView) holder.itemView.findViewById(id)).getText().toString();
    }

    private int preview(View card, String name, String title) throws Exception {
        ((TextView) card.findViewById(R.id.title)).setText(title);
        int width = Math.round(360 * context.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, width, card.getMeasuredHeight());
        Rect counts = bounds(card, R.id.update_counts);
        Rect footer = bounds(card, R.id.update_footer);
        TextView state = card.findViewById(R.id.state);
        assertTrue(state.getWidth() > 0);
        assertTrue(state.getHeight() > 0);
        assertTrue(state.getLineCount() > 0);
        assertTrue(state.getLayout().getLineRight(0) <= state.getWidth());
        assertTrue(counts.bottom <= footer.top);
        assertTrue(bounds(card, R.id.title).bottom <= bounds(card, R.id.update_time).top);
        assertTrue(bounds(card, R.id.update_time).bottom <= counts.top);
        assertTrue(counts.right <= bounds(card, R.id.state).left);
        assertTrue(((TextView) card.findViewById(R.id.title)).getLineCount() <= 2);
        Bitmap bitmap = Bitmap.createBitmap(width, card.getHeight(), Bitmap.Config.ARGB_8888);
        card.draw(new Canvas(bitmap));
        File dir = new File("build/update-history-previews");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
        return card.getHeight();
    }

    private Rect bounds(View root, int id) {
        View child = root.findViewById(id);
        Rect rect = new Rect();
        child.getDrawingRect(rect);
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }

    private int downloadCardHeight(View historyCard) {
        View downloadCard = LayoutInflater.from(context).inflate(R.layout.item_download, null, false);
        ViewGroup.LayoutParams coverSize = historyCard.findViewById(R.id.thumb).getLayoutParams();
        View cover = downloadCard.findViewById(R.id.thumb);
        ViewGroup.LayoutParams params = cover.getLayoutParams();
        params.width = coverSize.width;
        params.height = coverSize.height;
        cover.setLayoutParams(params);
        ((TextView) downloadCard.findViewById(R.id.title)).setText("第一行标题\n第二行标题\n第三行标题");
        ((TextView) downloadCard.findViewById(R.id.uploader)).setText("Uploader");
        ((TextView) downloadCard.findViewById(R.id.category)).setText("DOUJINSHI");
        ((TextView) downloadCard.findViewById(R.id.state)).setText("已完成");
        for (int id : new int[]{R.id.stop, R.id.progress_bar, R.id.percent, R.id.speed}) {
            downloadCard.findViewById(id).setVisibility(View.GONE);
        }
        downloadCard.measure(View.MeasureSpec.makeMeasureSpec(historyCard.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return downloadCard.getMeasuredHeight();
    }

    private static class TestScene extends GalleryUpdateHistoryScene {
        private final Context context;
        List<Summary> rows = List.of();
        DownloadInfo local;
        Announcer launched;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override protected Executor executor(Context context) { return Runnable::run; }
        @Override protected List<Summary> loadSummaries(Context context) { return rows; }
        @Override protected DownloadInfo findDownload(long gid) { return local != null && local.gid == gid ? local : null; }
        @Override public void startScene(Announcer announcer) { launched = announcer; }
    }

    private static class TestDownloads extends DownloadsScene {
        Announcer launched;
        @Override public void startScene(Announcer announcer) { launched = announcer; }
    }

    @Implements(EhApplication.class)
    public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static Conaco<Image> getConaco(Context context) { return null; }
    }

    @Implements(LoadImageView.class)
    public static class ImageShadow extends org.robolectric.shadows.ShadowView {
        @RealObject private LoadImageView view;
        @Implementation protected void load(String key, String url, DataContainer data, boolean memory, boolean network) {}
        @Implementation protected void load(Drawable drawable) { view.setImageDrawable(drawable); }
        @Implementation protected void unload() { view.setImageDrawable(null); }
    }
}
