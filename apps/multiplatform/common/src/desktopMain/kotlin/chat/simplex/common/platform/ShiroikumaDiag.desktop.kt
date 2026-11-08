package chat.simplex.common.platform

import java.io.File

// downstream (shiroikuma): the desktop app records into its data directory and has no recording
// notification, share sheet or SAF export.

actual fun diagBaseDir(): File? = dataDir

actual fun diagPid(): Int = ProcessHandle.current().pid().toInt()

actual fun diagUpdateNotification(recording: List<String>) {}

actual fun diagShare(files: List<File>) {}

actual fun diagExportInitialDir(): String? = null

actual fun diagExportTo(treeUri: String, subdir: String, files: List<File>) {
  val target = File(File(java.net.URI(treeUri)), subdir)
  if (!target.mkdirs()) error("could not create $target")
  files.forEach { it.copyTo(File(target, it.name)) }
}
