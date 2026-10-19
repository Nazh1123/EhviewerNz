package com.hippo.ehviewer.client;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;
import static com.hippo.ehviewer.client.GallerySeenStateTest.*;

public class SubscriptionProgressStoreTest {
    @Test public void successfulSourcesAdvanceIndependentlyAndSurviveRestart() {
        SubscriptionProgressStore store = new SubscriptionProgressStore("");
        String a = SubscriptionProgressStore.key("account", "sourceA");
        String b = SubscriptionProgressStore.key("account", "sourceB");
        store.complete(a, state(200));
        SubscriptionProgressStore restored = new SubscriptionProgressStore(store.serialize());
        assertEquals(200,marker(restored.get(a,state(100))));
        assertEquals(100,marker(restored.get(b,state(100))));
        assertTrue(restored.get(a,GallerySeenState.empty()).initialized());
        assertFalse(restored.get(b,GallerySeenState.empty()).initialized());
        restored.complete(a,state(150));
        assertEquals(200,marker(restored.get(a,GallerySeenState.empty())));
        assertEquals(300,marker(restored.get(a,state(300))));
    }

    @Test public void emptySuccessIsInitializedAndInvalidEntriesAreIgnored() {
        String key = SubscriptionProgressStore.key("a","b");
        SubscriptionProgressStore store = new SubscriptionProgressStore("broken\n" + key + ":oops\n");
        assertFalse(store.get(key,GallerySeenState.empty()).initialized());
        store.complete(key,GallerySeenState.empty().observe(java.util.List.of()));
        assertTrue(new SubscriptionProgressStore(store.serialize()).get(key,GallerySeenState.empty()).initialized());
        assertNotEquals(key,SubscriptionProgressStore.key("differentAccount","b"));
    }

    @Test public void onlyTransientFailuresReceiveBoundedBackoff() {
        IOException offline = new IOException("offline");
        assertEquals(2000,SubscriptionRetry.delayMillis(offline,0));
        assertEquals(5000,SubscriptionRetry.delayMillis(offline,1));
        assertEquals(-1,SubscriptionRetry.delayMillis(offline,2));
        assertEquals(-1,SubscriptionRetry.delayMillis(new IllegalArgumentException("invalid query"),0));
        assertTrue(SubscriptionRetry.isTransientStatus(503));
        assertTrue(SubscriptionRetry.isTransientStatus(408));
        assertFalse(SubscriptionRetry.isTransientStatus(403));
        assertFalse(SubscriptionRetry.isTransientStatus(429));
    }
}
