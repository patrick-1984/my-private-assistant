package com.example

import android.service.voice.VoiceInteractionService
import android.util.Log

class AssistService : VoiceInteractionService() {
  override fun onReady() {
    super.onReady()
    Log.i(TAG, "AssistService onReady")
  }

  companion object {
    private const val TAG = "TVVoiceAssist"
  }
}
