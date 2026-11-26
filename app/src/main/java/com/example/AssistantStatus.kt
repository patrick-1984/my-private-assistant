package com.example

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

data class AssistantRoleStatus(
  val isVoiceInteractionActive: Boolean,
  val isAssistantActive: Boolean,
  val isRecognizerActive: Boolean,
  val isMicKeyServiceEnabled: Boolean,
  val currentVoiceInteraction: String,
  val currentAssistant: String,
  val currentRecognizer: String,
  val setupCommands: String,
  val undoCommands: String,
) {
  val isFullAssistantActive: Boolean
    get() = isVoiceInteractionActive && isAssistantActive
}

object AssistantStatusChecker {

  private const val KATNISS_ASSIST =
    "com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService"
  private const val KATNISS_RECOGNIZER =
    "com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService"

  fun isVoiceInteractionActive(context: Context): Boolean {
    val resolver = context.contentResolver
    val assistComponent = ComponentName(context.packageName, AssistService::class.java.name)
    val currentVis =
      runCatching { Settings.Secure.getString(resolver, "voice_interaction_service") }
        .getOrNull()
        .orEmpty()
    return matchesComponent(currentVis, assistComponent)
  }

  fun isMicKeyServiceEnabled(context: Context): Boolean {
    val resolver = context.contentResolver
    val micKeyComponent = ComponentName(context.packageName, MicKeyService::class.java.name)
    val enabledServices =
      runCatching {
          Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }
        .getOrNull()
        .orEmpty()
    if (enabledServices.isBlank()) return false
    return enabledServices.split(':').any { entry -> matchesComponent(entry.trim(), micKeyComponent) }
  }

  fun inspect(context: Context): AssistantRoleStatus {
    val resolver = context.contentResolver
    val pkg = context.packageName

    val assistComponent = ComponentName(pkg, AssistService::class.java.name)
    val recognizerComponent = ComponentName(pkg, RecognizerService::class.java.name)
    val micKeyComponent = ComponentName(pkg, MicKeyService::class.java.name)

    val currentVis =
      runCatching { Settings.Secure.getString(resolver, "voice_interaction_service") }
        .getOrNull()
        .orEmpty()
    val currentAssist =
      runCatching { Settings.Secure.getString(resolver, "assistant") }.getOrNull().orEmpty()
    val currentRec =
      runCatching { Settings.Secure.getString(resolver, "voice_recognition_service") }
        .getOrNull()
        .orEmpty()

    val visMatch = matchesComponent(currentVis, assistComponent)
    val assistMatch = matchesComponent(currentAssist, assistComponent)
    val recMatch = matchesComponent(currentRec, recognizerComponent)
    val micKeyEnabled = isMicKeyServiceEnabled(context)

    val assistFlat = assistComponent.flattenToString()
    val recFlat = recognizerComponent.flattenToString()
    val micKeyFlat = micKeyComponent.flattenToString()

    val setup =
      """
      adb shell settings put secure voice_interaction_service $assistFlat
      adb shell settings put secure assistant $assistFlat
      adb shell settings put secure voice_recognition_service $recFlat
      adb shell settings put secure enabled_accessibility_services $micKeyFlat
      """.trimIndent()

    val undo =
      """
      adb shell settings put secure voice_interaction_service $KATNISS_ASSIST
      adb shell settings put secure assistant $KATNISS_ASSIST
      adb shell settings put secure voice_recognition_service $KATNISS_RECOGNIZER
      adb shell settings put secure enabled_accessibility_services ""
      """.trimIndent()

    return AssistantRoleStatus(
      isVoiceInteractionActive = visMatch,
      isAssistantActive = assistMatch,
      isRecognizerActive = recMatch,
      isMicKeyServiceEnabled = micKeyEnabled,
      currentVoiceInteraction = currentVis.ifBlank { "(unset)" },
      currentAssistant = currentAssist.ifBlank { "(unset)" },
      currentRecognizer = currentRec.ifBlank { "(unset)" },
      setupCommands = setup,
      undoCommands = undo,
    )
  }

  private fun matchesComponent(raw: String, expected: ComponentName): Boolean {
    if (raw.isBlank()) return false
    val parsed = ComponentName.unflattenFromString(raw) ?: return false
    return parsed.packageName == expected.packageName && parsed.className == expected.className
  }
}
