package com.hippo.ehviewer.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One real first-page request per planned bookmark source, independent of unread cursors. */
public final class BookmarkSubscriptionTest {
    private static final String KEY_RESULT = "bookmark_subscription_test_result";
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    public record Result(int bookmarks, int requests, long elapsedMillis, boolean failed) {
        public String bookmarkText() { return bookmarks < 0 ? "-" : Integer.toString(bookmarks); }
        public String requestText() { return requests < 0 ? "-" : Integer.toString(requests); }
        public String durationText() {
            return elapsedMillis < 0 ? "-" : String.format(Locale.ROOT, "%.3f", elapsedMillis / 1000.0);
        }
    }

    private final Executor mExecutor;
    private final Supplier<List<QuickSearch>> mBookmarks;
    private final Consumer<EhRequest> mExecuteRequest;
    private final LongSupplier mClock;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<BookmarkSubscriptionPlanner.Source> mQueue = new ArrayDeque<>();
    @Nullable private Runnable mListener;
    private boolean mRunning;
    private boolean mFailed;
    private int mBookmarkCount;
    private int mRequestCount;
    private int mActiveRequests;
    private long mStarted;

    public BookmarkSubscriptionTest(Context context) {
        this(EhApplication.getExecutorService(context), EhDB::getSubscribedQuickSearch,
                request -> EhApplication.getEhClient(context.getApplicationContext()).execute(request),
                SystemClock::elapsedRealtime);
    }

    BookmarkSubscriptionTest(Executor executor, Supplier<List<QuickSearch>> bookmarks,
                             Consumer<EhRequest> executeRequest, LongSupplier clock) {
        mExecutor = executor;
        mBookmarks = bookmarks;
        mExecuteRequest = executeRequest;
        mClock = clock;
    }

    public static Result getLastResult() {
        String value = Settings.getString(KEY_RESULT, "");
        try {
            String[] parts = value.split(",");
            if (parts.length == 4) {
                return new Result(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Long.parseLong(parts[2]), Boolean.parseBoolean(parts[3]));
            }
        } catch (NumberFormatException ignored) {
            // A missing or damaged measurement is displayed as a dash, never as zero.
        }
        return new Result(-1, -1, -1, false);
    }

    public boolean isRunning() { return mRunning; }

    public void setListener(@Nullable Runnable listener) { mListener = listener; }

    /** Continues across settings recreation; the application owns this instance. */
    public boolean start() {
        if (mRunning) return false;
        mRunning = true;
        mFailed = false;
        mBookmarkCount = -1;
        mRequestCount = 0;
        mActiveRequests = 0;
        mStarted = mClock.getAsLong();
        notifyChanged();
        mExecutor.execute(() -> {
            try {
                List<BookmarkSubscriptionPlanner.Source> sources =
                        BookmarkSubscriptionPlanner.plan(mBookmarks.get());
                mHandler.post(() -> {
                    mBookmarkCount = 0;
                    for (BookmarkSubscriptionPlanner.Source source : sources) {
                        mBookmarkCount += source.getBookmarks().size();
                    }
                    mQueue.addAll(sources);
                    pumpRequests();
                });
            } catch (Exception error) {
                mHandler.post(() -> {
                    mFailed = true;
                    mRequestCount = -1;
                    finish();
                });
            }
        });
        return true;
    }

    private void pumpRequests() {
        while (!mQueue.isEmpty() && mActiveRequests < MAX_CONCURRENT_REQUESTS) {
            BookmarkSubscriptionPlanner.Source source = mQueue.removeFirst();
            ListUrlBuilder builder = source.createBuilder();
            EhRequest request = new EhRequest()
                    .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
                    .setArgs(builder.build(), builder.getMode(), true, source.isMergedUploaderSearch())
                    .setCallback(new EhClient.Callback<GalleryListParser.Result>() {
                        private boolean mCompleted;
                        private void complete(boolean failed) {
                            if (mCompleted) return;
                            mCompleted = true;
                            mFailed |= failed;
                            mActiveRequests--;
                            pumpRequests();
                        }
                        @Override public void onSuccess(GalleryListParser.Result result) { complete(false); }
                        @Override public void onFailure(Exception error) { complete(true); }
                        @Override public void onCancel() { complete(true); }
                    });
            mActiveRequests++;
            mRequestCount++;
            try {
                mExecuteRequest.accept(request);
            } catch (Exception error) {
                request.getCallback().onFailure(error);
            }
        }
        if (mRunning && mQueue.isEmpty() && mActiveRequests == 0) finish();
    }

    private void finish() {
        long elapsed = Math.max(0L, mClock.getAsLong() - mStarted);
        Settings.putString(KEY_RESULT, mBookmarkCount + "," + mRequestCount + ","
                + elapsed + "," + mFailed);
        mRunning = false;
        notifyChanged();
    }

    private void notifyChanged() {
        if (mListener != null) mListener.run();
    }
}
