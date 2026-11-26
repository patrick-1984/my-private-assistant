package com.example

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.example.audio.AssistAudioBridge
import com.example.data.SettingsRepository

class AssistSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession {
    return AssistSession(this)
  }
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)

    val summaryLines = mutableListOf<String>()
    val foundAudioPfd = extractAndLogBundle("args", args, summaryLines)
    val summary =
      if (summaryLines.isEmpty()) "onShow(flags=$showFlags): no bundle args"
      else "onShow(flags=$showFlags): " + summaryLines.joinToString("; ")
    Log.i(TAG, summary)
    SettingsRepository.get(context).setLastAssistArgsLog(summary)

    if (foundAudioPfd != null) {
      val duplicated = runCatching { foundAudioPfd.dup() }.getOrNull()
      AssistAudioBridge.offerStream(duplicated)
    } else {
      AssistAudioBridge.offerStream(null)
    }

    val sourcePackage = args?.getString(MainActivity.EXTRA_SOURCE_PACKAGE)
    Log.i(TAG, "AssistSession.onShow source_package=${sourcePackage ?: "(home/none)"}")

    val intent =
      Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
        putExtra(MainActivity.EXTRA_HAS_REMOTE_STREAM, foundAudioPfd != null)
        if (!sourcePackage.isNullOrBlank()) {
          putExtra(MainActivity.EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
      }
    context.startActivity(intent)
    finish()
  }

  @Suppress("DEPRECATION")
  private fun extractAndLogBundle(
    prefix: String,
    bundle: Bundle?,
    summaryOut: MutableList<String>,
  ): ParcelFileDescriptor? {
    if (bundle == null) return null
    var audioPfd: ParcelFileDescriptor? = null

    for (key in bundle.keySet()) {
      val value = bundle.get(key)
      val desc = "$prefix.$key=${value?.javaClass?.simpleName ?: "null"}"
      Log.i(TAG, "Bundle key: $desc -> $value")
      summaryOut.add(desc)

      if (value is Bundle) {
        val nested = extractAndLogBundle("$prefix.$key", value, summaryOut)
        if (audioPfd == null && nested != null) audioPfd = nested
      } else if (value is ParcelFileDescriptor) {
        if (key == AUDIO_STREAM_KEY || key.contains("audio", ignoreCase = true) || audioPfd == null) {
          audioPfd = value
        }
      }
    }

    // Explicit typed check for AUDIO_STREAM_KEY on modern APIs
    if (audioPfd == null && bundle.containsKey(AUDIO_STREAM_KEY)) {
      audioPfd =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          bundle.getParcelable(AUDIO_STREAM_KEY, ParcelFileDescriptor::class.java)
        } else {
          bundle.getParcelable(AUDIO_STREAM_KEY)
        }
    }

    return audioPfd
  }

  companion object {
    private const val TAG = "TVVoiceAssist"
    private const val AUDIO_STREAM_KEY = "AUDIO_STREAM_KEY"
  }
}
