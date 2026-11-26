package com.example

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

class AssistService : VoiceInteractionService() {
  override fun onReady() {
    super.onReady()
    activeInstance = this
    Log.i(TAG, "AssistService onReady")
  }

  override fun onShutdown() {
    if (activeInstance === this) {
      activeInstance = null
    }
    super.onShutdown()
  }

  companion object {
    private const val TAG = "TVVoiceAssist"

    @Volatile private var activeInstance: AssistService? = null

    fun tryShowSession(context: Context, args: Bundle = Bundle()): Boolean {
      if (!AssistantStatusChecker.isVoiceInteractionActive(context)) {
        return false
      }
      val service = activeInstance ?: return false
      return try {
        service.showSession(args, 0)
        Log.i(TAG, "Invoked AssistService.showSession(args, 0)")
        true
      } catch (e: Exception) {
        Log.w(TAG, "AssistService.showSession failed: ${e.message}")
        false
      }
    }
  }
}
