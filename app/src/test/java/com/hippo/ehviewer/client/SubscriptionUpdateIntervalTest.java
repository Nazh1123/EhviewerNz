package com.hippo.ehviewer.client;

import android.app.Application;

import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionUpdateIntervalTest {
    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL, null);
    }

    @Test
    public void missingOrInvalidStoredIntervalDefaultsToSixtyMinutes() {
        assertEquals(60, Settings.getAutoSubscriptionUpdateIntervalMinutes());
        assertEquals(3_600_000L, Settings.getAutoSubscriptionUpdateIntervalMillis());
        for (String invalid : new String[]{"", "0", "-1", "1.5", "minutes", "2147483648", "+5"}) {
            assertNull(Settings.normalizeAutoSubscriptionUpdateInterval(invalid));
            Settings.putString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL, invalid);
            assertEquals(60, Settings.getAutoSubscriptionUpdateIntervalMinutes());
        }
    }

    @Test
    public void intervalChangesRescheduleRelativeToLastCheckAndPreserveCancellationDelay() {
        long lastCheck = 1_790_000_000_000L;
        assertEquals(lastCheck + 3_600_000L,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(lastCheck, 0L));
        Settings.putString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL, "15");
        assertEquals(lastCheck + 900_000L,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(lastCheck, 0L));
        long retryNotBefore = lastCheck + 1_000_000L;
        assertEquals(retryNotBefore,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(lastCheck, retryNotBefore));
        assertEquals(0L, SubscriptionUpdateManager.calculateNextAutomaticCheckTime(0L, 0L));
        assertEquals(retryNotBefore,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(0L, retryNotBefore));
    }

    @Test
    public void normalizedAndLargeIntegerMinutesDoNotOverflowMilliseconds() {
        assertEquals("60", Settings.normalizeAutoSubscriptionUpdateInterval(" 0060 "));
        Settings.putString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL, "2147483647");
        assertEquals(Integer.MAX_VALUE, Settings.getAutoSubscriptionUpdateIntervalMinutes());
        assertEquals(128_849_018_820_000L, Settings.getAutoSubscriptionUpdateIntervalMillis());
    }

    @Test public void homepageSelectionUsesStrictThresholdsAndFractionalMinutes() {
        long last = 1_790_000_000_000L;
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(8, last, last));
        assertTrue(SubscriptionUpdateManager.shouldUseHomepageScan(9, last, last));
        assertTrue(SubscriptionUpdateManager.shouldUseHomepageScan(9, last, last + 30 * 60_000L - 1));
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(9, last, last + 30 * 60_000L));
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(9, last, last + 30 * 60_000L + 1));
        assertTrue(SubscriptionUpdateManager.shouldUseHomepageScan(120, last, last + 400 * 60_000L - 1));
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(121, last, last + 400 * 60_000L));
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(1000, last, last + 401 * 60_000L));
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(1000, 0, last));
        assertFalse(SubscriptionUpdateManager.shouldUseHomepageScan(1000, last, last - 1));
    }
}
