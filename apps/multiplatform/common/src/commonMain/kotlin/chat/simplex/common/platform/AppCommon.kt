package chat.simplex.common.platform

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import chat.simplex.common.BuildConfigCommon
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.DefaultTheme
import chat.simplex.common.views.helpers.generalGetString
import chat.simplex.res.MR
import java.util.*

enum class AppPlatform {
  ANDROID, DESKTOP;

  val isAndroid: Boolean
    get() = this == ANDROID

  val isDesktop: Boolean
    get() = this == DESKTOP
}

expect val appPlatform: AppPlatform

expect val deviceName: String

expect fun isAppVisibleAndFocused(): Boolean

val appVersionInfo: Pair<String, Int?> = if (appPlatform == AppPlatform.ANDROID)
  BuildConfigCommon.ANDROID_VERSION_NAME to BuildConfigCommon.ANDROID_VERSION_CODE
else
  BuildConfigCommon.DESKTOP_VERSION_NAME to BuildConfigCommon.DESKTOP_VERSION_CODE

val databaseBackend: String = if (appPlatform == AppPlatform.ANDROID) "sqlite" else BuildConfigCommon.DATABASE_BACKEND

// Country of the Google Play account, only set in the google flavor of the Android app
val androidPlayStoreCountry: MutableState<String?> = mutableStateOf(null)

class FifoQueue<E>(private var capacity: Int) : LinkedList<E>() {
  override fun add(element: E): Boolean {
    if (size > capacity) removeFirstOrNull()
    return super.add(element)
  }
}

// LALAL VERSION CODE
fun runMigrations() {
  // downstream (shiroikuma): one-shot, outside the version-code chain (the fork's injected code is
  // far past every branch below). The muted colour was yellow at 60% alpha, which over black reads
  // olive rather than dimmed; anyone still carrying that exact value gets the opaque default. A
  // deliberately chosen colour is left alone — only the old default is rewritten.
  val secondaryOpaque = ChatController.appPrefs.secondaryOpaqueApplied
  if (!secondaryOpaque.get()) {
    if (ChatController.appPrefs.uiSecondaryColor.get() == AppPreferences.LEGACY_UI_SECONDARY_COLOR) {
      ChatController.appPrefs.uiSecondaryColor.set(AppPreferences.DEFAULT_UI_SECONDARY_COLOR)
    }
    secondaryOpaque.set(true)
  }
  val lastMigration = ChatController.appPrefs.lastMigratedVersionCode
  if (lastMigration.get() < BuildConfigCommon.ANDROID_VERSION_CODE) {
    while (true) {
      if (lastMigration.get() < 117) {
        if (ChatController.appPrefs.currentTheme.get() == DefaultTheme.DARK.name) {
          ChatController.appPrefs.currentTheme.set(DefaultTheme.SIMPLEX.name)
        }
        lastMigration.set(117)
      } else if (lastMigration.get() < 203) {
        // Moving to a different key for storing themes as a List
        val oldOverrides = ChatController.appPrefs.themeOverridesOld.get().values.toList()
        ChatController.appPrefs.themeOverrides.set(oldOverrides)
        ChatController.appPrefs.currentThemeIds.set(oldOverrides.associate { it.base.themeName to it.themeId })
        lastMigration.set(203)
      } else {
        lastMigration.set(BuildConfigCommon.ANDROID_VERSION_CODE)
        break
      }
    }
  }
}

enum class AppUpdatesChannel {
  DISABLED,
  STABLE,
  BETA;

  val text: String
    get() = when (this) {
      DISABLED -> generalGetString(MR.strings.app_check_for_updates_disabled)
      STABLE -> generalGetString(MR.strings.app_check_for_updates_stable)
      BETA -> generalGetString(MR.strings.app_check_for_updates_beta)
    }
}
