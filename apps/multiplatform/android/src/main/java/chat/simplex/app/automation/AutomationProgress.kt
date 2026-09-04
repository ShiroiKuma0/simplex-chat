package chat.simplex.app.automation

import android.content.Context
import android.content.Intent
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.views.usersettings.UiEximProgress

// downstream (shiroikuma): ONE progress sender, shared by both automation doors.
//
// §3 of the contract applies to the data door (§2a) exactly as it does to the broadcast receiver
// (§1) — an export driven through the provider takes just as long and is watched by the same
// caller, whose rule is that an app silent for two minutes is presumed dead. The contract is
// explicit that the existing §1 sender is to be PARAMETERISED on the correlation id rather than
// copied into a second one: two implementations of the same watchdog drift, and the one that
// drifts is always the one nobody is looking at.
//
// The correlation id goes out under BOTH names — `reply_id` for the receiver's channel, `job_id`
// for the provider's — so one progress reader on the caller's side serves both doors.
//
// `setPackage` is on every message, and the reporter is inert without a reply package: since API
// 26 an implicit broadcast is not delivered to a manifest-declared receiver at all, so progress
// without it is not weak progress, it is none.

private const val PROGRESS_INTERVAL_MS = 500L

/**
 * How long silence is allowed to last before [beat] speaks up anyway. Well inside the caller's
 * 30 s expectation and its 2-minute death sentence.
 */
private const val HEARTBEAT_MS = 15_000L

const val AUTOMATION_APP_LABEL = "白い熊 SimpleX"

/**
 * Real counts, never a percentage; at most one message every 500 ms, and a mandatory final one.
 *
 * **A throttle is not a heartbeat**, which is why [beat] exists separately. The throttle only ever
 * speaks when the export calls it, so an export that blocks says nothing — and on the data door
 * the destination is a descriptor the CALLER opened, which may be a **pipe**: a single write then
 * blocks for exactly as long as 応用管理 is slow to drain it, with archives here running to
 * hundreds of megabytes. "This export is fast" is reasoning about our own speed, and the thing
 * that stalls is not ours.
 */
class AutomationProgressReporter(
  private val context: Context,
  private val progressAction: String?,
  private val replyPackage: String?,
  private val correlationId: String,
) {
  private val active = !progressAction.isNullOrEmpty() && !replyPackage.isNullOrEmpty()

  @Volatile private var current = 0L
  @Volatile private var total = 0L
  @Volatile private var unit = ""
  @Volatile private var text = "…"
  private var lastSentAt = 0L
  private val lock = Any()

  /** Hand this to the export engine as its [UiEximProgress]. */
  val onProgress: UiEximProgress = { c, t, u, x ->
    current = c; total = t; unit = u; text = x
    val now = System.currentTimeMillis()
    val send = synchronized(lock) {
      if (c >= t || now - lastSentAt >= PROGRESS_INTERVAL_MS) { lastSentAt = now; true } else false
    }
    if (send) emit()
  }

  /**
   * Re-sends the last known numbers if nothing has been said for [HEARTBEAT_MS].
   *
   * Safe to call from another coroutine while the export thread is blocked mid-write — that is the
   * entire point, and `sendBroadcast` does not block. A heartbeat is a promise rather than a
   * shield: it keeps the caller waiting, so every step it covers must still be bounded.
   */
  fun beat() {
    val now = System.currentTimeMillis()
    val send = synchronized(lock) {
      if (now - lastSentAt >= HEARTBEAT_MS) { lastSentAt = now; true } else false
    }
    if (send) emit()
  }

  private fun emit() {
    if (!active) return
    try {
      context.sendBroadcast(Intent(progressAction).apply {
        setPackage(replyPackage)
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        putExtra("reply_id", correlationId)
        putExtra("job_id", correlationId)
        putExtra("app", AUTOMATION_APP_LABEL)
        putExtra("text", text)
        putExtra("current", current)
        putExtra("total", total)
        putExtra("unit", unit)
      })
    } catch (e: Throwable) {
      Log.w(TAG, "progress broadcast failed: ${e.message}")
    }
  }
}
