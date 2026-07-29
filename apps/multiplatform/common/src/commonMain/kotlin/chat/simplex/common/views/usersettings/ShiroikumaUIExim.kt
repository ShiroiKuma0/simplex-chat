package chat.simplex.common.views.usersettings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.ArchiveConfig
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.model.SharedPreference
import chat.simplex.common.model.json
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.ThemeManager
import chat.simplex.common.views.database.*
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// downstream (shiroikuma): Export/Import of every 白い熊 Simplex UI setting, by category —
// same idea and flow as the Kōjiki UI page: a persisted export directory queried for the
// latest export, category checkboxes, one-tap export into the directory (save-as fallback),
// import via file picker, and a bordered result dialog. The Cancel/Import/Export pill row
// follows ArcaneChat's dialog style. On success the whole chain auto-closes (info dialog →
// panel → UI settings page); failures leave the panel open.
//
// The Accounts category (first in the list) embeds the SimpleX chat-database archive
// (upstream's export/import database machinery): export stops the chat, produces the archive
// and restarts; import confirms the destructive replacement, then wipes and re-imports the
// database with the chat stopped.
//
// The same engine backs the headless 保存復元 automation path (ShiroikumaAutomation.kt +
// StateExportReceiver): one ZIP per request, categories selectable by id, progress reported
// as real counts. The panel and the receiver are two thin callers of runUiExport below.

// mandatory family naming: <english-dash-separated-app-name>_<timestamp>.zip, no version and
// no decoration, so every sister app's backups sort and read uniformly in one directory
const val UI_EXPORT_PREFIX = "shiroikuma-simplex_"
// backups written before the family convention landed — still recognised by "last export"
private const val UI_EXPORT_PREFIX_LEGACY = "shiroikuma-simplex-ui_"
private const val UI_EXPORT_FORMAT = "shiroikuma-simplex-ui"
// the SimpleX chat-database archive (all accounts/profiles with their keys, contacts and
// messages), embedded verbatim as one entry inside the UI export ZIP
private const val ACCOUNTS_ENTRY = "accounts.zip"
internal val WARN_COLOR = Color(0xFFFF5252)

// ───────────────────────── categories ─────────────────────────

// `defaultSelected` is this app's own answer to "does this item start ticked?" — sent as the
// fourth field of LIST_CATEGORIES and used to seed the panel below, so 保存復元's item editor
// and the in-app sheet open on the same set instead of the picker guessing.
//
// Everything here is `on`. An item starts unticked only when what it holds is large, derived
// AND re-creatable (downloaded map tiles, a regenerable thumbnail cache); this app exports
// nothing of that kind.
enum class UiEximCategory(val id: String, val label: String, val defaultSelected: Boolean = true) {
  ACCOUNTS("accounts", "Accounts"),
  APP_COLORS("app_colors", "App colors"),
  FONT("font", "Font"),
  CHAT_LIST("chat_list", "Chat list"),
  CHAT_BUBBLES("chat_bubbles", "Chat bubbles"),
  CHAT_VIEW("chat_view", "Chat view"),
  DELIVERY_TICKS("delivery_ticks", "Delivery ticks");
}

// The one category with independently selectable parts: Font carries both the font settings
// and the installed .ttf/.otf files, which are far bigger than everything else combined.
const val UI_EXIM_FONT_FILES_ID = "font.files"
// Ticked despite that size: an imported .ttf is a user file this app cannot re-create, so it
// fails the "derived and re-creatable" test the same way every other item does.
private const val UI_EXIM_FONT_FILES_DEFAULT = true

/** One selectable item of `LIST_CATEGORIES` / `items`: a category, or a part of one. */
data class UiEximItem(
  val id: String,
  val label: String,
  val parentId: String?,
  val defaultSelected: Boolean = true,
)

fun uiEximItems(): List<UiEximItem> = buildList {
  for (cat in UiEximCategory.entries) {
    add(UiEximItem(cat.id, cat.label, null, cat.defaultSelected))
    if (cat == UiEximCategory.FONT) {
      add(UiEximItem(UI_EXIM_FONT_FILES_ID, "Font files (.ttf/.otf)", cat.id, UI_EXIM_FONT_FILES_DEFAULT))
    }
  }
}

/**
 * What one export/import request covers. A parent id without its children means "that
 * category's own data only" — so `font` is the font settings and `font.files` the files.
 */
