package chat.simplex.common.views.chat.item

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.ui.graphics.painter.Painter
import dev.icerock.moko.resources.compose.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.appPreferences
import chat.simplex.common.ui.theme.isInDarkTheme
import chat.simplex.res.*
import chat.simplex.common.ui.theme.ThemeManager.colorFromReadableHex
import kotlin.math.roundToInt

// downstream (shiroikuma): base height of the sent/received delivery ticks, scaled by appPreferences.messageTickScale
const val BASE_TICK_HEIGHT_DP = 17f

// downstream (shiroikuma): the glyph each delivery state draws. Ported from the ArcaneChat fork's
// tick work — there the glyphs are vector drawables, here they are the same stroked paths the
// footer already drew, so a settings preview renders through the exact code path the chat uses and
// cannot drift from it. `widthFactor` is the glyph's width as a multiple of the tick height.
enum class TickGlyph(val key: String, val label: String, val widthFactor: Float) {
  TICK1("tick1", "Single tick", 1f),
  TICK2("tick2", "Double tick", 1.5f),
  TICK3("tick3", "Triple tick", 2f),
  DOT("dot", "Dot", 1f),
  CIRCLE_TICK("circle_check", "Tick in a circle", 1f),
  ARROW_UP("arrow_up", "Up arrow", 1f),
  CLOCK("clock", "Clock", 1f),
  BANG("bang", "Exclamation mark", 1f),
  NONE("none", "Hidden", 0f);

  companion object {
    fun from(key: String?, fallback: TickGlyph = TICK1): TickGlyph =
      values().firstOrNull { it.key == key } ?: fallback
  }
}

// downstream (shiroikuma): the sent / delivered delivery ticks, drawn ourselves as stroked paths
// instead of the stock vector Icons. This makes size = canvas size (scales freely, no ContentScale
// cap, no bubble clipping) and thickness = stroke width (a real weight, not a multiplied/ghosted
// icon). `thickness` (0..n) adds to a baseline stroke fraction; 0 ≈ the stock check weight.
@Composable
fun TickIcon(glyph: TickGlyph, color: Color, sizeDp: Float, thickness: Float) {
  if (glyph == TickGlyph.NONE) return
  val h = sizeDp.coerceAtLeast(1f)
  // downstream (shiroikuma): the dot is a fraction of the tick box, so at a tick size that looks
  // right it reads far too small next to one. Its multiplier is read here rather than passed in, so
  // the chat footer, the settings previews and the glyph picker can never disagree about it. The
  // canvas grows with the dot when the multiplier pushes it past the tick box.
  val dotScale = remember { appPreferences.messageTickDotScale.state }.value.coerceAtLeast(0.1f)
  val dotFraction = (0.36f + thickness * 0.06f) * dotScale   // dot diameter as a fraction of h
  val w = if (glyph == TickGlyph.DOT) maxOf(h, h * dotFraction) else h * glyph.widthFactor
  val canvasH = if (glyph == TickGlyph.DOT) maxOf(h, h * dotFraction) else h
  Canvas(Modifier.size(w.dp, canvasH.dp)) {
    val sw = size.height * (0.12f + thickness * 0.045f)   // baseline 0.12, grows with thickness
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val m = sw / 2f                      // inset so round caps aren't clipped at the edges
    val box = h.dp.toPx()                // each tick occupies an h-wide box
    val cx = size.width / 2f
    val cy = size.height / 2f
    fun checkIn(left: Float, top: Float, w: Float, hh: Float): Path = Path().apply {
      moveTo(left + 0.05f * w, top + 0.55f * hh)
      lineTo(left + 0.38f * w, top + 0.95f * hh)
      lineTo(left + 0.95f * w, top + 0.05f * hh)
    }
    // each further check of a multi-tick overlaps the previous one by half a box
    fun check(index: Int): Path =
      checkIn(index * box * 0.5f + m, m, box - 2 * m, size.height - 2 * m)
    fun stroke(path: Path) = drawPath(path, color = color, style = st)
    when (glyph) {
      TickGlyph.TICK1 -> stroke(check(0))
      TickGlyph.TICK2 -> repeat(2) { stroke(check(it)) }
      TickGlyph.TICK3 -> repeat(3) { stroke(check(it)) }
      TickGlyph.DOT -> drawCircle(color, radius = box * dotFraction / 2f, center = androidx.compose.ui.geometry.Offset(cx, cy))
      TickGlyph.CIRCLE_TICK -> {
        val r = size.minDimension / 2f - m
        drawCircle(color, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy), style = st)
        stroke(checkIn(cx - r * 0.62f, cy - r * 0.55f, r * 1.24f, r * 1.1f))
      }
      TickGlyph.ARROW_UP -> {
        val head = (size.width - 2 * m) * 0.32f
        stroke(Path().apply { moveTo(cx, size.height - m); lineTo(cx, m) })
        stroke(Path().apply {
          moveTo(cx - head, m + head)
          lineTo(cx, m)
          lineTo(cx + head, m + head)
        })
      }
      TickGlyph.CLOCK -> {
        val r = size.minDimension / 2f - m
        drawCircle(color, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy), style = st)
        stroke(Path().apply { moveTo(cx, cy); lineTo(cx, cy - r * 0.58f) })
        stroke(Path().apply { moveTo(cx, cy); lineTo(cx + r * 0.45f, cy) })
      }
      TickGlyph.BANG -> {
        stroke(Path().apply { moveTo(cx, m); lineTo(cx, size.height * 0.62f) })
        drawCircle(color, radius = sw * 0.6f, center = androidx.compose.ui.geometry.Offset(cx, size.height - m - sw * 0.6f))
      }
      TickGlyph.NONE -> {}
    }
  }
}

