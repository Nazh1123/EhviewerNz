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

package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.thread.PriorityThread;
import com.hippo.unifile.UniFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Reads a recursively indexed SAF folder without copying its images into app storage. */
public final class LocalFolderGalleryProvider extends GalleryProvider2 implements Runnable {

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger();

    @NonNull
    private final Context context;
    @Nullable
    private final LocalFolderGallerySource source;
    private final Stack<Integer> requests = new Stack<>();
    private final AtomicInteger decodingIndex =
            new AtomicInteger(GalleryPageView.INVALID_INDEX);
    private final AtomicReference<List<LocalFolderGalleryScanner.ImageEntry>> images =
            new AtomicReference<>(Collections.emptyList());

    @Nullable
    private Thread backgroundThread;
    private volatile int size = STATE_WAIT;
    @Nullable
    private String error;

    public LocalFolderGalleryProvider(@NonNull Context context, @NonNull String encodedSource) {
        this.context = context.getApplicationContext();
        source = LocalFolderGallerySource.parse(encodedSource);
    }

    @Override
    public void start() {
        super.start();
        backgroundThread = new PriorityThread(this,
                "LocalFolderGallery-" + ID_GENERATOR.incrementAndGet(),
                Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (backgroundThread != null) {
            backgroundThread.interrupt();
            backgroundThread = null;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    protected void onRequest(int index) {
        synchronized (requests) {
            if (!requests.contains(index) && index != decodingIndex.get()) {
                requests.add(index);
                requests.notify();
            }
        }
        notifyPageWait(index);
    }

    @Override
    protected void onForceRequest(int index) {
        onRequest(index);
    }

    @Override
    public void onCancelRequest(int index) {
        synchronized (requests) {
            requests.remove(Integer.valueOf(index));
        }
    }

    @Override
    @Nullable
    public String getError() {
        return error;
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        LocalFolderGalleryScanner.ImageEntry image = getImage(index);
        if (image == null) {
            return Integer.toString(index);
        }
        String extension = FileUtils.getExtensionFromFilename(image.filename);
        return extension == null
                ? image.filename
                : image.filename.substring(0, image.filename.length() - extension.length() - 1);
    }

    @Override
    public boolean save(int index, @NonNull UniFile destination) {
        LocalFolderGalleryScanner.ImageEntry image = getImage(index);
        if (image == null) {
            return false;
        }
        try (InputStream input = context.getContentResolver().openInputStream(image.uri);
             OutputStream output = destination.openOutputStream()) {
            if (input == null) {
                return false;
            }
            IOUtils.copy(input, output);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile directory, @NonNull String filename) {
        LocalFolderGalleryScanner.ImageEntry image = getImage(index);
        if (image == null) {
            return null;
        }
        String extension = FileUtils.getExtensionFromFilename(image.filename);
        UniFile destination = directory.createFile(
                extension == null ? filename : filename + '.' + extension);
        return destination != null && save(index, destination) ? destination : null;
    }

    @Override
    public void run() {
        if (source == null) {
            fail(R.string.local_folder_not_accessible);
            return;
        }

        LocalFolderGalleryScanner.ScanResult result;
        try {
            result = LocalFolderGalleryScanner.scan(context, source);
        } catch (LocalFolderGalleryScanner.ScanException e) {
            if (e.reason == LocalFolderGalleryScanner.Reason.CANCELLED) {
                return;
            }
            fail(e.reason == LocalFolderGalleryScanner.Reason.TOO_LARGE
                    ? R.string.local_folder_scan_too_large
                    : R.string.local_folder_not_accessible);
            return;
        }
        if (result.images.isEmpty()) {
            fail(R.string.local_folder_no_images);
            return;
        }

        images.set(result.images);
        size = result.images.size();
        notifyDataChanged();

        while (!Thread.currentThread().isInterrupted()) {
            int index;
            synchronized (requests) {
                if (requests.isEmpty()) {
                    try {
                        requests.wait();
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    continue;
                }
                index = requests.pop();
                decodingIndex.set(index);
            }
            decode(index);
            decodingIndex.set(GalleryPageView.INVALID_INDEX);
        }
        images.set(Collections.emptyList());
    }

    private void decode(int index) {
        LocalFolderGalleryScanner.ImageEntry image = getImage(index);
        if (image == null) {
            notifyPageFailed(index, GetText.getString(R.string.error_out_of_range));
            return;
        }
        try {
            ParcelFileDescriptor descriptor = context.getContentResolver()
                    .openFileDescriptor(image.uri, "r");
            if (descriptor == null) {
                notifyPageFailed(index, GetText.getString(R.string.error_reading_failed));
                return;
            }
            try (FileInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                Image decoded = Image.decode(input, false, getAnimatedWebpDecodeMode(index));
                if (decoded == null) {
                    try (InputStream fallback =
                                 context.getContentResolver().openInputStream(image.uri)) {
                        if (fallback != null) {
                            decoded = Image.decode(
                                    BitmapDrawable.createFromStream(fallback, null), false);
                        }
                    }
                }
                if (decoded != null) {
                    notifyPageSucceed(index, decoded);
                } else {
                    notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                }
            }
        } catch (IOException | RuntimeException e) {
            notifyPageFailed(index, GetText.getString(R.string.error_reading_failed));
        }
    }

    @Nullable
    private LocalFolderGalleryScanner.ImageEntry getImage(int index) {
        List<LocalFolderGalleryScanner.ImageEntry> current = images.get();
        return index >= 0 && index < current.size() ? current.get(index) : null;
    }

    private void fail(int stringId) {
        size = STATE_ERROR;
        error = GetText.getString(stringId);
        notifyDataChanged();
    }
}
