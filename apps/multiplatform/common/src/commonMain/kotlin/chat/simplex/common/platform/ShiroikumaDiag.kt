package chat.simplex.common.platform

import chat.simplex.common.BuildConfigCommon
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.model.SharedPreference
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * downstream (shiroikuma): UI → Diagnostics. Every diagnostic this fork writes sits behind its own
 * switch, all OFF by default — off means the line is never written. The same contract as 白い熊
 * 地図's, Jami's and 乞食's Diagnostics (global skill “diagnostics-ui-settings”).
 *
 * Everything goes to “/sdcard/Android/data/shiroikuma.simplex/files/diag/”, one file per kind per
 * day: “simplex-<kind>_<yyyy-MM-dd>.txt”. Never “.log”: EMUI's nightly clean-up (Phone Manager,
 * while charging) deletes every “*.log” under Android/data as junk — on 2026-11-05 it wiped the
 * old “service-trace.log” and with it the record of the night SimplexService died.
 *
 * Each line is opened, appended and closed, so a file deleted under us is simply recreated on the
 * next line — nothing keeps writing into an unlinked inode.
 */
object ShiroikumaDiag {
  enum class Kind(val key: String, val title: String, val tag: String, val description: String) {
    SERVICE(
      "service", "Service life-cycle", "simplex-service",
      "Process starts and how the previous process ended (Android's exit record), the background " +
        "service being created, started, destroyed or swiped away, every restart path (boot, " +
        "update, alarm, the 15-minute watchdog) and whether it took."
    ),
    DEVICE(
      "device", "Device and power", "simplex-device",
      "Screen on and off, Doze entering and leaving, power-save mode, and at each process start " +
        "and watchdog run the app's standby bucket, battery-optimisation and background-restriction state."
    ),
    NETWORK(
      "network", "Connectivity and relays", "simplex-network",
      "Network changes as Android reports them (Wi-Fi, mobile, lost, validated) and what was passed " +
        "to the chat core, plus the core's subscription status for the relay connections."
    ),
    MESSAGES(
      "messages", "Messages and calls", "simplex-messages",
      "Each message and call invitation as it arrives — chat id, when it was sent and how late it " +
        "arrived — and whether a notification was shown or why not. No message text is written."
    );

    val pref: SharedPreference<Boolean>
      get() = when (this) {
        SERVICE -> appPrefs.diagService
        DEVICE -> appPrefs.diagDevice
        NETWORK -> appPrefs.diagNetwork
        MESSAGES -> appPrefs.diagMessages
      }
  }

  const val DIR = "diag"
  private const val PREFIX = "simplex-"
  private const val EXT = ".txt"
  // a day's file stops growing here, so a runaway logger cannot fill the phone
  private const val DAY_CAP = 16L * 1024 * 1024
  private val lock = Any()
  private var marks = 0

  private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private fun clockFmt() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  fun isOn(kind: Kind): Boolean = try { kind.pref.get() } catch (_: Throwable) { false }

  fun anyOn(): Boolean = Kind.values().any { isOn(it) }

  /** Writes [message] to the [kind]'s file of today — only while its switch is on. */
  fun log(kind: Kind, message: String) {
    if (isOn(kind)) write(kind, message)
  }

  /** As [log], with the message built only when the switch is on. */
  inline fun log(kind: Kind, message: () -> String) {
    if (isOn(kind)) write(kind, message())
  }

  fun write(kind: Kind, message: String) {
    try {
      val d = dir() ?: return
      synchronized(lock) {
        val now = Date()
        val f = File(d, "$PREFIX${kind.key}_${dayFmt().format(now)}$EXT")
        if (f.length() > DAY_CAP) return
        FileOutputStream(f, true).use {
          it.write("${clockFmt().format(now)} pid=${diagPid()} $message\n".toByteArray(Charsets.UTF_8))
        }
      }
    } catch (_: Throwable) {
      // diagnostics must never break the app
    }
  }

  fun set(kind: Kind, value: Boolean) {
    kind.pref.set(value)
    if (value) write(kind, "diag ${kind.key} on, ${BuildConfigCommon.ANDROID_VERSION_NAME} (${BuildConfigCommon.ANDROID_VERSION_CODE})")
    else write(kind, "diag ${kind.key} off")
    updateNotification()
  }

  fun allOff() {
    Kind.values().filter { isOn(it) }.forEach {
      write(it, "diag ${it.key} off from the notification")
      it.pref.set(false)
    }
    updateNotification()
  }

  /** A numbered marker in every recording log: “something happened here”. 0 when nothing records. */
  fun mark(from: String): Int {
    val on = Kind.values().filter { isOn(it) }
    if (on.isEmpty()) return 0
    val n = synchronized(lock) { ++marks }
    on.forEach { write(it, "mark #$n from $from") }
    return n
  }

  fun updateNotification() {
    try {
      diagUpdateNotification(Kind.values().filter { isOn(it) }.map { it.title })
    } catch (_: Throwable) {
    }
  }

  /** Once per process: move old trace files in, apply retention, show the recording notification. */
  fun init() {
    updateNotification()
    Thread({
      migrate()
      cleanup()
    }, "simplex-diag-init").apply { isDaemon = true }.start()
  }

  fun dir(): File? {
    val base = diagBaseDir() ?: return null
    val d = File(base, DIR)
    return if (d.isDirectory || d.mkdirs()) d else null
  }

  // ---------- moving the old trace in ----------

