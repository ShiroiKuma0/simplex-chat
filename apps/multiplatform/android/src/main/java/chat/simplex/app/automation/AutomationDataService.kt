package chat.simplex.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import chat.simplex.app.R
import chat.simplex.common.model.AppPreferences
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.tmpDir
import chat.simplex.common.views.usersettings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a data export or import driven through [AutomationProvider] actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes. Two hard reasons it cannot be done
 * anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress, and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored.
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 */
class AutomationDataService: Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Read first, decide later. NOTHING may return before startForeground below.
    val jobId = intent?.getStringExtra(EXTRA_JOB)
    val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) ?: false
    val replyAction = intent?.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
    val replyPackage = intent?.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
    val progressAction = intent?.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)

    val replied = AtomicBoolean(false)
    fun reply(result: String) {
      // Exactly one terminal answer per job, whatever path got here — a synchronous failure and
      // an asynchronous success must never both fire. The same guard the broadcast contract has
      // carried since the first sister app.
      if (!replied.compareAndSet(false, true)) return
      Log.i(TAG, "AutomationDataService [$jobId] -> ${result.lineSequence().first()}")
      jobId?.let { AutomationJobs.finish(it) }
      if (jobId == null || replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
      sendBroadcast(
        Intent(replyAction).apply {
          setPackage(replyPackage)
          // Without this a caller that has been backgrounded never hears the answer, and on a
          // clean phone the caller may not have been launched at all.
          addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
          putExtra(AutomationProvider.KEY_JOB_ID, jobId)
          putExtra("reply_id", jobId)
          putExtra(AutomationProvider.KEY_RESULT, result)
        },
      )
    }

    // BEFORE every return, and after `reply` exists.
    //
    // Once the provider called `startForegroundService`, the platform requires this call whatever
    // this service then decides — it kills the process with
    // ForegroundServiceDidNotStartInTimeException otherwise. So it must precede even the "nothing
    // to do" exits below: a caller retrying with a stale job id finds the handover entry already
    // drained, and returning from there without going foreground would KILL THE APP rather than
    // no-op. That is a different path from a refused start, and it is the one that crashes.
    //
    // The catch covers the other direction: `startForeground` throws when the declared
    // foregroundServiceType disagrees with the manifest, and on API 31+ can be refused outright
    // for a background start, which a provider `call()` always is. The provider has already
    // answered OK:<job_id> by then, so the reply broadcast is the only channel left — dying
    // silently would leave the caller waiting for an answer that never comes.
    try {
      startForeground(NOTIFICATION_ID, notification(importing))
    } catch (e: Exception) {
      Log.e(TAG, "AutomationDataService startForeground refused: ${e.stackTraceToString()}")
      jobId?.let { runCatching { HANDOVER.remove(it)?.close() } }
      reply("ERROR:cannot go foreground: ${e.javaClass.simpleName}")
      return stop(startId)
    }

    // Only now is it safe to give up. Both of these are silent no-ops by design: a duplicate or
    // stale start carries a job id whose descriptor another invocation already took.
    if (jobId == null) return stop(startId)
    val fd = HANDOVER.remove(jobId) ?: run {
      Log.w(TAG, "AutomationDataService: no descriptor for [$jobId] — stale or duplicate start")
      return stop(startId)
    }

    val reporter = AutomationProgressReporter(this, progressAction, replyPackage, jobId)
    scope.launch {
      // The heartbeat runs beside the work, not inside it: the whole point is to keep speaking
      // while the worker is blocked writing into a descriptor the caller is draining slowly.
      val heartbeat = scope.launch {
        while (isActive) { delay(5_000); reporter.beat() }
      }
      try {
        fd.use { open ->
          val items = intent?.getStringExtra(AutomationProvider.KEY_ITEMS)
          if (importing) runImport(jobId, open, items, ::reply)
          else runExport(jobId, open, items, reporter.onProgress, ::reply)
        }
      } catch (e: UiExportCancelledException) {
        reply("ERROR:cancelled")
      } catch (t: Throwable) {
        Log.e(TAG, "AutomationDataService failed: ${t.stackTraceToString()}")
        reply("ERROR:${short(t)}")
      } finally {
        heartbeat.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
      }
    }
    return START_NOT_STICKY
  }

  private suspend fun runExport(
    jobId: String,
    fd: ParcelFileDescriptor,
    items: String?,
    progress: UiEximProgress,
    reply: (String) -> Unit,
  ) {
    val sel = try {
      UiEximSelection.parse(items)
    } catch (e: IllegalArgumentException) {
      reply("ERROR:${e.message}"); return
    }
    if (sel.isEmpty()) { reply("ERROR:no categories selected"); return }
    if (UiEximCategory.ACCOUNTS in sel.cats && !awaitChatReady(jobId)) {
      reply("ERROR:chat database not ready"); return
    }

    var written = 0L
    val outcome = ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
      // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we may
      // not be able to see it at all — it can be an anonymous pipe, or a descriptor into a
      // directory this app cannot list.
      val counting = object: OutputStream() {
        override fun write(b: Int) { out.write(b); written++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
        override fun flush() { out.flush() }
        // close() is deliberately NOT delegated: the export engine closes what openOut() hands
        // it, and the descriptor must outlive that so this `use` block owns exactly one close.
      }
      runHeadlessUiExport(
        sel,
        openOut = { counting },
        onProgress = progress,
        isCancelled = { AutomationJobs.isCancelled(jobId) },
      )
    }
    if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
    else reply("OK:$written|${outcome.itemCount} categories")
  }

  /**
   * Spool the archive to disk, then apply it.
   *
   * Not into a byte array: this app's backup embeds the whole chat database and its files, so the
   * archive is routinely hundreds of megabytes and reading it into memory to sniff it would be
   * fatal. The guarantee is unchanged — nothing is written until the entire archive has arrived
   * and its manifest has been checked — only the bound moves from RAM to disk.
   */
  private suspend fun runImport(
    jobId: String,
    fd: ParcelFileDescriptor,
    items: String?,
    reply: (String) -> Unit,
  ) {
    val sel = try {
      UiEximSelection.parse(items)
    } catch (e: IllegalArgumentException) {
      reply("ERROR:${e.message}"); return
    }
    if (sel.isEmpty()) { reply("ERROR:no categories selected"); return }

    tmpDir.mkdirs()
    val spool = File(tmpDir, "automation-import-$jobId.zip")
    try {
      ParcelFileDescriptor.AutoCloseInputStream(fd).use { ins ->
        FileOutputStream(spool).use { ins.copyTo(it) }
      }
      if (spool.length() == 0L) { reply("ERROR:empty archive"); return }
      if (UiEximCategory.ACCOUNTS in sel.cats && !awaitChatReady(jobId)) {
        reply("ERROR:chat database not ready"); return
      }
      val summary = runHeadlessUiImport(spool, sel)
      // Flush BEFORE saying OK. The caller force-stops us the instant it hears success, and that
      // is a SIGKILL — nothing un-flushed survives it. This app's settings layer writes through
      // multiplatform-settings' SharedPreferencesSettings, whose default is `apply()`: the value
      // is in memory immediately and on disk whenever the framework gets round to it, which under
      // a kill is never. A restore would then report success over data that was never written.
      //
      // A fresh `edit().commit()` is what forces it: SharedPreferences keeps ONE in-memory map per
      // file, and commit writes that whole map to disk synchronously — so it flushes every earlier
      // apply() too, not just this empty edit.
      flushPreferences()
      reply("OK:$summary")
    } finally {
      spool.delete()
    }
  }

  /**
   * Waits (bounded) for the chat core to finish starting.
   *
   * A provider call starts this process, so on a clean phone the core is still coming up while
   * the request is already in flight — which is exactly the restore case. Bounded rather than
   * open-ended: the contract's rule is that an app which hangs while still ticking is worse than
   * one that dies, because it holds its slot until the full timeout.
   */
  private suspend fun awaitChatReady(jobId: String): Boolean {
    repeat(120) {
      if (AutomationJobs.isCancelled(jobId)) throw UiExportCancelledException()
      if (chatModel.chatDbStatus.value != null || chatModel.chatRunning.value != null) return true
      delay(500)
    }
    return false
  }

  /** Synchronous write-through of everything [runHeadlessUiImport] just set. See its call site. */
  private fun flushPreferences() {
    runCatching {
      getSharedPreferences(AppPreferences.SHARED_PREFS_ID, Context.MODE_PRIVATE)
        .edit().commit()
    }.onFailure { Log.e(TAG, "flushPreferences failed: ${it.message}") }
  }

  private fun short(t: Throwable): String =
    (t.message ?: t::class.simpleName ?: "failed").lineSequence().first().take(160)

  private fun notification(importing: Boolean): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager?.createNotificationChannel(
        NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW),
      )
    }
    return Notification.Builder(this, CHANNEL)
      .setContentTitle(if (importing) "データを戻しています" else "データを書き出しています")
      .setSmallIcon(R.drawable.ntf_service_icon)
      .setOngoing(true)
      .build()
  }

  /**
   * Give up cleanly. Every caller of this now runs after [startForeground] has succeeded (or has
   * just failed), so the notification is dropped explicitly rather than left to service teardown;
   * `stopForeground` on a service that never went foreground is a harmless no-op.
   */
  private fun stop(startId: Int): Int {
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    stopSelf(startId)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "automation_data"
    private const val NOTIFICATION_ID = 9714
    private const val EXTRA_JOB = "job"
    private const val EXTRA_IMPORTING = "importing"

    /**
     * The descriptor's way across, because an Intent is the wrong vehicle for one.
     *
     * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and the
     * copy's lifetime stops being ours to reason about. Handing it through a map keyed by the job
     * id keeps exactly one open descriptor with exactly one owner — the service, which closes it
     * in a `finally`.
     */
    private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

    /**
     * Starts the service, or returns why it could not start.
     *
     * `startForegroundService` from a binder call is a background start, and on API 31+ it can be
     * refused outright with `ForegroundServiceStartNotAllowedException` unless this app is exempt
     * from battery optimisation. The caller must be told that as a refusal, and its descriptor
     * must not be left stranded in [HANDOVER] — so the handover entry is removed here and the
     * provider closes the dup. When 応用管理 reports a failed row naming a foreground service
     * start, the fix is the battery-optimisation exemption on this app, not the code.
     */
    fun start(
      context: Context,
      jobId: String,
      fd: ParcelFileDescriptor,
      importing: Boolean,
      extras: Bundle?,
    ): String? {
      HANDOVER[jobId] = fd
      return try {
        context.startForegroundService(
          Intent(context, AutomationDataService::class.java).apply {
            putExtra(EXTRA_JOB, jobId)
            putExtra(EXTRA_IMPORTING, importing)
            putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
            putExtra(AutomationProvider.KEY_REPLY_ACTION, extras?.getString(AutomationProvider.KEY_REPLY_ACTION))
            putExtra(AutomationProvider.KEY_REPLY_PACKAGE, extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE))
            putExtra(AutomationProvider.KEY_PROGRESS_ACTION, extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION))
          },
        )
        null
      } catch (e: Throwable) {
        // This path owns the descriptor: it never reached a service that could close it, and the
        // provider deliberately does not close it either — exactly one owner on every path.
        HANDOVER.remove(jobId)
        runCatching { fd.close() }
        Log.e(TAG, "AutomationDataService start refused: ${e.stackTraceToString()}")
        "ERROR:" + (e.message ?: e::class.simpleName ?: "service start refused").lineSequence().first().take(160)
      }
    }
  }
}
