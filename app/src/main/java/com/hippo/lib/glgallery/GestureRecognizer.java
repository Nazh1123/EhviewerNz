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

package com.hippo.lib.glgallery;

import android.content.Context;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.core.view.GestureDetectorCompat;

// This class aggregates three gesture detectors: GestureDetector,
// ScaleGestureDetector, and DownUpDetector.
class GestureRecognizer {
    @SuppressWarnings("unused")
    private static final String TAG = "GestureRecognizer";

    public interface Listener {
        boolean onSingleTapUp(float x, float y);
        boolean onSingleTapConfirmed(float x, float y);
        boolean onDoubleTap(float x, float y);
        boolean onDoubleTapConfirmed(float x, float y);
        boolean isDoubleTapRegion(float x, float y);
        void onLongPress(float x, float y);
        boolean onScroll(float dx, float dy, float totalX, float totalY, float x, float y);

        /**
         * @param velocityX Finger from top to bottom is positive
         * @param velocityY Finger from left to right is positive
         */
        boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY);
        boolean onScaleBegin(float focusX, float focusY);
        boolean onScale(float focusX, float focusY, float scale);
        void onScaleEnd();
        void onDown(float x, float y);
        void onUp();
        void onPointerDown(float x, float y);
        void onPointerUp();
    }

    private final GestureDetectorCompat mGestureDetector;
    private final GestureDetectorCompat mDoubleTapGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    private final DownUpDetector mDownUpDetector;
    private final Listener mListener;
    private final MyGestureListener mGestureListener;
    private final DoubleTapGestureListener mDoubleTapGestureListener;
    private boolean mPageAreaDoubleTapEnabled;
    private boolean mCurrentGestureUsesDoubleTap;
    private boolean mDoubleTapChainBroken;
    private boolean mPendingSingleTap;
    private float mPendingSingleTapX;
    private float mPendingSingleTapY;

    public GestureRecognizer(Context context, Listener listener) {
        mListener = listener;
        mGestureListener = new MyGestureListener();
        mGestureDetector = new GestureDetectorCompat(context, mGestureListener,
                null /* ignoreMultitouch */);
        // Keep the primary detector free from double-tap deferral. It handles scrolling,
        // long presses and taps that must respond immediately, such as page turns.
        mGestureDetector.setOnDoubleTapListener(null);
        mDoubleTapGestureListener = new DoubleTapGestureListener();
        mDoubleTapGestureDetector = new GestureDetectorCompat(
                context, mDoubleTapGestureListener, null /* handler */);
        mDoubleTapGestureDetector.setOnDoubleTapListener(mDoubleTapGestureListener);
        mScaleDetector = new ScaleGestureDetector(
                context, new MyScaleListener());
        mDownUpDetector = new DownUpDetector(new MyDownUpListener());
    }

    public void setPageAreaDoubleTapEnabled(boolean enabled) {
        mPageAreaDoubleTapEnabled = enabled;
    }

    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean useDoubleTap = mPageAreaDoubleTapEnabled
                    || mListener.isDoubleTapRegion(event.getX(), event.getY());
            if (useDoubleTap && mDoubleTapChainBroken) {
                finishPendingSingleTap();
                cancelDoubleTapGesture();
                mDoubleTapChainBroken = false;
            } else if (!useDoubleTap) {
                // Do not cancel a pending central-area tap immediately: it still needs its
                // confirmed-single callback. Break the chain before the next eligible tap.
                mDoubleTapChainBroken = true;
            }
            mCurrentGestureUsesDoubleTap = useDoubleTap;
        }

        mScaleDetector.onTouchEvent(event);
        if (mCurrentGestureUsesDoubleTap) {
            mDoubleTapGestureDetector.onTouchEvent(event);
        }
        mGestureDetector.onTouchEvent(event);
        mDownUpDetector.onTouchEvent(event);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mCurrentGestureUsesDoubleTap = false;
        }
    }

    public boolean isDown() {
        return mDownUpDetector.isDown();
    }

    public void cancelScale() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancelEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        mScaleDetector.onTouchEvent(cancelEvent);
        cancelEvent.recycle();
    }

    private void finishPendingSingleTap() {
        if (mPendingSingleTap) {
            mPendingSingleTap = false;
            mListener.onSingleTapConfirmed(mPendingSingleTapX, mPendingSingleTapY);
        }
    }

    private void cancelDoubleTapGesture() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancelEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        mDoubleTapGestureDetector.onTouchEvent(cancelEvent);
        cancelEvent.recycle();
    }

    private class MyGestureListener
                extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return mCurrentGestureUsesDoubleTap
                    || mListener.onSingleTapUp(e.getX(), e.getY());
        }

        @Override
        public void onLongPress(MotionEvent e) {
            mListener.onLongPress(e.getX(), e.getY());
        }

        @Override
        public boolean onScroll(
                MotionEvent e1, MotionEvent e2, float dx, float dy) {
            return mListener.onScroll(
                    dx, dy, e2.getX() - e1.getX(), e2.getY() - e1.getY(), e2.getX(), e2.getY());
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                float velocityY) {
            return mListener.onFling(e1, e2, velocityX, velocityY);
        }
    }

    private class DoubleTapGestureListener
            extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            mPendingSingleTap = true;
            mPendingSingleTapX = e.getX();
            mPendingSingleTapY = e.getY();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            mPendingSingleTap = false;
            return mListener.onSingleTapConfirmed(e.getX(), e.getY());
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            mPendingSingleTap = false;
            return mListener.onDoubleTap(e.getX(), e.getY());
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                return mListener.onDoubleTapConfirmed(e.getX(), e.getY());
            }
            return true;
        }
    }

    private class MyScaleListener
            extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return mListener.onScaleBegin(
                    detector.getFocusX(), detector.getFocusY());
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return mListener.onScale(detector.getFocusX(),
                    detector.getFocusY(), detector.getScaleFactor());
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mListener.onScaleEnd();
        }
    }

    private class MyDownUpListener implements DownUpDetector.DownUpListener {
        @Override
        public void onDown(MotionEvent e) {
            mListener.onDown(e.getX(), e.getY());
        }

        @Override
        public void onUp(MotionEvent e) {
            mListener.onUp();
        }

        @Override
        public void onPointerDown(MotionEvent e) {
            mListener.onPointerDown(e.getX(), e.getY());
        }

        @Override
        public void onPointerUp(MotionEvent e) {
            mListener.onPointerUp();
        }
    }
}
