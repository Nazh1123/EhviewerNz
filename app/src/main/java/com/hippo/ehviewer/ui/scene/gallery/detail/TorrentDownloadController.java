package com.hippo.ehviewer.ui.scene.gallery.detail;

import com.hippo.ehviewer.util.GalleryTimeFormatter;
import static com.hippo.ehviewer.client.EhConfig.TORRENT_PATH;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.content.FileProvider;
import com.hippo.ehviewer.BuildConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.data.TorrentDownloadMessage;
import com.hippo.ehviewer.client.data.TorrentInfo;
import com.hippo.ehviewer.download.DownloadTorrentManager;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.FileUtils;
import com.hippo.widget.ProgressView;

import java.io.File;
import java.lang.ref.WeakReference;

import okhttp3.OkHttpClient;

class TorrentDownloadController {

    interface Host {
        @Nullable Context getEHContext();

        boolean isHostActive();

        void retryTorrentDownload();
    }

    @NonNull
    private final Host host;
    private final long gid;
    @NonNull
    private final String token;
    @NonNull
    private final Handler torrentDownloadHandler;

    @Nullable
    private TorrentInfo[] torrentList;
    @Nullable
    private AlertDialog downLoadAlertDialog;
    @Nullable
    private Dialog torrentListDialog;
    @Nullable
    private View torrentDownloadView;
    @Nullable
    private TextView downloadProgress;

    TorrentDownloadController(@NonNull Host host, long gid, @NonNull String token) {
        this.host = host;
        this.gid = gid;
        this.token = token;
        this.torrentDownloadHandler = new TorrentDownloadHandler(this);
    }

