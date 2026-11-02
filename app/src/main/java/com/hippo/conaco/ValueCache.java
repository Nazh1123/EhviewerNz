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

package com.hippo.conaco;

import androidx.annotation.NonNull;
import android.util.LruCache;

import com.hippo.beerbelly.BeerBelly;
import com.hippo.streampipe.InputStreamPipe;

import java.io.OutputStream;

class ValueCache<V> extends BeerBelly<V> {

    private final ValueHelper<V> mHelper;
    private final LruCache<String, V> sharedMemory;
    private final java.util.function.Predicate<String> sharedKey;

    public ValueCache(BeerBellyParams params, ValueHelper<V> helper, int sharedBytes,
                      java.util.function.Predicate<String> sharedKey) {
        super(params);
        mHelper = helper;
        this.sharedKey = sharedKey;
        sharedMemory = params.hasMemoryCache && sharedBytes > 0 && sharedKey != null
                ? new LruCache<String, V>(sharedBytes) {
                    @Override protected int sizeOf(String key, V value) { return mHelper.sizeOf(key, value); }
                    @Override protected void entryRemoved(boolean evicted, String key, V oldValue, V newValue) {
                        mHelper.onRemoveFromMemoryCache(key, oldValue);
                    }
                } : null;
    }

    private boolean shared(String key) { return sharedMemory != null && sharedKey.test(key); }

    @Override public V getFromMemory(@NonNull String key) {
        return shared(key) ? sharedMemory.get(key) : super.getFromMemory(key);
    }

    @Override public boolean putToMemory(@NonNull String key, @NonNull V value) {
        if (!shared(key)) return super.putToMemory(key, value);
        // Never insert an oversized image only to evict/recycle it before delivery to its view.
        if (mHelper.sizeOf(key, value) > sharedMemory.maxSize()) return false;
        mHelper.onAddToMemoryCache(value);
        sharedMemory.put(key, value);
        return true;
    }

    @Override public void removeFromMemory(@NonNull String key) {
        if (shared(key)) sharedMemory.remove(key);
        else super.removeFromMemory(key);
    }

    @Override public int memorySize() {
        return super.memorySize() + (sharedMemory != null ? sharedMemory.size() : 0);
    }

    @Override public void clearMemory() {
        super.clearMemory();
        if (sharedMemory != null) sharedMemory.evictAll();
    }

    @Override
    protected int sizeOf(String key, V value) {
        return mHelper.sizeOf(key, value);
    }

    @Override
    protected void memoryEntryAdded(V value) {
        mHelper.onAddToMemoryCache(value);
    }

    @Override
    protected void memoryEntryRemoved(boolean evicted, String key, V oldValue, V newValue) {
        if (oldValue != null) {
            mHelper.onRemoveFromMemoryCache(key, oldValue);
        }
    }

    @Override
    protected V read(@NonNull InputStreamPipe isPipe,boolean hardware) {
        return mHelper.decode(isPipe,hardware);
    }

    @Override
    protected boolean write(OutputStream os, V value) {
        throw new UnsupportedOperationException("Not support write object");
    }
}
