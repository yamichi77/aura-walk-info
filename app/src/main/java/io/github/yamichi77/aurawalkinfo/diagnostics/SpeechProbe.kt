package io.github.yamichi77.aurawalkinfo.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val JAPANESE = "ja-JP"

internal suspend fun runSpeechProbe(context: Context, showMessage: (String) -> Unit) = coroutineScope {
    val available = SpeechRecognizer.isRecognitionAvailable(context)
    val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val packages = context.packageManager.queryIntentServices(
        Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.ResolveInfoFlags.of(0),
    ).map { it.serviceInfo.packageName }.distinct().sorted()
    logProbe("speech recognizer ${if (available) "PASS" else "FAIL"} available=$available")
    logProbe("speech onDevice ${if (onDevice) "PASS" else "FAIL"} available=$onDevice")
    logProbe("speech services ${if (packages.isNotEmpty()) "PASS" else "FAIL"} count=${packages.size} packages=${packages.joinToString()}")

    val geocoder = async { geocodeCount(context) }
    val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, JAPANESE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    val permission = context.hasPermission(Manifest.permission.RECORD_AUDIO)
    logProbe("speech microphone ${if (permission) "PASS" else "FAIL"} granted=$permission")

    val recognized = try {
        when {
            onDevice -> withRecognizer(SpeechRecognizer.createOnDeviceSpeechRecognizer(context)) {
                reportJapaneseSupport(context, it, request)
                permission && listen(it, request, showMessage)
            }
            available && permission -> {
                logProbe("speech ja-JP SKIP onDevice=0")
                withRecognizer(SpeechRecognizer.createSpeechRecognizer(context)) {
                    listen(it, request, showMessage)
                }
            }
            else -> {
                logProbe("speech ja-JP SKIP onDevice=0")
                false
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logProbe("speech recognition FAIL error=${error.javaClass.simpleName}")
        false
    }
    if (!permission || !onDevice && !available) {
        logProbe("speech recognition SKIP partial=0 final=0")
    }
    val count = geocoder.await()
    logProbe("speech geocoder ${if (count != null && count > 0) "PASS" else "FAIL"} count=${count ?: 0}")
    logProbe("speech result ${if (recognized && count != null && count > 0) "PASS" else "FAIL"} " +
        "tokyoStation=$recognized geocoderCount=${count ?: 0}")
}

private suspend fun <T> withRecognizer(
    recognizer: SpeechRecognizer,
    work: suspend (SpeechRecognizer) -> T,
): T = try {
    work(recognizer)
} finally {
    recognizer.destroy()
}

private suspend fun reportJapaneseSupport(context: Context, recognizer: SpeechRecognizer, request: Intent) {
    val support = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine<RecognitionSupport?> { continuation ->
            recognizer.checkRecognitionSupport(request, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (continuation.isActive) continuation.resume(support)
                }

                override fun onError(error: Int) {
                    if (!continuation.isActive) return
                    logProbe("speech supportCallback FAIL error=$error")
                    continuation.resume(null)
                }
            })
        }
    }
    fun List<String>.hasJapanese() = any { Locale.forLanguageTag(it).language == "ja" }
    val installed = support?.installedOnDeviceLanguages?.hasJapanese() == true
    val downloadable = support?.supportedOnDeviceLanguages?.hasJapanese() == true
    val pending = support?.pendingOnDeviceLanguages?.hasJapanese() == true
    val status = when {
        support == null -> "SKIP"
        installed || downloadable || pending -> "PASS"
        else -> "FAIL"
    }
    logProbe("speech ja-JP $status installed=$installed downloadable=$downloadable pending=$pending")
}

private suspend fun listen(
    recognizer: SpeechRecognizer,
    request: Intent,
    showMessage: (String) -> Unit,
): Boolean {
    val done = CompletableDeferred<Unit>()
    var partial = false
    var final: String? = null
    var errorCode: Int? = null
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            showMessage("「東京駅」と話してください")
            logProbe("speech ready PASS listening=1")
        }

        override fun onPartialResults(results: Bundle?) {
            partial = partial || !results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).isNullOrEmpty()
        }

        override fun onResults(results: Bundle?) {
            final = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.replace(Regex("[\\r\\n]"), " ")
            done.complete(Unit)
        }

        override fun onError(error: Int) {
            errorCode = error
            done.complete(Unit)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    recognizer.startListening(request)
    if (withTimeoutOrNull(8_000) { done.await() } == null) {
        recognizer.stopListening()
        withTimeoutOrNull(2_000) { done.await() }
    }
    showMessage("認識終了。検索結果を確認中")
    val matched = final?.trim() == "東京駅"
    logProbe("speech recognition ${if (matched) "PASS" else "FAIL"} partial=${if (partial) 1 else 0} " +
        "final=${if (final.isNullOrEmpty()) 0 else 1} text=${final.orEmpty()} error=${errorCode ?: "NA"}")
    return matched
}

private suspend fun geocodeCount(context: Context): Int? = try {
    if (!Geocoder.isPresent()) null else withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { continuation ->
            Geocoder(context, Locale.JAPAN).getFromLocationName("東京駅", 3, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses.size)
                }

                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    logProbe("speech geocoderError FAIL error=${error.javaClass.simpleName}")
    null
}
