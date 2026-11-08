package chat.simplex.common.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import chat.simplex.common.helpers.APPLICATION_ID
import java.io.File

// downstream (shiroikuma): Android side of ShiroikumaDiag — the diag directory, the recording
// notification (Mark / Turn all off), share and export.

private const val DIAG_CHANNEL = "shiroikuma_diag"
private const val DIAG_NOTIFICATION_ID = 0x51D1A6
const val DIAG_ACTION_MARK = "shiroikuma.simplex.action.DIAG_MARK"
const val DIAG_ACTION_ALL_OFF = "shiroikuma.simplex.action.DIAG_ALL_OFF"

actual fun diagBaseDir(): File? = try { androidAppContext.getExternalFilesDir(null) } catch (_: Throwable) { null }

actual fun diagPid(): Int = Process.myPid()

actual fun diagUpdateNotification(recording: List<String>) {
  val c = androidAppContext
  val nm = NotificationManagerCompat.from(c)
  if (recording.isEmpty()) {
    nm.cancel(DIAG_NOTIFICATION_ID)
    return
  }
  try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel(DIAG_CHANNEL, "Diagnostics recording", NotificationManager.IMPORTANCE_LOW))
    }
    val which = recording.joinToString(", ")
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val b = NotificationCompat.Builder(c, DIAG_CHANNEL)
      .setSmallIcon(android.R.drawable.ic_menu_edit)
      .setContentTitle("Recording diagnostics")
      .setContentText(which)
      .setStyle(NotificationCompat.BigTextStyle().bigText(which))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .addAction(0, "Mark", PendingIntent.getBroadcast(c, 1, Intent(DIAG_ACTION_MARK).setClass(c, ShiroikumaDiagReceiver::class.java), flags))
      .addAction(0, "Turn all off", PendingIntent.getBroadcast(c, 2, Intent(DIAG_ACTION_ALL_OFF).setClass(c, ShiroikumaDiagReceiver::class.java), flags))
    c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
      b.setContentIntent(PendingIntent.getActivity(c, 3, it, flags))
    }
    nm.notify(DIAG_NOTIFICATION_ID, b.build())
  } catch (_: SecurityException) {
    // no notification permission: recording goes on regardless
  }
}

/** The Mark and Turn all off actions of the recording notification. */
class ShiroikumaDiagReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      DIAG_ACTION_MARK -> {
        val n = ShiroikumaDiag.mark("notification")
        if (n > 0) Toast.makeText(context, "Mark #$n", Toast.LENGTH_SHORT).show()
      }
      DIAG_ACTION_ALL_OFF -> ShiroikumaDiag.allOff()
    }
  }
}

actual fun diagShare(files: List<File>) {
  val c = androidAppContext
  val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(c, "$APPLICATION_ID.provider", it) })
  if (uris.isEmpty()) return
  val send = Intent(Intent.ACTION_SEND_MULTIPLE)
    .setType("text/plain")
    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    .putExtra(Intent.EXTRA_SUBJECT, "白い熊 Simplex diagnostics")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  c.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual fun diagExportInitialDir(): String? =
  DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:tmp").toString()

actual fun diagExportTo(treeUri: String, subdir: String, files: List<File>) {
  val cr = androidAppContext.contentResolver
  val tree = Uri.parse(treeUri)
  val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
  val sub = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, subdir)
    ?: error("the chosen directory is not writable")
  for (f in files) {
    // octet-stream: the provider takes the name verbatim instead of appending an extension
    val out = DocumentsContract.createDocument(cr, sub, "application/octet-stream", f.name) ?: error("could not create ${f.name}")
    (cr.openOutputStream(out) ?: error("could not write ${f.name}")).use { os ->
      f.inputStream().use { it.copyTo(os, 64 * 1024) }
    }
  }
}
