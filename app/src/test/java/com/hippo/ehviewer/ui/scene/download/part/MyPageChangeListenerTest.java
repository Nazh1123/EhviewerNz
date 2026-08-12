/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.ui.scene.download.part;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class MyPageChangeListenerTest {

    @Test
    public void firstDifferentPageAfterViewRecreationIsNotIgnored() {
        MyPageChangeListener listener = new MyPageChangeListener();
        AtomicInteger changedPage = new AtomicInteger(-1);
        listener.setIndexPage(2);
        listener.setNeedInitPage(true);
        listener.setPageChangeCallback(callback(changedPage));

        listener.onPageSelectedChanged(3, 2, 5, 500);

        assertEquals(3, listener.getIndexPage());
        assertEquals(3, changedPage.get());
        assertFalse(listener.isNeedInitPage());
    }

    @Test
    public void initializationCallbackForCurrentPageIsStillIgnored() {
        MyPageChangeListener listener = new MyPageChangeListener();
        AtomicInteger changedPage = new AtomicInteger(-1);
        listener.setIndexPage(2);
        listener.setNeedInitPage(true);
        listener.setPageChangeCallback(callback(changedPage));

        listener.onPageSelectedChanged(2, 1, 5, 500);

        assertEquals(2, listener.getIndexPage());
        assertEquals(-1, changedPage.get());
        assertFalse(listener.isNeedInitPage());
    }

    private static MyPageChangeListener.PageChangeCallback callback(AtomicInteger changedPage) {
        return new MyPageChangeListener.PageChangeCallback() {
            @Override
            public void onPageChanged(int newIndexPage) {
                changedPage.set(newIndexPage);
            }

            @Override
            public void onPageSizeChanged(int newPageSize) {
            }
        };
    }
}