  /**
   * Up to 7.1-beta.9+002 the service trace was “files/service-trace.log” (+ “.log.1”) — always on,
   * and deleted by EMUI. Move whatever survives into the service kind's files, split by day.
   */
  private fun migrate() {
    val base = diagBaseDir() ?: return
    val d = dir() ?: return
    listOf(File(base, "service-trace.log.1"), File(base, "service-trace.log")).forEach { old ->
      if (!old.isFile) return@forEach
      try {
        synchronized(lock) {
          // lines start “yyyy-MM-dd HH:mm:ss.SSS …”: keep the clock, file them by their day
          val byDay = LinkedHashMap<String, StringBuilder>()
          old.forEachLine { line ->
            val day = if (line.length > 11 && line[4] == '-' && line[10] == ' ') line.substring(0, 10) else dayFmt().format(Date(old.lastModified()))
            val rest = if (line.length > 11 && line[4] == '-' && line[10] == ' ') line.substring(11) else line
            byDay.getOrPut(day) { StringBuilder() }.append(rest).append('\n')
          }
          byDay.forEach { (day, text) ->
            val to = File(d, "${PREFIX}service_$day$EXT")
            // old lines go in front of anything already recorded that day
            val tmp = File(d, to.name + ".moving")
            FileOutputStream(tmp).use { out ->
              out.write(text.toString().toByteArray(Charsets.UTF_8))
              if (to.exists()) copy(to, out)
            }
            if (!tmp.renameTo(to)) error("rename $tmp")
          }
          old.delete()
        }
      } catch (_: Throwable) {
        // left where it was; the next start tries again
      }
    }
  }

  private fun copy(f: File, out: OutputStream) {
    FileInputStream(f).use { it.copyTo(out, 64 * 1024) }
  }

  // ---------- what is stored ----------

  /** All files in the diag directory, oldest name first. */
  fun files(): List<File> =
    dir()?.listFiles { f -> f.isFile && !f.name.endsWith(".moving") }?.sortedBy { it.name } ?: emptyList()

  class Summary {
    var files = 0
    var bytes = 0L
    var first = Long.MAX_VALUE
    var last = 0L
    /** day → [first, last] on that day */
    val days = TreeMap<String, LongArray>()
    /** kind → [files, bytes] */
    val kinds = TreeMap<String, LongArray>()
  }

  fun summarize(): Summary {
    val s = Summary()
    for (f in files()) {
      val end = f.lastModified()
      var start = firstTime(f)
      if (start <= 0 || start > end) start = end
      s.files++
      s.bytes += f.length()
      s.first = minOf(s.first, start)
      s.last = maxOf(s.last, end)
      dayOf(f.name)?.let { day ->
        val span = s.days[day]
        if (span == null) s.days[day] = longArrayOf(start, end)
        else { span[0] = minOf(span[0], start); span[1] = maxOf(span[1], end) }
      }
      val kind = kindOf(f.name)
      val k = s.kinds.getOrPut(kind) { longArrayOf(0, 0) }
      k[0]++
      k[1] += f.length()
    }
    return s
  }

  private fun kindOf(name: String): String =
    if (name.startsWith(PREFIX) && name.contains('_')) name.substring(PREFIX.length, name.indexOf('_')) else "other"

  private fun dayOf(name: String): String? {
    val us = name.indexOf('_')
    return if (us >= 0 && name.length >= us + 11) name.substring(us + 1, us + 11) else null
  }

  /** The time of a file's first line: the day from its name, the clock from the line. */
  private fun firstTime(f: File): Long = try {
    val day = dayOf(f.name)
    val line = f.bufferedReader().use { it.readLine() }
    if (day == null || line == null || line.length < 8) 0
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("$day ${line.substring(0, 8)}")?.time ?: 0
  } catch (_: Throwable) {
    0
  }

  // ---------- keeping, export, purge ----------

  /** Deletes the files of days older than the kept ones (0 keeps everything); returns how many went. */
  fun cleanup(): Int {
    val keep = try { appPrefs.diagKeepDays.get() } catch (_: Throwable) { 0 }
    if (keep <= 0) return 0
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_MONTH, -(keep - 1))
    val oldestKept = dayFmt().format(cal.time)
    var n = 0
    synchronized(lock) {
      for (f in files()) {
        val day = dayOf(f.name)
        if (day != null && day < oldestKept && f.delete()) n++
      }
    }
    return n
  }

  /** Copies every diag file into a new “<stamp>_simplex-diag” subdirectory of the chosen directory. */
  fun export(treeUri: String): String {
    val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + "_simplex-diag"
    diagExportTo(treeUri, name, files())
    return name
  }

  /** Deletes every diag file; returns how many went. */
  fun purge(): Int {
    var n = 0
    synchronized(lock) {
      for (f in files()) if (f.delete()) n++
    }
    return n
  }
}

/** The app's external files directory (“/sdcard/Android/data/<package>/files”), or null. */
expect fun diagBaseDir(): File?

expect fun diagPid(): Int

/** Shows (non-empty list) or cancels the ongoing “recording” notification with Mark and Turn all off. */
expect fun diagUpdateNotification(recording: List<String>)

/** Offers every diag file to another app. */
expect fun diagShare(files: List<File>)

/** Where the export directory picker first opens (“/sdcard/tmp”), or null. */
expect fun diagExportInitialDir(): String?

/** Creates [subdir] in the picked tree and copies [files] into it; throws when it cannot. */
expect fun diagExportTo(treeUri: String, subdir: String, files: List<File>)
