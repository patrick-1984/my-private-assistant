package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.ActivateProfileResult
import com.example.data.AppHandoffStatus
import com.example.data.AppSettings
import com.example.data.EndpointProfile
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import com.example.data.SettingsRepository
import com.example.handoff.AppHandoff
import com.example.handoff.ForegroundAppTracker
import com.example.handoff.HandoffMethod
import com.example.handoff.HandoffResult
import com.example.handoff.InstalledTvApp
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.TvVoiceSearchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  fun setUp() {
    ForegroundAppTracker.clear()
  }

  @Test
  fun `app name matches TV Voice Search`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("TV Voice Search", context.getString(R.string.app_name))
  }

  @Test
  fun `SearchScreen displays lean pill bar, active current app target, and fallback picker on failure`() {
    val smartTube = InstalledTvApp(AppHandoff.PKG_SMARTTUBE, "SmartTube", HandoffMethod.SMARTTUBE_URI, null)
    val newPipe = InstalledTvApp(AppHandoff.PKG_NEWPIPE, "NewPipe", HandoffMethod.NEWPIPE_SHARE, null)
    var selectedFallback: InstalledTvApp? = null

    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SearchScreen(
          state =
            MainUiState(
              phase = VoicePhase.ERROR,
              searchText = "polskie piosenki",
              statusMessage = "Could not hand off to Player",
              activeSourcePackage = AppHandoff.PKG_NEWPIPE,
              showFallbackAppPicker = true,
              installedApps = listOf(smartTube, newPipe),
              settings =
                AppSettings(
                  endpointInput = "https://stt.example.com",
                  searchInCurrentApp = true,
                  targetPackage = AppHandoff.PKG_SMARTTUBE,
                  targetLabel = "SmartTube",
                ),
            ),
          hasMicPermission = true,
          onMicClick = {},
          onRequestPermission = {},
          onSearchTextChange = {},
          onSubmitSearch = {},
          onSelectFallbackApp = { selectedFallback = it },
          onOpenSettings = {},
          onDismiss = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("search_target_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("search_target_button").assertTextContains("Search in NewPipe")
    composeTestRule.onNodeWithTag("status_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("fallback_picker_container").assertIsDisplayed()
    composeTestRule.onNodeWithTag("fallback_app_${AppHandoff.PKG_SMARTTUBE}").performClick()
    assertEquals(smartTube, selectedFallback)
  }

  @Test
  fun `SearchScreen shows concise error status without fallback picker when endpoint is not set`() {
    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SearchScreen(
          state = MainUiState(phase = VoicePhase.ERROR, statusMessage = "No server"),
          hasMicPermission = true,
          onMicClick = {},
          onRequestPermission = {},
          onSearchTextChange = {},
          onSubmitSearch = {},
          onSelectFallbackApp = {},
          onOpenSettings = {},
          onDismiss = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("status_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("fallback_picker_container").assertDoesNotExist()
  }

  @Test
  fun `SettingsScreen displays Configuration Sets, icon buttons, Editor with Name and Done, and privacy note`() {
    val nvidia = EndpointProfile.defaultNvidiaProfile()
    val localDraft =
      EndpointProfile(
        id = "local_1",
        customName = "",
        endpointInput = "https://stt.example.com",
        useIpOverride = true,
        overrideIp = "10.3.3.20",
        ignoreSsl = true,
        apiKey = "sk-123",
        apiKeyNotNeeded = false,
        useCustomModel = true,
        modelName = "whisper-1",
        liveTranscription = true,
        detectedEndpointUrl = "https://stt.example.com/v1/audio/transcriptions",
        detectedFamily = ServerFamily.OPENAI_V1,
        detectedForInput = "https://stt.example.com|true|10.3.3.20|true",
      )
    val smartTube = InstalledTvApp(AppHandoff.PKG_SMARTTUBE, "SmartTube", HandoffMethod.SMARTTUBE_URI, null)
    val newPipe = InstalledTvApp(AppHandoff.PKG_NEWPIPE, "NewPipe", HandoffMethod.NEWPIPE_SHARE, null)
    var confirmed: Boolean? = null
    var doneClicked = false

    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SettingsScreen(
          state =
            MainUiState(
              isSettingsOpen = true,
              testStatus = TestStatus.Ok("Connected"),
              availableModels = listOf("whisper-1", "Systran/faster-whisper-large-v3"),
              profileDraft = localDraft,
              isDraftNew = false,
              profileBanner = "API key needed for \"NVIDIA\".",
              isProfileBannerError = true,
              pendingVerificationApp = newPipe,
              installedApps = listOf(smartTube, newPipe),
              settings =
                AppSettings(
                  endpointInput = localDraft.endpointInput,
                  useIpOverride = true,
                  overrideIp = "10.3.3.20",
                  ignoreSsl = true,
                  useApiKey = true,
                  useCustomModel = true,
                  liveTranscription = true,
                  micButtonCapture = true,
                  searchInCurrentApp = true,
                  appHandoffStatuses = mapOf(AppHandoff.PKG_SMARTTUBE to AppHandoffStatus.CONFIRMED),
                  profiles = listOf(nvidia, localDraft),
                  activeProfileId = localDraft.id,
                ),
            ),
          onBack = {},
          onStartNewProfile = {},
          onEditProfile = {},
          onActivateProfile = {},
          onDeleteProfile = {},
          onSaveProfileDone = { doneClicked = true },
          onCancelProfileDraft = {},
          onProfileNameChange = {},
          onEndpointChange = {},
          onToggleIpOverride = {},
          onOverrideIpChange = {},
          onToggleIgnoreSsl = {},
          onToggleApiKeyNotNeeded = {},
          onTestEndpoint = {},
          onToggleCustomModel = {},
          onRefreshModels = {},
          onToggleLiveTranscription = {},
          onToggleSearchInCurrentApp = {},
          onSelectTargetApp = {},
          onTestAppSearch = {},
          onConfirmAppTest = { confirmed = it },
          onDismissPendingVerification = {},
          onToggleNeverUseApp = {},
          onSelectLanguage = {},
          onApiKeyChange = {},
          onModelNameChange = {},
          onSelectMicSource = {},
          onToggleMicCapture = {},
          onRefreshAssistantStatus = {},
          onCopyAdbCommand = { _, _ -> },
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_back_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_banner_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_row_${EndpointProfile.NVIDIA_PRESET_ID}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("activate_profile_${EndpointProfile.NVIDIA_PRESET_ID}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("edit_profile_${EndpointProfile.NVIDIA_PRESET_ID}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete_profile_${EndpointProfile.NVIDIA_PRESET_ID}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_profile_button").performScrollTo().assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("profile_name_input"))
    composeTestRule.onNodeWithTag("profile_name_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("endpoint_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("api_key_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("api_key_not_needed_checkbox").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("test_endpoint_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("live_transcription_checkbox").performScrollTo().assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("search_current_app_switch"))
    composeTestRule.onNodeWithTag("search_current_app_switch").performScrollTo().assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("pending_verification_banner"))
    composeTestRule.onNodeWithTag("confirm_app_works_button").performScrollTo().performClick()
    assertEquals(true, confirmed)

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("profile_done_button"))
    composeTestRule.onNodeWithTag("profile_done_button").performScrollTo().performClick()
    assertTrue(doneClicked)

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("mic_privacy_note"))
    composeTestRule.onNodeWithTag("mic_capture_switch").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("mic_privacy_note").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `SettingsRepository seeds inactive NVIDIA preset, enforces API key check on activate, and supports Done without test`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = SettingsRepository.get(context)

    // 1. Fresh install has NVIDIA preset inactive and activeProfileId == null
    val initial = repo.settings.value
    assertNull(initial.activeProfileId)
    assertEquals("", initial.endpointInput)
    val nvidia = initial.profiles.firstOrNull { it.id == EndpointProfile.NVIDIA_PRESET_ID }
    assertNotNull(nvidia)
    assertEquals("NVIDIA", nvidia!!.displayName)
    assertEquals(EndpointProfile.NVIDIA_ENDPOINT_URL, nvidia.endpointInput)
    assertEquals(EndpointProfile.NVIDIA_DEFAULT_MODEL, nvidia.modelName)
    assertFalse(nvidia.apiKeyNotNeeded)
    assertTrue(nvidia.isMissingRequiredApiKey)

    // 2. Activating NVIDIA without API key is blocked cleanly
    val blockedAct = repo.activateProfile(nvidia.id)
    assertTrue(blockedAct is ActivateProfileResult.MissingApiKey)
    assertNull(repo.settings.value.activeProfileId)

    // 3. Adding custom endpoint with no name auto-derives FQDN without https://, allows activation when apiKeyNotNeeded = true
    val localProfile =
      EndpointProfile(
        id = "lan_whisper",
        customName = "",
        endpointInput = "https://whisper.home.lan:8443",
        useIpOverride = true,
        overrideIp = "10.3.3.20",
        apiKeyNotNeeded = true,
        targetPackage = AppHandoff.PKG_NEWPIPE,
        targetLabel = "NewPipe",
        language = LanguageOption.POLISH,
      )
    assertEquals("whisper.home.lan:8443 (10.3.3.20)", localProfile.displayName)
    val saveOutcome = repo.saveProfile(localProfile, activateIfEligible = true)
    assertTrue(saveOutcome is ActivateProfileResult.Activated)
    assertEquals("lan_whisper", repo.settings.value.activeProfileId)
    assertEquals("https://whisper.home.lan:8443", repo.settings.value.endpointInput)
    assertEquals(AppHandoff.PKG_NEWPIPE, repo.settings.value.targetPackage)
    assertEquals(LanguageOption.POLISH, repo.settings.value.language)

    // 4. Updating NVIDIA with API key lets it activate and switches active settings
    val updatedNvidia = nvidia.copy(apiKey = "nvapi-valid-key")
    val nvidiaSave = repo.saveProfile(updatedNvidia, activateIfEligible = true)
    assertTrue(nvidiaSave is ActivateProfileResult.Activated)
    assertEquals(nvidia.id, repo.settings.value.activeProfileId)
    assertEquals(EndpointProfile.NVIDIA_ENDPOINT_URL, repo.settings.value.endpointInput)

    // 5. Switch back to LAN profile and delete custom profile cleanly
    assertTrue(repo.activateProfile("lan_whisper") is ActivateProfileResult.Activated)
    assertEquals("lan_whisper", repo.settings.value.activeProfileId)
    repo.deleteProfile("lan_whisper")
    assertNull(repo.settings.value.activeProfileId)
  }

  @Test
  fun `EndpointProfile deriveDisplayName strips https, falls back to IP, and fits screen length`() {
    assertEquals(
      "Custom Name",
      EndpointProfile.deriveDisplayName("Custom Name", "https://example.com", false, ""),
    )
    assertEquals(
      "stt.internal.lan:8443",
      EndpointProfile.deriveDisplayName("", "https://stt.internal.lan:8443/", false, ""),
    )
    assertEquals(
      "10.3.3.20",
      EndpointProfile.deriveDisplayName("", "", true, "10.3.3.20"),
    )
    val longName =
      EndpointProfile.deriveDisplayName(
        "",
        "https://very-long-internal-whisper-transcription-server.tailscale.ts.net/v1/audio/transcriptions",
        false,
        "",
      )
    assertTrue(longName.length <= 36)
    assertTrue(longName.endsWith("…"))
    assertFalse(longName.contains("https://"))
  }

  @Test
  fun `MicKeyService tracks foreground app via TYPE_WINDOW_STATE_CHANGED, passes source_package, and clears on launcher`() {
    val service = Robolectric.setupService(MicKeyService::class.java)
    val repo = SettingsRepository.get(service)
    repo.setMicButtonCapture(true)

    service.onAccessibilityEvent(
      AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
        packageName = AppHandoff.PKG_SMARTTUBE
      }
    )
    assertEquals(AppHandoff.PKG_SMARTTUBE, ForegroundAppTracker.currentSourcePackage())

    service.onAccessibilityEvent(
      AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
        packageName = "com.android.systemui"
      }
    )
    assertEquals(AppHandoff.PKG_SMARTTUBE, ForegroundAppTracker.currentSourcePackage())

    val downSearch = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH)
    assertTrue(service.handleKeyEvent(downSearch))

    val launched = shadowOf(service).nextStartedActivity
    assertNotNull(launched)
    assertEquals(MainActivity::class.java.name, launched.component?.className)
    assertTrue((launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    assertTrue(launched.getBooleanExtra(MainActivity.EXTRA_START_LISTENING, false))
    assertEquals(AppHandoff.PKG_SMARTTUBE, launched.getStringExtra(MainActivity.EXTRA_SOURCE_PACKAGE))

    assertTrue(service.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEARCH)))
    assertFalse(service.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))

    service.onAccessibilityEvent(
      AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
        packageName = "com.google.android.apps.tv.launcherx"
      }
    )
    assertNull(ForegroundAppTracker.currentSourcePackage())

    repo.setMicButtonCapture(false)
    assertFalse(service.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH)))
    repo.setMicButtonCapture(true)
  }

  @Test
  fun `launchWithFallback routes to SmartTube or NewPipe when foregrounded and falls back cleanly`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val shadowPm = shadowOf(app.packageManager)
    val kodiPkg = "org.xbmc.kodi"

    installSmartTube(shadowPm)
    installNewPipe(shadowPm)
    installLaunchOnlyApp(shadowPm, kodiPkg)

    val resNewPipe =
      AppHandoff.launchWithFallback(
        context = app,
        sourcePackage = AppHandoff.PKG_NEWPIPE,
        searchInCurrentApp = true,
        defaultPackage = AppHandoff.PKG_SMARTTUBE,
        defaultLabel = "SmartTube",
        appStatuses = emptyMap(),
        rawQuery = "ambient music",
      )
    assertTrue(resNewPipe is HandoffResult.Launched)
    assertEquals(AppHandoff.PKG_NEWPIPE, (resNewPipe as HandoffResult.Launched).packageName)
    assertTrue(resNewPipe.usedCurrentApp)
    assertEquals(AppHandoff.PKG_NEWPIPE, shadowOf(app).nextStartedActivity?.`package`)

    val resKodi =
      AppHandoff.launchWithFallback(
        context = app,
        sourcePackage = kodiPkg,
        searchInCurrentApp = true,
        defaultPackage = AppHandoff.PKG_SMARTTUBE,
        defaultLabel = "SmartTube",
        appStatuses = emptyMap(),
        rawQuery = "ambient music",
      )
    assertTrue(resKodi is HandoffResult.Launched)
    assertEquals(AppHandoff.PKG_SMARTTUBE, (resKodi as HandoffResult.Launched).packageName)
    assertFalse(resKodi.usedCurrentApp)
    assertEquals(AppHandoff.PKG_SMARTTUBE, shadowOf(app).nextStartedActivity?.`package`)

    val resHome =
      AppHandoff.launchWithFallback(
        context = app,
        sourcePackage = null,
        searchInCurrentApp = true,
        defaultPackage = AppHandoff.PKG_SMARTTUBE,
        defaultLabel = "SmartTube",
        appStatuses = emptyMap(),
        rawQuery = "nature 4k",
      )
    assertTrue(resHome is HandoffResult.Launched)
    assertEquals(AppHandoff.PKG_SMARTTUBE, (resHome as HandoffResult.Launched).packageName)
    assertFalse(resHome.usedCurrentApp)

    val resBlocked =
      AppHandoff.launchWithFallback(
        context = app,
        sourcePackage = AppHandoff.PKG_NEWPIPE,
        searchInCurrentApp = true,
        defaultPackage = AppHandoff.PKG_SMARTTUBE,
        defaultLabel = "SmartTube",
        appStatuses = mapOf(AppHandoff.PKG_NEWPIPE to AppHandoffStatus.NOT_WORKING),
        rawQuery = "nature 4k",
      )
    assertTrue(resBlocked is HandoffResult.Launched)
    assertEquals(AppHandoff.PKG_SMARTTUBE, (resBlocked as HandoffResult.Launched).packageName)

    val resToggleOff =
      AppHandoff.launchWithFallback(
        context = app,
        sourcePackage = AppHandoff.PKG_NEWPIPE,
        searchInCurrentApp = false,
        defaultPackage = AppHandoff.PKG_SMARTTUBE,
        defaultLabel = "SmartTube",
        appStatuses = emptyMap(),
        rawQuery = "nature 4k",
      )
    assertTrue(resToggleOff is HandoffResult.Launched)
    assertEquals(AppHandoff.PKG_SMARTTUBE, (resToggleOff as HandoffResult.Launched).packageName)

    val resFailed =
      AppHandoff.launchWithFallback(
        context = app,
        sourcePackage = AppHandoff.PKG_NEWPIPE,
        searchInCurrentApp = true,
        defaultPackage = AppHandoff.PKG_SMARTTUBE,
        defaultLabel = "SmartTube",
        appStatuses =
          mapOf(
            AppHandoff.PKG_NEWPIPE to AppHandoffStatus.NOT_WORKING,
            AppHandoff.PKG_SMARTTUBE to AppHandoffStatus.NOT_WORKING,
          ),
        rawQuery = "nature 4k",
      )
    assertTrue(resFailed is HandoffResult.Failed)
  }

  @Test
  fun `SmartTube handoff uses YouTube search URI with UTF-8 Polish encoding and never ACTION_SEARCH`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    installSmartTube(shadowOf(context.packageManager))

    val built =
      AppHandoff.buildHandoffIntent(
        context.packageManager,
        AppHandoff.PKG_SMARTTUBE,
        "polskie piosenki",
      )
    assertNotNull(built)
    val (intent, method) = built!!
    assertEquals(HandoffMethod.SMARTTUBE_URI, method)
    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertNotEquals(Intent.ACTION_SEARCH, intent.action)
    assertEquals(AppHandoff.PKG_SMARTTUBE, intent.`package`)
    assertEquals(
      "https://www.youtube.com/results?search_query=polskie+piosenki",
      intent.dataString,
    )
  }

  @Test
  fun `NewPipe handoff uses ACTION_SEND text plain`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    installNewPipe(shadowOf(context.packageManager))

    val built =
      AppHandoff.buildHandoffIntent(
        context.packageManager,
        AppHandoff.PKG_NEWPIPE,
        "polskie piosenki",
      )
    assertNotNull(built)
    val (intent, method) = built!!
    assertEquals(HandoffMethod.NEWPIPE_SHARE, method)
    assertEquals(Intent.ACTION_SEND, intent.action)
    assertEquals("text/plain", intent.type)
    assertEquals("polskie piosenki", intent.getStringExtra(Intent.EXTRA_TEXT))
    assertEquals(AppHandoff.PKG_NEWPIPE, intent.`package`)
  }

  private fun installSmartTube(shadowPm: org.robolectric.shadows.ShadowPackageManager) {
    shadowPm.installPackage(PackageInfo().apply { packageName = AppHandoff.PKG_SMARTTUBE })
    val viewIntent =
      Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.youtube.com/results?search_query=test"),
      ).setPackage(AppHandoff.PKG_SMARTTUBE)
    shadowPm.addResolveInfoForIntent(
      viewIntent,
      ResolveInfo().apply {
        activityInfo =
          ActivityInfo().apply {
            packageName = AppHandoff.PKG_SMARTTUBE
            name = "ui.search.tags.SearchTagsActivity"
          }
      },
    )
  }

  private fun installNewPipe(shadowPm: org.robolectric.shadows.ShadowPackageManager) {
    shadowPm.installPackage(PackageInfo().apply { packageName = AppHandoff.PKG_NEWPIPE })
    val sendIntent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(AppHandoff.PKG_NEWPIPE)
    shadowPm.addResolveInfoForIntent(
      sendIntent,
      ResolveInfo().apply {
        activityInfo =
          ActivityInfo().apply {
            packageName = AppHandoff.PKG_NEWPIPE
            name = "org.schabi.newpipe.RouterActivity"
          }
      },
    )
  }

  private fun installLaunchOnlyApp(
    shadowPm: org.robolectric.shadows.ShadowPackageManager,
    pkg: String,
  ) {
    shadowPm.installPackage(PackageInfo().apply { packageName = pkg })
    val launchIntent =
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
    shadowPm.addResolveInfoForIntent(
      launchIntent,
      ResolveInfo().apply {
        activityInfo =
          ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
          }
      },
    )
  }
}
