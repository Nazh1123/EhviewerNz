/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SubscriptionUpdateManagerTest {

    @Test
    public void parseGidsIgnoresInvalidAndDuplicateEntries() {
        Set<Long> expected = new HashSet<>(Arrays.asList(12L, 45L));

        assertEquals(expected, SubscriptionUpdateManager.parseGids(
                "12,broken,45,12,-3,0"));
    }

    @Test
    public void unreadGidsRoundTripWithoutLosingValues() {
        Set<Long> expected = new HashSet<>(Arrays.asList(1L, 8L, 999L));

        String serialized = SubscriptionUpdateManager.serializeGids(expected);

        assertEquals(expected, SubscriptionUpdateManager.parseGids(serialized));
        assertTrue(SubscriptionUpdateManager.parseGids(null).isEmpty());
        assertTrue(SubscriptionUpdateManager.parseGids("").isEmpty());
    }

    @Test
    public void nextAutomaticCheckHonorsCancelRetryWindow() {
        long lastCheckTime = 1_000L;
        long intervalCheckTime = lastCheckTime
                + SubscriptionUpdateManager.CHECK_INTERVAL_MS;
        long retryNotBeforeTime = intervalCheckTime + 30_000L;

        assertEquals(retryNotBeforeTime,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(
                        lastCheckTime, retryNotBeforeTime));
    }

    @Test
    public void nextAutomaticCheckUsesNormalIntervalAfterRetryExpiresFirst() {
        long lastCheckTime = 10_000L;
        long intervalCheckTime = lastCheckTime
                + SubscriptionUpdateManager.CHECK_INTERVAL_MS;

        assertEquals(intervalCheckTime,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(
                        lastCheckTime, 20_000L));
        assertEquals(0L,
                SubscriptionUpdateManager.calculateNextAutomaticCheckTime(0L, 0L));
    }

    @Test
    public void automaticCheckResultIsFreshThroughThirtySecondBoundary() {
        long completedRealtime = 5_000L;

        assertTrue(SubscriptionUpdateManager.isAutomaticCheckResultFresh(
                completedRealtime, completedRealtime));
        assertTrue(SubscriptionUpdateManager.isAutomaticCheckResultFresh(
                completedRealtime, completedRealtime
                        + SubscriptionUpdateManager.AUTOMATIC_CHECK_REUSE_WINDOW_MS));
        assertFalse(SubscriptionUpdateManager.isAutomaticCheckResultFresh(
                completedRealtime, completedRealtime
                        + SubscriptionUpdateManager.AUTOMATIC_CHECK_REUSE_WINDOW_MS + 1L));
        assertFalse(SubscriptionUpdateManager.isAutomaticCheckResultFresh(
                completedRealtime, completedRealtime - 1L));
    }
}
