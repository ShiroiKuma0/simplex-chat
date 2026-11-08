package chat.simplex.app

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.ShiroikumaDiag
import chat.simplex.common.platform.ShiroikumaDiag.Kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fork diagnostics for the background service: the “Service life-cycle” and “Device and power”
 * kinds of UI → Diagnostics (ShiroikumaDiag). Off unless their switches are on.
 *
 *   adb shell ls /sdcard/Android/data/shiroikuma.simplex/files/diag/
 */
object ServiceTrace {
  fun log(context: Context, message: String) = ShiroikumaDiag.log(Kind.SERVICE, message)

  /**
   * How every process since the last one we reported ended, as Android recorded it. EMUI's own
   * killer may bypass this record entirely — then the next start shows no entry, which is itself
   * the finding.
   */
  fun logExitReasons(context: Context) {
    if (!ShiroikumaDiag.isOn(Kind.SERVICE) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
      val am = context.getSystemService(ActivityManager::class.java) ?: return
      val seen = appPrefs.diagLastExitTs.get()
      val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
        .filter { it.timestamp > seen && it.processName == context.packageName }
        .sortedBy { it.timestamp }
      val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
      if (exits.isEmpty()) ShiroikumaDiag.write(Kind.SERVICE, "previous process: no exit record since ${if (seen > 0) fmt.format(Date(seen)) else "ever"}")
      exits.forEach {
        ShiroikumaDiag.write(
          Kind.SERVICE,
          "previous process exit at ${fmt.format(Date(it.timestamp))} pid=${it.pid} reason=${exitReason(it.reason)} " +
            "importance=${it.importance} pss=${it.pss / 1024}MB description=${it.description}"
        )
      }
      exits.lastOrNull()?.let { appPrefs.diagLastExitTs.set(it.timestamp) }
    } catch (e: Throwable) {
      ShiroikumaDiag.write(Kind.SERVICE, "exit reasons unavailable: $e")
    }
  }

  private fun exitReason(r: Int): String = when (r) {
    1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN($r)"
  }

  /** What the system thinks of this app right now: bucket, battery optimisation, restriction, Doze. */
  fun logDeviceState(context: Context, why: String) {
    ShiroikumaDiag.log(Kind.DEVICE) {
      val pm = context.getSystemService(PowerManager::class.java)
      val am = context.getSystemService(ActivityManager::class.java)
      val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.let(::bucketName) ?: "?" else "n/a"
      val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am?.isBackgroundRestricted.toString() else "n/a"
      "state ($why): bucket=$bucket ignoringBatteryOptimizations=${pm?.isIgnoringBatteryOptimizations(context.packageName)} " +
        "backgroundRestricted=$restricted deviceIdle=${pm?.isDeviceIdleMode} powerSave=${pm?.isPowerSaveMode} interactive=${pm?.isInteractive}"
    }
  }

  private fun bucketName(b: Int): String = when (b) {
    5 -> "EXEMPTED"; 10 -> "ACTIVE"; 20 -> "WORKING_SET"; 30 -> "FREQUENT"; 40 -> "RARE"; 45 -> "RESTRICTED"; 50 -> "NEVER"; else -> b.toString()
  }

  private var deviceReceiver: BroadcastReceiver? = null

  /** Screen, Doze and power-save changes, for the “Device and power” kind; registered once per process. */
  fun registerDeviceReceiver(context: Context) {
    if (deviceReceiver != null) return
    val r = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        ShiroikumaDiag.log(Kind.DEVICE) {
          val pm = c.getSystemService(PowerManager::class.java)
          when (intent.action) {
            Intent.ACTION_SCREEN_ON -> "screen on"
            Intent.ACTION_SCREEN_OFF -> "screen off"
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze ${if (pm?.isDeviceIdleMode == true) "entered" else "left"}"
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power save ${if (pm?.isPowerSaveMode == true) "on" else "off"}"
            else -> intent.action ?: "?"
          }
        }
      }
    }
    val f = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
      addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
    }
    try {
      androidx.core.content.ContextCompat.registerReceiver(context.applicationContext, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
      deviceReceiver = r
    } catch (_: Throwable) {
    }
  }
}
