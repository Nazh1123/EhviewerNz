package com.hippo.ehviewer.ui.scene.gallery.detail;

import static com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl;

import android.app.AlertDialog;
import android.content.Context;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.scene.Announcer;

public class GalleryUpdateDialog {
    final GalleryDetailScene detailScene;
    final Context context;

    private GalleryDetail galleryDetail;

    private AlertDialog dialog;

    public GalleryUpdateDialog(GalleryDetailScene scene, Context context) {
        this.detailScene = scene;
        this.context = context;
    }

    public void showSelectDialog(GalleryDetail galleryDetail) {
        if (galleryDetail == this.galleryDetail && dialog != null) {
            dialog.setTitle(R.string.new_version);
            dialog.show();
            return;
        }
        this.galleryDetail = galleryDetail;
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setSingleChoiceItems(galleryDetail.getUpdateVersionName(), -1, (dia, index) -> {
//            GalleryInfo gi = (GalleryInfo) galleryDetail.getNewGalleryDetail(index);
//            if (gi == null) {
//                return;
//            }
//            Bundle args = new Bundle();
//            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
//            args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi);
//            Announcer announcer = new Announcer(GalleryDetailScene.class).setArgs(args);
            dialog.dismiss();
            Announcer announcer = createAnnouncerFromClipboardUrl(galleryDetail.newVersions[index].versionUrl);
            detailScene.startScene(announcer);
        });
        dialog = builder.create();
        dialog.setTitle(R.string.new_version);
        dialog.show();
    }

    public void destroy() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
