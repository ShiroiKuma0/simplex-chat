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

const val UI_EXPORT_PREFIX = "shiroikuma-simplex-ui_"
private const val UI_EXPORT_FORMAT = "shiroikuma-simplex-ui"
// the SimpleX chat-database archive (all accounts/profiles with their keys, contacts and
// messages), embedded verbatim as one entry inside the UI export ZIP
private const val ACCOUNTS_ENTRY = "accounts.zip"
internal val WARN_COLOR = Color(0xFFFF5252)

// ───────────────────────── categories ─────────────────────────

enum class UiEximCategory(val id: String, val label: String) {
  ACCOUNTS("accounts", "Accounts"),
  APP_COLORS("app_colors", "App colors"),
  FONT("font", "Font"),
  CHAT_LIST("chat_list", "Chat list"),
  CHAT_BUBBLES("chat_bubbles", "Chat bubbles"),
  CHAT_VIEW("chat_view", "Chat view"),
  DELIVERY_TICKS("delivery_ticks", "Delivery ticks");
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
  )
}

// ───────────────────────── status / naming ─────────────────────────

fun uiExportFileName(): String =
  UI_EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

/** (message, isWarning) for the "last export" line — queried from the export directory. */
fun uiLastExportStatus(dirUri: String?): Pair<String, Boolean> {
  if (dirUri == null) return "No directory set yet — pick one to enable one-tap export." to true
  val newest = uiExportDirFiles(dirUri)
    .filter { it.name.startsWith(UI_EXPORT_PREFIX) && it.name.endsWith(".zip") }
    .maxByOrNull { it.lastModified }
    ?: return "No export in this directory yet." to true
  val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified))
  return "Last export: $ts" to false
}

// ───────────────────────── export / import engine ─────────────────────────

private fun writeUiExportZip(cats: Set<UiEximCategory>, out: OutputStream, accountsArchive: File?) {
  ZipOutputStream(out).use { zip ->
    fun entry(name: String, bytes: ByteArray) {
      zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }
    val manifest = buildJsonObject {
      put("format", JsonPrimitive(UI_EXPORT_FORMAT))
      put("version", JsonPrimitive(2))
      put("app", JsonPrimitive("shiroikuma.simplex"))
      put("createdTs", JsonPrimitive(System.currentTimeMillis()))
      put("categories", JsonArray(cats.map { JsonPrimitive(it.id) }))
    }
    entry("manifest.json", manifest.toString().toByteArray())
    for (cat in UiEximCategory.entries) {
      if (cat !in cats) continue
      if (cat == UiEximCategory.ACCOUNTS) {
        // streamed, not read into memory — the chat archive can be hundreds of MB
        if (accountsArchive != null) {
          zip.putNextEntry(ZipEntry(ACCOUNTS_ENTRY))
          accountsArchive.inputStream().use { it.copyTo(zip) }
          zip.closeEntry()
        }
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
      if (cat == UiEximCategory.FONT) {
        for (f in fontsDir.listFiles()?.filter { it.isFile } ?: emptyList()) {
          entry("fonts/${f.name}", f.readBytes())
        }
      }
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
private fun applyUiPrefEntries(entries: Map<String, ByteArray>, cats: Set<UiEximCategory>): List<String> {
  val lines = ArrayList<String>()
  for (cat in UiEximCategory.entries) {
    if (cat !in cats || cat == UiEximCategory.ACCOUNTS) continue
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
    var extra = ""
    if (cat == UiEximCategory.FONT) {
      fontsDir.mkdirs()
      var fn = 0
      for ((name, fontBytes) in entries) {
        if (!name.startsWith("fonts/")) continue
        val fname = name.removePrefix("fonts/").substringAfterLast('/')
        if (fname.isEmpty()) continue
        File(fontsDir, fname).writeBytes(fontBytes); fn++
      }
      if (fn > 0) extra = " + $fn font file${if (fn == 1) "" else "s"}"
    }
    lines.add("${cat.label}: $n setting${if (n == 1) "" else "s"}$extra")
  }
  return lines
}

// ───────────────────────── panel ─────────────────────────

private class UiEximState {
  val checks = mutableStateMapOf<UiEximCategory, Boolean>().apply {
    UiEximCategory.entries.forEach { put(it, true) }
  }
  // bumped after a directory pick or an export so the status line recomputes
  val refresh = mutableStateOf(0)
  // an accounts export/import (chat stop → archive → chat start) is running
  val busy = mutableStateOf(false)

  fun selected(): Set<UiEximCategory> = UiEximCategory.entries.filter { checks[it] == true }.toSet()
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
              if (state.selected().isEmpty()) {
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
  val cats = state.selected()
  if (cats.isEmpty()) {
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
  startUiExport(state, cats, name) {
    uiExportDirCreateFile(dirUri, name) ?: error("could not create a file in the export directory")
  }
}

private fun runUiExportToUri(uri: URI, state: UiEximState) {
  startUiExport(state, state.selected(), getFileName(uri) ?: "export file") { uri.outputStream() }
}

/** With Accounts selected the chat is stopped for the archive export and restarted after. */
private fun startUiExport(state: UiEximState, cats: Set<UiEximCategory>, doneName: String, openOut: () -> OutputStream) {
  if (UiEximCategory.ACCOUNTS in cats) {
    stopChatRunBlockStartChat(
      chatModel.chatRunning.value == false,
      mutableStateOf(appPrefs.chatLastStart.get()),
      state.busy
    ) {
      performUiExport(state, cats, doneName, openOut)
      true
    }
  } else {
    withLongRunningApi { performUiExport(state, cats, doneName, openOut) }
  }
}

private suspend fun performUiExport(state: UiEximState, cats: Set<UiEximCategory>, doneName: String, openOut: () -> OutputStream) {
  state.busy.value = true
  var accountsErrors = 0
  val res = runCatching {
    var archive: File? = null
    try {
      if (UiEximCategory.ACCOUNTS in cats) {
        val exportDir = File(tmpDir, "ui-exim").also { it.mkdirs() }
        val (path, errs) = exportChatArchive(chatModel, exportDir, mutableStateOf(null))
        accountsErrors = errs.size
        archive = File(path)
      }
      withContext(Dispatchers.IO) {
        openOut().use { writeUiExportZip(cats, it, archive) }
      }
    } finally {
      archive?.delete()
    }
  }
  state.busy.value = false
  res.onSuccess {
    state.refresh.value++
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
  val cats = state.selected()
  withLongRunningApi {
    val data = try {
      withContext(Dispatchers.IO) { readUiImportZip(uri, UiEximCategory.ACCOUNTS in cats) }
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
            applyUiImport(data, cats, state)
            true
          }
        },
        onDismiss = { accountsTmp.delete() },
        onDismissRequest = { accountsTmp.delete() },
        destructive = true,
      )
    } else {
      applyUiImport(data, cats, state)
    }
  }
}

private suspend fun applyUiImport(data: UiImportData, cats: Set<UiEximCategory>, state: UiEximState) {
  state.busy.value = true
  val res = runCatching {
    val lines = ArrayList<String>()
    val accountsTmp = data.accountsTmp
    if (accountsTmp != null) lines.add(importAccountsArchive(accountsTmp))
    withContext(Dispatchers.IO) { lines.addAll(applyUiPrefEntries(data.entries, cats)) }
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
