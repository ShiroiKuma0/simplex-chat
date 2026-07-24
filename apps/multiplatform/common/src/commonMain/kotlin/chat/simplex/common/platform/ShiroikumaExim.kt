package chat.simplex.common.platform

import androidx.compose.runtime.Composable
import java.io.OutputStream

// downstream (shiroikuma): platform plumbing for the 白い熊 Simplex UI page's Export/Import —
// a persistable directory chooser plus list/create helpers over the chosen directory.
// Android stores a SAF tree URI (persisted permission); desktop uses a plain filesystem path.

data class UiExportFileInfo(val name: String, val lastModified: Long)

@Composable
expect fun rememberDirectoryChooserLauncher(onResult: (String?) -> Unit): DirectoryChooserLauncher

expect class DirectoryChooserLauncher() {
  suspend fun launch(initialDir: String?)
}

/** Display name of the chosen directory, or null when it's gone/inaccessible. */
expect fun uiExportDirName(dirUri: String): String?

/** Files (not subdirectories) inside the chosen directory. Empty on any access failure. */
expect fun uiExportDirFiles(dirUri: String): List<UiExportFileInfo>

/** Create [fileName] inside the chosen directory and open it for writing, or null on failure. */
expect fun uiExportDirCreateFile(dirUri: String, fileName: String): OutputStream?
