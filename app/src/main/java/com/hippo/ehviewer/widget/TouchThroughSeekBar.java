/*
 * Copyright 2026 Hippo
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

package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatSeekBar;

public class TouchThroughSeekBar extends AppCompatSeekBar {

    private boolean mTouchHandlingEnabled = true;

    public TouchThroughSeekBar(Context context) {
        super(context);
    }

    public TouchThroughSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TouchThroughSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setTouchHandlingEnabled(boolean enabled) {
        mTouchHandlingEnabled = enabled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mTouchHandlingEnabled && super.onTouchEvent(event);
    }
}
