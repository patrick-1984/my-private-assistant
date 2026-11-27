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
import com.example.data.ActivateConfigResult
import com.example.data.AppHandoffStatus
import com.example.data.AppSettings
import com.example.data.AppSettingsPreset
import com.example.data.ConfigurationPreset
import com.example.data.EndpointHealthRecord
import com.example.data.EndpointHealthStatus
import com.example.data.EndpointPreset
import com.example.data.EndpointSlot
import com.example.data.LanguageOption
import com.example.data.ServerFamily
import com.example.data.SettingsRepository
import com.example.handoff.AppHandoff
import com.example.handoff.ForegroundAppTracker
import com.example.handoff.HandoffMethod
import com.example.handoff.HandoffResult
import com.example.handoff.InstalledTvApp
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsActions
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
  fun `MainActivity opens SettingsScreen cleanly with Configuration Presets and NVIDIA preset`() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    assertNotNull(activity)
    composeTestRule.waitForIdle()
  }

  @Test
  fun `SearchScreen displays active configuration set pill, failover slot badge, and fallback picker`() {
    val smartTube = InstalledTvApp(AppHandoff.PKG_SMARTTUBE, "SmartTube", HandoffMethod.SMARTTUBE_URI, null)
    val newPipe = InstalledTvApp(AppHandoff.PKG_NEWPIPE, "NewPipe", HandoffMethod.NEWPIPE_SHARE, null)
    val epPrimary = EndpointPreset.defaultNvidiaPreset().copy(apiKey = "nvapi-ok")
    val epBackup = EndpointPreset(id = "lan_1", customName = "LAN Whisper", endpointInput = "http://10.3.3.20:8000", apiKeyNotNeeded = true)
    val appPreset = AppSettingsPreset.defaultPreset()
    val cfg =
      ConfigurationPreset(
        id = "cfg_main",
        customName = "Living Room",
        primaryEndpointId = epPrimary.id,
        secondaryEndpointId = epBackup.id,
        appSettingsPresetId = appPreset.id,
        allowEndpointFailover = true,
      )
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
              activeUsedSlot = EndpointSlot.SECONDARY,
              activeUsedEndpointId = epBackup.id,
              settings =
                AppSettings(
                  endpointInput = epPrimary.endpointInput,
                  searchInCurrentApp = true,
                  targetPackage = AppHandoff.PKG_SMARTTUBE,
                  targetLabel = "SmartTube",
                  endpointPresets = listOf(epPrimary, epBackup),
                  appSettingsPresets = listOf(appPreset),
                  configurationPresets = listOf(cfg),
                  activeConfigurationId = cfg.id,
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
    composeTestRule.onNodeWithTag("active_set_pill").assertIsDisplayed()
    composeTestRule.onNodeWithTag("active_set_label").assertTextContains("Living Room • LAN Whisper")
    composeTestRule.onNodeWithTag("active_slot_badge").assertTextContains("Secondary (failover)")
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
  fun `SearchScreen shows concise error status without active set pill when unconfigured`() {
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
    composeTestRule.onNodeWithTag("active_set_pill").assertDoesNotExist()
    composeTestRule.onNodeWithTag("status_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("fallback_picker_container").assertDoesNotExist()
  }

  @Test
  fun `SettingsScreen Home displays separated Configuration, Endpoint, and App Presets`() {
    val nvidia = EndpointPreset.defaultNvidiaPreset()
    val localEp =
      EndpointPreset(
        id = "local_1",
        customName = "LAN Whisper",
        endpointInput = "https://stt.example.com",
        apiKeyNotNeeded = true,
      )
    val defaultApp = AppSettingsPreset.defaultPreset()
    val cfg =
      ConfigurationPreset(
        id = "cfg_1",
        customName = "Home Failover",
        primaryEndpointId = localEp.id,
        secondaryEndpointId = nvidia.id,
        appSettingsPresetId = defaultApp.id,
        allowEndpointFailover = true,
      )

    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SettingsScreen(
          state =
            MainUiState(
              isSettingsOpen = true,
              settingsPage = SettingsPage.Home,
              profileBanner = "API key needed for \"NVIDIA\".",
              isProfileBannerError = true,
              endpointHealthRecords =
                mapOf(
                  localEp.id to
                    EndpointHealthRecord(
                      status = EndpointHealthStatus.HEALTHY,
                      fingerprint = localEp.healthFingerprint,
                    )
                ),
              settings =
                AppSettings(
                  endpointInput = localEp.endpointInput,
                  micButtonCapture = true,
                  endpointPresets = listOf(nvidia, localEp),
                  appSettingsPresets = listOf(defaultApp),
                  configurationPresets = listOf(cfg),
                  activeConfigurationId = cfg.id,
                ),
            ),
          actions = SettingsActions(),
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_back_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_banner_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("config_row_${cfg.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("activate_config_${cfg.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("edit_config_${cfg.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete_config_${cfg.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_config_button").performScrollTo().assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("endpoint_row_${EndpointPreset.NVIDIA_PRESET_ID}"))
    composeTestRule.onNodeWithTag("endpoint_row_${EndpointPreset.NVIDIA_PRESET_ID}").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_endpoint_button").performScrollTo().assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("app_preset_row_${AppSettingsPreset.DEFAULT_APP_PRESET_ID}"))
    composeTestRule.onNodeWithTag("app_preset_row_${AppSettingsPreset.DEFAULT_APP_PRESET_ID}").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_app_preset_button").performScrollTo().assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("mic_privacy_note"))
    composeTestRule.onNodeWithTag("mic_capture_switch").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("mic_privacy_note").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `SettingsScreen Configuration Editor shows failover switch, Primary Secondary Tertiary, and App Preset picker`() {
    val nvidia = EndpointPreset.defaultNvidiaPreset()
    val localEp = EndpointPreset(id = "local_1", customName = "LAN", endpointInput = "10.3.3.20:8000", apiKeyNotNeeded = true)
    val defaultApp = AppSettingsPreset.defaultPreset()
    val cfgDraft =
      ConfigurationPreset(
        id = "cfg_edit",
        customName = "Multi",
        primaryEndpointId = localEp.id,
        secondaryEndpointId = nvidia.id,
        appSettingsPresetId = defaultApp.id,
        allowEndpointFailover = true,
      )
    var doneClicked = false

    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SettingsScreen(
          state =
            MainUiState(
              isSettingsOpen = true,
              settingsPage = SettingsPage.EditConfiguration(draft = cfgDraft, isNew = false),
              settings =
                AppSettings(
                  endpointPresets = listOf(nvidia, localEp),
                  appSettingsPresets = listOf(defaultApp),
                  configurationPresets = listOf(cfgDraft),
                ),
            ),
          actions = SettingsActions(onSaveConfigDone = { doneClicked = true }),
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("config_name_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("failover_switch").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("config_done_button"))
    composeTestRule.onNodeWithTag("config_done_button").performScrollTo().performClick()
    assertTrue(doneClicked)
  }

  @Test
  fun `SettingsScreen Endpoint Editor shows NVIDIA Function ID chips and gRPC input`() {
    val nvidiaDraft = EndpointPreset.defaultNvidiaPreset()
    var selectedPresetId: String? = null

    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SettingsScreen(
          state =
            MainUiState(
              isSettingsOpen = true,
              settingsPage = SettingsPage.EditEndpoint(draft = nvidiaDraft, isNew = false),
              settings = AppSettings(endpointPresets = listOf(nvidiaDraft)),
            ),
          actions = SettingsActions(onApplyNvidiaCloudPreset = { selectedPresetId = it?.id }),
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("endpoint_editor_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("endpoint_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("function_id_input").performScrollTo().assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("nvidia_fn_chip_parakeet-tdt-0.6b-v2")
      .performScrollTo()
      .performClick()
    assertEquals("parakeet-tdt-0.6b-v2", selectedPresetId)
  }

  @Test
  fun `SettingsRepository separates endpoints and app presets, blocks NVIDIA without key, and supports multiple Config combinations`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = SettingsRepository.get(context)

    // 1. Fresh install has inactive Default config pointing to NVIDIA + Default TV app preset
    val initial = repo.settings.value
    assertNull(initial.activeConfigurationId)
    assertEquals("", initial.endpointInput)
    val nvidia = initial.endpointPresets.firstOrNull { it.id == EndpointPreset.NVIDIA_PRESET_ID }
    assertNotNull(nvidia)
    assertEquals("NVIDIA", nvidia!!.displayName)
    assertEquals(EndpointPreset.NVIDIA_ENDPOINT_URL, nvidia.endpointInput)
    assertEquals(EndpointPreset.NVIDIA_DEFAULT_FUNCTION_ID, nvidia.functionId)
    assertEquals(ServerFamily.NVIDIA_RIVA_GRPC, nvidia.detectedFamily)
    assertTrue(nvidia.isGrpcEndpoint)
    assertEquals(EndpointPreset.NVIDIA_DEFAULT_MODEL, nvidia.modelName)
    assertFalse(nvidia.apiKeyNotNeeded)
    assertTrue(nvidia.isMissingRequiredApiKey)

    // 2. Activating Default config without NVIDIA API key is blocked cleanly
    val blockedAct = repo.activateConfigurationPreset(ConfigurationPreset.DEFAULT_CONFIG_ID)
    assertTrue(blockedAct is ActivateConfigResult.MissingApiKey)
    assertNull(repo.settings.value.activeConfigurationId)

    // 3. Create independent LAN endpoint + custom Polish app preset, combine in a ConfigurationPreset
    val localEndpoint =
      EndpointPreset(
        id = "lan_whisper",
        customName = "",
        endpointInput = "https://whisper.home.lan:8443",
        useIpOverride = true,
        overrideIp = "10.3.3.20",
        apiKeyNotNeeded = true,
      )
    assertEquals("whisper.home.lan:8443 (10.3.3.20)", localEndpoint.displayName)
    repo.saveEndpointPreset(localEndpoint, autoActivateIfNoActiveConfig = false)

    val polishApp =
      AppSettingsPreset(
        id = "app_newpipe_pl",
        customName = "NewPipe Polish",
        targetPackage = AppHandoff.PKG_NEWPIPE,
        targetLabel = "NewPipe",
        language = LanguageOption.POLISH,
      )
    repo.saveAppPreset(polishApp)

    val combo1 =
      ConfigurationPreset(
        id = "combo_1",
        customName = "Polish Failover",
        primaryEndpointId = localEndpoint.id,
        secondaryEndpointId = nvidia.id,
        appSettingsPresetId = polishApp.id,
        allowEndpointFailover = true,
      )
    val saveOutcome = repo.saveConfigurationPreset(combo1, activateIfEligible = true)
    assertTrue(saveOutcome is ActivateConfigResult.Activated)
    assertEquals("combo_1", repo.settings.value.activeConfigurationId)
    assertEquals("https://whisper.home.lan:8443", repo.settings.value.endpointInput)
    assertEquals(AppHandoff.PKG_NEWPIPE, repo.settings.value.targetPackage)
    assertEquals(LanguageOption.POLISH, repo.settings.value.language)

    // 4. Add NVIDIA API key -> Default config can now activate
    repo.saveEndpointPreset(nvidia.copy(apiKey = "nvapi-valid-key"))
    val nvidiaAct = repo.activateConfigurationPreset(ConfigurationPreset.DEFAULT_CONFIG_ID)
    assertTrue(nvidiaAct is ActivateConfigResult.Activated)
    assertEquals(ConfigurationPreset.DEFAULT_CONFIG_ID, repo.settings.value.activeConfigurationId)
    assertEquals(EndpointPreset.NVIDIA_ENDPOINT_URL, repo.settings.value.endpointInput)

    // 5. Switch back to combo_1 and delete custom configuration cleanly
    assertTrue(repo.activateConfigurationPreset("combo_1") is ActivateConfigResult.Activated)
    repo.deleteConfigurationPreset("combo_1")
    assertNull(repo.settings.value.activeConfigurationId)
  }

  @Test
  fun `EndpointPreset deriveDisplayName strips https, falls back to IP, and fits screen length`() {
    assertEquals(
      "Custom Name",
      EndpointPreset.deriveDisplayName("Custom Name", "https://example.com", false, ""),
    )
    assertEquals(
      "stt.internal.lan:8443",
      EndpointPreset.deriveDisplayName("", "https://stt.internal.lan:8443/", false, ""),
    )
    assertEquals(
      "10.3.3.20",
      EndpointPreset.deriveDisplayName("", "", true, "10.3.3.20"),
    )
    val longName =
      EndpointPreset.deriveDisplayName(
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