class UiEximSelection(val cats: Set<UiEximCategory>, val fontFiles: Boolean) {
  val itemCount: Int get() = cats.size + if (fontFiles) 1 else 0
  fun isEmpty(): Boolean = itemCount == 0
  /** Ids recorded in the manifest, in list order. */
  fun ids(): List<String> = uiEximItems()
    .filter { if (it.id == UI_EXIM_FONT_FILES_ID) fontFiles else cats.any { c -> c.id == it.id } }
    .map { it.id }

  companion object {
    /** What an absent `items` extra means: exactly the items marked `on` above. */
    fun defaults() = UiEximSelection(
      UiEximCategory.entries.filter { it.defaultSelected }.toSet(),
      UI_EXIM_FONT_FILES_DEFAULT,
    )

    /** The panel's checkbox list is per category; ticking Font takes its files along. */
    fun ofCategories(cats: Set<UiEximCategory>) =
      UiEximSelection(cats, UiEximCategory.FONT in cats)

    /**
     * Parses the automation `items` extra. Absent/blank selects this app's default set.
     * @throws IllegalArgumentException naming every unknown id.
     */
    fun parse(items: String?): UiEximSelection {
      if (items.isNullOrBlank()) return defaults()
      val requested = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
      if (requested.isEmpty()) return defaults()
      val known = uiEximItems().associateBy { it.id }
      val unknown = requested.filter { it !in known }
      if (unknown.isNotEmpty()) {
        throw IllegalArgumentException("unknown category in items: ${unknown.joinToString(",")}")
      }
      val cats = UiEximCategory.entries.filter { it.id in requested }.toSet()
      return UiEximSelection(cats, UI_EXIM_FONT_FILES_ID in requested)
    }
  }
}

/**
 * Progress reporting for a running export: real counts, never a percentage.
 * [current]/[total] are in [unit]s; [text] is the display line 白い熊 reads.
 */
typealias UiEximProgress = (current: Long, total: Long, unit: String, text: String) -> Unit

private val NO_PROGRESS: UiEximProgress = { _, _, _, _ -> }

/**
 * Polled by the export at every boundary it can safely unwind from — between ZIP entries, and
 * between the copy buffers of the accounts archive, which is far too big to wait out. Nothing
 * is ever interrupted mid-write; the flag is only read.
 */
typealias UiEximCancelled = () -> Boolean

private val NOT_CANCELLED: UiEximCancelled = { false }

/**
 * Raised when [UiEximCancelled] reports a cancel. Carries the exact wording the 保存復元
 * contract's terminal reply uses, so the caller's `ERROR:${message}` reads `ERROR:cancelled`.
 * Whoever opened the output file deletes it as it unwinds.
 */
class UiExportCancelledException: Exception("cancelled")

private fun checkCancel(isCancelled: UiEximCancelled) {
  if (isCancelled()) throw UiExportCancelledException()
}

/** `4.6 MB`, `1.20 GB` — for display beside the exact byte count. */
fun uiHumanSize(bytes: Long): String = when {
  bytes >= 1L shl 30 -> "%.2f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
  bytes >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
  bytes >= 1L shl 10 -> "%.1f KB".format(Locale.ROOT, bytes / (1L shl 10).toDouble())
  else -> "$bytes B"
}

private sealed class PrefSpec(val key: String) {
  class Str(key: String, val pref: SharedPreference<String?>): PrefSpec(key)
  class Flt(key: String, val pref: SharedPreference<Float>): PrefSpec(key)
  class Bool(key: String, val pref: SharedPreference<Boolean>): PrefSpec(key)
}

