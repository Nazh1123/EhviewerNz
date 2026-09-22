package com.hippo.ehviewer.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnimatedWebpLongPressSpeedTest {
    @Test
    public void customSpeedIsAnExtraEquallySpacedStep() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(2.2f, 1f);
        speed.begin(100, 0);
        assertEquals(2.2f, speed.getSpeed(), 0f);
        assertTrue(speed.move(132, 100));
        assertEquals(2.5f, speed.getSpeed(), 0f);
        assertTrue(speed.move(100, 200));
        assertEquals(2.2f, speed.getSpeed(), 0f);
        assertTrue(speed.move(68, 300));
        assertEquals(2.0f, speed.getSpeed(), 0f);
    }

    @Test
    public void halfStepSettingIsNotDuplicatedAndLargeMovesCrossSeveralSteps() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(2.5f, 1f);
        speed.begin(0, 0);
        assertTrue(speed.move(-32, 100));
        assertEquals(2f, speed.getSpeed(), 0f);
        assertTrue(speed.move(-128, 200));
        assertEquals(0.5f, speed.getSpeed(), 0f);
    }

    @Test
    public void subStepMovementDoesNotExtendInitialThreeSecondWindow() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(2.2f, 2f);
        speed.begin(100, 1000);
        assertFalse(speed.move(163, 2000));
        assertFalse(speed.move(37, 3999));
        assertEquals(4000, speed.getDeadline());
        assertTrue(speed.isAdjustable(3999));
        assertFalse(speed.move(164, 4000));
        assertFalse(speed.move(300, 5000));
        assertEquals(2.2f, speed.getSpeed(), 0f);
    }

    @Test
    public void fullStepsResetIdleTimeoutButJitterDoesNot() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(1.5f, 1f);
        speed.begin(0, 0);
        assertTrue(speed.move(32, 2900));
        assertEquals(4400, speed.getDeadline());
        assertTrue(speed.move(64, 4399));
        assertEquals(5899, speed.getDeadline());
        assertFalse(speed.move(63, 5898));
        assertTrue(speed.isAdjustable(5898));
        assertFalse(speed.move(96, 5899));
        assertEquals(2.5f, speed.getSpeed(), 0f);
    }

    @Test
    public void earlyMovementUsesIdleTimeoutInsteadOfInitialTimeout() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(2.2f, 1f);
        speed.begin(0, 0);
        assertTrue(speed.move(32, 100));
        assertFalse(speed.isAdjustable(1600));
    }

    @Test
    public void nextHoldRetainsSelectionAndReopensAdjustmentIncludingAtOneTimes() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(1.5f, 1f);
        speed.begin(0, 0);
        assertTrue(speed.move(-32, 100));
        assertFalse(speed.isAdjustable(1600));
        speed.begin(200, 5000);
        assertEquals(1f, speed.getSpeed(), 0f);
        assertTrue(speed.isAdjustable(5000));
        assertTrue(speed.move(232, 5100));
        assertEquals(1.5f, speed.getSpeed(), 0f);
        assertEquals(1.5f, new AnimatedWebpLongPressSpeed(1.5f, 1f).getSpeed(), 0f);
    }

    @Test
    public void endpointsClampAndAllowImmediateReversal() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(2.2f, 1f);
        speed.begin(0, 0);
        assertTrue(speed.move(320, 100));
        assertEquals(3f, speed.getSpeed(), 0f);
        assertFalse(speed.move(384, 200));
        assertEquals(1600, speed.getDeadline());
        assertTrue(speed.move(352, 300));
        assertEquals(2.5f, speed.getSpeed(), 0f);
        assertTrue(speed.move(-320, 400));
        assertEquals(0.5f, speed.getSpeed(), 0f);
        assertTrue(speed.move(-288, 500));
        assertEquals(1f, speed.getSpeed(), 0f);
    }

    @Test
    public void configuredSpeedBelowHalfIsPreservedAsLowestStep() {
        AnimatedWebpLongPressSpeed speed = new AnimatedWebpLongPressSpeed(0.1f, 1f);
        speed.begin(0, 0);
        assertTrue(speed.move(32, 100));
        assertEquals(0.5f, speed.getSpeed(), 0f);
        assertTrue(speed.move(0, 200));
        assertEquals(0.1f, speed.getSpeed(), 0f);
        assertFalse(speed.move(-32, 300));
    }
}
