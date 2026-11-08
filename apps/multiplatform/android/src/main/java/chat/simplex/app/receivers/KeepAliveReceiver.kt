package chat.simplex.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import chat.simplex.app.ServiceTrace
import chat.simplex.app.SimplexService
import chat.simplex.app.model.NtfManager

/**
 * downstream (shiroikuma): the keep-alive door for 自由作業盤's watchdog (approved by 白い熊,
 * 2026-11-05). SimplexService is not exported, so no other app — nor `am` from shell or Shizuku —
 * can start it; and EMUI's kills drop the app's own restart job. The watchdog therefore sends
 * `${applicationId}.action.KEEP_ALIVE` (ordered, FLAG_INCLUDE_STOPPED_PACKAGES, so it also reaches
 * a force-stopped app) every 15 minutes and on unlock.
 *
 * No permission and no token: the door only does what the app's own settings already want.
 * Reply (same channel as StateExportReceiver): `result` = OFF | ALIVE | STARTED | ERROR:<reason>,
 * sent to reply_action in reply_package with reply_id.
 */
class KeepAliveReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val replyAction = intent.getStringExtra("reply_action")
    val replyPackage = intent.getStringExtra("reply_package")
    val replyId = intent.getStringExtra("reply_id") ?: ""
    // a process this young was most likely created by this very broadcast: the service was dead,
    // and SimplexApp.onCreate may already be racing to start it — still a restart for the watchdog
    val processAgeMs = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
    val freshProcess = processAgeMs < 10_000

    val result = try {
      when {
        !SimplexService.shouldServiceRun() -> "OFF"
        SimplexService.isServiceStarted && !freshProcess -> "ALIVE"
        !NtfManager.areNotificationsEnabledInSystem() -> "ERROR:notifications disabled in system"
        else -> {
          SimplexService.isServiceStarting = true
          ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, SimplexService::class.java).setAction(SimplexService.Action.START.name)
          )
          "STARTED"
        }
      }
    } catch (e: Throwable) {
      SimplexService.isServiceStarting = false
      "ERROR:${e.javaClass.simpleName}${e.message?.let { ": " + it.take(120) } ?: ""}"
    }

    ServiceTrace.log(context, "KeepAlive [$replyId] from ${replyPackage ?: "?"} processAge=${processAgeMs / 1000}s -> $result")
    runCatching { setResultData(result) }
    if (replyAction != null && replyPackage != null) {
      context.sendBroadcast(Intent(replyAction).apply {
        setPackage(replyPackage)
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        putExtra("reply_id", replyId)
        putExtra("result", result)
      })
    }
  }
}