// Every settable item of the UI page, split the same way as the page's sections.
// Keys are logical names, deliberately decoupled from the SharedPreferences key strings.
private fun UiEximCategory.specs(): List<PrefSpec> = when (this) {
  // accounts live in the chat database, not in prefs — handled by the archive entry, not specs
  UiEximCategory.ACCOUNTS -> emptyList()
  UiEximCategory.APP_COLORS -> listOf(
    PrefSpec.Str("uiBackgroundColor", appPrefs.uiBackgroundColor),
    PrefSpec.Str("uiTextColor", appPrefs.uiTextColor),
    PrefSpec.Str("uiAccentColor", appPrefs.uiAccentColor),
    PrefSpec.Str("uiSecondaryColor", appPrefs.uiSecondaryColor),
    PrefSpec.Str("recentPickedColors", appPrefs.recentPickedColors),
  )
  UiEximCategory.FONT -> listOf(
    PrefSpec.Str("appFontFamily", appPrefs.appFontFamily),
    PrefSpec.Flt("fontScale", appPrefs.fontScale),
  )
  UiEximCategory.CHAT_LIST -> listOf(
    PrefSpec.Str("chatListNameColor", appPrefs.chatListNameColor),
    PrefSpec.Flt("profileImageCornerRadius", appPrefs.profileImageCornerRadius),
    PrefSpec.Bool("showPrivateNotes", appPrefs.showPrivateNotes),
  )
  UiEximCategory.CHAT_BUBBLES -> listOf(
    PrefSpec.Str("bubbleReceivedBackgroundColor", appPrefs.bubbleReceivedBackgroundColor),
    PrefSpec.Str("bubbleReceivedTextColor", appPrefs.bubbleReceivedTextColor),
    PrefSpec.Str("bubbleReceivedBorderColor", appPrefs.bubbleReceivedBorderColor),
    PrefSpec.Str("bubbleSentBackgroundColor", appPrefs.bubbleSentBackgroundColor),
    PrefSpec.Str("bubbleSentTextColor", appPrefs.bubbleSentTextColor),
    PrefSpec.Str("bubbleSentBorderColor", appPrefs.bubbleSentBorderColor),
    PrefSpec.Flt("bubbleBorderWidth", appPrefs.bubbleBorderWidth),
    PrefSpec.Flt("chatItemRoundness", appPrefs.chatItemRoundness),
    PrefSpec.Bool("chatItemTail", appPrefs.chatItemTail),
    PrefSpec.Flt("bubbleSenderIconSize", appPrefs.bubbleSenderIconSize),
    PrefSpec.Flt("bubbleSenderNameSize", appPrefs.bubbleSenderNameSize),
  )
  UiEximCategory.CHAT_VIEW -> listOf(
    PrefSpec.Bool("chatDateBold", appPrefs.chatDateBold),
    PrefSpec.Bool("chatDateUnderline", appPrefs.chatDateUnderline),
    PrefSpec.Flt("callIconScale", appPrefs.callIconScale),
  )
  UiEximCategory.DELIVERY_TICKS -> listOf(
    PrefSpec.Flt("messageTickScale", appPrefs.messageTickScale),
    PrefSpec.Flt("messageTickThickness", appPrefs.messageTickThickness),
    PrefSpec.Str("messageTickSentColor", appPrefs.messageTickSentColor),
    PrefSpec.Str("messageTickReceivedColor", appPrefs.messageTickReceivedColor),
    PrefSpec.Flt("messageTickDotScale", appPrefs.messageTickDotScale),
    PrefSpec.Str("messageTickSentGlyph", appPrefs.messageTickSentGlyph),
    PrefSpec.Str("messageTickDeliveredGlyph", appPrefs.messageTickDeliveredGlyph),
  )
}

// ───────────────────────── status / naming ─────────────────────────

fun uiExportFileName(): String =
  UI_EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

/** (message, isWarning) for the "last export" line — queried from the export directory. */
fun uiLastExportStatus(dirUri: String?): Pair<String, Boolean> {
  if (dirUri == null) return "No directory set yet — pick one to enable one-tap export." to true
  val newest = uiExportDirFiles(dirUri)
    .filter {
      (it.name.startsWith(UI_EXPORT_PREFIX) || it.name.startsWith(UI_EXPORT_PREFIX_LEGACY)) &&
        it.name.endsWith(".zip")
    }
    .maxByOrNull { it.lastModified }
    ?: return "No export in this directory yet." to true
  val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified))
  return "Last export: $ts" to false
}

// ───────────────────────── export / import engine ─────────────────────────

