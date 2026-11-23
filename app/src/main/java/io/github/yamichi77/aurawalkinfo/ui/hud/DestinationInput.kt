package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yamichi77.aurawalkinfo.nav.DestinationSpeech
import io.github.yamichi77.aurawalkinfo.nav.MicrophoneDenied
import io.github.yamichi77.aurawalkinfo.nav.NavCandidate
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

internal sealed interface DestinationPrompt {
    data object Map : DestinationPrompt
    data object Permission : DestinationPrompt
    data class Listening(val partial: String = "") : DestinationPrompt
    data object Searching : DestinationPrompt
    data class Candidates(val items: List<NavCandidate>) : DestinationPrompt
    data object Routing : DestinationPrompt
    data class Message(val text: String) : DestinationPrompt
    data object ConfirmEnd : DestinationPrompt
}

internal class DestinationInput(
    private val scope: CoroutineScope,
    private val nav: NavRepository,
    private val speech: DestinationSpeech,
    private val requestMicrophone: suspend () -> Boolean,
    private val clock: Clock,
    private val mapAcceptsInput: () -> Boolean,
) {
    var prompt by mutableStateOf<DestinationPrompt>(DestinationPrompt.Map)
        private set
    private var work: Job? = null
    private var confirmUntil = 0L

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
            target == HudTarget.NAV_CANCEL && current is DestinationPrompt.Candidates -> close()
            current is DestinationPrompt.Candidates -> {
                val index = destinationCandidateTargets.indexOf(target)
                current.items.getOrNull(index)?.let(::route)
            }
        }
    }

    fun close() {
        work?.cancel()
        work = null
        when (prompt) {
            DestinationPrompt.Searching, is DestinationPrompt.Candidates, DestinationPrompt.Routing ->
                if (!nav.state.value.isNavigationActive) nav.cancel()
            else -> Unit
        }
        prompt = DestinationPrompt.Map
    }

    private fun listen() {
        prompt = DestinationPrompt.Permission
        work = scope.launch {
            val permitted = requestMicrophone()
            if (!mapAcceptsInput()) return@launch
            if (!permitted) {
                showMessage(MicrophoneDenied)
                return@launch
            }
            prompt = DestinationPrompt.Listening()
            val result = speech.listenWithDeadline { partial ->
                if (prompt is DestinationPrompt.Listening && mapAcceptsInput()) {
                    prompt = DestinationPrompt.Listening(partial)
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
        prompt = DestinationPrompt.Searching
        nav.search(query)
        val result = nav.state.first { it is NavState.Candidates || it is NavState.Error }
        if (!mapAcceptsInput()) return
        when (result) {
            is NavState.Candidates -> prompt = DestinationPrompt.Candidates(result.items)
            is NavState.Error -> {
                nav.cancel()
                showMessage(result.reason.hudMessage(searching = true))
            }
            else -> Unit
        }
    }

    private fun route(candidate: NavCandidate) {
        prompt = DestinationPrompt.Routing
        work = scope.launch {
            nav.selectCandidate(candidate, mapAcceptsInput)
            val result = nav.state.first { it.isNavigationActive || it is NavState.Error || it == NavState.Idle }
            if (!mapAcceptsInput()) return@launch
            if (result is NavState.Error) {
                nav.cancel()
                showMessage(result.reason.hudMessage(searching = false))
            } else prompt = DestinationPrompt.Map
        }
    }

    private fun confirmEnd() {
        work?.cancel()
        confirmUntil = clock.elapsedRealtimeMillis() + 3_000
        prompt = DestinationPrompt.ConfirmEnd
        work = scope.launch {
            delay(3_000)
            prompt = DestinationPrompt.Map
        }
    }

    private suspend fun showMessage(text: String) {
        prompt = DestinationPrompt.Message(text)
        delay(3_000)
        prompt = DestinationPrompt.Map
    }
}

internal val destinationCandidateTargets = listOf(
    HudTarget.NAV_CANDIDATE_1, HudTarget.NAV_CANDIDATE_2, HudTarget.NAV_CANDIDATE_3,
)

internal val HudTarget.isDestinationInput: Boolean
    get() = this == HudTarget.NAV_DESTINATION || this == HudTarget.NAV_CANCEL || this in destinationCandidateTargets
