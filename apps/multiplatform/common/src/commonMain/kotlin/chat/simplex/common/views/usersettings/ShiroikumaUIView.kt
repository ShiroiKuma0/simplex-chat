package chat.simplex.common.views.usersettings

import SectionBottomSpacer
import SectionItemView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import chat.simplex.common.model.AppPreferences
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.model.SharedPreference
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.ui.theme.ThemeManager.colorFromReadableHex
import chat.simplex.common.ui.theme.ThemeManager.toReadableHex
import chat.simplex.common.views.chat.item.BASE_TICK_HEIGHT_DP
import chat.simplex.common.views.chat.item.TickGlyph
import chat.simplex.common.views.chat.item.TickIcon
import chat.simplex.common.views.helpers.*
import dev.icerock.moko.resources.compose.painterResource
import chat.simplex.res.MR
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min
import kotlin.math.roundToInt

// downstream (shiroikuma): the 白い熊 Simplex UI page — every fork customization settable in one
// place, structured like the sister repos (denwa/messeji/renrakusaki): bold headings with an
// accent rule, deeply indented items per category level, tight rows, live previews everywhere.

private val INDENT_STEP = 72.dp
private val BASE_PADDING = 16.dp
private val ROW_MIN_HEIGHT = 38.dp
private const val MAX_RECENT_COLORS = 8
private val FONT_EXTENSIONS = setOf("ttf", "otf")
private const val FONT_SAMPLE = "白い熊相撲道 AaIiMmQq 012 áčž"

fun showShiroikumaUIModal() {
  ModalManager.start.showModal(settings = true) { ShiroikumaUIView() }
}