private fun writeUiExportZip(
  sel: UiEximSelection,
  out: OutputStream,
  accountsArchive: File?,
  onProgress: UiEximProgress,
  isCancelled: UiEximCancelled,
) {
  val total = sel.itemCount.toLong()
  var done = 0L
  fun step(label: String) {
    done++
    onProgress(done, total, "区分", "区分 $done/$total — $label")
  }
  ZipOutputStream(out).use { zip ->
    fun entry(name: String, bytes: ByteArray) {
      zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }
    val manifest = buildJsonObject {
      put("format", JsonPrimitive(UI_EXPORT_FORMAT))
      put("version", JsonPrimitive(2))
      put("app", JsonPrimitive("shiroikuma.simplex"))
      put("createdTs", JsonPrimitive(System.currentTimeMillis()))
      put("categories", JsonArray(sel.ids().map { JsonPrimitive(it) }))
    }
    entry("manifest.json", manifest.toString().toByteArray())
    for (cat in UiEximCategory.entries) {
      if (cat !in sel.cats) continue
      checkCancel(isCancelled)
      if (cat == UiEximCategory.ACCOUNTS) {
        // streamed, not read into memory — the chat archive can be hundreds of MB, so this is
        // also the one place worth reporting byte progress from
        if (accountsArchive != null) {
          val size = accountsArchive.length()
          zip.putNextEntry(ZipEntry(ACCOUNTS_ENTRY))
          accountsArchive.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
              val n = ins.read(buf)
              if (n <= 0) break
              // between buffers, not just between entries: this copy alone can run for minutes,
              // and a cancel that waited it out would deliver the backup 白い熊 had stopped
              checkCancel(isCancelled)
              zip.write(buf, 0, n)
              copied += n
              onProgress(copied, size, "バイト", "${uiHumanSize(copied)} / ${uiHumanSize(size)}")
            }
          }
          zip.closeEntry()
        }
        step(cat.label)
        continue
      }
      val obj = buildJsonObject {
        for (spec in cat.specs()) when (spec) {
          is PrefSpec.Str -> put(spec.key, spec.pref.get()?.let { JsonPrimitive(it) } ?: JsonNull)
          is PrefSpec.Flt -> put(spec.key, JsonPrimitive(spec.pref.get()))
          is PrefSpec.Bool -> put(spec.key, JsonPrimitive(spec.pref.get()))
        }
      }
      entry("${cat.id}.json", obj.toString().toByteArray())
      step(cat.label)
    }
    // the font files are their own selectable item, independent of the font settings
    if (sel.fontFiles) {
      for (f in fontsDir.listFiles()?.filter { it.isFile } ?: emptyList()) {
        checkCancel(isCancelled)
        entry("fonts/${f.name}", f.readBytes())
      }
      step("Font files")
    }
  }
}

private class UiImportData(val entries: Map<String, ByteArray>, val accountsTmp: File?)

/**
 * Streams the archive: small JSON/font entries into memory, the accounts chat-database
 * archive (potentially huge) straight to a temp file — only when its import is wanted.
 */
private fun readUiImportZip(uri: URI, wantAccounts: Boolean): UiImportData {
  val entries = HashMap<String, ByteArray>()
  var accountsTmp: File? = null
  try {
    val ins = uri.inputStream() ?: error("no input stream")
    ZipInputStream(ins).use { zip ->
      var e = zip.nextEntry
      while (e != null) {
        if (!e.isDirectory) {
          if (e.name == ACCOUNTS_ENTRY) {
            if (wantAccounts) {
              tmpDir.mkdirs()
              val f = File(tmpDir, "ui-import-$ACCOUNTS_ENTRY")
              FileOutputStream(f).use { zip.copyTo(it) }
              accountsTmp = f
            }
          } else {
            entries[e.name] = zip.readBytes()
          }
        }
        e = zip.nextEntry
      }
    }
    val manifest = entries["manifest.json"]?.let {
      runCatching { json.parseToJsonElement(it.decodeToString()).jsonObject }.getOrNull()
    }
    if (manifest?.get("format")?.jsonPrimitive?.contentOrNull != UI_EXPORT_FORMAT) {
      error("not a 白い熊 Simplex UI export (missing or wrong manifest)")
    }
    return UiImportData(entries, accountsTmp)
  } catch (e: Throwable) {
    accountsTmp?.delete()
    throw e
  }
}

/**
 * Upstream's database-import sequence (DatabaseView.importArchive), run with the chat
 * stopped: wipe current storage, import the archive, re-read app settings on next start.
 */
