package chat.simplex.common.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

// downstream (shiroikuma): All-Files-Access probe for the 保存復元 contract. The contract's
// `path` extra is an absolute directory (e.g. /sdcard/tmp) shared by every sister app, which
// scoped storage only allows with MANAGE_EXTERNAL_STORAGE — declared in the manifest and
// granted once by 白い熊 from the system screen this opens.

actual fun hasAllFilesAccess(): Boolean? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else null

actual fun openAllFilesAccessSettings() {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
  val ctx = androidAppContext
  val pkg = ctx.packageName
  // the per-app screen first; some ROMs only offer the global list
  val intents = listOf(
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg")),
    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
  )
  for (i in intents) {
    try {
      ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return
    } catch (e: Exception) {
      Log.w(TAG, "openAllFilesAccessSettings: ${e.message}")
    }
  }
}
