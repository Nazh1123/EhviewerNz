package com.hippo.ehviewer.client;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class SubscriptionProgressStoreTest {
    @Test public void successfulSourcesAdvanceIndependentlyAndSurviveRestart() {
        SubscriptionProgressStore store = new SubscriptionProgressStore("");
        String a = SubscriptionProgressStore.key("account", "sourceA");
        String b = SubscriptionProgressStore.key("account", "sourceB");
        store.complete(a, 200);
        SubscriptionProgressStore restored = new SubscriptionProgressStore(store.serialize());
        assertEquals(200,restored.get(a,100,true).cursor());
        assertEquals(100,restored.get(b,100,true).cursor());
        assertTrue(restored.get(a,0,false).initialized());
        assertFalse(restored.get(b,0,false).initialized());
        restored.complete(a,150);
        assertEquals(200,restored.get(a,0,false).cursor());
        assertEquals(300,restored.get(a,300,true).cursor());
    }

    @Test public void emptySuccessIsInitializedAndInvalidEntriesAreIgnored() {
        String key = SubscriptionProgressStore.key("a","b");
        SubscriptionProgressStore store = new SubscriptionProgressStore("broken\n" + key + ":oops\n");
        assertFalse(store.get(key,0,false).initialized());
        store.complete(key,0);
        assertTrue(new SubscriptionProgressStore(store.serialize()).get(key,0,false).initialized());
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