private suspend fun importAccountsArchive(archive: File): String {
  try {
    chatModel.controller.apiDeleteStorage()
    wallpapersDir.mkdirs()
    val config = ArchiveConfig(archive.absolutePath, parentTempDirectory = databaseExportDir.toString())
    val errs = chatModel.controller.apiImportArchive(config)
    appPrefs.shouldImportAppSettings.set(true)
    DatabaseUtils.ksDatabasePassword.remove()
    chatModel.chatDbChanged.value = true
    return "Accounts: chat database replaced" +
      if (errs.isEmpty()) "" else " (${errs.size} non-fatal error${if (errs.size == 1) "" else "s"})"
  } finally {
    archive.delete()
  }
}

/** Applies the selected pref categories found in the archive; returns per-category summary lines. */
private fun applyUiPrefEntries(entries: Map<String, ByteArray>, sel: UiEximSelection): List<String> {
  val lines = ArrayList<String>()
  for (cat in UiEximCategory.entries) {
    if (cat !in sel.cats || cat == UiEximCategory.ACCOUNTS) continue
    val data = entries["${cat.id}.json"] ?: continue
    val obj = json.parseToJsonElement(data.decodeToString()).jsonObject
    var n = 0
    for (spec in cat.specs()) {
      val v = obj[spec.key] ?: continue
      when (spec) {
        is PrefSpec.Str -> { spec.pref.set(if (v is JsonNull) null else v.jsonPrimitive.content); n++ }
        is PrefSpec.Flt -> v.jsonPrimitive.floatOrNull?.let { spec.pref.set(it); n++ }
        is PrefSpec.Bool -> v.jsonPrimitive.booleanOrNull?.let { spec.pref.set(it); n++ }
      }
    }
    lines.add("${cat.label}: $n setting${if (n == 1) "" else "s"}")
  }
  // font files restore independently of the font settings, matching their own `items` id
  if (sel.fontFiles) {
    var fn = 0
    for ((name, fontBytes) in entries) {
      if (!name.startsWith("fonts/")) continue
      val fname = name.removePrefix("fonts/").substringAfterLast('/')
      if (fname.isEmpty()) continue
      fontsDir.mkdirs()
      File(fontsDir, fname).writeBytes(fontBytes); fn++
    }
    if (fn > 0) lines.add("Font files: $fn file${if (fn == 1) "" else "s"}")
  }
  return lines
}

// ───────────────────────── headless export core ─────────────────────────

class UiExportOutcome(val itemCount: Int, val accountsErrors: Int)

/**
 * Writes the whole selection as one ZIP. Assumes the chat is already stopped when Accounts is
 * part of [sel] — the panel arranges that through stopChatRunBlockStartChat, the automation
 * path through [runHeadlessUiExport].
 */
private suspend fun writeExport(
  sel: UiEximSelection,
  openOut: () -> OutputStream,
  onProgress: UiEximProgress,
  isCancelled: UiEximCancelled = NOT_CANCELLED,
): UiExportOutcome {
  var accountsErrors = 0
  var archive: File? = null
  try {
    checkCancel(isCancelled)
    if (UiEximCategory.ACCOUNTS in sel.cats) {
      val exportDir = File(tmpDir, "ui-exim").also { it.mkdirs() }
      val (path, errs) = exportChatArchive(chatModel, exportDir, mutableStateOf(null))
      accountsErrors = errs.size
      archive = File(path)
      // upstream's archive export runs to completion whatever we do — but it writes only into
      // tmpDir, so bailing here still costs the caller nothing: openOut() has yet to be called
      checkCancel(isCancelled)
    }
    withContext(Dispatchers.IO) {
      openOut().use { writeUiExportZip(sel, it, archive, onProgress, isCancelled) }
    }
  } finally {
    archive?.delete()
  }
  return UiExportOutcome(sel.itemCount, accountsErrors)
}

/**
 * The 保存復元 automation entry point: one request → exactly one ZIP, no Activity and no user
 * interaction. Stops the chat around the Accounts archive and starts it again afterwards
 * (without the local-authentication prompt the interactive path uses — the token is the gate).
 */
