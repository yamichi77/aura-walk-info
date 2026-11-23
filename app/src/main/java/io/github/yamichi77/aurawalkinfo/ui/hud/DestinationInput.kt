package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yamichi77.aurawalkinfo.location.JapaneseAddressFormatter
import io.github.yamichi77.aurawalkinfo.location.ReverseGeocoder
import io.github.yamichi77.aurawalkinfo.nav.DestinationSpeech
import io.github.yamichi77.aurawalkinfo.nav.MicrophoneDenied
import io.github.yamichi77.aurawalkinfo.nav.NavCandidate
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRepository
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.SpeechResult
import io.github.yamichi77.aurawalkinfo.nav.listenWithDeadline
import io.github.yamichi77.aurawalkinfo.session.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface DestinationPrompt {
    data object Map : DestinationPrompt
    data object Permission : DestinationPrompt
    data class Listening(val partial: String = "") : DestinationPrompt
    data object Searching : DestinationPrompt
    data class Candidates(val items: List<NavCandidate>) : DestinationPrompt
    data object Routing : DestinationPrompt
    data class Preview(val route: NavState.Preview) : DestinationPrompt
    data class Message(val text: String) : DestinationPrompt
    data object ConfirmEnd : DestinationPrompt
}

internal class DestinationInput(
    private val scope: CoroutineScope,
    private val nav: NavRepository,
    private val speech: DestinationSpeech,
    private val requestMicrophone: suspend () -> Boolean,
    private val clock: Clock,
    private val onInputChanged: () -> Unit = {},
    private val onGuidanceStarted: () -> Unit = {},
    private val mapAcceptsInput: () -> Boolean,
) {
    var prompt by mutableStateOf<DestinationPrompt>(DestinationPrompt.Map)
        private set
    val enteringDestination: Boolean get() = when (prompt) {
        is DestinationPrompt.Listening, DestinationPrompt.Searching, is DestinationPrompt.Candidates,
        DestinationPrompt.Routing, is DestinationPrompt.Preview, is DestinationPrompt.Message -> true
        else -> false
    }
    private var work: Job? = null
    private var confirmUntil = 0L

    private fun show(next: DestinationPrompt) {
        val wasEntering = enteringDestination
        prompt = next
        if (enteringDestination != wasEntering) onInputChanged()
    }

    fun tap(target: HudTarget) {
        if (!mapAcceptsInput()) return
        val current = prompt
        when {
            target == HudTarget.NAV_DESTINATION && current == DestinationPrompt.Map -> {
                if (nav.state.value.isNavigationActive) confirmEnd() else listen()
            }
            target == HudTarget.NAV_DESTINATION && current == DestinationPrompt.ConfirmEnd -> {
                if (nav.state.value.isNavigationActive && clock.elapsedRealtimeMillis() < confirmUntil) {
                    close()
                    nav.cancel()
                } else if (nav.state.value.isNavigationActive) {
                    confirmEnd()
                } else {
                    close()
                }
            }
            target == HudTarget.NAV_CANCEL && (current is DestinationPrompt.Candidates ||
                current is DestinationPrompt.Preview) -> close()
            target == HudTarget.NAV_START && current is DestinationPrompt.Preview -> {
                nav.startGuidance()
                onGuidanceStarted()
                show(DestinationPrompt.Map)
            }
            current is DestinationPrompt.Candidates -> {
                val index = destinationCandidateTargets.indexOf(target)
                current.items.getOrNull(index)?.let(::route)
            }
        }
    }

    fun onMapClosed() = close()

    fun selectMapPoint(point: NavPoint, geocoder: ReverseGeocoder) {
        if (!mapAcceptsInput() || prompt != DestinationPrompt.Map) return
        show(DestinationPrompt.Routing)
        work = scope.launch {
            val address = withTimeoutOrNull(3_000L) { geocoder.lookup(point.latitude, point.longitude) }
            val name = address?.let(JapaneseAddressFormatter::oneLine)?.takeIf(String::isNotBlank)
                ?: "地図で選んだ場所"
            nav.previewTo(point, name).join()
            awaitRoute()
        }
    }

    fun close() {
        work?.cancel()
        work = null
        when (prompt) {
            DestinationPrompt.Searching, is DestinationPrompt.Candidates, DestinationPrompt.Routing,
            is DestinationPrompt.Preview ->
                if (!nav.state.value.isNavigationActive) nav.cancel()
            else -> Unit
        }
        show(DestinationPrompt.Map)
    }

    private fun listen() {
        show(DestinationPrompt.Permission)
        work = scope.launch {
            val permitted = requestMicrophone()
            if (!mapAcceptsInput()) return@launch
            if (!permitted) {
                showMessage(MicrophoneDenied)
                return@launch
            }
            show(DestinationPrompt.Listening())
            val result = speech.listenWithDeadline { partial ->
                if (prompt is DestinationPrompt.Listening && mapAcceptsInput()) {
                    show(DestinationPrompt.Listening(partial))
                }
            }
            if (!mapAcceptsInput()) return@launch
            when (result) {
                is SpeechResult.Recognized -> search(result.query)
                is SpeechResult.Failed -> showMessage(result.message)
            }
        }
    }

    private suspend fun search(query: String) {
        show(DestinationPrompt.Searching)
        nav.search(query)
        val result = nav.state.first { it is NavState.Candidates || it is NavState.Error }
        if (!mapAcceptsInput()) return
        when (result) {
            is NavState.Candidates -> show(DestinationPrompt.Candidates(result.items))
            is NavState.Error -> {
                nav.cancel()
                showMessage(result.reason.hudMessage(searching = true))
            }
            else -> Unit
        }
    }

    private fun route(candidate: NavCandidate) {
        show(DestinationPrompt.Routing)
        val start = nav.selectCandidate(candidate)
        work = scope.launch {
            start.join()
            awaitRoute()
        }
    }

    private suspend fun awaitRoute() {
        val result = nav.state.first { it is NavState.Preview || it is NavState.Error || it == NavState.Idle }
        if (result is NavState.Preview && mapAcceptsInput()) {
            show(DestinationPrompt.Preview(result))
            return
        }
        if (result is NavState.Preview) nav.cancel()
        if (result is NavState.Error) {
            nav.cancel()
            if (mapAcceptsInput()) {
                showMessage(result.reason.hudMessage(searching = false))
                return
            }
        }
        show(DestinationPrompt.Map)
    }

    private fun confirmEnd() {
        work?.cancel()
        confirmUntil = clock.elapsedRealtimeMillis() + 3_000
        show(DestinationPrompt.ConfirmEnd)
        work = scope.launch {
            delay(3_000)
            show(DestinationPrompt.Map)
        }
    }

    private suspend fun showMessage(text: String) {
        show(DestinationPrompt.Message(text))
        delay(3_000)
        show(DestinationPrompt.Map)
    }
}

internal val destinationCandidateTargets = listOf(
    HudTarget.NAV_CANDIDATE_1, HudTarget.NAV_CANDIDATE_2, HudTarget.NAV_CANDIDATE_3,
)

internal val HudTarget.isDestinationInput: Boolean
    get() = this == HudTarget.NAV_DESTINATION || this == HudTarget.NAV_START ||
        this == HudTarget.NAV_CANCEL || this in destinationCandidateTargets
