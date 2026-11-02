package com.hippo.ehviewer.superresolution

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34])
class SuperResolutionScaleTest {
    private val settings get() = SuperResolutionSettings(RuntimeEnvironment.getApplication())
    @Before fun clear() { settings.preferences.edit().clear().commit() }

    @Test fun automaticUsesTheSmallestSupportedScaleAtThresholdsInEitherOrientation() {
        for ((shortSide, expected) in listOf(1 to 8, 149 to 8, 150 to 8, 299 to 8,
            300 to 4, 599 to 4, 600 to 2, 1200 to 2, 32768 to 2)) {
            assertEquals(expected, SuperResolutionScale.AUTO.resolve(shortSide, 32768))
            assertEquals(expected, SuperResolutionScale.AUTO.resolve(32768, shortSide))
        }
    }

    @Test fun defaultAndInvalidPreferencesPreserveTwoTimesAndValidSelectionsPersist() {
        assertEquals(SuperResolutionScale.X2, settings.read().scale)
        for (scale in SuperResolutionScale.entries) {
            settings.preferences.edit().putString(SuperResolutionSettings.SCALE, scale.value).commit()
            assertEquals(scale, settings.read().scale)
        }
        settings.preferences.edit().putString(SuperResolutionSettings.SCALE, "3").commit()
        assertEquals(SuperResolutionScale.X2, settings.read().scale)
        assertEquals(2, SuperResolutionScale.X2.resolve(32, 48))
        assertEquals(4, SuperResolutionScale.X4.resolve(1200, 1600))
        assertEquals(8, SuperResolutionScale.X8.resolve(1200, 1600))
    }
}
