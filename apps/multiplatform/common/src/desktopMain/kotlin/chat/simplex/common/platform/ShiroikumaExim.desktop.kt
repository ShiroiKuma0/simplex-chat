package chat.simplex.common.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.swing.JFileChooser

// downstream (shiroikuma): desktop implementation of the UI-page export directory — a plain
// filesystem path picked with a Swing directory chooser. The fork ships Android-only; this
// exists to keep the desktop target compiling and minimally functional.

@Composable
actual fun rememberDirectoryChooserLauncher(onResult: (String?) -> Unit): DirectoryChooserLauncher =
  remember { DirectoryChooserLauncher(onResult) }

actual class DirectoryChooserLauncher actual constructor() {
  private lateinit var onResult: (String?) -> Unit

  constructor(onResult: (String?) -> Unit): this() {
    this.onResult = onResult
  }

  actual suspend fun launch(initialDir: String?) {
    val res = withContext(Dispatchers.IO) {
      val chooser = JFileChooser(initialDir?.let(::File)).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Export directory"
      }
      if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
    onResult(res)
  }
}

actual fun uiExportDirName(dirUri: String): String? =
  File(dirUri).takeIf { it.isDirectory }?.name

actual fun uiExportDirFiles(dirUri: String): List<UiExportFileInfo> =
  File(dirUri).listFiles()?.filter { it.isFile }?.map { UiExportFileInfo(it.name, it.lastModified()) } ?: emptyList()

actual fun uiExportDirCreateFile(dirUri: String, fileName: String): OutputStream? = try {
  FileOutputStream(File(File(dirUri), fileName))
} catch (e: Exception) {
  null
}

actual class UiExportPartFile actual constructor(dirUri: String, fileName: String) {
  private val target = File(File(dirUri), fileName)
  private val part = File(File(dirUri), fileName + UI_EXPORT_PART_SUFFIX)
  private var opened = false
  private var committed = false

  actual fun open(): OutputStream = FileOutputStream(part).also { opened = true }

  actual fun commit(): Boolean {
    if (target.exists()) target.delete()
    committed = part.renameTo(target)
    return committed
  }

  actual fun discard() {
    if (opened && !committed) part.delete()
  }
}
