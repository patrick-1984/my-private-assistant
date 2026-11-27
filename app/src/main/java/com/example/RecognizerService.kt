package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.audio.RecordingOutcome
import com.example.audio.VoiceRecorder
import com.example.data.EndpointSlot
import com.example.data.SettingsRepository
import com.example.network.EndpointClient
import com.example.network.EndpointHealthManager
import com.example.network.TranscribeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecognizerService : RecognitionService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val settingsRepo by lazy { SettingsRepository.get(this) }
  private val healthManager by lazy { EndpointHealthManager.get(this) }
  private val endpointClient by lazy { EndpointClient() }

  private var activeRecorder: VoiceRecorder? = null
  private var activeJob: Job? = null

  override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
    Log.i(TAG, "onStartListening action=${recognizerIntent.action}")

    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      runCatching { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
      return
    }

    val settings = settingsRepo.settings.value
    if (settings.orderedActiveEndpoints().isEmpty()) {
      runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
      return
    }

    activeJob?.cancel()
    val recorder = VoiceRecorder(this)
    activeRecorder = recorder

    val extraPfd = extractAudioSourcePfd(recognizerIntent)
    val preCheckJob =
      settings.activeConfiguration?.let { cfg ->
        healthManager.onRecordingStarted(cfg, settings.endpointPresets, settings.activeAppPreset)
      }

    activeJob =
      scope.launch {
        runCatching { listener.readyForSpeech(Bundle()) }

        val recOutcome =
          recorder.record(
            micSource = settings.micSource,
            remoteStreamPfd = extraPfd,
            onSpeechStart = { scope.launch { runCatching { listener.beginningOfSpeech() } } },
            onRmsChanged = { rms -> scope.launch { runCatching { listener.rmsChanged(rms) } } },
          )

        runCatching { listener.endOfSpeech() }

        when (recOutcome) {
          is RecordingOutcome.Error -> {
            Log.w(TAG, "Recording error: ${recOutcome.message}")
            runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
          }
          is RecordingOutcome.Success -> {
            if (preCheckJob != null && preCheckJob.isActive) {
              preCheckJob.join()
            }
            val currentSettings = settingsRepo.settings.value
            val activeCfg = currentSettings.activeConfiguration
            val plan = healthManager.orderedFailoverPlan(activeCfg, currentSettings.endpointPresets)
            if (plan.isEmpty()) {
              runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
              return@launch
            }

            var finalResult: TranscribeResult? = null
            for ((slot, ep) in plan) {
              val res =
                endpointClient.transcribe(
                  wavBytes = recOutcome.wavBytes,
                  settings = ep.toProbeSettings(currentSettings.activeAppPreset),
                  onEndpointDetected = { url, family ->
                    settingsRepo.saveDetectedEndpointForPreset(ep.id, ep.cacheKey, url, family)
                  },
                  onEndpointInvalidated = {
                    settingsRepo.clearDetectedEndpointForPreset(ep.id)
                  },
                )
              when (res) {
                is TranscribeResult.Success -> {
                  healthManager.recordSuccess(ep)
                  healthManager.schedulePrimaryRecoveryIfUsingBackup(
                    config = activeCfg,
                    endpoints = currentSettings.endpointPresets,
                    appPreset = currentSettings.activeAppPreset,
                    usedSlot = slot,
                  )
                  finalResult = res
                  break
                }
                is TranscribeResult.Failure -> {
                  healthManager.recordFailure(ep, res.message)
                  finalResult = res
                }
              }
            }

            when (val result = finalResult) {
              is TranscribeResult.Success -> {
                val text = result.text.trim()
                if (text.isEmpty()) {
                  runCatching { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                } else {
                  val bundle =
                    Bundle().apply {
                      putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                      putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
                    }
                  runCatching { listener.results(bundle) }
                }
              }
              is TranscribeResult.Failure -> {
                Log.w(TAG, "Transcription error: ${result.message}")
                val code =
                  if (result.isAuthError) SpeechRecognizer.ERROR_CLIENT
                  else SpeechRecognizer.ERROR_NETWORK
                runCatching { listener.error(code) }
              }
              null -> runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            }
          }
        }
      }
  }

  override fun onStopListening(listener: Callback) {
    Log.i(TAG, "onStopListening")
    activeRecorder?.requestStop()
  }

  override fun onCancel(listener: Callback) {
    Log.i(TAG, "onCancel")
    activeRecorder?.requestStop()
    activeJob?.cancel()
    activeJob = null
  }

  override fun onDestroy() {
    super.onDestroy()
    activeRecorder?.requestStop()
    scope.cancel()
  }

  @Suppress("DEPRECATION")
  private fun extractAudioSourcePfd(intent: Intent): ParcelFileDescriptor? {
    val extras = intent.extras ?: return null
    val key =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RecognizerIntent.EXTRA_AUDIO_SOURCE
      } else {
        "android.speech.extra.AUDIO_SOURCE"
      }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      extras.getParcelable(key, ParcelFileDescriptor::class.java)
    } else {
      extras.getParcelable(key)
    }
  }

  companion object {
    private const val TAG = "TVVoiceRecognizer"
  }
}