suspend fun runHeadlessUiExport(
  sel: UiEximSelection,
  openOut: () -> OutputStream,
  onProgress: UiEximProgress = NO_PROGRESS,
  isCancelled: UiEximCancelled = NOT_CANCELLED,
): UiExportOutcome {
  if (sel.isEmpty()) throw IllegalArgumentException("no categories selected")
  val needsChatStop = UiEximCategory.ACCOUNTS in sel.cats
  if (needsChatStop && appPrefs.initialRandomDBPassphrase.get()) {
    // same rule as upstream's database export — such an archive can't be opened anywhere else
    throw IllegalStateException("accounts need a database passphrase (initial random passphrase is set)")
  }
  val wasRunning = needsChatStop && chatModel.chatRunning.value != false
  if (wasRunning) stopChatAsync(chatModel)
  try {
    return writeExport(sel, openOut, onProgress, isCancelled)
  } finally {
    if (wasRunning) {
      startChat(chatModel, mutableStateOf(appPrefs.chatLastStart.get()), chatModel.chatDbChanged, null)
    }
  }
}

// ───────────────────────── panel ─────────────────────────

private class UiEximState {
  // seeded from the same `defaultSelected` flags LIST_CATEGORIES sends, so this sheet and
  // 保存復元's item editor open on the same set. The rows are per category — Font's files have
  // no row of their own and follow their parent (UiEximSelection.ofCategories).
  val checks = mutableStateMapOf<UiEximCategory, Boolean>().apply {
    UiEximCategory.entries.forEach { put(it, it.defaultSelected) }
  }
  // bumped after a directory pick or an export so the status line recomputes
  val refresh = mutableStateOf(0)
  // an accounts export/import (chat stop → archive → chat start) is running
  val busy = mutableStateOf(false)

  fun selection(): UiEximSelection =
    UiEximSelection.ofCategories(UiEximCategory.entries.filter { checks[it] == true }.toSet())
}

fun showUiExportImportPanel() {
  // state lives outside the composable so it survives stacked alerts covering the panel
  val state = UiEximState()
  AlertManager.shared.showAlert { UiExportImportPanel(state) }
}

/** Auto-close the whole chain: the info dialog, the Export/Import panel, the UI settings page. */
private fun closeUiEximChain() {
  AlertManager.shared.hideAlert()
  AlertManager.shared.hideAlert()
  ModalManager.start.closeModal()
}

private fun showUiEximDoneDialog(title: String, message: String, buttons: List<Pair<String, () -> Unit>>) {
  AlertManager.shared.showAlert {
    val shape = RoundedCornerShape(16.dp)
    AlertDialog(
      onDismissRequest = {}, // acknowledge via the buttons — they drive the auto-close chain
      buttons = {
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 20.dp, bottom = 16.dp)) {
          Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary)
          Spacer(Modifier.height(10.dp))
          Text(message, fontSize = 14.sp, color = MaterialTheme.colors.primary)
          Spacer(Modifier.height(16.dp))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            buttons.forEachIndexed { i, (label, action) ->
              if (i > 0) Spacer(Modifier.width(10.dp))
              UiPillButton(label, onClick = action)
            }
          }
        }
      },
      backgroundColor = MaterialTheme.colors.background,
      shape = shape,
      modifier = Modifier.border(2.dp, MaterialTheme.colors.primary, shape)
    )
  }
}

// ArcaneChat-style round pill: black fill, thin accent border, accent text.
@Composable
private fun UiPillButton(label: String, onClick: () -> Unit) {
  val shape = RoundedCornerShape(50)
  Box(
    Modifier
      .clip(shape)
      .background(MaterialTheme.colors.background)
      .border(1.5.dp, MaterialTheme.colors.primary, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 6.dp)
  ) {
    Text(label, color = MaterialTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
  }
}

@Composable
private fun UiEximCheckRow(label: String, checked: Boolean, bold: Boolean = false, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable { onChange(!checked) },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(
      checked,
      onCheckedChange = onChange,
      colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.primary)
    )
    Spacer(Modifier.width(4.dp))
    Text(label, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
  }
}

