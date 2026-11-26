package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.handoff.AppHandoff
import com.example.handoff.HandoffMethod
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.TvVoiceSearchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `app name matches TV Voice Search`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("TV Voice Search", context.getString(R.string.app_name))
  }

  @Test
  fun `SearchScreen displays core controls`() {
    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SearchScreen(
          state = MainUiState(phase = VoicePhase.IDLE, statusMessage = "Press mic or OK to speak"),
          hasMicPermission = true,
          onMicClick = {},
          onRequestPermission = {},
          onSearchTextChange = {},
          onSubmitSearch = {},
          onOpenSettings = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("status_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("search_target_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_button").assertIsDisplayed()
  }

  @Test
  fun `SettingsScreen displays endpoint and language controls`() {
    composeTestRule.setContent {
      TvVoiceSearchTheme {
        SettingsScreen(
          state = MainUiState(isSettingsOpen = true),
          onBack = {},
          onEndpointChange = {},
          onTestEndpoint = {},
          onSelectTargetApp = {},
          onSelectLanguage = {},
          onApiKeyChange = {},
          onModelNameChange = {},
          onSelectMicSource = {},
          onRefreshAssistantStatus = {},
          onCopyAdbCommand = { _, _ -> },
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_back_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("endpoint_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("test_endpoint_button").assertIsDisplayed()
  }

  @Test
  fun `SmartTube handoff uses YouTube search URI with UTF-8 Polish encoding and never ACTION_SEARCH`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val shadowPm = shadowOf(context.packageManager)

    shadowPm.installPackage(
      android.content.pm.PackageInfo().apply { packageName = AppHandoff.PKG_SMARTTUBE }
    )
    val viewIntent =
      Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse("https://www.youtube.com/results?search_query=test"),
      )
    shadowPm.addResolveInfoForIntent(
      viewIntent.setPackage(AppHandoff.PKG_SMARTTUBE),
      android.content.pm.ResolveInfo().apply {
        activityInfo =
          android.content.pm.ActivityInfo().apply {
            packageName = AppHandoff.PKG_SMARTTUBE
            name = "ui.search.tags.SearchTagsActivity"
          }
      },
    )

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
    val shadowPm = shadowOf(context.packageManager)

    shadowPm.installPackage(
      android.content.pm.PackageInfo().apply { packageName = AppHandoff.PKG_NEWPIPE }
    )
    val sendIntent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(AppHandoff.PKG_NEWPIPE)
    shadowPm.addResolveInfoForIntent(
      sendIntent,
      android.content.pm.ResolveInfo().apply {
        activityInfo =
          android.content.pm.ActivityInfo().apply {
            packageName = AppHandoff.PKG_NEWPIPE
            name = "org.schabi.newpipe.RouterActivity"
          }
      },
    )

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
}
