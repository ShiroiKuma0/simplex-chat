package chat.simplex.app

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fork diagnostics: a capped trace of SimplexService life-cycle events (process start, service
 * create/start/destroy, restart attempts and their outcome), written to the app's external files
 * directory so it can be read over adb without logcat — EMUI keeps only ERROR lines there.
 *
 *   adb shell cat /sdcard/Android/data/shiroikuma.simplex/files/service-trace.log
 */
object ServiceTrace {
  private const val FILE_NAME = "service-trace.log"
  private const val MAX_BYTES = 256 * 1024L
  private val lock = Any()
  private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  fun log(context: Context, message: String) {
    try {
      val dir = context.applicationContext.getExternalFilesDir(null) ?: return
      synchronized(lock) {
        val file = File(dir, FILE_NAME)
        if (file.length() > MAX_BYTES) {
          val old = File(dir, "$FILE_NAME.1")
          old.delete()
          file.renameTo(old)
        }
        file.appendText("${fmt.format(Date())} pid=${Process.myPid()} $message\n")
      }
    } catch (_: Throwable) {
      // tracing must never break the service
    }
  }
}
