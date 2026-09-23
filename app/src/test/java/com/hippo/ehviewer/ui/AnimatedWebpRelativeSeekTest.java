package com.hippo.ehviewer.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AnimatedWebpRelativeSeekTest {
    @Test
    public void dragStartsAtCurrentPositionAndUsesDistance() {
        assertEquals(500, GalleryActivity.calculateAnimatedWebpSeekPosition(
                500, 0, 1000, 1001));
        assertEquals(700, GalleryActivity.calculateAnimatedWebpSeekPosition(
                500, 100, 1000, 1001));
        assertEquals(300, GalleryActivity.calculateAnimatedWebpSeekPosition(
                500, -100, 1000, 1001));
        assertEquals(1000, GalleryActivity.calculateAnimatedWebpSeekPosition(
                0, 500, 1000, 1001));
    }

    @Test
    public void dragClampsToDuration() {
        assertEquals(1000, GalleryActivity.calculateAnimatedWebpSeekPosition(
                900, 200, 1000, 1001));
        assertEquals(0, GalleryActivity.calculateAnimatedWebpSeekPosition(
                100, -200, 1000, 1001));
    }
}