@Composable
private fun UiExportImportPanel(state: UiEximState) {
  val shape = RoundedCornerShape(12.dp)
  AlertDialog(
    onDismissRequest = { AlertManager.shared.hideAlert() },
    buttons = {
      Column(
        Modifier
          .padding(horizontal = 20.dp)
          .padding(top = 16.dp, bottom = 14.dp)
          .verticalScroll(rememberScrollState())
      ) {
        // heading styled like the page: bold accent, text-wide underline
        Column(Modifier.width(IntrinsicSize.Max)) {
          Text("Export / Import", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary, maxLines = 1)
          Spacer(Modifier.height(2.dp))
          Box(Modifier.fillMaxWidth().height(2.5.dp).background(MaterialTheme.colors.primary))
        }
        Spacer(Modifier.height(12.dp))

        val dirUri = remember { appPrefs.uiExportDirectory.state }.value
        val refresh = state.refresh.value
        val dirName = remember(dirUri, refresh) { dirUri?.let { uiExportDirName(it) } }
        val status = remember(dirUri, refresh) { uiLastExportStatus(dirUri) }
        val dirLauncher = rememberDirectoryChooserLauncher { picked ->
          if (picked != null) {
            appPrefs.uiExportDirectory.set(picked)
            state.refresh.value++
          }
        }

        // persisted export directory — a bordered, clearly-tappable box
        val boxShape = RoundedCornerShape(10.dp)
        Column(
          Modifier
            .fillMaxWidth()
            .clip(boxShape)
            .border(2.dp, MaterialTheme.colors.primary, boxShape)
            .clickable { withLongRunningApi { dirLauncher.launch(dirUri) } }
            .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
          Text("Export directory (tap to choose)", fontSize = 12.sp, color = MaterialTheme.colors.primary)
          Text(
            dirName ?: "Not set — tap to choose a directory",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (dirName == null) WARN_COLOR else MaterialTheme.colors.onBackground
          )
        }
        Spacer(Modifier.height(6.dp))
        Text(
          status.first,
          fontSize = 14.sp,
          color = if (status.second) WARN_COLOR else MaterialTheme.colors.secondary
        )
        Spacer(Modifier.height(8.dp))

        UiEximCheckRow("Select all", checked = UiEximCategory.entries.all { state.checks[it] == true }, bold = true) { v ->
          UiEximCategory.entries.forEach { state.checks[it] = v }
        }
        UiEximCategory.entries.forEach { cat ->
          UiEximCheckRow(cat.label, checked = state.checks[cat] == true) { v -> state.checks[cat] = v }
        }
        Spacer(Modifier.height(14.dp))

        val importLauncher = rememberFileChooserLauncher(true) { uri: URI? ->
          if (uri != null) runUiImport(uri, state)
        }
        val saveAsLauncher = rememberFileChooserLauncher(false) { uri: URI? ->
          if (uri != null) runUiExportToUri(uri, state)
        }
        // ArcaneChat-style button line: Cancel alone on the left, Import/Export on the right
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          val busy = state.busy.value
          UiPillButton("Cancel") { if (!busy) AlertManager.shared.hideAlert() }
          Spacer(Modifier.weight(1f))
          if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colors.primary, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(10.dp))
          }
          UiPillButton("Import") {
            if (!busy) {
              if (state.selection().isEmpty()) {
                AlertManager.shared.showAlertMsg("Import", "No categories selected.")
              } else {
                withLongRunningApi { importLauncher.launch("*/*") }
              }
            }
          }
          Spacer(Modifier.width(8.dp))
          UiPillButton("Export") { if (!busy) onUiExportClicked(state, saveAsLauncher) }
        }
      }
    },
    backgroundColor = MaterialTheme.colors.background,
    shape = shape,
    modifier = Modifier.border(2.dp, MaterialTheme.colors.primary, shape)
  )
}

// ───────────────────────── actions ─────────────────────────

private fun onUiExportClicked(state: UiEximState, saveAsLauncher: FileChooserLauncher) {
  val sel = state.selection()
  val cats = sel.cats
  if (sel.isEmpty()) {
    AlertManager.shared.showAlertMsg("Export", "No categories selected.")
    return
  }
  if (UiEximCategory.ACCOUNTS in cats && appPrefs.initialRandomDBPassphrase.get()) {
    // same rule as upstream's database export: with the initial random passphrase the
    // archive couldn't be opened anywhere else
    AlertManager.shared.showAlertMsg(
      "Export",
      "Accounts can't be exported while the database uses the initial random passphrase. Set a passphrase in Database passphrase & export, or untick Accounts."
    )
    return
  }
  val dirUri = appPrefs.uiExportDirectory.get()
  if (dirUri == null) {
    // no folder set → save-as picker; the write happens in runUiExportToUri
    withLongRunningApi { saveAsLauncher.launch(uiExportFileName()) }
    return
  }
  val name = uiExportFileName()
  startUiExport(state, sel, name) {
    uiExportDirCreateFile(dirUri, name) ?: error("could not create a file in the export directory")
  }
}