@Composable
fun ShiroikumaUIView() {
  ColumnWithScrollBar {
    AppBarTitle("白い熊 Simplex UI")

    // ── Export / Import ────────────────────────────────────────
    UIHeading("Export / Import", first = true)
    ExportImportRow(1)
    AutomationSwitchRow(1)
    AutomationRequireTokenRow(1)
    AutomationTokenRow(1)

    // ── App colors ─────────────────────────────────────────────
    UIHeading("App colors")
    UIColorRow(1, "Background", appPrefs.uiBackgroundColor, AppPreferences.DEFAULT_SHIROIKUMA_BLACK, affectsTheme = true)
    UIColorRow(1, "Text", appPrefs.uiTextColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW, affectsTheme = true)
    UIColorRow(1, "Accent", appPrefs.uiAccentColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW, affectsTheme = true)
    UIColorRow(1, "Secondary (muted)", appPrefs.uiSecondaryColor, AppPreferences.DEFAULT_UI_SECONDARY_COLOR, affectsTheme = true)

    // ── Font ───────────────────────────────────────────────────
    UIHeading("Font")
    UIFontRow(1)
    UIFloatSliderRow(1, "Font size", appPrefs.fontScale, 0.75f..1.25f) { "${(it * 100).roundToInt()}%" }
    FontPreviewRow(1)

    // ── Chat list ──────────────────────────────────────────────
    UIHeading("Chat list")
    UIColorRow(1, "Contact name", appPrefs.chatListNameColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
    UISwitchRow(1, "Show Private notes", appPrefs.showPrivateNotes)
    UIFloatSliderRow(1, "Avatar corner radius", appPrefs.profileImageCornerRadius, 0f..50f) { "${it.roundToInt()}" }
    ChatListPreviewRow(1)

    // ── Chat bubbles ───────────────────────────────────────────
    UIHeading("Chat bubbles")
    UISubheading("Received", 1)
    UIColorRow(2, "Background", appPrefs.bubbleReceivedBackgroundColor, AppPreferences.DEFAULT_BUBBLE_BACKGROUND_COLOR)
    UIColorRow(2, "Text", appPrefs.bubbleReceivedTextColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
    UIColorRow(2, "Border", appPrefs.bubbleReceivedBorderColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
    UISubheading("Sent", 1)
    UIColorRow(2, "Background", appPrefs.bubbleSentBackgroundColor, AppPreferences.DEFAULT_BUBBLE_BACKGROUND_COLOR)
    UIColorRow(2, "Text", appPrefs.bubbleSentTextColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
    UIColorRow(2, "Border", appPrefs.bubbleSentBorderColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
    UISubheading("Shape", 1)
    UIFloatSliderRow(2, "Border width", appPrefs.bubbleBorderWidth, 0f..6f) { "%.1f dp".format(it) }
    UIFloatSliderRow(2, "Corner roundness", appPrefs.chatItemRoundness, 0f..1f) { "${(it * 100).roundToInt()}%" }
    UISwitchRow(2, "Tail", appPrefs.chatItemTail)
    UISubheading("Sender (received)", 1)
    UIFloatSliderRow(2, "Icon size", appPrefs.bubbleSenderIconSize, 12f..48f) { "${it.roundToInt()} dp" }
    UIFloatSliderRow(2, "Name size", appPrefs.bubbleSenderNameSize, 10f..24f) { "${it.roundToInt()} sp" }
    BubblePreview(1)

    // ── Chat view ──────────────────────────────────────────────
    UIHeading("Chat view")
    UISwitchRow(1, "Date header bold", appPrefs.chatDateBold)
    UISwitchRow(1, "Date header underline", appPrefs.chatDateUnderline)
    DateHeaderPreviewRow(1)
    UIFloatSliderRow(1, "Call icon size", appPrefs.callIconScale, 1f..4f) { "×${"%.1f".format(it)}" }
    CallIconPreviewRow(1)

    // ── Delivery ticks ─────────────────────────────────────────
    UIHeading("Delivery ticks")
    UIFloatSliderRow(1, "Size", appPrefs.messageTickScale, 1f..15f) { "×${"%.1f".format(it)}" }
    UIFloatSliderRow(1, "Thickness", appPrefs.messageTickThickness, 0f..4f) { "%.1f".format(it) }
    UIFloatSliderRow(1, "Dot size multiplier", appPrefs.messageTickDotScale, 1f..5f, steps = 39) { "×${"%.1f".format(it)}" }
    UIColorRow(1, "Sent tick's color", appPrefs.messageTickSentColor, AppPreferences.DEFAULT_MESSAGE_TICK_SENT_COLOR)
    TickGlyphRow(1, "Sent glyph", appPrefs.messageTickSentGlyph, TickGlyph.TICK1, appPrefs.messageTickSentColor, AppPreferences.DEFAULT_MESSAGE_TICK_SENT_COLOR)
    UIColorRow(1, "Delivered tick's color", appPrefs.messageTickReceivedColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
    TickGlyphRow(1, "Delivered glyph", appPrefs.messageTickDeliveredGlyph, TickGlyph.DOT, appPrefs.messageTickReceivedColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)

    SectionBottomSpacer()
  }
}

// ───────────────────────── building blocks ─────────────────────────

// kxkb-style section heading: bold accent title with an underline exactly as wide as the text,
// sections separated by a thin full-width hairline (1 px — thinnest possible) above each heading
// except the first.
@Composable
private fun UIHeading(text: String, first: Boolean = false) {
  if (!first) {
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Box(Modifier.fillMaxWidth().padding(top = 20.dp).height(hairline).background(MaterialTheme.colors.primary))
  }
  Column(
    Modifier
      .padding(start = BASE_PADDING, end = BASE_PADDING, top = if (first) 12.dp else 8.dp, bottom = 6.dp)
      .width(IntrinsicSize.Max)
  ) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary, maxLines = 1)
    Spacer(Modifier.height(2.dp))
    Box(Modifier.fillMaxWidth().height(2.5.dp).background(MaterialTheme.colors.primary))
  }
}

@Composable
private fun UISubheading(text: String, level: Int) {
  Column(
    Modifier
      .padding(start = BASE_PADDING + INDENT_STEP * level, end = BASE_PADDING, top = 10.dp, bottom = 2.dp)
      .width(IntrinsicSize.Max)
  ) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary, maxLines = 1)
    Spacer(Modifier.height(2.dp))
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(MaterialTheme.colors.primary))
  }
}

// downstream (shiroikuma): the Export/Import entry row — queries the export directory when the
// page opens and shows the latest export beneath the title (red warnings when unset/empty).
@Composable
private fun ExportImportRow(level: Int) {
  val dirUri = remember { appPrefs.uiExportDirectory.state }.value
  val status = remember(dirUri) { uiLastExportStatus(dirUri) }
  UIRow(level, click = { showUiExportImportPanel() }) {
    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
      Text("Export or import every UI setting, by category…", fontSize = 15.sp)
      Spacer(Modifier.height(1.dp))
      Text(
        status.first,
        fontSize = 13.sp,
        color = if (status.second) WARN_COLOR else MaterialTheme.colors.secondary
      )
    }
  }
}

