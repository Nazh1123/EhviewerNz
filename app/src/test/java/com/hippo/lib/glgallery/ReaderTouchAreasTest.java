package com.hippo.lib.glgallery;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReaderTouchAreasTest {
    @Test public void defaultsAreEqualThirdsAndRecommendationsKeepForkDimensions() {
        ReaderTouchAreas defaults = ReaderTouchAreas.defaults();
        assertEquals(1f / 3f, defaults.position(0), 0f);
        assertEquals(2f / 3f, defaults.position(1), 0f);
        assertEquals(.36f, ReaderTouchAreas.recommended().position(0), 0f);
        assertEquals(.64f, ReaderTouchAreas.recommended().position(1), 0f);
        assertEquals(ReaderKeyMap.CENTER_MENU, defaults.region(.35f, .2f, 1, 1));
        assertEquals(ReaderKeyMap.LEFT_TOP, ReaderTouchAreas.recommended().region(.35f, .2f, 1, 1));
    }

    @Test public void resizedRegionsTileTheViewportAndAgreeWithTheirBounds() {
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(0, .25f).withPosition(1, .8f)
                .withPosition(2, .7f).withPosition(3, .3f).withPosition(4, .2f).withPosition(5, .6f);
        for (int x = 0; x < 100; x++) for (int y = 0; y < 100; y++) {
            float nx = (x + .5f) / 100, ny = (y + .5f) / 100;
            int matches = 0, matched = -1;
            for (int region = 0; region < ReaderKeyMap.REGION_COUNT; region++) {
                float[] bounds = areas.bounds(region);
                if (nx >= bounds[0] && nx < bounds[2] && ny >= bounds[1] && ny < bounds[3]) {
                    matches++; matched = region;
                }
            }
            assertEquals(1, matches);
            assertEquals(matched, areas.region(nx * 1200, ny * 700, 1200, 700));
        }
        assertEquals(ReaderKeyMap.CENTER_MENU, areas.region(.25f, .2f, 1, 1));
        assertEquals(ReaderKeyMap.RIGHT_BOTTOM, areas.region(.8f, .3f, 1, 1));
        assertEquals(ReaderKeyMap.LEFT_BOTTOM, areas.region(.1f, .7f, 1, 1));
        assertEquals(ReaderKeyMap.CENTER_BOTTOM, areas.region(.5f, .6f, 1, 1));
        assertEquals(-1, areas.region(1, .5f, 1, 1));
        assertEquals(-1, areas.region(Float.NaN, .5f, 1, 1));
    }

    @Test public void draggingClampsAndMalformedStoredSizesRestoreACompleteLayout() {
        ReaderTouchAreas areas = ReaderTouchAreas.defaults();
        assertEquals(areas.max(0), areas.withPosition(0, 1).position(0), 0f);
        assertEquals(areas.min(5), areas.withPosition(5, 0).position(5), 0f);
        assertSame(areas, areas.withPosition(0, Float.NaN));
        assertSame(areas, ReaderTouchAreas.from(new float[]{.8f, .2f, .5f, .5f, .15f, .5f}, areas));
        float[] copy = areas.positions(); copy[0] = .1f;
        assertEquals(1f / 3f, areas.position(0), 0f);
    }

    @Test public void animationTakeoverIsTheUnionOfLowerBandAndResizedCenterBottom() {
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(0, .2f).withPosition(1, .75f);
        assertTrue(areas.isAnimatedControlArea(100, 700, 1000, 1000));
        assertFalse(areas.isAnimatedControlArea(100, 699, 1000, 1000));
        assertTrue(areas.isAnimatedControlArea(250, 800, 1000, 1000));
        assertTrue(areas.isAnimatedControlArea(700, 800, 1000, 1000));
        assertTrue(areas.isAnimatedControlArea(750, 800, 1000, 1000));
        assertFalse(areas.withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 1f).isAnimatedControlArea(100, 999, 1000, 1000));
        assertTrue(areas.withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 1f).isAnimatedControlArea(500, 500, 1000, 1000));
        assertFalse(areas.withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 1f).isAnimatedControlArea(500, 499, 1000, 1000));
        assertFalse(areas.isAnimatedControlArea(500, 100, 1000, 1000));
        ReaderTouchAreas lowerCenter = areas.withPosition(ReaderTouchAreas.CENTER_BOTTOM_SPLIT, .85f);
        assertTrue(lowerCenter.isAnimatedControlArea(500, 700, 1000, 1000));
        assertFalse(lowerCenter.isAnimatedControlArea(500, 699, 1000, 1000));
        assertTrue(lowerCenter.withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 1f).isAnimatedControlArea(500, 850, 1000, 1000));
        for (int direction = 0; direction < 3; direction++) {
            float width = direction == 2 ? 2000 : 1000, height = direction == 2 ? 500 : 1000;
            for (int x = 0; x < 100; x++) for (int y = 0; y < 100; y++) {
                float px = (x + .5f) / 100 * width, py = (y + .5f) / 100 * height;
                boolean expected = lowerCenter.region(px, py, width, height) == ReaderKeyMap.CENTER_BOTTOM
                        || py >= height * .7f;
                assertEquals(expected, lowerCenter.isAnimatedControlArea(px, py, width, height));
            }
        }
        assertFalse(areas.isAnimatedControlArea(1000, 999, 1000, 1000));
    }
}
