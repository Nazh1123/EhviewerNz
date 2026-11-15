package com.hippo.ehviewer.ui.scene.download;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.scene.Announcer;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DownloadLabelDraw {
    private final LayoutInflater inflater;
    private final DownloadsScene scene;
    private final ViewGroup container;
    private final Context context;

    private View view;
    private Toolbar toolbar;
    private ListView listView;

    public DownloadLabelDraw(LayoutInflater inflater, @Nullable ViewGroup container,DownloadsScene scene){
        this.inflater = inflater;
        this.container = container;
        this.scene = scene;
        this.context = scene.getEHContext();
    }

    public View createView(){
        view = inflater.inflate(R.layout.drawer_download_labels, container, false);
        assert context != null;
        AssertUtils.assertNotNull(context);

        toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.download_labels);
        toolbar.inflateMenu(R.menu.drawer_download);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            switch (id) {
                case R.id.action_settings:
                    scene.startScene(new Announcer(DownloadLabelsScene.class));
                    return true;
                case R.id.action_default_download_label:
                    DownloadManager dm = scene.getMDownloadManager();
                    if (null == dm) {
                        return true;
                    }

                    List<DownloadLabel> list = dm.getDownloadLabels();
                    final String[] items = new String[list.size() + 2];
                    items[0] = scene.getString(R.string.let_me_select);
                    items[1] = scene.getString(R.string.default_download_label_name);
                    for (int i = 0, n = list.size(); i < n; i++) {
                        items[i + 2] = list.get(i).getLabel();
                    }
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.default_download_label)
                            .setItems(items, (dialog, which) -> {
                                if (which == 0) {
                                    Settings.putHasDefaultDownloadLabel(false);
                                } else {
                                    Settings.putHasDefaultDownloadLabel(true);
                                    String label;
                                    if (which == 1) {
                                        label = null;
                                    } else {
                                        label = items[which];
                                    }
                                    Settings.putDefaultDownloadLabel(label);
                                }
                            }).show();
                    return true;
            }
            return false;
        });

        listView = view.findViewById(R.id.list_view);
        com.hippo.ehviewer.widget.DownloadFastScroller scroller = view.findViewById(R.id.label_fast_scroller);
        scroller.attachToListView(listView);
        updateDownloadLabels();
        return view;
    }

    public void updateDownloadLabels() {
        if (listView == null) return;
        DownloadManager manager = EhApplication.getDownloadManager(context);
        List<DownloadLabelItem> rows = new ArrayList<>();
        rows.add(new DownloadLabelItem(scene.getString(R.string.default_download_label_name),
                manager.getDefaultDownloadInfoList().size()));
        for (DownloadLabel label : manager.getLabelList()) {
            if (label.getParentId() != null) {
                DownloadLabel folder = null;
                for (DownloadLabel candidate : manager.getLabelList()) {
                    if (Objects.equals(candidate.getId(), label.getParentId())) folder = candidate;
                }
                if (folder != null && !Settings.isDownloadLabelExpanded(folder.getId(), folder.getLabel())) continue;
            }
            DownloadLabelItem row = new DownloadLabelItem(label.getLabel(), manager.getLabelCount(label.getLabel()));
            row.item = label;
            row.folderCount = label.getFolder() ? manager.getFolderCount(label) : null;
            rows.add(row);
        }
        listView.setAdapter(new DownloadLabelAdapter(context, R.layout.item_download_label_list, rows));
        listView.setOnItemClickListener((parent, clickedView, position, id) -> {
            if (scene.searching) {
                Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
                return;
            }
            DownloadLabelItem row = rows.get(position);
            if (row.item != null && row.item.getFolder()) {
                DownloadLabel folder = row.item;
                Settings.setDownloadLabelExpanded(folder.getId(), folder.getLabel(),
                        !Settings.isDownloadLabelExpanded(folder.getId(), folder.getLabel()));
                int first = listView.getFirstVisiblePosition();
                View firstView = listView.getChildAt(0);
                int top = firstView == null ? 0 : firstView.getTop();
                updateDownloadLabels();
                listView.setSelectionFromTop(first, top);
                if (scene.isContinuousLabelBrowse()) { scene.updateForLabel(); scene.updateView(); }
                return;
            }
            String label = row.item == null ? null : row.item.getLabel();
            if (scene.isContinuousLabelBrowse()) {
                scene.expandAndScrollToDownloadLabel(label);
                scene.closeDrawer(Gravity.RIGHT);
                return;
            }
            if (!ObjectUtils.equal(label, scene.mLabel)) {
                scene.mLabel = label;
                scene.updateForLabel();
                if (scene.searchKey != null && !scene.searchKey.isEmpty()) scene.startSearching();
                else scene.updateView();
            }
            scene.closeDrawer(Gravity.RIGHT);
        });
    }
}