// downstream (shiroikuma): the 保存復元 automation rows — deliberately part of the
// Export/Import section rather than a section of their own, so every sister app puts backup
// automation in the same place.
//
// Contract v2: the switch ships ON and the token is opt-in. The point is a phone that has just
// been wiped, where nothing has been configured and nobody has pasted anything — a gate that only
// opens once the phone is already set up is no gate for setting the phone up. The switch stays a
// switch rather than being removed because it is the only way to close this app off, and a
// feature that can be turned on but never off is one 白い熊 cannot retreat from.
@Composable
private fun AutomationSwitchRow(level: Int) {
  val enabled = remember { appPrefs.automationEnabled.state }.value
  Column {
    UIRow(level, click = { appPrefs.automationEnabled.set(!enabled) }) {
      Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
        Text("Automation export", fontSize = 15.sp)
        Spacer(Modifier.height(1.dp))
        Text(
          "Sister apps may trigger this app's export (保存復元), and back its data up and restore it (応用管理).",
          fontSize = 13.sp,
          color = MaterialTheme.colors.secondary
        )
      }
      Switch(checked = enabled, onCheckedChange = { appPrefs.automationEnabled.set(it) })
    }
    // writing into the shared backup directory an automation task names needs All-Files-Access
    val allFiles = if (enabled) hasAllFilesAccess() else null
    if (allFiles == false) {
      UIRow(level, click = { openAllFilesAccessSettings() }) {
        Text(
          "All-files access not granted — tap to grant it, or exports can only go to the directory chosen above.",
          Modifier.weight(1f).padding(vertical = 4.dp),
          fontSize = 13.sp,
          color = WARN_COLOR
        )
      }
    }
  }
}

/**
 * Row 2 — 「Use authorization token?」, default OFF.
 *
 * Off means any sister app may drive the automation; on means a caller must also present the
 * token below. The data door checks the caller's package, uid and signing certificate either way,
 * which is a stronger question than a shared secret and is not affected by this switch.
 */
@Composable
private fun AutomationRequireTokenRow(level: Int) {
  val enabled = remember { appPrefs.automationEnabled.state }.value
  val require = remember { appPrefs.automationRequireToken.state }.value
  if (!enabled) return
  UIRow(level, click = { appPrefs.automationRequireToken.set(!require) }) {
    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
      Text("Use authorization token?", fontSize = 15.sp)
      Spacer(Modifier.height(1.dp))
      Text(
        "Off: any sister app may drive the automation. On: a caller must also present the token below. " +
          "The data door checks the caller's identity and signature either way.",
        fontSize = 13.sp,
        color = MaterialTheme.colors.secondary
      )
    }
    Switch(checked = require, onCheckedChange = { appPrefs.automationRequireToken.set(it) })
  }
}

/**
 * Row 3 — the token itself, shown ONLY while row 2 is on. A 48-character secret sitting under an
 * off switch invites 白い熊 to paste it somewhere it will do nothing.
 */
