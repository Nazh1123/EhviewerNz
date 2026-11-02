package com.hippo.ehviewer.ui.scene.gallery.list;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.util.TagTranslationUtil;
import com.hippo.scene.Announcer;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.List;

public class BookmarksDraw {

    private final Context context;
    private final LayoutInflater inflater;

    private final EhTagDatabase ehTags;

    private static final String QUICK_SEARCH_DRAW_SCROLL_Y = "QuickSearchDrawScrollY";
    private static final String QUICK_SEARCH_DRAW_SCROLL_POS = "QuickSearchDrawScrollPos";

    final private EhApplication ehApplication;

    private ListView listView;

    public BookmarksDraw(@NonNull Context context, LayoutInflater inflater, EhTagDatabase ehTags) {
        this.context = context;
        this.inflater = inflater;
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(context);
        }
        this.ehTags = ehTags;
        ehApplication = (EhApplication) context.getApplicationContext();
    }

    @SuppressLint("NonConstantResourceId")
    public View onCreate(GalleryListScene scene) {
        View bookmarksView = inflater.inflate(R.layout.bookmarks_draw, null, false);

        Toolbar toolbar = (Toolbar) ViewUtils.$$(bookmarksView, R.id.toolbar);
        final TextView tip = (TextView) ViewUtils.$$(bookmarksView, R.id.tip);
        listView = (ListView) ViewUtils.$$(bookmarksView, R.id.list_view);


        AssertUtils.assertNotNull(context);

        final List<QuickSearch> list = EhDB.getAllQuickSearch();
        final ArrayAdapter<QuickSearch> adapter = new ArrayAdapter<>(context, R.layout.item_simple_list, list) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                QuickSearch search = getItem(position);
                String name = search.getName();
                // Translate only an unchanged keyword label. A custom name is user data;
                // rendering the drawer must never rewrite it or persist a translation.
                if (Settings.getShowTagTranslations() && ehTags != null && name != null
                        && name.equals(search.getKeyword())) {
                    String[] parts = name.split("  ");
                    for (int i = 0; i < parts.length; i++) {
                        parts[i] = TagTranslationUtil.getTagCN(
                                parts[i].replace("\"", "").replace("$", ""), ehTags);
                    }
                    name = TextUtils.join("  ", parts);
                }
                view.setText(name);
                return view;
            }
        };
        listView.setAdapter(adapter);
        //快速搜索点击tag事件监听
        listView.setOnItemClickListener((parent, view1, position, id) -> {
            if (null == scene.mHelper || null == scene.mUrlBuilder) {
                return;
            }

            scene.resetDownloadedOnlyModeForNewSearch();
            scene.mUrlBuilder.set(list.get(position));
            scene.mUrlBuilder.setPageIndex(0);
            scene.onUpdateUrlBuilder();
            scene.mHelper.refresh();
            scene.setState(GalleryListScene.STATE_NORMAL);
            scene.closeDrawer(Gravity.RIGHT);
        });
        listView.setOnScrollListener(new ScrollListener());

        tip.setText(R.string.quick_search_tip);
        toolbar.setLogo(R.drawable.ic_baseline_bookmarks_24);
        toolbar.setTitle(R.string.quick_search);
        toolbar.inflateMenu(R.menu.drawer_gallery_list);
        toolbar.setOnMenuItemClickListener(item -> {  //点击增加快速搜索按钮触发
            int id = item.getItemId();
            switch (id) {
                case R.id.action_add:
                    if (Settings.getQuickSearchTip()) {
                        scene.showQuickSearchTipDialog(list, adapter, listView, tip);
                    } else {
                        scene.showAddQuickSearchDialog(list, adapter, listView, tip);
                    }
                    break;
                case R.id.action_settings:
                    scene.startScene(new Announcer(QuickSearchScene.class));
                    break;
            }
            return true;
        });

        if (list.isEmpty()) {
            tip.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
        } else {
            tip.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            resume();
        }

        toolbar.setOnClickListener(l -> {
            scene.drawPager.setCurrentItem(1);
        });


        return bookmarksView;
    }

    public void resume() {
        Object scrollY = ehApplication.getTempCache(QUICK_SEARCH_DRAW_SCROLL_Y);
        Object pos = ehApplication.getTempCache(QUICK_SEARCH_DRAW_SCROLL_POS);
        if (scrollY != null && pos != null) {
            listView.setSelection((Integer) pos);
        }
    }

    private class ScrollListener implements AbsListView.OnScrollListener {
        public ScrollListener() {
            super();
        }

        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {
            View item = view.getChildAt(0);
            if (item == null) {
                return;
            }
            int firstPos = view.getFirstVisiblePosition();
            int top = item.getTop();
            int scrollY = firstPos * item.getHeight() - top;
            ehApplication.putTempCache(QUICK_SEARCH_DRAW_SCROLL_Y, scrollY);
            ehApplication.putTempCache(QUICK_SEARCH_DRAW_SCROLL_POS, firstPos);
        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        }
    }
}
