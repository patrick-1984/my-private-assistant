package com.example.handoff

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Tracks the last foreground user app seen via Accessibility TYPE_WINDOW_STATE_CHANGED
 * (package name only, without reading window content or view text).
 *
 * Design choice (Option 1a): MicKeyService already filters hardware keys via AccessibilityService.
 * Subscribing only to TYPE_WINDOW_STATE_CHANGED with canRetrieveWindowContent=false gives us
 * immediate foreground-package updates at zero polling cost and without extra permissions such as
 * PACKAGE_USAGE_STATS.
 */
object ForegroundAppTracker {

  private const val TAG = "TVVoiceAssist"

  private val LAUNCHER_PACKAGES =
    setOf(
      "com.google.android.apps.tv.launcherx",
      "com.google.android.tvlauncher",
      "com.google.android.leanbacklauncher",
    )

  private val SETTINGS_PACKAGES =
    setOf(
      "com.android.settings",
      "com.android.tv.settings",
    )

  private val TRANSIENT_SYSTEM_PACKAGES =
    setOf(
      "com.android.systemui",
      "com.google.android.katniss",
      "com.google.android.tv.remote.service",
      "com.google.android.inputmethod.latin",
      "com.google.android.googlequicksearchbox",
      "com.google.android.tts",
      "com.android.permissioncontroller",
      "com.google.android.permissioncontroller",
    )

  @Volatile private var lastForegroundPackage: String? = null
  @Volatile private var cachedHomePackages: Set<String> = emptySet()
  @Volatile private var cachedImePackages: Set<String> = emptySet()
  @Volatile private var lastCacheRefreshMs: Long = 0L

  fun onWindowPackageChanged(context: Context, packageName: String?) {
    val pkg = packageName?.trim().orEmpty()
    if (pkg.isEmpty()) return

    refreshDynamicIgnoredPackages(context)

    when (classifyPackage(context.packageName, pkg, cachedHomePackages, cachedImePackages)) {
      PackageRole.HOME_OR_SETTINGS -> {
        if (lastForegroundPackage != null) {
          Log.d(TAG, "Window changed to home/settings ($pkg); cleared source_package")
          lastForegroundPackage = null
        }
      }
      PackageRole.TRANSIENT_OVERLAY -> {
        // Keep the underlying app (e.g. SmartTube) when SystemUI, IME, or our own overlay shows.
      }
      PackageRole.USER_APP -> {
        if (lastForegroundPackage != pkg) {
          lastForegroundPackage = pkg
          Log.d(TAG, "Foreground app updated: source_package=$pkg")
        }
      }
    }
  }

  fun currentSourcePackage(): String? = lastForegroundPackage

  fun clear() {
    lastForegroundPackage = null
  }

  internal enum class PackageRole {
    HOME_OR_SETTINGS,
    TRANSIENT_OVERLAY,
    USER_APP,
  }

  internal fun classifyPackage(
    selfPackage: String,
    pkg: String,
    dynamicHomePackages: Set<String>,
    dynamicImePackages: Set<String>,
  ): PackageRole {
    if (pkg in LAUNCHER_PACKAGES || pkg in dynamicHomePackages || pkg in SETTINGS_PACKAGES) {
      return PackageRole.HOME_OR_SETTINGS
    }
    if (
      pkg == selfPackage ||
        pkg in TRANSIENT_SYSTEM_PACKAGES ||
        pkg in dynamicImePackages ||
        pkg.contains(".inputmethod", ignoreCase = true)
    ) {
      return PackageRole.TRANSIENT_OVERLAY
    }
    return PackageRole.USER_APP
  }

  private fun refreshDynamicIgnoredPackages(context: Context) {
    val now = System.currentTimeMillis()
    if (cachedHomePackages.isNotEmpty() && now - lastCacheRefreshMs < CACHE_TTL_MS) return
    lastCacheRefreshMs = now

    val pm = context.packageManager
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val homes =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(
          homeIntent,
          PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
      } else {
        @Suppress("DEPRECATION") pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
      }
    cachedHomePackages = homes.mapNotNull { it.activityInfo?.packageName }.toSet()

    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    cachedImePackages =
      runCatching { imm?.enabledInputMethodList?.map { it.packageName }?.toSet() }
        .getOrNull()
        .orEmpty()
  }

  private const val CACHE_TTL_MS = 60_000L
}
