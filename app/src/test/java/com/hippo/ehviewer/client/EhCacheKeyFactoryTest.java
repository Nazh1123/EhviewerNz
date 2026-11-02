package com.hippo.ehviewer.client;

import static org.junit.Assert.*;
import org.junit.Test;

public class EhCacheKeyFactoryTest {
    @Test public void rotatedHostsAndTimedTokensShareTheSameSheet() {
        String first = EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/old-token/123-0.webp");
        assertEquals(first, EhCacheKeyFactory.getNormalPreviewKey("https://b.hath.network/c2/new-token/123-0.webp"));
        assertTrue(EhCacheKeyFactory.isNormalPreviewKey(first));
        assertNotEquals(first, EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c1/token/123-0.webp"));
        assertNotEquals(first, EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/token/123-1.webp"));
        assertNotEquals(first, EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/token/124-0.webp"));
        assertNotEquals(first, EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/token/123-0.jpg"));
    }

    @Test public void unknownServersFormatsAndQueryParametersKeepTheirIdentity() {
        for (String prefix : new String[] {"https://example.com/c2/", "https://hath.network.evil/c2/",
                "https://a.hath.network/unknown/"}) {
            assertNotEquals(EhCacheKeyFactory.getNormalPreviewKey(prefix + "old/123-0.webp"),
                    EhCacheKeyFactory.getNormalPreviewKey(prefix + "new/123-0.webp"));
        }
        assertNotEquals(EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/t/123-0.webp?variant=a"),
                EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/t/123-0.webp?variant=b"));
        assertFalse(EhCacheKeyFactory.isNormalPreviewKey(EhCacheKeyFactory.getThumbKey(123)));
        assertFalse(EhCacheKeyFactory.isNormalPreviewKey(null));
    }
}
