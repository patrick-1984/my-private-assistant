package com.example.handoff

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import java.net.URLEncoder

enum class HandoffMethod(val label: String) {
  SMARTTUBE_URI("YouTube search URI"),
  NEWPIPE_SHARE("Share text"),
  ACTION_SEARCH("Search intent"),
  ACTION_WEB_SEARCH("Web search intent"),
  ACTION_SEND("Share text"),
  CLIPBOARD_LAUNCH("Clipboard + launch"),
  NOT_SUPPORTED("Not supported"),
}

data class InstalledTvApp(
  val packageName: String,
  val label: String,
  val handoffMethod: HandoffMethod,
  val iconBitmap: Bitmap?,
)

sealed class HandoffResult {
  data class Launched(val method: HandoffMethod, val copiedToClipboard: Boolean = false) :
    HandoffResult()

  data class Failed(val message: String) : HandoffResult()
}

object AppHandoff {

  const val PKG_SMARTTUBE = "org.smarttube.stable"
  const val PKG_NEWPIPE = "org.schabi.newpipe"

  private fun isSmartTubeFamily(pkg: String): Boolean {
    return pkg == PKG_SMARTTUBE ||
      pkg.startsWith("org.smarttube.") ||
      pkg.startsWith("com.liskovsoft.smarttube")
  }

  fun detectHandoffMethod(pm: PackageManager, packageName: String): HandoffMethod {
    if (!isPackageInstalled(pm, packageName)) return HandoffMethod.NOT_SUPPORTED

    if (isSmartTubeFamily(packageName)) {
      val viewIntent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=test"))
          .setPackage(packageName)
      return if (canResolve(pm, viewIntent)) {
        HandoffMethod.SMARTTUBE_URI
      } else {
        HandoffMethod.NOT_SUPPORTED
      }
    }

    if (packageName == PKG_NEWPIPE) {
      val sendIntent =
        Intent(Intent.ACTION_SEND)
          .setType("text/plain")
          .putExtra(Intent.EXTRA_TEXT, "test")
          .setPackage(packageName)
      if (canResolve(pm, sendIntent)) {
        return HandoffMethod.NEWPIPE_SHARE
      }
    }

    val searchIntent =
      Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "test").setPackage(packageName)
    if (canResolve(pm, searchIntent)) return HandoffMethod.ACTION_SEARCH

    val webSearchIntent =
      Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "test").setPackage(packageName)
    if (canResolve(pm, webSearchIntent)) return HandoffMethod.ACTION_WEB_SEARCH

    val sendIntent =
      Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "test")
        .setPackage(packageName)
    if (canResolve(pm, sendIntent)) return HandoffMethod.ACTION_SEND

    val launchIntent =
      pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) return HandoffMethod.CLIPBOARD_LAUNCH

    return HandoffMethod.NOT_SUPPORTED
  }

  fun buildHandoffIntent(pm: PackageManager, packageName: String, query: String): Pair<Intent, HandoffMethod>? {
    val method = detectHandoffMethod(pm, packageName)
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    val intent =
      when (method) {
        HandoffMethod.SMARTTUBE_URI -> {
          val encoded = URLEncoder.encode(query, "UTF-8")
          Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            .setPackage(packageName)
        }
        HandoffMethod.NEWPIPE_SHARE, HandoffMethod.ACTION_SEND -> {
          Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, query)
            .setPackage(packageName)
        }
        HandoffMethod.ACTION_SEARCH -> {
          Intent(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .setPackage(packageName)
        }
        HandoffMethod.ACTION_WEB_SEARCH -> {
          Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .setPackage(packageName)
        }
        HandoffMethod.CLIPBOARD_LAUNCH -> {
          pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return null
        }
        HandoffMethod.NOT_SUPPORTED -> return null
      }

    intent.addFlags(flags)
    return intent to method
  }

  fun launchSearch(context: Context, packageName: String, rawQuery: String): HandoffResult {
    val query = rawQuery.trim()
    if (query.isEmpty()) return HandoffResult.Failed("Search text is empty.")

    val pm = context.packageManager
    val built =
      buildHandoffIntent(pm, packageName, query)
        ?: return HandoffResult.Failed("Target app $packageName is not installed or supported.")

    val (intent, method) = built
    var copied = false
    if (method == HandoffMethod.CLIPBOARD_LAUNCH) {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("Voice search", query))
      copied = true
    }

    return try {
      context.startActivity(intent)
      HandoffResult.Launched(method = method, copiedToClipboard = copied)
    } catch (e: Exception) {
      HandoffResult.Failed("Failed to open $packageName: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  fun listInstalledTvApps(context: Context): List<InstalledTvApp> {
    val pm = context.packageManager
    val selfPkg = context.packageName
    val packages = linkedMapOf<String, String>()

    val leanbackIntent =
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    queryActivities(pm, leanbackIntent).forEach { ri ->
      val pkg = ri.activityInfo?.packageName ?: return@forEach
      if (pkg != selfPkg && !packages.containsKey(pkg)) {
        packages[pkg] = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
      }
    }

    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    queryActivities(pm, launcherIntent).forEach { ri ->
      val pkg = ri.activityInfo?.packageName ?: return@forEach
      if (pkg != selfPkg && !packages.containsKey(pkg)) {
        packages[pkg] = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
      }
    }

    return packages.entries
      .map { (pkg, label) ->
        val method = detectHandoffMethod(pm, pkg)
        val icon = runCatching { drawableToBitmap(pm.getApplicationIcon(pkg)) }.getOrNull()
        InstalledTvApp(
          packageName = pkg,
          label = label,
          handoffMethod = method,
          iconBitmap = icon,
        )
      }
      .sortedWith(
        compareBy<InstalledTvApp>(
          { priorityForPackage(it.packageName) },
          { it.handoffMethod == HandoffMethod.NOT_SUPPORTED },
          { it.label.lowercase() },
        )
      )
  }

  private fun priorityForPackage(pkg: String): Int {
    return when {
      pkg == PKG_SMARTTUBE -> 0
      pkg == PKG_NEWPIPE -> 1
      isSmartTubeFamily(pkg) -> 2
      else -> 3
    }
  }

  private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0)
      }
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }

  private fun canResolve(pm: PackageManager, intent: Intent): Boolean {
    return queryActivities(pm, intent).isNotEmpty()
  }

  private fun queryActivities(pm: PackageManager, intent: Intent) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(
        intent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
      )
    } else {
      @Suppress("DEPRECATION") pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

  private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
      return drawable.bitmap
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bitmap = Bitmap.createBitmap(w.coerceAtMost(144), h.coerceAtMost(144), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
  }
}