@Composable
fun CIMetaView(
  chatItem: ChatItem,
  timedMessagesTTL: Int?,
  metaColor: Color = MaterialTheme.colors.secondary,
  paleMetaColor: Color = if (isInDarkTheme()) {
    metaColor.copy(
      red = metaColor.red * 0.67F,
      green = metaColor.green * 0.67F,
      blue = metaColor.red * 0.67F)
  } else {
    metaColor.copy(
      red = minOf(metaColor.red * 1.33F, 1F),
      green = minOf(metaColor.green * 1.33F, 1F),
      blue = minOf(metaColor.red * 1.33F, 1F))
  },
  showStatus: Boolean = true,
  showEdited: Boolean = true,
  showTimestamp: Boolean,
  showViaProxy: Boolean,
  lineHeightColor: Color = metaColor,
) {
  val timeStyle = LocalTextStyle.current.merge(chatTextStyle(12.sp, lineHeightColor))
  Row(Modifier.padding(start = 3.dp), verticalAlignment = Alignment.CenterVertically) {
    if (chatItem.isDeletedContent) {
      Text(
        chatItem.timestampText,
        color = metaColor,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 3.dp),
        style = timeStyle
      )
    } else {
      CIMetaText(
        chatItem.meta,
        timedMessagesTTL,
        encrypted = chatItem.encryptedFile,
        metaColor,
        paleMetaColor,
        timeStyle,
        showStatus = showStatus,
        showEdited = showEdited,
        showViaProxy = showViaProxy,
        showTimestamp = showTimestamp,
        signedFileVerified = chatItem.file?.loaded
      )
    }
  }
}

