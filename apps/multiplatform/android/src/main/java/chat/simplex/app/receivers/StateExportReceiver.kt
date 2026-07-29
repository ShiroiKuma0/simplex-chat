package chat.simplex.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import chat.simplex.app.BuildConfig
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.*
import chat.simplex.common.views.usersettings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// downstream (shiroikuma): the 保存復元 state-export contract — 白い熊's automation app
// (白い熊 自由作業盤) backs up every sister app in one run by firing a token-gated intent at
// each of them. This app answers three actions:
//
//   <applicationId>.action.LIST_CATEGORIES  -> the exportable items, id<TAB>label<TAB>parent<TAB>on|off
//   <applicationId>.action.EXPORT_STATE     -> one ZIP written headlessly, path + size replied
//   <applicationId>.action.CANCEL_EXPORT    -> stops a running export; answers nothing, ever
//
// The reply is always a FRESH BROADCAST, never a Binder handed across processes: EMUI does not
// reliably carry a live ResultReceiver/PendingIntent/Messenger into another app's manifest
// receiver, and it severs the ordered-broadcast result channel between third-party apps. The
// ordered result is set too (correct AOSP behaviour) but is never the only reply.
// Verified on 白い熊's Mate XT, 2026-07-23.
//
// The export runs on an IO coroutine inside goAsync(), with no foreground service and no
// wakelock to unwind — so a cancel has only the write loop to stop, the part file to delete,
// and the terminal reply to send, all of which happen on the export's own failure path.

/** One EXPORT_STATE in flight, so a CANCEL_EXPORT arriving on another broadcast can reach it. */
private class ExportRun(val replyId: String) {
  @Volatile var cancelled = false
}

