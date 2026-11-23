package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import io.github.yamichi77.aurawalkinfo.nav.DestinationSpeech
import io.github.yamichi77.aurawalkinfo.nav.MicrophoneDenied
import io.github.yamichi77.aurawalkinfo.nav.NavCandidate
import io.github.yamichi77.aurawalkinfo.nav.NavError
import io.github.yamichi77.aurawalkinfo.nav.NavFailure
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRepository
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavService
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.SpeechNotRecognized
import io.github.yamichi77.aurawalkinfo.nav.SpeechResult
import io.github.yamichi77.aurawalkinfo.nav.SpeechUnavailable
import io.github.yamichi77.aurawalkinfo.nav.navPoint
import io.github.yamichi77.aurawalkinfo.nav.syntheticNavRoute
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDimmed
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationHudTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun speechShowsThreeCandidatesThenSelectedRouteShowsBothGuidanceLines() {
        val f = Fixture()
        render(f)
        tap("map")
        text("目的地").assertIsDisplayed()
        compose.onNodeWithTag("nav_destination").performTouchInput { click() }
        compose.onNodeWithTag("bigmap").assertIsDisplayed()
        compose.onNodeWithTag("nav_destination").assertDoesNotExist()
        text("話してください").assertIsDisplayed()
        text("合成の途中結果").assertIsDisplayed()
        compose.runOnIdle { f.recognized.complete(SpeechResult.Recognized("合成の検索語")) }
        text("検索中…").assertIsDisplayed()
        compose.runOnIdle { f.finishSearch() }
        (1..3).forEach { text("合成候補$it").assertIsDisplayed() }
        text("合成候補4").assertDoesNotExist()
        text("450m ・ みほん市試験町").assertIsDisplayed()
        text("やめる").assertIsDisplayed()
        tap("nav_candidate_2")
        text("経路を取得中…").assertIsDisplayed()
        compose.runOnIdle { f.finishRoute() }
        text("ナビ終了").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("合成の検索語", f.query)
            assertEquals("合成候補2", (f.nav.state.value as NavState.Guiding).destination.name)
            assertEquals(10_000L, f.map.closesAt)
        }

        tap("bigmap")
        text("40m先 右").assertIsDisplayed()
        text("残り 91m・2分").assertIsDisplayed()
    }

    @Test
    fun errorsAppearForThreeSecondsThenRestoreDestinationControl() {
        val f = Fixture(MotionState.STOPPED)
        render(f)
        tap("map")
        f.permission = false
        tap("nav_destination")
        checkMessage(f, MicrophoneDenied)
        f.permission = true
        tap("nav_destination")
        compose.runOnIdle { f.recognized.complete(SpeechResult.Failed(SpeechUnavailable)) }
        checkMessage(f, SpeechUnavailable)

        for ((error, message) in listOf(
            NavError.NO_RESULTS to "見つかりませんでした",
            NavError.NETWORK to "通信できません",
            NavError.DAILY_LIMIT to "今日の検索の上限です",
            NavError.PERMISSION to "APIキー・権限を確認してください",
        )) {
            f.recognized = CompletableDeferred(SpeechResult.Recognized("合成"))
            tap("nav_destination")
            text("検索中…").assertIsDisplayed()
            compose.runOnIdle { f.failSearch(error) }
            checkMessage(f, message)
        }
        assertEquals(f.clock.elapsedRealtimeMillis() + 30_000L, f.map.closesAt)
    }

    @Test
    fun speechTimesOutAtEightSecondsAndMapCloseAlsoCancelsListening() {
        val f = Fixture(MotionState.STOPPED)
        render(f)
        tap("map")
        tap("nav_destination")
        advance(f, 7_999)
        text("話してください").assertIsDisplayed()
        advance(f, 1)
        text(SpeechNotRecognized).assertIsDisplayed()
        advance(f, 3_000)
        text(SpeechNotRecognized).assertDoesNotExist()
        f.recognized = CompletableDeferred()
        tap("nav_destination")
        text("話してください").assertIsDisplayed()
        tap("bigmap")
        text("話してください").assertDoesNotExist()
        assertEquals(2, f.speechExits)
        assertEquals(NavState.Idle, f.nav.state.value)
    }

    @Test
    fun searchKeepsStoppedMapOpenPastThirtySecondsThenWalkingClosesItAndDiscardsLateCandidates() {
        val f = Fixture(MotionState.STOPPED)
        render(f)
        tap("map")
        advance(f, 9_000)
        speak(f)
        text("検索中…").assertIsDisplayed()
        advance(f, 31_000)
        text("検索中…").assertIsDisplayed()
        compose.runOnIdle {
            f.motion = MotionState.WALKING
            f.refreshMap()
        }
        advance(f, 9_999)
        text("検索中…").assertIsDisplayed()
        advance(f, 1)
        compose.runOnIdle { f.finishSearch() }
        text("合成候補1").assertDoesNotExist()
        text("検索中…").assertDoesNotExist()
        assertEquals(NavState.Idle, f.nav.state.value)
    }

    @Test
    fun timeoutDuringRoutingClosesOnlyTheMapAndLateRouteStartsGuidance() {
        val f = Fixture(MotionState.STOPPED)
        render(f)
        tap("map")
        advance(f, 9_000)
        speak(f)
        compose.runOnIdle { f.finishSearch() }
        tap("nav_candidate_1")
        text("経路を取得中…").assertIsDisplayed()
        assertEquals(null, f.map.closesAt)
        compose.runOnIdle {
            f.motion = MotionState.WALKING
            f.refreshMap()
        }
        advance(f, 10_000)
        text("経路を取得中…").assertDoesNotExist()
        compose.onNodeWithTag("bigmap").assertDoesNotExist()
        compose.runOnIdle {
            f.fixes.tryEmit(syntheticFix(f.clock.elapsedRealtimeMillis()))
            f.finishRoute()
        }
        text("40m先 右").assertIsDisplayed()
        text("残り 91m・2分").assertIsDisplayed()
        text("ナビ終了").assertDoesNotExist()
    }

    @Test
    fun manuallyClosingDuringSearchDiscardsLateCandidates() {
        val f = Fixture()
        render(f)
        tap("map")
        speak(f)
        text("検索中…").assertIsDisplayed()
        tap("bigmap")
        compose.runOnIdle { f.finishSearch() }
        tap("map")
        text("合成候補1").assertDoesNotExist()
        text("目的地").assertIsDisplayed()
        assertEquals(NavState.Idle, f.nav.state.value)
    }

    @Test
    fun twoEndTapsWithinThreeSecondsCancelButOneTapExpires() {
        val f = Fixture()
        render(f)
        compose.runOnIdle { f.startGuidance() }
        tap("map")
        text("ナビ終了").assertIsDisplayed()
        tap("nav_destination")
        val layouts = mutableListOf<TextLayoutResult>()
        text("もう一度でナビ終了").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val confirmation = layouts.single()
        assertFalse(
            "Confirmation label must fit within ${confirmation.layoutInput.constraints}",
            confirmation.hasVisualOverflow,
        )
        assertTrue(f.nav.state.value is NavState.Guiding)
        advance(f, 3_000)
        text("ナビ終了").assertIsDisplayed()
        tap("nav_destination")
        advance(f, 2_999)
        tap("nav_destination")
        text("目的地").assertIsDisplayed()
        assertEquals(NavState.Idle, f.nav.state.value)
    }

    @Test
    fun noFixGraysBothLinesAndRerouteFailureKeepsTheLastTurn() {
        val f = Fixture()
        val route = sampleGuiding()
        val navState = mutableStateOf<NavState>(route)
        render(f, displayState = { navState.value })
        compose.runOnIdle { f.motion = MotionState.NO_FIX }
        for (line in listOf("40m先 右", "残り 91m・2分")) {
            val layouts = mutableListOf<TextLayoutResult>()
            text(line).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(HudDimmed, layouts.single().layoutInput.style.color)
            assertFalse(layouts.single().isLineEllipsized(0))
        }
        compose.runOnIdle {
            f.motion = MotionState.WALKING
            navState.value = NavState.Rerouting(route)
        }
        text("経路を取り直し中…").assertIsDisplayed()
        compose.runOnIdle { navState.value = route.copy(rerouteError = NavError.NETWORK) }
        text("40m先 右").assertIsDisplayed()
        text("通信できません").assertIsDisplayed()
    }

    @Test
    fun arrivalDisappearsAtTenSecondsAndFastMotionHidesGuidanceImmediately() {
        val f = Fixture()
        render(f)
        compose.runOnIdle { f.startGuidance() }
        text("40m先 右").assertIsDisplayed()
        compose.runOnIdle { f.motion = MotionState.FAST }
        text(HudVisualSpec.Fast.message).assertIsDisplayed()
        text("40m先 右").assertDoesNotExist()
        text("残り 91m・2分").assertDoesNotExist()
        compose.onNodeWithTag("map").assertDoesNotExist()

        advance(f, 1)
        compose.runOnIdle {
            f.motion = MotionState.WALKING
            f.fixes.tryEmit(syntheticFix(f.clock.elapsedRealtimeMillis(), northMeters = 39.0, eastMeters = 52.0))
        }
        text("到着しました").assertIsDisplayed()
        text("残り 91m・2分").assertDoesNotExist()
        advance(f, 9_999)
        text("到着しました").assertIsDisplayed()
        advance(f, 1)
        text("到着しました").assertDoesNotExist()
    }

    private fun render(f: Fixture, displayState: (() -> NavState)? = null) {
        compose.setContent {
            val state by f.nav.state.collectAsState()
            AdditiveDisplayTheme {
                PhoneScreen(
                    hudVisualSpecFor(
                        hudTestState(f.motion).copy(mapCenter = null),
                        navState = displayState?.invoke() ?: state, destinationPrompt = f.input.prompt,
                    ),
                    false, HudDebugOptions(), {}, f.map.isOpen, quantumEnabled = false, onTap = f::tap,
                )
            }
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    private fun speak(f: Fixture) {
        tap("nav_destination")
        compose.runOnIdle { f.recognized.complete(SpeechResult.Recognized("合成")) }
    }

    private fun checkMessage(f: Fixture, message: String) {
        text(message).assertIsDisplayed()
        advance(f, 2_999)
        text(message).assertIsDisplayed()
        advance(f, 1)
        text(message).assertDoesNotExist()
        text("目的地").assertIsDisplayed()
    }

    private fun advance(f: Fixture, millis: Long) = compose.runOnIdle {
        f.scope.advanceTimeBy(millis)
        f.scope.runCurrent()
    }

    private class Fixture(initialMotion: MotionState = MotionState.WALKING) {
        val scope = TestScope(UnconfinedTestDispatcher())
        val clock = Clock { scope.testScheduler.currentTime }
        var motion by mutableStateOf(initialMotion)
        var map by mutableStateOf(BigMapControl())
        var permission = true
        var recognized = CompletableDeferred<SpeechResult>()
        var speechExits = 0
        var query: String? = null
        private var search: Continuation<Result<List<NavCandidate>>>? = null
        private var route: Continuation<NavRoute>? = null
        private var closeTimer: Job? = null
        val fixes = MutableSharedFlow<LocationSample>(replay = 1).apply { tryEmit(syntheticFix(0)) }
        private val sampleRoute = syntheticNavRoute()
        private val candidates = (1..4).map {
            val offset = 449.0 + it
            NavCandidate("$it", "合成候補$it", "日本、〒000-0000 架空県みほん市試験町１丁目", sampleRoute.polyline.last(), offset)
        }
        private val service = object : NavService {
            override suspend fun search(query: String, origin: NavPoint): List<NavCandidate> {
                this@Fixture.query = query
                // Simulate a service that returns even after its caller has been cancelled.
                return suspendCoroutine<Result<List<NavCandidate>>> { search = it }.getOrThrow()
            }
            override suspend fun route(origin: NavPoint, destination: NavPoint): NavRoute =
                suspendCoroutine { route = it }
        }
        val nav = NavRepository(scope, service, fixes, clock)
        val input: DestinationInput = DestinationInput(
            scope, nav, DestinationSpeech { partial ->
                partial("合成の途中結果")
                try {
                    recognized.await()
                } finally {
                    speechExits++
                }
            },
            { permission }, clock, onInputChanged = ::refreshMap,
        ) { mapAcceptsInput() }

        private fun mapAcceptsInput() =
            map.advance(clock.elapsedRealtimeMillis(), motion, input.enteringDestination).isOpen

        fun tap(target: HudTarget) {
            val now = clock.elapsedRealtimeMillis()
            refreshMap()
            if (target.isDestinationInput) {
                fixes.tryEmit(syntheticFix(now))
                input.tap(target)
            } else updateMap(map.tap(target, now, motion, input.enteringDestination))
            scheduleClose()
        }

        fun refreshMap() {
            updateMap(map.advance(clock.elapsedRealtimeMillis(), motion, input.enteringDestination))
            scheduleClose()
        }

        private fun scheduleClose() {
            closeTimer?.cancel()
            map.closesAt?.let { deadline ->
                closeTimer = scope.launch {
                    delay(deadline - clock.elapsedRealtimeMillis())
                    refreshMap()
                }
            }
        }

        private fun updateMap(next: BigMapControl) {
            val wasOpen = map.isOpen
            map = next
            if (wasOpen && !next.isOpen) input.onMapClosed()
        }

        fun finishSearch() = completeSearch(Result.success(candidates))
        fun failSearch(error: NavError) = completeSearch(Result.failure(NavFailure(error)))
        private fun completeSearch(result: Result<List<NavCandidate>>) {
            val response = checkNotNull(search)
            search = null
            response.resume(result)
        }
        fun finishRoute() {
            val response = checkNotNull(route)
            route = null
            response.resume(sampleRoute)
        }
        fun startGuidance() {
            nav.navigateTo(syntheticFix(0, northMeters = 39.0, eastMeters = 52.0).navPoint())
            finishRoute()
        }
    }
}
