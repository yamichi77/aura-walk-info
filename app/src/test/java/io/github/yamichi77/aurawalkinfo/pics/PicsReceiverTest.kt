package io.github.yamichi77.aurawalkinfo.pics

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PicsReceiverTest {
    private val action = "io.github.yamichi77.aurawalkinfo.PICS_REPLAY"

    @Test
    fun debugReplayPublishesExpiresAndClearsWithForegroundLifecycle() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val receiver = PicsReceiver(app, Clock { testScheduler.currentTime }, debuggable = true)
        val fakeState = WalkSessionState(motionState = MotionState.STOPPED, mapCenter = MapCenter(0.0, 0.0))
        val foreground = backgroundScope.launch {
            receiver.runForeground(MutableStateFlow(WalkSessionState()), fakeState)
        }
        runCurrent()
        val registered = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(action) }
        assertEquals(Manifest.permission.DUMP, registered.broadcastPermission)
        fun replay(scenario: String) {
            registered.broadcastReceiver.onReceive(app, Intent(action).putExtra("scenario", scenario))
            runCurrent()
        }

        replay("red_to_green")
        assertEquals(PicsLight(PicsAspect.RED, 3), receiver.candidate.value?.lights?.single())
        advanceTimeBy(2_400)
        runCurrent()
        assertEquals(PicsAspect.GREEN, receiver.candidate.value?.lights?.single()?.aspect)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(PicsFreshness.STALE, receiver.candidate.value?.freshness)
        advanceTimeBy(1_501)
        runCurrent()
        assertNull(receiver.candidate.value)

        replay("far")
        assertNull(receiver.candidate.value)
        replay("stale")
        assertEquals(PicsFreshness.FRESH, receiver.candidate.value?.freshness)
        replay("stop")
        assertNull(receiver.candidate.value)
        replay("stale")
        foreground.cancelAndJoin()
        assertNull(receiver.candidate.value)
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(action) })
    }

    @Test
    fun releaseConfigurationSkipsReplayRegistration() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val receiver = PicsReceiver(app, Clock { testScheduler.currentTime }, debuggable = false)
        backgroundScope.launch { receiver.runForeground(MutableStateFlow(WalkSessionState()), null) }
        runCurrent()
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(action) })
    }
}