@Composable
// changing this function requires updating reserveSpaceForMeta
private fun CIMetaText(
  meta: CIMeta,
  chatTTL: Int?,
  encrypted: Boolean?,
  color: Color,
  paleColor: Color,
  timeStyle: TextStyle,
  showStatus: Boolean = true,
  showEdited: Boolean = true,
  showTimestamp: Boolean,
  showViaProxy: Boolean,
  signedFileVerified: Boolean?,
) {
  val showSignature = appPreferences.privacyShowSignature.state.value
  val showEncryption = appPreferences.privacyShowEncryption.state.value
  if (showEdited && meta.itemEdited) {
    StatusIconText(painterResource(MR.images.ic_edit), color)
  }
  if (meta.disappearing) {
    StatusIconText(painterResource(MR.images.ic_timer), color)
    val ttl = meta.itemTimed?.ttl
    if (ttl != chatTTL) {
      Text(shortTimeText(ttl), color = color, fontSize = 12.sp, style = timeStyle)
    }
  }
  if (showViaProxy && meta.sentViaProxy == true) {
    Spacer(Modifier.width(4.dp))
    Icon(painterResource(MR.images.ic_arrow_forward), null, Modifier.height(17.dp), tint = MaterialTheme.colors.secondary)
  }
  if (showStatus) {
    Spacer(Modifier.width(4.dp))
    val statusIcon = meta.statusIcon(MaterialTheme.colors.primary, color, paleColor)
    if (statusIcon != null) {
      val (icon, statusColor) = statusIcon
      val status = meta.itemStatus
      if (status is CIStatus.SndSent || status is CIStatus.SndRcvd) {
        // downstream (shiroikuma): user-configurable size, thickness, color and glyph of the
        // sent/delivered delivery ticks
        val tickScale = remember { appPreferences.messageTickScale.state }.value
        val tickThickness = remember { appPreferences.messageTickThickness.state }.value
        val sentColor = remember { appPreferences.messageTickSentColor.state }.value?.colorFromReadableHex()
        val rcvdColor = remember { appPreferences.messageTickReceivedColor.state }.value?.colorFromReadableHex()
        val delivered = status is CIStatus.SndRcvd
        val tickColor = when {
          status is CIStatus.SndSent -> sentColor ?: statusColor
          // keep the red "bad message hash" warning regardless of the custom delivered color
          delivered && statusColor != Color.Red -> rcvdColor ?: statusColor
          else -> statusColor
        }
        val sentGlyph = remember { appPreferences.messageTickSentGlyph.state }.value
        val deliveredGlyph = remember { appPreferences.messageTickDeliveredGlyph.state }.value
        val glyph = if (delivered) TickGlyph.from(deliveredGlyph, TickGlyph.DOT) else TickGlyph.from(sentGlyph, TickGlyph.TICK1)
        TickIcon(glyph = glyph, color = tickColor, sizeDp = BASE_TICK_HEIGHT_DP * tickScale, thickness = tickThickness)
      } else {
        StatusIconText(painterResource(icon), statusColor)
      }
    } else if (!meta.disappearing) {
      StatusIconText(painterResource(MR.images.ic_circle_filled), Color.Transparent)
    }
  }
  if (encrypted != null && showEncryption) {
    Spacer(Modifier.width(4.dp))
    StatusIconText(painterResource(if (encrypted) MR.images.ic_lock else MR.images.ic_lock_open_right), color)
  }
  if (showSignature && meta.msgVerified?.verified == true && signedFileVerified != false) {
    Spacer(Modifier.width(4.dp))
    StatusIconText(painterResource(MR.images.ic_verified), color)
  } else if (meta.msgVerified is MsgVerified.SigMissing) {
    Spacer(Modifier.width(4.dp))
    StatusIconText(painterResource(MR.images.ic_verified_missing), Color.Red)
  }

  if (showTimestamp) {
    Spacer(Modifier.width(4.dp))
    Text(meta.timestampText, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = timeStyle)
  }
}

fun reserveSpaceForMeta(
  meta: CIMeta,
  chatTTL: Int?,
  encrypted: Boolean?,
  secondaryColor: Color,
  showStatus: Boolean = true,
  showEdited: Boolean = true,
  showViaProxy: Boolean = false,
  showTimestamp: Boolean,
  signedFileVerified: Boolean? = null
): String = buildString {
  reserveSpaceForMeta(meta, chatTTL, encrypted, secondaryColor, showStatus, showEdited, showViaProxy, showTimestamp, signedFileVerified, tallIconSpace = "", spaces = { append(it) }, text = { append(it) })
}

// the conditions in this function should match CIMetaText
private fun reserveSpaceForMeta(
  meta: CIMeta,
  chatTTL: Int?,
  encrypted: Boolean?,
  secondaryColor: Color,
  showStatus: Boolean,
  showEdited: Boolean,
  showViaProxy: Boolean,
  showTimestamp: Boolean,
  signedFileVerified: Boolean?,
  tallIconSpace: String,
  spaces: (String) -> Unit,
  text: (String) -> Unit
) {
  val showSignature = appPreferences.privacyShowSignature.state.value
  val showEncryption = appPreferences.privacyShowEncryption.state.value
  val iconSpace = " \u00A0\u00A0\u00A0"
  val whiteSpace = "\u00A0"
  if (!showTimestamp) spaces(iconSpace)
  var space: String? = null

  fun appendSpace() {
    space?.let(spaces)
    space = null
  }

  if (showEdited && meta.itemEdited) {
    spaces(iconSpace)
  }
  if (meta.itemTimed != null) {
    spaces(iconSpace)
    val ttl = meta.itemTimed.ttl
    if (ttl != chatTTL) {
      text(shortTimeText(ttl))
    }
    space = whiteSpace
  }
  if (showViaProxy && meta.sentViaProxy == true) {
    appendSpace()
    spaces(iconSpace + tallIconSpace)
  }
  if (showStatus) {
    appendSpace()
    if (meta.statusIcon(secondaryColor) != null) {
      if (meta.tallStatusIcon) {
        // downstream (shiroikuma): reserve proportionally more width for enlarged sent/received ticks
        val units = appPreferences.messageTickScale.get().roundToInt().coerceAtLeast(1)
        repeat(units) { spaces(iconSpace) }
        spaces(tallIconSpace)
      } else {
        spaces(iconSpace)
      }
    } else if (!meta.disappearing) {
      spaces(iconSpace)
    }
    space = whiteSpace
  }

  if (encrypted != null && showEncryption) {
    appendSpace()
    spaces(iconSpace)
    space = whiteSpace
  }
  if ((showSignature && meta.msgVerified?.verified == true && signedFileVerified != false) || meta.msgVerified is MsgVerified.SigMissing) {
    appendSpace()
    spaces(iconSpace)
    space = whiteSpace
  }
  if (showTimestamp) {
    appendSpace()
    text(meta.timestampText)
  }
}

