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
import java.util.concurrent.atomic.AtomicBoolean

// downstream (shiroikuma): the 保存復元 state-export contract — 白い熊's automation app
// (白い熊 自由作業盤) backs up every sister app in one run by firing a token-gated intent at
// each of them. This app answers two actions:
//
//   <applicationId>.action.LIST_CATEGORIES  -> the exportable items, id<TAB>label[<TAB>parent]
//   <applicationId>.action.EXPORT_STATE     -> one ZIP written headlessly, path + size replied
//
// The reply is always a FRESH BROADCAST, never a Binder handed across processes: EMUI does not
// reliably carry a live ResultReceiver/PendingIntent/Messenger into another app's manifest
// receiver, and it severs the ordered-broadcast result channel between third-party apps. The
// ordered result is set too (correct AOSP behaviour) but is never the only reply.
// Verified on 白い熊's Mate XT, 2026-07-23.
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
      when (AutomationAuth.check(intent.getStringExtra("token"))) {
        AutomationAuth.Result.DISABLED -> return reply("ERROR:automation disabled")
        AutomationAuth.Result.BAD_TOKEN -> return reply("ERROR:bad token")
        AutomationAuth.Result.OK -> {}
      }
      when (action) {
        ACTION_LIST_CATEGORIES -> reply(listCategories())
        ACTION_EXPORT_STATE -> CoroutineScope(Dispatchers.IO).launch {
          try {
            reply(runExport(context, intent, replyId))
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

  // ───────────────────────── LIST_CATEGORIES ─────────────────────────

  private fun listCategories(): String = buildString {
    append("OK:")
    uiEximItems().forEachIndexed { i, item ->
      if (i > 0) append('\n')
      append(item.id).append('\t').append(item.label)
      if (item.parentId != null) append('\t').append(item.parentId)
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

    // the chat core is started asynchronously by SimplexApp; a cold start from this broadcast
    // may still be mid-initialisation when the accounts archive is requested
    if (UiEximCategory.ACCOUNTS in sel.cats && !awaitChatReady()) {
      return "ERROR:chat database not ready"
    }

    val progressAction = intent.getStringExtra("progress_action")
    val replyPackage = intent.getStringExtra("reply_package")
    val progress = progressReporter(context, progressAction, replyPackage, replyId)

    var written = 0L
    val outcome = runHeadlessUiExport(
      sel,
      openOut = {
        val raw = (target as Target.Ready).open()
        object: FilterOutputStream(raw) {
          override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
          override fun write(b: Int) { out.write(b); written++ }
        }
      },
      onProgress = progress,
    )
    val path = (target as Target.Ready).displayPath
    val errs =
      if (outcome.accountsErrors == 0) ""
      else " (${outcome.accountsErrors} file error${if (outcome.accountsErrors == 1) "" else "s"} in the accounts archive)"
    return "OK:$path|$written|${uiHumanSize(written)}|${outcome.itemCount} categories$errs"
  }

  /** Waits (bounded) for the chat controller to finish starting. */
  private suspend fun awaitChatReady(): Boolean {
    repeat(120) {
      if (chatModel.chatDbStatus.value != null || chatModel.chatRunning.value != null) return true
      delay(500)
    }
    return false
  }

  // ───────────────────────── output target ─────────────────────────

  private sealed class Target {
    class Ready(val displayPath: String, val open: () -> OutputStream): Target()
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
      return Target.Ready(f.absolutePath) { f.outputStream() }
    }
    val dirUri = appPrefs.uiExportDirectory.get() ?: return null
    val shown = absolutePathOfTree(dirUri) ?: uiExportDirName(dirUri) ?: "export directory"
    return Target.Ready("$shown/$name") {
      uiExportDirCreateFile(dirUri, name) ?: error("could not create a file in the export directory")
    }
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
    private const val APP_LABEL = "白い熊 SimpleX"
    private const val PROGRESS_INTERVAL_MS = 500L
  }
}
