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
