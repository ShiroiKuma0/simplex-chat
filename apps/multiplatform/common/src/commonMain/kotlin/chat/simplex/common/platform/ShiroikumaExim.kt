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

/** Suffix the headless export writes under until it has something worth naming. */
const val UI_EXPORT_PART_SUFFIX = ".part"

/**
 * An export being written into the chosen directory as `<fileName>`[UI_EXPORT_PART_SUFFIX].
 * The final name is claimed only by [commit], so a run that fails or is cancelled leaves the
 * directory exactly as it found it — no short archive under the real name, no stray part file.
 * Used by the headless 保存復元 path, where nobody is watching to clean up after a cancel.
 */
expect class UiExportPartFile(dirUri: String, fileName: String) {
  /** Creates the part file and opens it for writing. Throws if the directory is unusable. */
  fun open(): OutputStream
  /** Renames the part file to its final name. False when the rename was refused. */
  fun commit(): Boolean
  /** Deletes the part file. A no-op before [open] and after a successful [commit]. */
  fun discard()
}
