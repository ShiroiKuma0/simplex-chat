package chat.simplex.common.views.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration

// downstream (shiroikuma): the platform long-press timeout (ViewConfiguration.getLongPressTimeout,
// stretched further by EMUI) is long enough that the fork's long-press shortcuts — avatar,
// new-chat button, settings cog, all opening the 白い熊 Simplex UI page — feel broken: you have to
// hold unnaturally long before they fire. Wrapping just those targets in QuickLongPress lowers the
// timeout for that subtree; everything else (message menus, text selection) keeps the platform
// value, so ordinary long presses don't start triggering by accident.

const val SHIROIKUMA_LONG_PRESS_MS = 250L

private class QuickLongPressViewConfiguration(base: ViewConfiguration) : ViewConfiguration by base {
  override val longPressTimeoutMillis: Long = SHIROIKUMA_LONG_PRESS_MS
}

@Composable
fun QuickLongPress(content: @Composable () -> Unit) {
  val base = LocalViewConfiguration.current
  val configuration = remember(base) { QuickLongPressViewConfiguration(base) }
  CompositionLocalProvider(LocalViewConfiguration provides configuration) {
    content()
  }
}
