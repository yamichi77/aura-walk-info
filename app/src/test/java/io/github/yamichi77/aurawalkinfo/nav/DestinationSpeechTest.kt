package io.github.yamichi77.aurawalkinfo.nav

import android.speech.SpeechRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationSpeechTest {
    @Test
    fun prefersStandardAndUsesOnDeviceOnlyWhenStandardIsUnavailable() = runTest {
        val standard = RecognitionMode.STANDARD
        val onDevice = RecognitionMode.ON_DEVICE
        val availability = listOf(setOf(standard, onDevice), setOf(standard), setOf(onDevice), emptySet())
        val expectedAttempts = listOf(listOf(standard), listOf(standard), listOf(onDevice), emptyList())
        for ((available, expected) in availability.zip(expectedAttempts)) {
            val attempts = mutableListOf<RecognitionMode>()
            val speech = FallbackDestinationSpeech(available::contains) { mode, _ ->
                attempts += mode
                RecognitionAttempt.Recognized("合成の施設")
            }
            val result = speech.listenWithDeadline {}
            assertEquals(expected, attempts)
            assertEquals(
                if (expected.isEmpty()) SpeechResult.Failed(SpeechUnavailable)
                else SpeechResult.Recognized("合成の施設"),
                result,
            )
        }
    }

    @Test
    fun allSixEligibleErrorsAllowExactlyOneOnDeviceAttempt() = runTest {
        for (error in listOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        )) {
            val attempts = mutableListOf<RecognitionMode>()
            val speech = FallbackDestinationSpeech({ true }) { mode, _ ->
                attempts += mode
                if (mode == RecognitionMode.STANDARD) RecognitionAttempt.Error(error)
                else RecognitionAttempt.Recognized("合成の施設")
            }
            assertEquals(SpeechResult.Recognized("合成の施設"), speech.listenWithDeadline {})
            assertEquals(listOf(RecognitionMode.STANDARD, RecognitionMode.ON_DEVICE), attempts)
        }
    }

    @Test
    fun preservesNetworkMessageWhenNoOnDeviceRecognizerIsAvailable() = runTest {
        val onlyStandard: (RecognitionMode) -> Boolean = { it == RecognitionMode.STANDARD }
        for ((error, message) in listOf(
            SpeechRecognizer.ERROR_NETWORK to "通信できません",
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to "通信できません",
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechUnavailable,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED to SpeechUnavailable,
        )) {
            val speech = FallbackDestinationSpeech(onlyStandard) { mode, _ ->
                assertEquals(RecognitionMode.STANDARD, mode)
                RecognitionAttempt.Error(error)
            }
            assertEquals(SpeechResult.Failed(message), speech.listenWithDeadline {})
        }
    }

    @Test
    fun failedOnDeviceAttemptIsFinalAndNoMatchOrPermissionErrorDoesNotRetry() = runTest {
        val attempts = mutableListOf<RecognitionMode>()
        val bothFail = FallbackDestinationSpeech({ true }) { mode, _ ->
            attempts += mode
            RecognitionAttempt.Error(
                if (mode == RecognitionMode.STANDARD) SpeechRecognizer.ERROR_NETWORK
                else SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            )
        }
        assertEquals(SpeechResult.Failed(SpeechUnavailable), bothFail.listenWithDeadline {})
        assertEquals(listOf(RecognitionMode.STANDARD, RecognitionMode.ON_DEVICE), attempts)

        for ((error, message) in listOf(
            SpeechRecognizer.ERROR_NO_MATCH to SpeechNotRecognized,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to MicrophoneDenied,
        )) {
            val speech = FallbackDestinationSpeech({ true }) { mode, _ ->
                assertEquals(RecognitionMode.STANDARD, mode)
                RecognitionAttempt.Error(error)
            }
            assertEquals(SpeechResult.Failed(message), speech.listenWithDeadline {})
        }
    }

    @Test
    fun fallbackSharesTheEightSecondBudgetWithStandardRecognition() = runTest {
        val times = mutableListOf<Pair<RecognitionMode, Long>>()
        var onDeviceStoppedAt: Long? = null
        val speech = FallbackDestinationSpeech({ true }) { mode, _ ->
            times += mode to testScheduler.currentTime
            if (mode == RecognitionMode.STANDARD) {
                delay(7_000)
                RecognitionAttempt.Error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            } else {
                try {
                    delay(1_001)
                    RecognitionAttempt.Recognized("期限後の結果")
                } finally {
                    onDeviceStoppedAt = testScheduler.currentTime
                }
            }
        }
        assertEquals(SpeechResult.Failed(SpeechNotRecognized), speech.listenWithDeadline {})
        assertEquals(listOf(RecognitionMode.STANDARD to 0L, RecognitionMode.ON_DEVICE to 7_000L), times)
        assertEquals(8_000L, onDeviceStoppedAt)
        assertEquals(8_000L, testScheduler.currentTime)
    }
}
