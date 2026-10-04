/*
 * Copyright 2016 Hippo Seven
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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.GalleryUpdateRecord;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.SimpleHandler;
import java.util.Locale;
import java.util.Arrays;

public class EhGalleryProvider extends GalleryProvider2 implements SpiderQueen.OnSpiderListener {

    private final Context mContext;
    private final GalleryInfo mGalleryInfo;
    @Nullable
    private SpiderQueen mSpiderQueen;
    private final long mUpdateRecordTime;
    private volatile int[] mUpdatePages;
    private volatile int mInitialUpdateReadingPage;
    private long mUpdateProgressSequence;
    private volatile String mUpdateError;
    private volatile boolean mStopped;

    public EhGalleryProvider(Context context, GalleryInfo galleryInfo) {
        this(context, galleryInfo, 0L);
    }

    public EhGalleryProvider(Context context, GalleryInfo galleryInfo, long updateRecordTime) {
        mContext = context;
        mGalleryInfo = galleryInfo;
        mUpdateRecordTime = updateRecordTime;
    }

    @Override
    public void start() {
        super.start();

        mSpiderQueen = SpiderQueen.obtainSpiderQueen(mContext, mGalleryInfo, SpiderQueen.MODE_READ);
        mSpiderQueen.addOnSpiderListener(this);
        if (mUpdateRecordTime > 0 && mUpdatePages == null) {
            Context application = mContext.getApplicationContext();
            EhApplication.getExecutorService(application).execute(() -> {
                try {
                    GalleryUpdateRecord record = GalleryUpdateRecordStore.get(application)
                            .find(mGalleryInfo.gid);
                    if (record == null || record.completedAt != mUpdateRecordTime
                            || !record.complete || record.addedPages.length == 0) {
                        mUpdateError = application.getString(R.string.gallery_update_log_unavailable);
                    } else {
                        mInitialUpdateReadingPage = record.readingPage;
                        mUpdatePages = record.addedPages.clone();
                    }
                } catch (RuntimeException e) {
                    mUpdateError = application.getString(R.string.gallery_update_log_unavailable);
                }
                if (!mStopped) notifyDataChanged();
            });
        }
    }

    @Override
    public void stop() {
        mStopped = true;
        super.stop();

        if (mSpiderQueen != null) {
            mSpiderQueen.removeOnSpiderListener(this);
            // Activity recreate may called, so wait 3000s
            SimpleHandler.getInstance().postDelayed(new ReleaseTask(mSpiderQueen), 3000);
            mSpiderQueen = null;
        }
    }

    @Override
    public int getStartPage() {
        if (mUpdateRecordTime > 0) return mInitialUpdateReadingPage;
        if (mSpiderQueen != null) {
            return mSpiderQueen.getStartPage();
        } else {
            return super.getStartPage();
        }
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        return String.format(Locale.US, "%d-%s-%08d", mGalleryInfo.gid, mGalleryInfo.token,
                getSourcePage(index) + 1);
    }

    @NonNull
    @Override
    public String getTranslationFilename(int index) {
        return com.hippo.ehviewer.spider.SpiderDen.generateImageFilename(getSourcePage(index), "");
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        if (null != mSpiderQueen) {
            return mSpiderQueen.save(getSourcePage(index), file);
        } else {
            return false;
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        if (null != mSpiderQueen) {
            return mSpiderQueen.save(getSourcePage(index), dir, filename);
        } else {
            return null;
        }
    }

    @Nullable
    @Override
    public SaveResult saveWithResult(int index, @NonNull UniFile dir,
                                     @NonNull String filename) {
        return mSpiderQueen != null
                ? mSpiderQueen.saveWithResult(getSourcePage(index), dir, filename) : null;
    }

    @Override
    public synchronized void putStartPage(int page) {
        if (mUpdateRecordTime > 0) {
            int[] pages = mUpdatePages;
            if (pages == null || page < 0 || page >= pages.length) return;
            long sequence = ++mUpdateProgressSequence;
            Context application = mContext.getApplicationContext();
            EhApplication.getExecutorService(application).execute(() -> {
                synchronized (EhGalleryProvider.this) {
                    if (sequence != mUpdateProgressSequence) return;
                    GalleryUpdateRecordStore.get(application).saveReadingPage(
                            mGalleryInfo.gid, mUpdateRecordTime, page);
                }
            });
            return;
        }
        if (mSpiderQueen != null) {
            mSpiderQueen.putStartPage(page);
        }
    }

    @Override
    public GalleryProvider2 createTranslationProvider(Context context) {
        EhGalleryProvider provider = new EhGalleryProvider(context.getApplicationContext(),
                mGalleryInfo, mUpdateRecordTime);
        int[] pages = mUpdatePages;
        if (pages != null) provider.mUpdatePages = pages.clone();
        provider.mInitialUpdateReadingPage = mInitialUpdateReadingPage;
        return provider;
    }

    @Override
    public String getTranslationIdentity() {
        return "eh:" + mGalleryInfo.gid
                + (mUpdateRecordTime > 0 ? ":update:" + mUpdateRecordTime : "");
    }

    @Override
    public int getSourcePage(int index) {
        int[] pages = mUpdatePages;
        return pages != null && index >= 0 && index < pages.length ? pages[index] : index;
    }

    private int readerIndex(int sourcePage) {
        if (mUpdateRecordTime <= 0) return sourcePage;
        int[] pages = mUpdatePages;
        return pages != null ? Arrays.binarySearch(pages, sourcePage) : -1;
    }

    @Override
    public void prepareTranslationSource(int index) {
        if (mSpiderQueen != null) mSpiderQueen.prepareTranslationSource(getSourcePage(index));
    }

    @Override
    public int size() {
        if (mUpdateRecordTime > 0) {
            if (mUpdateError != null) return GalleryProvider.STATE_ERROR;
            if (mUpdatePages == null) return GalleryProvider.STATE_WAIT;
            int sourceSize = mSpiderQueen != null ? mSpiderQueen.size() : GalleryProvider.STATE_ERROR;
            return sourceSize < 0 ? sourceSize : mUpdatePages.length;
        }
        if (mSpiderQueen != null) {
            return mSpiderQueen.size();
        } else {
            return GalleryProvider.STATE_ERROR;
        }
    }

    @Override
    protected void onRequest(int index) {
        if (mSpiderQueen != null) {
            Object object = mSpiderQueen.request(getSourcePage(index));
            if (object instanceof Float) {
                notifyPagePercent(index, (Float) object);
            } else if (object instanceof String) {
                notifyPageFailed(index, (String) object);
            } else if (object == null) {
                notifyPageWait(index);
            }
        }
    }

    @Override
    protected void onForceRequest(int index) {
        if (mSpiderQueen != null) {
            Object object = mSpiderQueen.forceRequest(getSourcePage(index));
            if (object instanceof Float) {
                notifyPagePercent(index, (Float) object);
            } else if (object instanceof String) {
                notifyPageFailed(index, (String) object);
            } else if (object == null) {
                notifyPageWait(index);
            }
        }
    }

    @Override
    protected void onCancelRequest(int index) {
        if (mSpiderQueen != null) {
            mSpiderQueen.cancelRequest(getSourcePage(index));
        }
    }

    @Override
    public void setAnimatedWebpDecodeMode(int index, int mode) {
        super.setAnimatedWebpDecodeMode(index, mode);
        if (mSpiderQueen != null) {
            mSpiderQueen.setAnimatedWebpDecodeMode(getSourcePage(index), mode);
        }
    }

    @Override
    public String getError() {
        if (mUpdateError != null) return mUpdateError;
        if (mSpiderQueen != null) {
            return mSpiderQueen.getError();
        } else {
            return "Error"; // TODO
        }
    }

    @Override
    public void onGetPages(int pages) {
        notifyDataChanged();
    }

    @Override
    public void onGet509(int index) {
        // TODO
    }

    @Override
    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        index = readerIndex(index);
        if (index < 0) return;
        if (contentLength > 0) {
            notifyPagePercent(index, (float) receivedSize / contentLength);
        }
    }

    @Override
    public void onPageSuccess(int index, int finished, int downloaded, int total) {
        index = readerIndex(index);
        if (index < 0) return;
        notifyDataChanged(index);
    }

    @Override
    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
        index = readerIndex(index);
        if (index < 0) return;
        notifyPageFailed(index, error);
    }

    @Override
    public void onFinish(int finished, int downloaded, int total) {
    }

    @Override
    public void onGetImageSuccess(int index, Image image) {
        index = readerIndex(index);
        if (index < 0) return;
        notifyPageSucceed(index, image);
    }

    @Override
    public void onGetImageFailure(int index, String error) {
        index = readerIndex(index);
        if (index < 0) return;
        notifyPageFailed(index, error);
    }

    private static class ReleaseTask implements Runnable {

        private SpiderQueen mSpiderQueen;

        public ReleaseTask(SpiderQueen spiderQueen) {
            mSpiderQueen = spiderQueen;
        }

        @Override
        public void run() {
            if (null != mSpiderQueen) {
                SpiderQueen.releaseSpiderQueen(mSpiderQueen, SpiderQueen.MODE_READ);
                mSpiderQueen = null;
            }
        }
    }
}
