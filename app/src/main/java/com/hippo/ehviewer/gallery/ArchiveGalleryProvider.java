/*
 * Copyright 2019 Hippo Seven
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
import android.net.Uri;
import android.os.Process;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.thread.PriorityThread;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

public class ArchiveGalleryProvider extends GalleryProvider2 {

  private static final AtomicInteger sIdGenerator = new AtomicInteger();

  private final UniFile file;
  private final Context context;
  private final Uri uri;
  private final ArchivePageSource.Factory sourceFactory;
  private final Object saveLock = new Object();
  private ArchivePageSource saveSource;
  private volatile String[] pageNames = new String[0];
  private volatile boolean stopped;

  private Thread archiveThread;
  private Thread decodeThread;

  private volatile int size = STATE_WAIT;
  private String error;

  private final Stack<Integer> requests = new Stack<>();
  private final AtomicInteger extractingIndex = new AtomicInteger(GalleryPageView.INVALID_INDEX);
  private final LinkedHashMap<Integer, InputStream> streams = new LinkedHashMap<>();
  private final AtomicInteger decodingIndex = new AtomicInteger(GalleryPageView.INVALID_INDEX);

  public ArchiveGalleryProvider(Context context, Uri uri) {
    this(context, uri, factory(context.getApplicationContext(), uri));
  }

  private static ArchivePageSource.Factory factory(Context context, Uri uri) {
    return () -> {
      UniFile archive = UniFile.fromUri(context, uri);
      if (archive == null) throw new IOException("Archive unavailable");
      return ArchivePageSource.open(archive);
    };
  }

  ArchiveGalleryProvider(Context context, Uri uri, ArchivePageSource.Factory factory) {
    this.context = context.getApplicationContext();
    this.uri = uri;
    sourceFactory = factory;
    file = UniFile.fromUri(context, uri);
  }

  public Uri getArchiveUri() { return uri; }

  @Override public UniFile getTranslationDirectory() {
    return ArchiveResultStorage.directory(context, uri, false);
  }

  @Override
  public GalleryProvider2 createTranslationProvider(Context context) {
    return new ArchiveGalleryProvider(context.getApplicationContext(), uri, sourceFactory);
  }

  @Override
  public String getTranslationIdentity() { return file != null ? "archive:" + file.getUri() : null; }

  @Override public String getResultSourceStamp(int index) {
    return "";
  }

  @Override public String getResultPageName(int index) {
    String[] names = pageNames;
    return index >= 0 && index < names.length ? names[index] : Integer.toString(index);
  }

  @Override public String getTranslationFilename(int index) {
    return withoutExtension(getResultPageName(index));
  }

  private static String withoutExtension(String name) {
    String extension = com.hippo.lib.yorozuya.FileUtils.getExtensionFromFilename(name);
    return extension == null ? name : name.substring(0, name.length() - extension.length() - 1);
  }

  @Override public ReaderPreviewSource createPreviewSource(Context context) {
    return new ReaderPreviewSource() {
      private ArchivePageSource source;
      @Override public ReaderPreviewBitmapCache.Result load(int index, int edge) throws IOException {
        if (file == null || index < 0) return null;
        String identity = file.getUri() + ":" + file.lastModified() + ":" + file.length() + ":" + index;
        ReaderPreviewBitmapCache.Result cached = ReaderPreviewBitmapCache.cached(context, identity, edge);
        if (cached != null) return cached;
        if (source == null) source = sourceFactory.open();
        if (index >= source.names().length) return null;
        java.io.File temporary = java.io.File.createTempFile("reader-preview-", ".image", context.getCacheDir());
        try {
          try (java.io.OutputStream output = cancellable(new java.io.FileOutputStream(temporary))) {
            source.extract(index, output);
          }
          return ReaderPreviewBitmapCache.load(context, identity, edge,
              Uri.fromFile(temporary), () -> new java.io.FileInputStream(temporary));
        } finally { temporary.delete(); }
      }
      @Override public void close() throws IOException { if (source != null) source.close(); }
    };
  }

  @Override
  public void start() {
    super.start();
    stopped = false;

    int id = sIdGenerator.incrementAndGet();

    archiveThread = new PriorityThread(
        new ArchiveTask(), "ArchiveTask" + '-' + id, Process.THREAD_PRIORITY_BACKGROUND);
    archiveThread.start();

    decodeThread = new PriorityThread(
        new DecodeTask(), "DecodeTask" + '-' + id, Process.THREAD_PRIORITY_BACKGROUND);
    decodeThread.start();
  }

  @Override
  public void stop() {
    stopped = true;
    super.stop();

    if (archiveThread != null) {
      archiveThread.interrupt();
      archiveThread = null;
    }
    if (decodeThread != null) {
      decodeThread.interrupt();
      decodeThread = null;
    }
    synchronized (saveLock) {
      if (saveSource != null) {
        try { saveSource.close(); } catch (IOException ignored) { }
        saveSource = null;
      }
    }
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  protected void onRequest(int index) {
    boolean inDecodeTask;
    synchronized (streams) {
      inDecodeTask = streams.keySet().contains(index) || index == decodingIndex.get();
    }

    synchronized (requests) {
      boolean inArchiveTask = requests.contains(index) || index == extractingIndex.get();
      if (!inArchiveTask && !inDecodeTask) {
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
  protected void onCancelRequest(int index) {
    synchronized (requests) {
      requests.remove(Integer.valueOf(index));
    }
  }

  @Override
  public String getError() {
    return error;
  }

  @NonNull
  @Override
  public String getImageFilename(int index) {
    String path = getResultPageName(index);
    return withoutExtension(path.substring(path.lastIndexOf('/') + 1));
  }

  private ArchivePageSource sourceForSave() throws IOException {
    if (stopped) throw new java.io.InterruptedIOException();
    if (saveSource == null) {
      saveSource = sourceFactory.open();
      pageNames = saveSource.names();
    }
    return saveSource;
  }

  private java.io.OutputStream cancellable(java.io.OutputStream output) {
    return new java.io.FilterOutputStream(output) {
      @Override public void write(int value) throws IOException {
        checkActive(); out.write(value);
      }
      @Override public void write(byte[] bytes, int offset, int count) throws IOException {
        checkActive(); out.write(bytes, offset, count);
      }
      private void checkActive() throws IOException {
        if (stopped || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
      }
    };
  }

  @Override
  public boolean save(int index, @NonNull UniFile file) {
    synchronized (saveLock) {
      try {
        ArchivePageSource source = sourceForSave();
        if (index < 0 || index >= pageNames.length || file.getUri().equals(uri)) return false;
        try (java.io.OutputStream output = cancellable(file.openOutputStream(false))) {
          source.extract(index, output);
        }
        return true;
      } catch (IOException | RuntimeException failure) { return false; }
    }
  }

  @Nullable
  @Override
  public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
    SaveResult result = saveWithResult(index, dir, filename);
    return result == null ? null : result.file;
  }

  @Nullable
  @Override
  public SaveResult saveWithResult(int index, @NonNull UniFile dir, @NonNull String filename) {
    synchronized (saveLock) {
      SaveResult destination = null;
      try {
        ArchivePageSource source = sourceForSave();
        if (index < 0 || index >= pageNames.length) return null;
        String extension = com.hippo.lib.yorozuya.FileUtils.getExtensionFromFilename(pageNames[index]);
        destination = prepareSaveDestination(dir, extension == null ? filename : filename + "." + extension);
        if (destination == null || destination.file.getUri().equals(uri)) return null;
        try (java.io.OutputStream output = cancellable(destination.file.openOutputStream(false))) {
          source.extract(index, output);
        }
        return destination;
      } catch (IOException | RuntimeException failure) {
        if (destination != null) destination.deleteIfCreated();
        return null;
      }
    }
  }

  private class ArchiveTask implements Runnable {
    @Override public void run() {
      try (ArchivePageSource source = sourceFactory.open()) {
        pageNames = source.names();
        size = pageNames.length;
        notifyDataChanged();
        while (!stopped && !Thread.currentThread().isInterrupted()) {
          int index;
          synchronized (requests) {
            while (requests.isEmpty()) requests.wait();
            index = requests.pop();
            extractingIndex.set(index);
          }
          if (index < 0 || index >= pageNames.length) {
            extractingIndex.set(GalleryPageView.INVALID_INDEX);
            notifyPageFailed(index, GetText.getString(R.string.error_out_of_range));
            continue;
          }
          Pipe pipe = new Pipe(4 * 1024);
          synchronized (streams) {
            if (streams.containsKey(index)) {
              extractingIndex.set(GalleryPageView.INVALID_INDEX);
              continue;
            }
            streams.put(index, pipe.inputStream);
            streams.notifyAll();
          }
          try (java.io.OutputStream output = cancellable(pipe.outputStream)) {
            source.extract(index, output);
          } catch (IOException failure) {
            if (!stopped) notifyPageFailed(index, GetText.getString(R.string.error_reading_failed));
          } finally { extractingIndex.set(GalleryPageView.INVALID_INDEX); }
        }
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      } catch (IOException | RuntimeException failure) {
        if (!stopped) {
          android.util.Log.w("ArchiveGalleryProvider", "Unable to read archive " + uri, failure);
          size = STATE_ERROR;
          error = GetText.getString(R.string.error_invalid_archive);
          notifyDataChanged();
        }
      } finally {
        synchronized (streams) {
          for (InputStream input : streams.values()) {
            try { input.close(); } catch (IOException ignored) { }
          }
          streams.clear();
        }
      }
    }
  }

  private class DecodeTask implements Runnable {
    @Override
    public void run() {
      while (!Thread.currentThread().isInterrupted()) {
        int index;
        InputStream stream;
        synchronized (streams) {
          if (streams.isEmpty()) {
            try {
              streams.wait();
            } catch (InterruptedException e) {
              // Interrupted
              break;
            }
            continue;
          }

          Iterator<Map.Entry<Integer, InputStream>> iterator = streams.entrySet().iterator();
          Map.Entry<Integer, InputStream> entry = iterator.next();
          iterator.remove();
          index = entry.getKey();
          try{
            stream = entry.getValue();
          } catch (ClassCastException e) {
            notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
            decodingIndex.lazySet(index);
            return;
          }
          decodingIndex.lazySet(index);
        }

        try (InputStream input = stream) {
          Image image = Image.decode(BitmapDrawable.createFromStream(input, null), false);
          // Finish the entry even if the bitmap decoder stops before the archive stream's EOF.
          byte[] remaining = new byte[4096];
          try { while (input.read(remaining) != -1) { } }
          catch (IOException failure) {
            if (image != null) image.recycle();
            throw failure;
          }
          if (stopped) {
            if (image != null) image.recycle();
            continue;
          }
          if (image != null) {
            notifyPageSucceed(index, image);
          } else {
            notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
          }
        } catch (IOException | RuntimeException failure) {
          if (!stopped) notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
        } finally {
          decodingIndex.lazySet(GalleryPageView.INVALID_INDEX);
        }
      }
    }
  }

}
