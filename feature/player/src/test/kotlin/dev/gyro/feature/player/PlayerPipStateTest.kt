package dev.gyro.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPipStateTest {

    @Test
    fun screenLockWhileInPipDoesNotDismissSession() {
        assertFalse(
            shouldDismissPipOnStop(
                isActivityInPipMode = true,
                isInPipMode = true,
                isInteractive = false,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun configurationChangeWhileInPipDoesNotDismissSession() {
        assertFalse(
            shouldDismissPipOnStop(
                isActivityInPipMode = true,
                isInPipMode = true,
                isInteractive = true,
                isChangingConfigurations = true,
            )
        )
    }

    @Test
    fun interactiveStopWhileInPipDismissesSession() {
        assertTrue(
            shouldDismissPipOnStop(
                isActivityInPipMode = true,
                isInPipMode = false,
                isInteractive = true,
                isChangingConfigurations = false,
            )
        )
        assertTrue(
            shouldDismissPipOnStop(
                isActivityInPipMode = false,
                isInPipMode = true,
                isInteractive = true,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun interactiveStopOutsidePipDoesNotDismissSession() {
        assertFalse(
            shouldDismissPipOnStop(
                isActivityInPipMode = false,
                isInPipMode = false,
                isInteractive = true,
                isChangingConfigurations = false,
            )
        )
    }
}
