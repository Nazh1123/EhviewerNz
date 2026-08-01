package com.hippo.ehviewer.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class UpdateCheckPolicyTest {

    private static final long NOW = 2_000_000_000L;

    @Test
    public void checksImmediatelyWhenLastVersionIsMissing() {
        assertTrue(UpdateCheckPolicy.shouldCheck(
                NOW, null, "2.2.0.1.9", NOW));
    }

    @Test
    public void checksImmediatelyWhenAppVersionChanged() {
        assertTrue(UpdateCheckPolicy.shouldCheck(
                NOW, "2.2.0.3.0", "2.2.0.1.9", NOW));
    }

    @Test
    public void skipsRecentCheckForSameVersion() {
        assertFalse(UpdateCheckPolicy.shouldCheck(
                NOW - TimeUnit.HOURS.toMillis(23),
                "2.2.0.1.9", "2.2.0.1.9", NOW));
    }

    @Test
    public void checksDailyForSameVersion() {
        assertTrue(UpdateCheckPolicy.shouldCheck(
                NOW - TimeUnit.DAYS.toMillis(1),
                "2.2.0.1.9", "2.2.0.1.9", NOW));
    }
}