private fun runUiExportToUri(uri: URI, state: UiEximState) {
  startUiExport(state, state.selection(), getFileName(uri) ?: "export file") { uri.outputStream() }
}

/** With Accounts selected the chat is stopped for the archive export and restarted after. */
private fun startUiExport(state: UiEximState, sel: UiEximSelection, doneName: String, openOut: () -> OutputStream) {
  if (UiEximCategory.ACCOUNTS in sel.cats) {
    stopChatRunBlockStartChat(
      chatModel.chatRunning.value == false,
      mutableStateOf(appPrefs.chatLastStart.get()),
      state.busy
    ) {
      performUiExport(state, sel, doneName, openOut)
      true
    }
  } else {
    withLongRunningApi { performUiExport(state, sel, doneName, openOut) }
  }
}

private suspend fun performUiExport(state: UiEximState, sel: UiEximSelection, doneName: String, openOut: () -> OutputStream) {
  state.busy.value = true
  // the chat is already stopped for us by startUiExport when Accounts is selected
  val res = runCatching { writeExport(sel, openOut, NO_PROGRESS) }
  state.busy.value = false
  res.onSuccess { outcome ->
    state.refresh.value++
    val accountsErrors = outcome.accountsErrors
    val warn =
      if (accountsErrors == 0) ""
      else "\n\n$accountsErrors file error${if (accountsErrors == 1) "" else "s"} in the accounts archive — exported without those files."
    showUiEximDoneDialog("✓ Export", "Exported:\n\n$doneName$warn", listOf("OK" to ::closeUiEximChain))
  }.onFailure { e ->
    Log.e(TAG, "UI export failed: ${e.stackTraceToString()}")
    AlertManager.shared.showAlertMsg("Export failed", e.message ?: e.toString())
  }
}

private fun runUiImport(uri: URI, state: UiEximState) {
  val sel = state.selection()
  withLongRunningApi {
    val data = try {
      withContext(Dispatchers.IO) { readUiImportZip(uri, UiEximCategory.ACCOUNTS in sel.cats) }
    } catch (e: Throwable) {
      Log.e(TAG, "UI import failed: ${e.stackTraceToString()}")
      AlertManager.shared.showAlertMsg("Import failed", e.message ?: e.toString())
      return@withLongRunningApi
    }
    val accountsTmp = data.accountsTmp
    if (accountsTmp != null) {
      // replacing the chat database is destructive — confirm, then run with the chat stopped
      AlertManager.shared.showAlertDialog(
        title = "Replace accounts?",
        text = "The archive contains an accounts backup — the full chat database. Importing it DELETES the current database (all profiles, contacts and messages) and replaces it with the archived one.",
        confirmText = "Replace",
        onConfirm = {
          stopChatRunBlockStartChat(
            chatModel.chatRunning.value == false,
            mutableStateOf(appPrefs.chatLastStart.get()),
            state.busy
          ) {
            applyUiImport(data, sel, state)
            true
          }
        },
        onDismiss = { accountsTmp.delete() },
        onDismissRequest = { accountsTmp.delete() },
        destructive = true,
      )
    } else {
      applyUiImport(data, sel, state)
    }
  }
}

private suspend fun applyUiImport(data: UiImportData, sel: UiEximSelection, state: UiEximState) {
  state.busy.value = true
  val res = runCatching {
    val lines = ArrayList<String>()
    val accountsTmp = data.accountsTmp
    if (accountsTmp != null) lines.add(importAccountsArchive(accountsTmp))
    withContext(Dispatchers.IO) { lines.addAll(applyUiPrefEntries(data.entries, sel)) }
    if (lines.isEmpty()) error("the file contains none of the selected categories")
    lines.joinToString("\n")
  }
  state.busy.value = false
  res.onSuccess { summary ->
    // apply what can be applied live; the restart offer covers the rest (fonts, accounts)
    ThemeManager.applyTheme(appPrefs.currentTheme.get()!!)
    showUiEximDoneDialog(
      "✓ Import — 100% success",
      "Restored:\n\n$summary\n\nRestart to apply everything.",
      listOf(
        "Later" to ::closeUiEximChain,
        "Restart now" to { restartChatOrApp() }
      )
    )
  }.onFailure { e ->
    Log.e(TAG, "UI import failed: ${e.stackTraceToString()}")
    AlertManager.shared.showAlertMsg("Import failed", e.message ?: e.toString())
  }
}