@Composable
private fun AutomationTokenRow(level: Int) {
  val enabled = remember { appPrefs.automationEnabled.state }.value
  val require = remember { appPrefs.automationRequireToken.state }.value
  if (!enabled || !require) return
  val clipboard = LocalClipboardManager.current
  // read (and thus lazily generate) the token so the row always shows a value
  val token = remember { mutableStateOf(AutomationAuth.token()) }
  UIRow(level, click = {
    clipboard.setText(AnnotatedString(token.value))
    showToast("Automation token copied")
  }) {
    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
      Text("Automation token", fontSize = 15.sp)
      Spacer(Modifier.height(1.dp))
      Text(
        AutomationAuth.abbreviated(token.value),
        fontSize = 13.sp,
        color = MaterialTheme.colors.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    Text(
      "Regenerate",
      Modifier
        .clickable {
          AlertManager.shared.showAlertDialog(
            title = "Regenerate token?",
            text = "The current token stops working immediately. Every task that has it pasted — 自由作業盤's 保存復元の設定 — must be updated with the new one.",
            confirmText = "Regenerate",
            onConfirm = {
              token.value = AutomationAuth.regenerate()
              showToast("New automation token generated")
            },
            destructive = true,
          )
        }
        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
      fontSize = 13.sp,
      color = MaterialTheme.colors.primary
    )
  }
}

@Composable
private fun UIRow(level: Int, click: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
  val base = Modifier.fillMaxWidth().sizeIn(minHeight = ROW_MIN_HEIGHT)
  Row(
    (if (click != null) base.clickable(onClick = click) else base)
      .padding(start = BASE_PADDING + INDENT_STEP * level, end = BASE_PADDING, top = 2.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) { content() }
}

@Composable
private fun ColorSwatch(color: Color, size: Dp = 24.dp, onClick: (() -> Unit)? = null) {
  val m = Modifier
    .size(size)
    .clip(CircleShape)
    .background(color)
    .border(1.dp, MaterialTheme.colors.secondary, CircleShape)
  Box(if (onClick != null) m.clickable(onClick = onClick) else m)
}

@Composable
private fun UIColorRow(level: Int, label: String, pref: SharedPreference<String?>, defaultColor: String, affectsTheme: Boolean = false) {
  val color = remember { pref.state }.value?.colorFromReadableHex() ?: defaultColor.colorFromReadableHex()
  UIRow(level, click = { showRgbaEditor(label, pref, defaultColor, affectsTheme) }) {
    Text(label, Modifier.weight(1f), fontSize = 15.sp)
    ColorSwatch(color)
  }
}

@Composable
private fun UIFloatSliderRow(level: Int, label: String, pref: SharedPreference<Float>, range: ClosedFloatingPointRange<Float>, steps: Int = 0, format: (Float) -> String) {
  val value = remember { pref.state }.value
  UIRow(level) {
    Text(label, Modifier.weight(0.5f), fontSize = 15.sp)
    Slider(
      value.coerceIn(range),
      onValueChange = { pref.set(it) },
      Modifier.weight(0.5f).padding(horizontal = 6.dp),
      valueRange = range,
      steps = steps,
      colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    )
    Text(format(value), Modifier.widthIn(min = 48.dp), fontSize = 13.sp, textAlign = TextAlign.End)
  }
}

@Composable
private fun UISwitchRow(level: Int, label: String, pref: SharedPreference<Boolean>) {
  val value = remember { pref.state }.value
  UIRow(level, click = { pref.set(!value) }) {
    Text(label, Modifier.weight(1f), fontSize = 15.sp)
    Switch(checked = value, onCheckedChange = { pref.set(it) })
  }
}

// ───────────────────────── RGBA color editor ─────────────────────────

private fun refreshShiroikumaTheme() {
  ThemeManager.applyTheme(appPrefs.currentTheme.get()!!)
}

private fun showRgbaEditor(title: String, pref: SharedPreference<String?>, defaultColor: String, affectsTheme: Boolean) {
  ModalManager.start.showModal {
    RgbaColorEditor(
      title = title,
      initialColor = pref.get()?.colorFromReadableHex() ?: defaultColor.colorFromReadableHex(),
      defaultColor = defaultColor.colorFromReadableHex(),
      onColorChange = {
        pref.set(it.toReadableHex())
        if (affectsTheme) refreshShiroikumaTheme()
      },
      onReset = {
        pref.set(defaultColor)
        if (affectsTheme) refreshShiroikumaTheme()
      }
    )
  }
}

fun recentPickedColors(): List<Color> =
  appPrefs.recentPickedColors.get()
    ?.split(",")
    ?.mapNotNull { s -> try { s.trim().colorFromReadableHex() } catch (e: Exception) { null } }
    ?: emptyList()

fun addRecentPickedColor(color: Color) {
  val hex = color.toReadableHex()
  val existing = appPrefs.recentPickedColors.get()?.split(",")?.map { it.trim() } ?: emptyList()
  val list = listOf(hex) + existing.filter { it != hex }
  appPrefs.recentPickedColors.set(list.take(MAX_RECENT_COLORS).joinToString(","))
}

@Composable
fun RgbaColorEditor(
  title: String,
  initialColor: Color,
  defaultColor: Color,
  onColorChange: (Color) -> Unit,
  onReset: () -> Unit,
) {
  ColumnWithScrollBar(Modifier.imePadding()) {
    AppBarTitle(title)
    var red by remember { mutableStateOf(initialColor.red) }
    var green by remember { mutableStateOf(initialColor.green) }
    var blue by remember { mutableStateOf(initialColor.blue) }
    var alpha by remember { mutableStateOf(initialColor.alpha) }
    val current = Color(red, green, blue, alpha)
    val apply = { c: Color ->
      red = c.red; green = c.green; blue = c.blue; alpha = c.alpha
      onColorChange(c)
    }

    // one-click boxes with the previously selected colors
    val recent = remember { recentPickedColors() }
    if (recent.isNotEmpty()) {
      Row(
        Modifier.padding(horizontal = BASE_PADDING).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        recent.forEach { c -> ColorSwatch(c, size = 34.dp, onClick = { apply(c) }) }
      }
      Spacer(Modifier.height(12.dp))
    }

    // live preview
    Box(
      Modifier
        .padding(horizontal = BASE_PADDING)
        .fillMaxWidth()
        .height(56.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(current)
        .border(1.dp, MaterialTheme.colors.secondary, RoundedCornerShape(8.dp))
    )
    Spacer(Modifier.height(4.dp))
    Text(
      current.toReadableHex().uppercase(),
      Modifier.padding(horizontal = BASE_PADDING),
      fontSize = 13.sp,
      color = MaterialTheme.colors.secondary
    )
    Spacer(Modifier.height(6.dp))

    // the 4 RGBA sliders
    ChannelSlider("R", red) { red = it; onColorChange(Color(red, green, blue, alpha)) }
    ChannelSlider("G", green) { green = it; onColorChange(Color(red, green, blue, alpha)) }
    ChannelSlider("B", blue) { blue = it; onColorChange(Color(red, green, blue, alpha)) }
    ChannelSlider("A", alpha) { alpha = it; onColorChange(Color(red, green, blue, alpha)) }

    SectionItemView({ apply(defaultColor); onReset() }) {
      Text("Reset to default", color = MaterialTheme.colors.primary)
    }

    // record the final color as a one-click preset when the editor closes
    val latest = rememberUpdatedState(current)
    DisposableEffect(Unit) {
      onDispose { addRecentPickedColor(latest.value) }
    }
    SectionBottomSpacer()
  }
}

@Composable
private fun ChannelSlider(label: String, value: Float, onChange: (Float) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = BASE_PADDING),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, Modifier.width(20.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Slider(value, onValueChange = onChange, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0f..1f)
    Text((value * 255).roundToInt().toString(), Modifier.width(36.dp), fontSize = 13.sp, textAlign = TextAlign.End)
  }
}

// ───────────────────────── fonts ─────────────────────────

private fun installedFonts(): List<File> =
  fontsDir.listFiles()
    ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
    ?.sortedBy { it.name.lowercase() }
    ?: emptyList()

@Composable
private fun rememberSelectedFontFamily(): FontFamily? {
  val fontName = remember { appPrefs.appFontFamily.state }.value
  return remember(fontName) {
    if (fontName.isNullOrEmpty()) null
    else File(fontsDir, fontName).takeIf { it.exists() }?.let { fontFamilyFromFile(it) }
  }
}

@Composable
private fun UIFontRow(level: Int) {
  val fontName = remember { appPrefs.appFontFamily.state }.value
  val family = rememberSelectedFontFamily()
  UIRow(level, click = { ModalManager.start.showModal { FontPickerView() } }) {
    Text("Font", Modifier.weight(1f), fontSize = 15.sp)
    Text(
      if (fontName.isNullOrEmpty()) "Inter (default)" else fontName.substringBeforeLast('.'),
      fontSize = 15.sp,
      fontFamily = family,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colors.primary
    )
  }
}

@Composable
private fun FontPreviewRow(level: Int) {
  val family = rememberSelectedFontFamily()
  UIRow(level) {
    Text(FONT_SAMPLE, fontSize = 16.sp, fontFamily = family, maxLines = 2)
  }
}

@Composable
fun FontPickerView() {
  ColumnWithScrollBar {
    AppBarTitle("Font")
    val current = remember { appPrefs.appFontFamily.state }.value
    var fonts by remember { mutableStateOf(installedFonts()) }

    FontOptionRow("Inter (default)", null, selected = current.isNullOrEmpty()) {
      appPrefs.appFontFamily.set(null)
    }
    fonts.forEach { f ->
      FontOptionRow(f.nameWithoutExtension, f, selected = current == f.name) {
        appPrefs.appFontFamily.set(f.name)
      }
    }

    val importFontLauncher = rememberFileChooserLauncher(true) { to: URI? ->
      if (to != null && saveFontFile(to) != null) {
        fonts = installedFonts()
      }
    }
    SectionItemView({ withLongRunningApi { importFontLauncher.launch("*/*") } }) {
      Text("Add font (.ttf / .otf)…", color = MaterialTheme.colors.primary)
    }
    SectionBottomSpacer()
  }
}

@Composable
private fun FontOptionRow(name: String, file: File?, selected: Boolean, onClick: () -> Unit) {
  val family = remember(file?.name) { file?.let { fontFamilyFromFile(it) } }
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = BASE_PADDING, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        name,
        fontSize = 16.sp,
        fontFamily = family,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colors.primary else Color.Unspecified
      )
      Text(FONT_SAMPLE, fontSize = 14.sp, fontFamily = family, color = MaterialTheme.colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (selected) {
      Icon(painterResource(MR.images.ic_check), null, tint = MaterialTheme.colors.primary)
    }
  }
}

private fun saveFontFile(uri: URI): String? {
  val name = getFileName(uri)
  if (name == null || name.substringAfterLast('.', "").lowercase() !in FONT_EXTENSIONS) {
    AlertManager.shared.showAlertMsg("Not a font file", "Choose a .ttf or .otf file.")
    return null
  }
  return try {
    val inputStream = uri.inputStream()!!
    val dest = File(fontsDir, name)
    Files.copy(inputStream, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    dest.name
  } catch (e: Exception) {
    AlertManager.shared.showAlertMsg(generalGetString(MR.strings.error), e.stackTraceToString())
    null
  }
}

// ───────────────────────── previews ─────────────────────────

@Composable
private fun prefColor(pref: SharedPreference<String?>, default: String): Color =
  remember { pref.state }.value?.colorFromReadableHex() ?: default.colorFromReadableHex()

@Composable
private fun ChatListPreviewRow(level: Int) {
  val nameColor = prefColor(appPrefs.chatListNameColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
  UIRow(level) {
    ProfileImage(size = 46.dp, image = null)
    Spacer(Modifier.width(8.dp))
    Text("白い熊", color = nameColor, style = MaterialTheme.typography.h3, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun BubblePreview(level: Int) {
  val rcvBg = prefColor(appPrefs.bubbleReceivedBackgroundColor, AppPreferences.DEFAULT_BUBBLE_BACKGROUND_COLOR)
  val rcvText = prefColor(appPrefs.bubbleReceivedTextColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
  val rcvBorder = prefColor(appPrefs.bubbleReceivedBorderColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
  val sentBg = prefColor(appPrefs.bubbleSentBackgroundColor, AppPreferences.DEFAULT_BUBBLE_BACKGROUND_COLOR)
  val sentText = prefColor(appPrefs.bubbleSentTextColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
  val sentBorder = prefColor(appPrefs.bubbleSentBorderColor, AppPreferences.DEFAULT_SHIROIKUMA_YELLOW)
  val roundness = remember { appPrefs.chatItemRoundness.state }.value.coerceIn(0f, 1f)
  val borderWidth = remember { appPrefs.bubbleBorderWidth.state }.value
  val senderIconSize = remember { appPrefs.bubbleSenderIconSize.state }.value
  val senderNameSize = remember { appPrefs.bubbleSenderNameSize.state }.value
  val shape = RoundedCornerShape(18.dp * roundness)

  @Composable
  fun bubble(bg: Color, border: Color, text: String, textColor: Color, senderRow: Boolean = false) {
    Box(
      Modifier
        .clip(shape)
        .background(bg)
        .let { if (borderWidth > 0f) it.border(borderWidth.dp, border, shape) else it }
        .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
      Column {
        Text(text, color = textColor, fontSize = 15.sp)
        if (senderRow) {
          Spacer(Modifier.height(3.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileImage(size = senderIconSize.dp, image = null, color = textColor)
            Spacer(Modifier.width(4.dp))
            Text("白い熊", color = textColor, fontSize = senderNameSize.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(start = BASE_PADDING + INDENT_STEP * level, end = BASE_PADDING, top = 6.dp, bottom = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    bubble(rcvBg, rcvBorder, "こんにちは — received", rcvText, senderRow = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      bubble(sentBg, sentBorder, "Hello — sent", sentText)
    }
  }
}

@Composable
private fun DateHeaderPreviewRow(level: Int) {
  val bold = remember { appPrefs.chatDateBold.state }.value
  val underline = remember { appPrefs.chatDateUnderline.state }.value
  UIRow(level) {
    Text(
      "July 7, 2026",
      fontSize = 14.sp,
      fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
      textDecoration = if (underline) TextDecoration.Underline else null,
      color = MaterialTheme.colors.secondary
    )
  }
}

@Composable
private fun CallIconPreviewRow(level: Int) {
  val scale = remember { appPrefs.callIconScale.state }.value
  UIRow(level) {
    Text("Preview", Modifier.weight(1f), fontSize = 15.sp)
    Icon(painterResource(MR.images.ic_call), null, Modifier.size(24.dp * scale), tint = MaterialTheme.colors.primary)
    Spacer(Modifier.width(8.dp))
    Icon(painterResource(MR.images.ic_call_end), null, Modifier.size(24.dp * scale), tint = MaterialTheme.colors.secondary)
  }
}

// the configured tick size, capped so a ×15 tick doesn't blow the settings rows apart
@Composable
private fun tickPreviewSizeDp(): Float =
  BASE_TICK_HEIGHT_DP * min(remember { appPrefs.messageTickScale.state }.value, 3f)

// downstream (shiroikuma): glyph row + picker, ported from the ArcaneChat fork. The row shows the
// state's current glyph, and the picker previews every option at the configured size, colour and
// thickness — rendered by the same TickIcon the chat footer uses, so the choice is made on the
// picture and a preview can never drift from the real thing.
@Composable
private fun TickGlyphRow(
  level: Int,
  label: String,
  pref: SharedPreference<String?>,
  default: TickGlyph,
  colorPref: SharedPreference<String?>,
  defaultColor: String,
) {
  val glyph = TickGlyph.from(remember { pref.state }.value, default)
  val color = prefColor(colorPref, defaultColor)
  val thickness = remember { appPrefs.messageTickThickness.state }.value
  val sizeDp = tickPreviewSizeDp()
  UIRow(level, click = { showTickGlyphPicker(label, pref, default, color, sizeDp, thickness) }) {
    Text(label, Modifier.weight(1f), fontSize = 15.sp)
    Text(glyph.label, fontSize = 13.sp, color = MaterialTheme.colors.secondary)
    Spacer(Modifier.width(12.dp))
    Box(Modifier.widthIn(min = 24.dp), contentAlignment = Alignment.CenterEnd) {
      TickIcon(glyph = glyph, color = color, sizeDp = sizeDp, thickness = thickness)
    }
  }
}

private fun showTickGlyphPicker(
  title: String,
  pref: SharedPreference<String?>,
  default: TickGlyph,
  color: Color,
  sizeDp: Float,
  thickness: Float,
) {
  ModalManager.start.showModal {
    ColumnWithScrollBar {
      AppBarTitle(title)
      val current = TickGlyph.from(remember { pref.state }.value, default)
      TickGlyph.values().forEach { g ->
        UIRow(0, click = { pref.set(g.key); ModalManager.start.closeModal() }) {
          if (g == current) {
            Icon(painterResource(MR.images.ic_done_filled), null, Modifier.size(18.dp), tint = MaterialTheme.colors.primary)
            Spacer(Modifier.width(6.dp))
          } else {
            Spacer(Modifier.width(24.dp))
          }
          Text(g.label, Modifier.weight(1f), fontSize = 15.sp)
          TickIcon(glyph = g, color = color, sizeDp = sizeDp, thickness = thickness)
        }
      }
      SectionBottomSpacer()
    }
  }
}
