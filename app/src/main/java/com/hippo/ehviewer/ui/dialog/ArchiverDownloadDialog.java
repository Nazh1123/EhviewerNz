package com.hippo.ehviewer.ui.dialog;

import static com.hippo.ehviewer.ui.scene.BaseScene.LENGTH_LONG;
import static com.hippo.ehviewer.ui.scene.BaseScene.LENGTH_SHORT;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.ArchiverData;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.exception.NoHAtHClientException;
import com.hippo.ehviewer.download.ArchiverDownloadCompleter;
import com.hippo.ehviewer.download.ArchiverDownloader;
import com.hippo.ehviewer.download.ArchiveQuota;
import com.hippo.ehviewer.client.parser.ArchiveQuotaParser;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.EhCallback;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.scene.SceneFragment;

public class ArchiverDownloadDialog implements
        DialogInterface.OnDismissListener, EhClient.Callback<ArchiverData> {
    final private GalleryDetail galleryDetail;
    final private Context context;
    final private GalleryDetailScene detailScene;
    private Dialog dialog;
    private TextView currentFunds;
    private TextView originalCost;
    private TextView originalSize;
    private TextView resampleCost;
    private TextView resampleSize;
    private Button resampleDownload;
    private Button originalDownload;
    private ProgressBar progressBar;
    private LinearLayout body;
    private ArchiverData data = new ArchiverData();
    private String quotaAccount;
    private TextView quotaUsage;
    private EhRequest quotaRequest;
    private boolean quotaRefreshing;
    private boolean archiveLoaded;

    private void updateDownloadButtons() {
        boolean enabled = archiveLoaded && (!quotaRefreshing
                || ArchiveQuota.ledger(context).hasQuota(quotaAccount));
        originalDownload.setEnabled(enabled);
        resampleDownload.setEnabled(enabled);
    }

    private void updateQuota() {
        boolean visible = quotaAccount != null && !quotaAccount.isEmpty()
                && quotaAccount.equals(ArchiveQuota.account(context));
        quotaUsage.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        boolean donor = ArchiveQuota.ledger(context).quota(quotaAccount) > 0;
        SpannableStringBuilder line = new SpannableStringBuilder();
        if (donor) line.append(ArchiveQuota.format(context, quotaAccount)).append("  ");
        if (quotaRefreshing) {
            line.append(context.getString(R.string.archive_quota_refreshing));
        } else {
            appendQuotaAction(line, R.string.archive_quota_refresh, () -> refreshQuota(true));
        }
        if (donor) {
            line.append("  ");
            appendQuotaAction(line, R.string.archive_quota_reset, () -> {
                ArchiverDownloader.getInstance(context).resetQuota(quotaAccount);
                updateQuota();
            });
        }
        quotaUsage.setText(line);
    }

    private void appendQuotaAction(SpannableStringBuilder line, int label, Runnable action) {
        int start = line.length();
        line.append(context.getString(label));
        line.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                if (quotaAccount.equals(ArchiveQuota.account(context))) action.run();
            }
        }, start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void refreshQuota(boolean force) {
        if (quotaRefreshing || quotaAccount.isEmpty()
                || !quotaAccount.equals(ArchiveQuota.account(context))) return;
        if (!force && ArchiveQuota.ledger(context).hasQuota(quotaAccount)) {
            ArchiverDownloader.getInstance(context).checkpointQuota();
            updateQuota();
            return;
        }
        quotaRefreshing = true;
        ArchiverDownloader.getInstance(context).checkpointQuota();
        updateQuota();
        updateDownloadButtons();
        quotaRequest = new EhRequest().setMethod(EhClient.METHOD_GET_ARCHIVE_QUOTA)
                .setArgs(quotaAccount, force).setCallback(new EhClient.Callback<Long>() {
                    private void finished(boolean failed) {
                        quotaRefreshing = false;
                        quotaRequest = null;
                        if (!dialog.isShowing()) return;
                        ArchiverDownloader.getInstance(context).checkpointQuota();
                        updateQuota();
                        updateDownloadButtons();
                        if (failed && quotaAccount.equals(ArchiveQuota.account(context))) {
                            Toast.makeText(context, R.string.archive_quota_refresh_failed, Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override public void onSuccess(Long result) { finished(false); }
                    @Override public void onFailure(Exception e) { finished(true); }
                    @Override public void onCancel() { finished(false); }
                });
        EhApplication.getEhClient(context).execute(quotaRequest);
    }

    public ArchiverDownloadDialog(GalleryDetail galleryDetail, GalleryDetailScene detailScene) {
        this.galleryDetail = galleryDetail;
        this.detailScene = detailScene;
        this.context = detailScene.getEHContext();
    }

    public void showDialog() {
        quotaAccount = ArchiveQuota.account(context);
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_archiver_title)
                .setView(R.layout.dialog_archiver)
                .setOnDismissListener(this)
                .show();

        currentFunds = dialog.findViewById(R.id.dialog_archiver_current_funds);
        originalCost = dialog.findViewById(R.id.dialog_archiver_original_cost);
        originalSize = dialog.findViewById(R.id.dialog_archiver_original_size);
        resampleCost = dialog.findViewById(R.id.dialog_archiver_resample_cost);
        resampleSize = dialog.findViewById(R.id.dialog_archiver_resample_size);
        resampleDownload = dialog.findViewById(R.id.dialog_archiver_resample_download);
        originalDownload = dialog.findViewById(R.id.dialog_archiver_original_download);
        progressBar = dialog.findViewById(R.id.dialog_archiver_progress);
        body = dialog.findViewById(R.id.dialog_archiver_body);
        quotaUsage = dialog.findViewById(R.id.dialog_archiver_quota_usage);
        quotaUsage.setMovementMethod(LinkMovementMethod.getInstance());
        refreshQuota(false);
        resampleDownload.setOnClickListener(this::onArchiverDownload);
        originalDownload.setOnClickListener(this::onArchiverDownload);
        EhRequest mRequest = new EhRequest().setMethod(EhClient.METHOD_ARCHIVER)
                .setArgs(galleryDetail.archiveUrl, galleryDetail.gid, galleryDetail.token)
                .setCallback(this);

        assert mRequest != null;

        EhApplication.getEhClient(context).execute(mRequest);
    }

    private void onArchiverDownload(View view) {
        try {
            String url = null;
            String dltype = null;
            String dlcheck = null;
            if (view == originalDownload) {
                url = data.originalUrl;
                dltype = "org";
                dlcheck = "Download Original Archive";
            } else if (view == resampleDownload) {
                url = data.resampleUrl;
                dltype = "res";
                dlcheck = "Download Resample Archive";
            }

            if (url == null) {
                return;
            }
            MainActivity activity = detailScene.getActivity2();

            if (null != context && null != activity && galleryDetail != null) {
                EhRequest request = new EhRequest();
                request.setMethod(EhClient.METHOD_DOWNLOAD_ARCHIVER);
                request.setArgs(url, galleryDetail.archiveUrl, dltype, dlcheck);
                boolean free = ArchiveQuotaParser.isFree(view == originalDownload
                        ? data.originalCost : data.resampleCost);
                request.setCallback(new DownloadArchiverListener(context, activity.getStageId(), detailScene.getTag(), free));
                EhApplication.getEhClient(context).execute(request);
            }

        } finally {
            progressBar.setVisibility(View.VISIBLE);
            body.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (quotaRequest != null) quotaRequest.cancel();
        // 下载在应用内继续；完成由 ArchiverDownloadCompleter 处理
    }

    @Override
    public void onSuccess(ArchiverData result) {
        if (!dialog.isShowing()) return;
        data = result;
        archiveLoaded = true;
        updateDownloadButtons();
        String cF;
        if (Settings.getGallerySite() == EhUrl.SITE_E) {
            cF = context.getString(R.string.archiver_dialog_current_funds) + data.funds;
        } else {
            cF = data.funds;
        }

        currentFunds.setText(cF);
        String oC = context.getString(R.string.archiver_dialog_cost, data.originalCost);
        String rC = context.getString(R.string.archiver_dialog_cost, data.resampleCost);
        originalCost.setText(oC);
        resampleCost.setText(rC);

        String oS = context.getString(R.string.archiver_dialog_size, data.originalSize);
        String rS = context.getString(R.string.archiver_dialog_size, data.resampleSize);

        originalSize.setText(oS);
        resampleSize.setText(rS);
        progressBar.setVisibility(View.GONE);
        body.setVisibility(View.VISIBLE);
    }

    @Override
    public void onFailure(Exception e) {
    }

    @Override
    public void onCancel() {

    }

    private class DownloadArchiverListener extends EhCallback<GalleryDetailScene, String> {
        final Context context;
        final boolean free;

        public DownloadArchiverListener(Context context, int stageId, String sceneTag, boolean free) {
            super(context, stageId, sceneTag);
            this.context = context;
            this.free = free;
        }

        @Override
        public void onSuccess(String downloadUrl) {
            if (dialog != null && !dialog.isShowing()) {
                return;
            }

            if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show();
                return;
            }

            progressBar.setVisibility(View.INVISIBLE);
            body.setVisibility(View.VISIBLE);
            dialog.dismiss();
            showTip(R.string.download_archive_started, LENGTH_SHORT);
            String fileName = ArchiverDownloadCompleter.createFileName(galleryDetail.title, galleryDetail.gid);
            if (fileName.isEmpty()) {
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show();
                return;
            }

            Uri downloadUri = Uri.parse(downloadUrl);
            String scheme = downloadUri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                Log.w("ArchiverDownloadDialog", "Invalid download URL scheme: " + downloadUrl);
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show();
                return;
            }

            ArchiverDownloader downloader = ArchiverDownloader.getInstance(context);
            if (downloader == null) {
                Toast.makeText(context, R.string.download_state_failed, Toast.LENGTH_LONG).show();
                return;
            }

            downloader.start(context, galleryDetail, downloadUrl, fileName,
                    free && quotaAccount.equals(ArchiveQuota.account(context)), quotaAccount);
            detailScene.bindArchiverProgress(galleryDetail);
        }

        @Override
        public void onFailure(Exception e) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            if (e instanceof NoHAtHClientException) {
                showTip(R.string.download_h_h_failure_no_hath, LENGTH_LONG);
            } else {
                showTip(R.string.download_archive_failure, LENGTH_LONG);
            }
        }

        @Override
        public void onCancel() {
        }

        @Override
        public boolean isInstance(SceneFragment scene) {
            return scene instanceof GalleryDetailScene;
        }
    }
}