    void showTorrentList(@NonNull Context context, @NonNull String torrentUrl, @NonNull OkHttpClient okHttpClient) {
        if (!host.isHostActive() || torrentListDialog != null && torrentListDialog.isShowing()) {
            return;
        }
        TorrentListDialogHelper helper = new TorrentListDialogHelper(torrentUrl, okHttpClient);
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.torrents)
                .setView(R.layout.dialog_torrent_list)
                .setOnDismissListener(helper)
                .show();
        torrentListDialog = dialog;
        helper.setDialog(dialog);
    }

    void release() {
        if (torrentListDialog != null) {
            torrentListDialog.dismiss();
            torrentListDialog = null;
        }
        dismissTorrentDialog();
        torrentList = null;
        torrentDownloadView = null;
        downloadProgress = null;
    }

    private void ensureDownloadView(@NonNull Context context) {
        if (torrentDownloadView == null) {
            torrentDownloadView = View.inflate(context, R.layout.notification_contentview, null);
        }
    }

    private void handleTorrentDownloadMessage(@Nullable TorrentDownloadMessage message) {
        Context context = host.getEHContext();
        if (context == null || !host.isHostActive() || message == null) {
            return;
        }
        if (message.progress == 200) {
            dismissTorrentDialog();
            String text = context.getString(R.string.torrent_exist, message.path);
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            return;
        }
        if (message.failed) {
            dismissTorrentDialog();
            showTorrentDownloadDialog(message, false);
            return;
        }
        showTorrentDownloadDialog(message, true);
    }

    @SuppressLint("SetTextI18n")
    private void showTorrentDownloadDialog(@NonNull TorrentDownloadMessage message, boolean success) {
        Context context = host.getEHContext();
        if (context == null || !host.isHostActive()) {
            return;
        }

        ensureDownloadView(context);
        if (torrentDownloadView == null) {
            return;
        }

        if (message.progress == 100 || !success) {
            View detail = torrentDownloadView.findViewById(R.id.download_detail);
            View progressView = torrentDownloadView.findViewById(R.id.progress_view);
            detail.setVisibility(View.VISIBLE);
            progressView.setVisibility(View.GONE);

            TextView state = torrentDownloadView.findViewById(R.id.download_state);
            TextView path = torrentDownloadView.findViewById(R.id.download_path);
            Button leftButton = torrentDownloadView.findViewById(R.id.leader);
            Button openButton = torrentDownloadView.findViewById(R.id.torrent_open);
            Button rightButton = torrentDownloadView.findViewById(R.id.action);

            path.setText(context.getString(R.string.download_torrent_path, message.path));
            rightButton.setText(R.string.sure);
            rightButton.setOnClickListener(l -> dismissTorrentDialog());
            openButton.setVisibility(success ? View.VISIBLE : View.GONE);
            openButton.setOnClickListener(success ? l -> openTorrent(context, message.path) : null);

            if (success) {
                leftButton.setText(R.string.open_directory);
                leftButton.setOnClickListener(l -> {
                    dismissTorrentDialog();
                    FileUtils.openAssignFolder(message.dir, context);
                });
                state.setText(context.getString(R.string.download_torrent_state) + context.getString(R.string.download_state_finish));
            } else {
                leftButton.setText(R.string.try_again);
                leftButton.setOnClickListener(l -> {
                    dismissTorrentDialog();
                    host.retryTorrentDownload();
                });
                state.setText(context.getString(R.string.download_torrent_state) + context.getString(R.string.download_state_failed));
            }
            if (downLoadAlertDialog != null) {
                downLoadAlertDialog.setCancelable(true);
            }
        } else {
            String progressString = message.progress + "%";
            if (downLoadAlertDialog != null && downLoadAlertDialog.isShowing()) {
                if (downloadProgress != null) {
                    downloadProgress.setText(progressString);
                }
                return;
            }
            View detail = torrentDownloadView.findViewById(R.id.download_detail);
            View progressView = torrentDownloadView.findViewById(R.id.progress_view);
            detail.setVisibility(View.GONE);
            progressView.setVisibility(View.VISIBLE);
            downloadProgress = torrentDownloadView.findViewById(R.id.download_progress);
            downloadProgress.setText(progressString);
        }

        TextView tName = torrentDownloadView.findViewById(R.id.download_name);
        tName.setText(message.name);
        if (downLoadAlertDialog != null) {
            downLoadAlertDialog.show();
        } else {
            if (torrentDownloadView.getParent() != null) {
                ((ViewGroup) torrentDownloadView.getParent()).removeView(torrentDownloadView);
            }
            downLoadAlertDialog = new AlertDialog.Builder(context)
                    .setView(torrentDownloadView)
                    .setCancelable(false)
                    .show();
        }
    }

    private void openTorrent(@NonNull Context context, @Nullable String path) {
        try {
            if (path == null || !new File(path).isFile()) {
                Toast.makeText(context, R.string.torrent_cannot_open, Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(context, BuildConfig.FILE_PROVIDER_AUTHORITY,
                    new File(path));
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/x-bittorrent")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("torrent", uri));
            context.startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException e) {
            Toast.makeText(context, R.string.torrent_cannot_open, Toast.LENGTH_LONG).show();
        }
    }

    private void dismissTorrentDialog() {
        if (downLoadAlertDialog == null) {
            return;
        }
        if (!host.isHostActive()) {
            downLoadAlertDialog = null;
            return;
        }
        try {
            if (downLoadAlertDialog.isShowing()) {
                downLoadAlertDialog.dismiss();
            }
        } catch (IllegalArgumentException e) {
            ExceptionUtils.throwIfFatal(e);
        }
        downLoadAlertDialog = null;
    }

    static class TorrentListAdapter extends ArrayAdapter<TorrentInfo> {

        TorrentListAdapter(@NonNull Context context, @NonNull TorrentInfo[] data) {
            super(context, 0, data);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_torrent, parent, false);
            TorrentInfo item = getItem(position);
            if (item != null) {
                ((TextView) view.findViewById(R.id.torrent_name)).setText(item.name);
                ((TextView) view.findViewById(R.id.torrent_seeds)).setText(
                        getContext().getString(R.string.torrent_seeds, available(item.seeds)));
                ((TextView) view.findViewById(R.id.torrent_peers)).setText(
                        getContext().getString(R.string.torrent_peers, available(item.peers)));
                ((TextView) view.findViewById(R.id.torrent_downloads)).setText(
                        getContext().getString(R.string.torrent_downloads, available(item.downloads)));
                ((TextView) view.findViewById(R.id.torrent_posted)).setText(
                        available(GalleryTimeFormatter.format(item.posted)));
                ((TextView) view.findViewById(R.id.torrent_size)).setText(available(item.size));
            }
            return view;
        }

        private String available(@Nullable String value) {
            return value == null || value.isEmpty()
                    ? getContext().getString(R.string.torrent_unavailable) : value;
        }
    }

    private static class TorrentDownloadHandler extends Handler {

        @NonNull
        private final WeakReference<TorrentDownloadController> controllerRef;

        TorrentDownloadHandler(@NonNull TorrentDownloadController controller) {
            super(Looper.getMainLooper());
            controllerRef = new WeakReference<>(controller);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            TorrentDownloadController controller = controllerRef.get();
            if (controller == null) {
                return;
            }
            Bundle data = msg.getData();
            TorrentDownloadMessage message = data.getParcelable("torrent_download_message");
            controller.handleTorrentDownloadMessage(message);
        }
    }

    private class TorrentListDialogHelper implements AdapterView.OnItemClickListener,
            DialogInterface.OnDismissListener, EhClient.Callback<TorrentInfo[]> {

        @NonNull
        private final String torrentUrl;
        @NonNull
        private final OkHttpClient okHttpClient;
        @Nullable
        private ProgressView mProgressView;
        @Nullable
        private TextView mErrorText;
        @Nullable
        private ListView mListView;
        @Nullable
        private EhRequest mRequest;
        @Nullable
        private Dialog mDialog;

        TorrentListDialogHelper(@NonNull String torrentUrl, @NonNull OkHttpClient okHttpClient) {
            this.torrentUrl = torrentUrl;
            this.okHttpClient = okHttpClient;
        }

        void setDialog(@Nullable Dialog dialog) {
            if (dialog == null) {
                return;
            }
            mDialog = dialog;
            mProgressView = (ProgressView) ViewUtils.$$(dialog, R.id.progress);
            mErrorText = (TextView) ViewUtils.$$(dialog, R.id.text);
            mListView = (ListView) ViewUtils.$$(dialog, R.id.list_view);
            mListView.setOnItemClickListener(this);

            Context context = host.getEHContext();
            if (context == null) {
                return;
            }
            if (torrentList == null) {
                mErrorText.setVisibility(View.GONE);
                mListView.setVisibility(View.GONE);
                mRequest = new EhRequest().setMethod(EhClient.METHOD_GET_TORRENT_LIST)
                        .setArgs(torrentUrl, gid, token)
                        .setCallback(this);
                if (mRequest == null) {
                    return;
                }
                EhApplication.getEhClient(context).execute(mRequest);
            } else {
                bind(torrentList);
            }
        }

        private void bind(@NonNull TorrentInfo[] data) {
            if (mDialog == null || mProgressView == null || mErrorText == null || mListView == null) {
                return;
            }

            if (data.length == 0) {
                mProgressView.setVisibility(View.GONE);
                mErrorText.setVisibility(View.VISIBLE);
                mListView.setVisibility(View.GONE);
                mErrorText.setText(R.string.no_torrents);
            } else {
                mProgressView.setVisibility(View.GONE);
                mErrorText.setVisibility(View.GONE);
                mListView.setVisibility(View.VISIBLE);
                mListView.setAdapter(new TorrentListAdapter(mDialog.getContext(), data));
            }
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Context context = host.getEHContext();
            if (context != null && torrentList != null && position < torrentList.length) {
                downLoadPlanB(position, context);
            }
        }

        private void downLoadPlanB(int position, @NonNull Context context) {
            try {
                String url = torrentList[position].url;
                String name = torrentList[position].name + ".torrent";
                String path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath() + "/" + TORRENT_PATH;
                DownloadTorrentManager downloadTorrentManager = DownloadTorrentManager.get(okHttpClient);
                if (!EhApplication.addDownloadTorrent(context, url)) {
                    Toast.makeText(context, R.string.downloading, Toast.LENGTH_LONG).show();
                    return;
                }
                downloadTorrentManager.download(url, path, name, torrentDownloadHandler, context);
            } catch (Exception e) {
                ExceptionUtils.throwIfFatal(e);
            }
            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            if (torrentListDialog == dialog) torrentListDialog = null;
            if (mRequest != null) {
                mRequest.cancel();
                mRequest = null;
            }
            mDialog = null;
            mProgressView = null;
            mErrorText = null;
            mListView = null;
        }

        @Override
        public void onSuccess(TorrentInfo[] result) {
            if (mRequest != null) {
                mRequest = null;
                torrentList = result;
                bind(result);
            }
        }

        @Override
        public void onFailure(Exception e) {
            mRequest = null;
            Context context = host.getEHContext();
            if (context != null && mProgressView != null && mErrorText != null && mListView != null) {
                mProgressView.setVisibility(View.GONE);
                mErrorText.setVisibility(View.VISIBLE);
                mListView.setVisibility(View.GONE);
                mErrorText.setText(ExceptionUtils.getReadableString(e));
            }
        }

        @Override
        public void onCancel() {
            mRequest = null;
        }
    }
}