class StateExportReceiver: BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    val replyAction = intent.getStringExtra("reply_action")
    val replyPackage = intent.getStringExtra("reply_package")
    val replyId = intent.getStringExtra("reply_id") ?: ""
    val pending = goAsync()
    // exactly one terminal reply per request, whichever path gets there first
    val replied = AtomicBoolean(false)

    fun reply(result: String) {
      if (!replied.compareAndSet(false, true)) return
      Log.i(TAG, "StateExportReceiver: $action [$replyId] -> ${result.lineSequence().first()}")
      // correct AOSP behaviour, and free — but never the only reply, since EMUI severs the
      // ordered-result channel between third-party apps (goAsync() also detaches it here)
      try {
        pending.setResultData(result)
      } catch (e: Throwable) {
        Log.w(TAG, "setResultData failed: ${e.message}")
      }
      if (replyAction != null && replyPackage != null) {
        context.sendBroadcast(Intent(replyAction).apply {
          setPackage(replyPackage)
          addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
          putExtra("reply_id", replyId)
          putExtra("result", result)
        })
      }
      runCatching { pending.finish() }
    }

    try {
      val auth = AutomationAuth.check(intent.getStringExtra("token"))
      if (action == ACTION_CANCEL_EXPORT) {
        // Fire-and-forget: this action answers nothing, not even to refuse. Its reply_id is the
        // *export's*, so any reply sent here would reach 保存復元 on that request's channel and
        // be taken for its terminal answer — while the export is still unwinding and about to
        // send the real one. A bad token is refused in the log and nowhere else.
        if (auth == AutomationAuth.Result.OK) cancelExport(replyId)
        else Log.w(TAG, "CANCEL_EXPORT [$replyId] refused: $auth")
        runCatching { pending.finish() }
        return
      }
      when (auth) {
        AutomationAuth.Result.DISABLED -> return reply("ERROR:automation disabled")
        AutomationAuth.Result.BAD_TOKEN -> return reply("ERROR:bad token")
        AutomationAuth.Result.OK -> {}
      }
      when (action) {
        ACTION_LIST_CATEGORIES -> reply(listCategories())
        ACTION_EXPORT_STATE -> CoroutineScope(Dispatchers.IO).launch {
          try {
            reply(runExport(context, intent, replyId))
          } catch (e: UiExportCancelledException) {
            // the expected end of a cancelled run, not a failure — the part file is already gone
            Log.i(TAG, "EXPORT_STATE [$replyId] cancelled")
            reply("ERROR:cancelled")
          } catch (e: Throwable) {
            Log.e(TAG, "EXPORT_STATE failed: ${e.stackTraceToString()}")
            reply("ERROR:${shortReason(e)}")
          }
        }
        else -> reply("ERROR:unknown action")
      }
    } catch (e: Throwable) {
      Log.e(TAG, "StateExportReceiver failed: ${e.stackTraceToString()}")
      reply("ERROR:${shortReason(e)}")
    }
  }

  // ───────────────────────── CANCEL_EXPORT ─────────────────────────

  /**
   * Flips the running export's flag and returns. Everything the contract asks for next — the
   * write loop unwinding at its next boundary, the part file being deleted, the terminal
   * `ERROR:cancelled` — happens on that export's own failure path, guarded by the AtomicBoolean
   * that already makes its reply exactly-once. So a cancel can never race a success into a
   * double reply, and cancelling nothing (finished already, or never started) is a silent no-op.
   *
   * An empty [replyId] means "the export you are running", which the contract makes unambiguous
   * by forbidding two at once; the list is walked anyway rather than trusting that.
   */
  private fun cancelExport(replyId: String) {
    val targets =
      if (replyId.isEmpty()) runningExports.toList()
      else runningExports.filter { it.replyId == replyId }
    if (targets.isEmpty()) {
      Log.i(TAG, "CANCEL_EXPORT [$replyId]: nothing running")
      return
    }
    for (run in targets) {
      run.cancelled = true
      Log.i(TAG, "CANCEL_EXPORT: cancelling [${run.replyId}]")
    }
  }

  // ───────────────────────── LIST_CATEGORIES ─────────────────────────

  // id<TAB>label<TAB>parent<TAB>on|off — the parent field is empty for a top-level item, and the
  // fourth is this app stating whether the item starts ticked in 保存復元's picker rather than
  // leaving it to guess. Positional and, on the picker's side, optional.
  private fun listCategories(): String = buildString {
    append("OK:")
    uiEximItems().forEachIndexed { i, item ->
      if (i > 0) append('\n')
      append(item.id).append('\t')
        .append(item.label).append('\t')
        .append(item.parentId ?: "").append('\t')
        .append(if (item.defaultSelected) "on" else "off")
    }
  }

  // ───────────────────────── EXPORT_STATE ─────────────────────────

  private suspend fun runExport(context: Context, intent: Intent, replyId: String): String {
    val sel = try {
      UiEximSelection.parse(intent.getStringExtra("items"))
    } catch (e: IllegalArgumentException) {
      return "ERROR:${e.message}"
    }
    if (sel.isEmpty()) return "ERROR:no categories selected"

    val name = uiExportFileName()
    val target = resolveTarget(intent.getStringExtra("path"), name) ?: return "ERROR:no-directory"
    if (target is Target.Error) return "ERROR:${target.reason}"
    val ready = target as Target.Ready

    val progressAction = intent.getStringExtra("progress_action")
    val replyPackage = intent.getStringExtra("reply_package")
    val progress = progressReporter(context, progressAction, replyPackage, replyId)

    // registered before the first thing worth interrupting, so a cancel fired straight after the
    // export request still lands — the chat-ready wait below can run for a minute on a cold start
    val run = ExportRun(replyId)
    runningExports.add(run)
    var committed = false
    var written = 0L
    try {
      // the chat core is started asynchronously by SimplexApp; a cold start from this broadcast
      // may still be mid-initialisation when the accounts archive is requested
      if (UiEximCategory.ACCOUNTS in sel.cats && !awaitChatReady(run)) {
        return "ERROR:chat database not ready"
      }
      val outcome = runHeadlessUiExport(
        sel,
        openOut = {
          val raw = ready.open()
          object: FilterOutputStream(raw) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            override fun write(b: Int) { out.write(b); written++ }
          }
        },
        onProgress = progress,
        isCancelled = { run.cancelled },
      )
      // the archive is complete — only now does it get the name 保存復元 was promised
      committed = ready.commit()
      if (!committed) return "ERROR:could not give the export its final name"
      val errs =
        if (outcome.accountsErrors == 0) ""
        else " (${outcome.accountsErrors} file error${if (outcome.accountsErrors == 1) "" else "s"} in the accounts archive)"
      return "OK:${ready.displayPath}|$written|${uiHumanSize(written)}|${outcome.itemCount} categories$errs"
    } finally {
      // one exit for every ending — cancelled, thrown, or a refused rename. The part file goes,
      // and since the final name was never taken, the directory is left exactly as it was found.
      runningExports.remove(run)
      if (!committed) ready.discard()
    }
  }

  /** Waits (bounded) for the chat controller to finish starting. */
  private suspend fun awaitChatReady(run: ExportRun): Boolean {
    repeat(120) {
      if (run.cancelled) throw UiExportCancelledException()
      if (chatModel.chatDbStatus.value != null || chatModel.chatRunning.value != null) return true
      delay(500)
    }
    return false
  }

  // ───────────────────────── output target ─────────────────────────

  private sealed class Target {
    /**
     * Written under `<name>.part` throughout; [displayPath] is the name it takes only once
     * [commit] succeeds. [discard] removes the part file, and is what makes a cancelled run
     * leave nothing behind.
     */
    class Ready(
      val displayPath: String,
      val open: () -> OutputStream,
      val commit: () -> Boolean,
      val discard: () -> Unit,
    ): Target()
    class Error(val reason: String): Target()
  }

  /**
   * Directory precedence per the contract: the `path` extra overrides everything, then the
   * app's own configured export directory, then no-directory. `path` is an arbitrary absolute
   * directory, which scoped storage only allows with All-Files-Access.
   */
  private fun resolveTarget(path: String?, name: String): Target? {
    if (!path.isNullOrBlank()) {
      val dir = File(path)
      if (!dir.isDirectory) dir.mkdirs()
      if (!dir.isDirectory || !dir.canWrite()) {
        return if (hasAllFilesAccess() == false) Target.Error("no-storage-access")
        else Target.Error("cannot write to $path")
      }
      val f = File(dir, name)
      val part = File(dir, name + UI_EXPORT_PART_SUFFIX)
      var renamed = false
      return Target.Ready(
        f.absolutePath,
        open = { part.outputStream() },
        commit = {
          if (f.exists()) f.delete()
          renamed = part.renameTo(f)
          renamed
        },
        discard = { if (!renamed) part.delete() },
      )
    }
    val dirUri = appPrefs.uiExportDirectory.get() ?: return null
    val shown = absolutePathOfTree(dirUri) ?: uiExportDirName(dirUri) ?: "export directory"
    val part = UiExportPartFile(dirUri, name)
    return Target.Ready(
      "$shown/$name",
      open = { part.open() },
      commit = { part.commit() },
      discard = { part.discard() },
    )
  }

  /**
   * Best-effort real path behind a SAF tree on primary storage, so the reply carries an
   * absolute path the caller can act on rather than an opaque content URI.
   */
  private fun absolutePathOfTree(dirUri: String): String? = try {
    val docId = DocumentsContract.getTreeDocumentId(Uri.parse(dirUri))
    if (docId.startsWith("primary:")) {
      val rel = docId.removePrefix("primary:")
      val root = Environment.getExternalStorageDirectory().absolutePath
      if (rel.isEmpty()) root else "$root/$rel"
    } else null
  } catch (e: Exception) {
    null
  }

  // ───────────────────────── progress ─────────────────────────

  /** Real counts, never a percentage; at most one broadcast every 500 ms plus a final one. */
  private fun progressReporter(
    context: Context,
    progressAction: String?,
    replyPackage: String?,
    replyId: String,
  ): UiEximProgress {
    if (progressAction == null || replyPackage == null) return { _, _, _, _ -> }
    var last = 0L
    return { current, total, unit, text ->
      val now = System.currentTimeMillis()
      if (current >= total || now - last >= PROGRESS_INTERVAL_MS) {
        last = now
        try {
          context.sendBroadcast(Intent(progressAction).apply {
            setPackage(replyPackage)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("reply_id", replyId)
            putExtra("app", APP_LABEL)
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
  }

  private fun shortReason(e: Throwable): String =
    (e.message ?: e::class.simpleName ?: "failed").lineSequence().first().take(160)

  companion object {
    // the action strings carry the applicationId (shiroikuma.simplex), matching the manifest's
    // ${applicationId} placeholder — not the Kotlin namespace, which stays chat.simplex.app
    val ACTION_EXPORT_STATE = "${BuildConfig.APPLICATION_ID}.action.EXPORT_STATE"
    val ACTION_LIST_CATEGORIES = "${BuildConfig.APPLICATION_ID}.action.LIST_CATEGORIES"
    val ACTION_CANCEL_EXPORT = "${BuildConfig.APPLICATION_ID}.action.CANCEL_EXPORT"
    private const val APP_LABEL = "白い熊 SimpleX"
    private const val PROGRESS_INTERVAL_MS = 500L

    // process-wide, because each broadcast gets its own receiver instance: the CANCEL_EXPORT
    // that arrives minutes after EXPORT_STATE has no other way back to the run it must stop
    private val runningExports = CopyOnWriteArrayList<ExportRun>()
  }
}
