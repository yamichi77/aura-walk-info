package io.github.yamichi77.aurawalkinfo.nav

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface SpeechResult {
    data class Recognized(val query: String) : SpeechResult
    data class Failed(val message: String) : SpeechResult
}

internal fun interface DestinationSpeech {
    suspend fun listen(onPartial: (String) -> Unit): SpeechResult
}

internal const val MicrophoneDenied = "マイクの権限がありません"
internal const val SpeechUnavailable = "音声入力が使えません"
internal const val SpeechNotRecognized = "聞き取れませんでした"

internal suspend fun DestinationSpeech.listenWithDeadline(onPartial: (String) -> Unit): SpeechResult =
    withTimeoutOrNull(8_000) { listen(onPartial) } ?: SpeechResult.Failed(SpeechNotRecognized)

internal enum class RecognitionMode { STANDARD, ON_DEVICE }

internal sealed interface RecognitionAttempt {
    data class Recognized(val query: String) : RecognitionAttempt
    data class Error(val code: Int) : RecognitionAttempt
    data object Unavailable : RecognitionAttempt
}

internal class FallbackDestinationSpeech(
    private val isAvailable: (RecognitionMode) -> Boolean,
    private val recognize: suspend (RecognitionMode, (String) -> Unit) -> RecognitionAttempt,
) : DestinationSpeech {
    override suspend fun listen(onPartial: (String) -> Unit): SpeechResult {
        val standard = if (isAvailable(RecognitionMode.STANDARD)) {
            recognize(RecognitionMode.STANDARD, onPartial)
        } else RecognitionAttempt.Unavailable

        currentCoroutineContext().ensureActive()
        if (standard.canTryOnDevice() && isAvailable(RecognitionMode.ON_DEVICE)) {
            val onDevice = recognize(RecognitionMode.ON_DEVICE, onPartial)
            if (onDevice != RecognitionAttempt.Unavailable) return onDevice.toSpeechResult()
        }
        return standard.toSpeechResult(networkMessage = "通信できません")
    }
}

private fun RecognitionAttempt.canTryOnDevice() = when (this) {
    RecognitionAttempt.Unavailable -> true
    is RecognitionAttempt.Recognized -> false
    is RecognitionAttempt.Error -> when (code) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> true
        else -> false
    }
}

private fun RecognitionAttempt.toSpeechResult(networkMessage: String = SpeechUnavailable): SpeechResult = when (this) {
    is RecognitionAttempt.Recognized -> SpeechResult.Recognized(query)
    RecognitionAttempt.Unavailable -> SpeechResult.Failed(SpeechUnavailable)
    is RecognitionAttempt.Error -> SpeechResult.Failed(
        when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MicrophoneDenied
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> networkMessage
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechNotRecognized
            else -> SpeechUnavailable
        },
    )
}

internal class AndroidDestinationSpeech(private val context: Context) : DestinationSpeech {
    private val selection = FallbackDestinationSpeech(
        isAvailable = { mode ->
            when (mode) {
                RecognitionMode.STANDARD -> SpeechRecognizer.isRecognitionAvailable(context)
                RecognitionMode.ON_DEVICE -> SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }
        },
        recognize = ::recognize,
    )

    override suspend fun listen(onPartial: (String) -> Unit): SpeechResult = selection.listen(onPartial)

    private suspend fun recognize(mode: RecognitionMode, onPartial: (String) -> Unit): RecognitionAttempt {
        return try {
            val recognizer = when (mode) {
                RecognitionMode.STANDARD -> SpeechRecognizer.createSpeechRecognizer(context)
                RecognitionMode.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }
            try {
                recognize(recognizer, onPartial)
            } finally {
                recognizer.destroy()
            }
        } catch (_: SecurityException) {
            RecognitionAttempt.Error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        } catch (_: UnsupportedOperationException) {
            RecognitionAttempt.Unavailable
        }
    }

    private suspend fun recognize(
        recognizer: SpeechRecognizer,
        onPartial: (String) -> Unit,
    ): RecognitionAttempt {
        val result = CompletableDeferred<RecognitionAttempt>()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(results: Bundle?) {
                if (!result.isCompleted) results.firstPhrase()?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                result.complete(results.firstPhrase()?.let(RecognitionAttempt::Recognized)
                    ?: RecognitionAttempt.Error(SpeechRecognizer.ERROR_NO_MATCH))
            }

            override fun onError(error: Int) {
                result.complete(RecognitionAttempt.Error(error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
            return result.await()
        } finally {
            if (result.isActive) {
                result.cancel()
                // Late callbacks must not update an input session that has already closed.
                recognizer.cancel()
            }
        }
    }
}

private fun Bundle?.firstPhrase() =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        ?.takeIf(String::isNotEmpty)
