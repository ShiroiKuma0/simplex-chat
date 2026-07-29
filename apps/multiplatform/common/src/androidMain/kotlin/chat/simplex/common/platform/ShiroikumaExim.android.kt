package chat.simplex.common.platform

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import java.io.OutputStream

// downstream (shiroikuma): SAF (DocumentsContract) implementation of the UI-page export directory.
// Uses DocumentsContract directly instead of androidx.documentfile so no new dependency is needed.

@Composable
actual fun rememberDirectoryChooserLauncher(onResult: (String?) -> Unit): DirectoryChooserLauncher {
  val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
    onResult = { uri ->
      if (uri != null) {
        try {
          androidAppContext.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          )
        } catch (e: Exception) {
          Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        }
        onResult(uri.toString())
      } else {
        onResult(null)
      }
    }
  )
  return DirectoryChooserLauncher(launcher)
}

actual class DirectoryChooserLauncher actual constructor() {
  private lateinit var launcher: ManagedActivityResultLauncher<Uri?, Uri?>

  constructor(launcher: ManagedActivityResultLauncher<Uri?, Uri?>): this() {
    this.launcher = launcher
  }

  actual suspend fun launch(initialDir: String?) {
    launcher.launch(initialDir?.let { runCatching { Uri.parse(it) }.getOrNull() })
  }
}

private fun treeDocumentUri(dirUri: String): Uri? = try {
  val tree = Uri.parse(dirUri)
  DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
} catch (e: Exception) {
  null
}

actual fun uiExportDirName(dirUri: String): String? = try {
  val docUri = treeDocumentUri(dirUri) ?: return null
  androidAppContext.contentResolver.query(
    docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
  )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    ?: Uri.parse(dirUri).lastPathSegment?.substringAfterLast(':')?.ifEmpty { null }
} catch (e: Exception) {
  null
}

actual fun uiExportDirFiles(dirUri: String): List<UiExportFileInfo> = try {
  val tree = Uri.parse(dirUri)
  val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
  androidAppContext.contentResolver.query(
    childrenUri,
    arrayOf(
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    ),
    null, null, null
  )?.use { c ->
    val res = ArrayList<UiExportFileInfo>()
    while (c.moveToNext()) {
      val mime = c.getString(2)
      if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
        res.add(UiExportFileInfo(name = c.getString(0) ?: continue, lastModified = c.getLong(1)))
      }
    }
    res
  } ?: emptyList()
} catch (e: Exception) {
  Log.w(TAG, "uiExportDirFiles failed: ${e.message}")
  emptyList()
}

actual fun uiExportDirCreateFile(dirUri: String, fileName: String): OutputStream? = try {
  val parentDocUri = treeDocumentUri(dirUri) ?: return null
  val fileUri = DocumentsContract.createDocument(
    androidAppContext.contentResolver, parentDocUri, "application/zip", fileName
  ) ?: return null
  androidAppContext.contentResolver.openOutputStream(fileUri)
} catch (e: Exception) {
  Log.w(TAG, "uiExportDirCreateFile failed: ${e.message}")
  null
}

actual class UiExportPartFile actual constructor(
  private val dirUri: String,
  private val fileName: String,
) {
  private var uri: Uri? = null
  private var committed = false

  actual fun open(): OutputStream {
    val parentDocUri = treeDocumentUri(dirUri) ?: error("the export directory is no longer reachable")
    // octet-stream, not application/zip: a provider appends the extension its mime implies when
    // the display name doesn't already end in one, which would turn "….zip.part" into
    // "….zip.part.zip". With octet-stream the name is taken verbatim.
    val created = DocumentsContract.createDocument(
      androidAppContext.contentResolver, parentDocUri, "application/octet-stream", fileName + UI_EXPORT_PART_SUFFIX
    ) ?: error("could not create a file in the export directory")
    uri = created
    return androidAppContext.contentResolver.openOutputStream(created)
      ?: error("could not open the export file for writing")
  }

  actual fun commit(): Boolean {
    val u = uri ?: return false
    return try {
      // null means "renamed, same URI"; a refusal arrives as an exception
      DocumentsContract.renameDocument(androidAppContext.contentResolver, u, fileName)?.let { uri = it }
      committed = true
      true
    } catch (e: Exception) {
      Log.w(TAG, "renameDocument failed: ${e.message}")
      false
    }
  }

  actual fun discard() {
    val u = uri ?: return
    if (committed) return
    uri = null
    try {
      DocumentsContract.deleteDocument(androidAppContext.contentResolver, u)
    } catch (e: Exception) {
      Log.w(TAG, "deleteDocument failed: ${e.message}")
    }
  }
}
