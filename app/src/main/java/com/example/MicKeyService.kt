package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.data.SettingsRepository
import com.example.handoff.ForegroundAppTracker

/**
 * Global remote mic button interceptor.
 *
 * Uses Option 1a for current-app detection: receives ONLY AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
 * to read event.packageName (with canRetrieveWindowContent="false"), keeping screen content
 * completely unread while knowing which app is in the foreground before MainActivity opens.
 */
class MicKeyService : AccessibilityService() {

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.i(TAG, "MicKeyService connected")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      ForegroundAppTracker.onWindowPackageChanged(this, event.packageName?.toString())
    }
  }

  override fun onInterrupt() {
    // Nothing to interrupt
  }

  override fun onKeyEvent(event: KeyEvent): Boolean = handleKeyEvent(event)

  internal fun handleKeyEvent(event: KeyEvent): Boolean {
    if (!SettingsRepository.get(this).settings.value.micButtonCapture) {
      return false
    }

    val code = event.keyCode
    val isMicKey =
      code == KeyEvent.KEYCODE_SEARCH ||
        code == KeyEvent.KEYCODE_ASSIST ||
        code == KeyEvent.KEYCODE_VOICE_ASSIST

    if (!isMicKey) {
      return false
    }

    return when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount == 0) {
          startAssistantFlow()
        }
        true
      }
      KeyEvent.ACTION_UP -> true
      else -> true
    }
  }

  private fun startAssistantFlow() {
    val sourcePackage = ForegroundAppTracker.currentSourcePackage()
    Log.i(TAG, "Mic key pressed: source_package=${sourcePackage ?: "(home/none)"}")

    val args =
      Bundle().apply {
        if (!sourcePackage.isNullOrBlank()) {
          putString(MainActivity.EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
      }

    if (AssistService.tryShowSession(this, args)) {
      return
    }
    Log.i(TAG, "Launching MainActivity with start_listening=true, source_package=$sourcePackage")
    val intent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(MainActivity.EXTRA_START_LISTENING, true)
        if (!sourcePackage.isNullOrBlank()) {
          putExtra(MainActivity.EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
      }
    startActivity(intent)
  }

  companion object {
    private const val TAG = "TVVoiceAssist"
  }
}
