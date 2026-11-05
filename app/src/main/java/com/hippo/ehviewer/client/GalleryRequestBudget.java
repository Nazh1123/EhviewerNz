package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.exception.CancelledException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** List HTML and gdata share eight task slots, including initial subscription loads. */
public final class GalleryRequestBudget {
    public static final int MAX_CONCURRENT = 8;
    static final GalleryRequestBudget SHARED = new GalleryRequestBudget(MAX_CONCURRENT);
    private final Semaphore slots;

    GalleryRequestBudget(int limit) { slots = new Semaphore(limit, true); }

    static boolean appliesTo(int method) {
        return method == EhClient.METHOD_GET_GALLERY_LIST
                || method == EhClient.METHOD_GET_GALLERY_LIST_HTML
                || method == EhClient.METHOD_PREPARE_GALLERY_LIST
                || method == EhClient.METHOD_PREPARE_SUBSCRIPTION_LIST
                || method == EhClient.METHOD_GET_DISPLAY_METADATA;
    }

    void acquire(BooleanSupplier cancelled) throws CancelledException {
        try {
            while (!cancelled.getAsBoolean()) {
                if (slots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    if (!cancelled.getAsBoolean()) return;
                    slots.release();
                    break;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        throw new CancelledException();
    }

    void release() { slots.release(); }
}