@Composable
fun metaReserveCopy(
  meta: CIMeta,
  chatTTL: Int?,
  encrypted: Boolean?,
  signedFileVerified: Boolean?,
  secondaryColor: Color,
  showStatus: Boolean = true,
  showEdited: Boolean = true,
  showViaProxy: Boolean,
  showTimestamp: Boolean,
  breakBefore: Boolean = false
): AnnotatedString =
  metaReserveCopy(
    meta, chatTTL, encrypted, signedFileVerified, secondaryColor, showStatus, showEdited, showViaProxy, showTimestamp,
    density = LocalDensity.current,
    bodyFontSize = MaterialTheme.typography.body1.fontSize,
    direction = LocalLayoutDirection.current,
    breakBefore = breakBefore
  )

fun metaReserveCopy(
  meta: CIMeta,
  chatTTL: Int?,
  encrypted: Boolean?,
  signedFileVerified: Boolean?,
  secondaryColor: Color,
  showStatus: Boolean,
  showEdited: Boolean,
  showViaProxy: Boolean,
  showTimestamp: Boolean,
  density: Density,
  bodyFontSize: TextUnit,
  direction: LayoutDirection,
  breakBefore: Boolean = false
): AnnotatedString {
  val spaceStyle = SpanStyle(fontSize = with(density) { bodyFontSize.value.dp.toSp() }, fontWeight = FontWeight.Normal)
  val textStyle = SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
  return buildAnnotatedString {
    append((if (breakBefore) " " else "\u2060") + if (direction == LayoutDirection.Ltr) "\u200E" else "\u200F")
    reserveSpaceForMeta(
      meta, chatTTL, encrypted, secondaryColor, showStatus, showEdited, showViaProxy, showTimestamp, signedFileVerified, tallIconSpace = "\u00A0",
      spaces = { withStyle(spaceStyle) { append(it.replace(' ', '\u00A0')) } },
      text = { withStyle(textStyle) { append(it.replace(' ', '\u00A0')) } }
    )
  }
}

private val CIMeta.tallStatusIcon: Boolean get() =
  itemStatus is CIStatus.SndSent || itemStatus is CIStatus.SndRcvd

@Composable
private fun StatusIconText(icon: Painter, color: Color) {
  Icon(icon, null, Modifier.height(12.dp), tint = color)
}

@Preview
@Composable
fun PreviewCIMetaView() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello"
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewUnread() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello",
      status = CIStatus.RcvNew()
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendFailed() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello",
      status = CIStatus.CISSndError(SndError.Other("CMD SYNTAX"))
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendNoAuth() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello", status = CIStatus.SndErrorAuth()
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendSent() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello", status = CIStatus.SndSent(SndCIStatusProgress.Complete)
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEdited() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello",
      itemEdited = true
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEditedUnread() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectRcv(), previewDate, "hello",
      itemEdited = true,
      status= CIStatus.RcvNew()
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEditedSent() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), previewDate, "hello",
      itemEdited = true,
      status= CIStatus.SndSent(SndCIStatusProgress.Complete)
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewDeletedContent() = BubblePreview {
  CIMetaView(
    chatItem = ChatItem.getDeletedContentSampleData(ts = previewDate),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}
