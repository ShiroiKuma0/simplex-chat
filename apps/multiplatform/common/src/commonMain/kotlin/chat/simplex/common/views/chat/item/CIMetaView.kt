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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.appPreferences
import chat.simplex.common.ui.theme.isInDarkTheme
import chat.simplex.common.ui.theme.ThemeManager.colorFromReadableHex
import chat.simplex.res.MR
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

// downstream (shiroikuma): base height of the sent/received delivery ticks, scaled by appPreferences.messageTickScale
const val BASE_TICK_HEIGHT_DP = 17f

// downstream (shiroikuma): the sent (single) / received (double) delivery ticks, drawn ourselves as stroked
// check paths instead of the stock vector Icons. This makes size = canvas size (scales freely, no ContentScale
// cap, no bubble clipping) and thickness = stroke width (a real weight, not a multiplied/ghosted icon).
// `thickness` (0..n) adds to a baseline stroke fraction; 0 ≈ the stock check weight.
@Composable
fun TickIcon(double: Boolean, color: Color, sizeDp: Float, thickness: Float) {
  val h = sizeDp.coerceAtLeast(1f)
  val checkW = h                        // each check occupies an h-wide box
  val secondOffset = h * 0.5f           // the second check of a double tick overlaps the first
  val widthDp = if (double) checkW + secondOffset else checkW
  Canvas(Modifier.size(widthDp.dp, h.dp)) {
    val sw = size.height * (0.12f + thickness * 0.045f)   // baseline 0.12, grows with thickness
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun check(ox: Float): Path {
      val m = sw / 2f                    // inset so round caps aren't clipped at the edges
      val left = ox + m; val right = ox + checkW.dp.toPx() - m
      val top = m; val bot = size.height - m
      val w = right - left; val hh = bot - top
      return Path().apply {
        moveTo(left + 0.05f * w, top + 0.55f * hh)
        lineTo(left + 0.38f * w, top + 0.95f * hh)
        lineTo(left + 0.95f * w, top + 0.05f * hh)
      }
    }
    drawPath(check(0f), color = color, style = st)
    if (double) drawPath(check(secondOffset.dp.toPx()), color = color, style = st)
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
) {
  Row(Modifier.padding(start = 3.dp), verticalAlignment = Alignment.CenterVertically) {
    if (chatItem.isDeletedContent) {
      Text(
        chatItem.timestampText,
        color = metaColor,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 3.dp)
      )
    } else {
      CIMetaText(
        chatItem.meta,
        timedMessagesTTL,
        encrypted = chatItem.encryptedFile,
        metaColor,
        paleMetaColor,
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
      Text(shortTimeText(ttl), color = color, fontSize = 12.sp)
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
        // downstream (shiroikuma): user-configurable size, thickness and color of the sent/received delivery ticks
        val tickScale = remember { appPreferences.messageTickScale.state }.value
        val tickThickness = remember { appPreferences.messageTickThickness.state }.value
        val sentColor = remember { appPreferences.messageTickSentColor.state }.value?.colorFromReadableHex()
        val rcvdColor = remember { appPreferences.messageTickReceivedColor.state }.value?.colorFromReadableHex()
        val tickColor = when {
          status is CIStatus.SndSent -> sentColor ?: statusColor
          // keep the red "bad message hash" warning regardless of the custom received color
          status is CIStatus.SndRcvd && statusColor != Color.Red -> rcvdColor ?: statusColor
          else -> statusColor
        }
        TickIcon(double = status is CIStatus.SndRcvd, color = tickColor, sizeDp = BASE_TICK_HEIGHT_DP * tickScale, thickness = tickThickness)
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
    Text(meta.timestampText, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

// the conditions in this function should match CIMetaText
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
): String {
  val showSignature = appPreferences.privacyShowSignature.state.value
  val showEncryption = appPreferences.privacyShowEncryption.state.value
  val iconSpace = " \u00A0\u00A0\u00A0"
  val whiteSpace = "\u00A0"
  var res = if (showTimestamp) "" else iconSpace
  var space: String? = null

  fun appendSpace() {
    if (space != null) {
      res += space
      space = null
    }
  }

  if (showEdited && meta.itemEdited) {
    res += iconSpace
  }
  if (meta.itemTimed != null) {
    res += iconSpace
    val ttl = meta.itemTimed.ttl
    if (ttl != chatTTL) {
      res += shortTimeText(ttl)
    }
    space = whiteSpace
  }
  if (showViaProxy && meta.sentViaProxy == true) {
    appendSpace()
    res += iconSpace
  }
  if (showStatus) {
    appendSpace()
    if (meta.statusIcon(secondaryColor) != null) {
      // downstream (shiroikuma): reserve proportionally more width for enlarged sent/received ticks
      val sndTick = meta.itemStatus is CIStatus.SndSent || meta.itemStatus is CIStatus.SndRcvd
      val units = if (sndTick) appPreferences.messageTickScale.get().roundToInt().coerceAtLeast(1) else 1
      repeat(units) { res += iconSpace }
    } else if (!meta.disappearing) {
      res += iconSpace
    }
    space = whiteSpace
  }

  if (encrypted != null && showEncryption) {
    appendSpace()
    res += iconSpace
    space = whiteSpace
  }
  if ((showSignature && meta.msgVerified?.verified == true && signedFileVerified != false) || meta.msgVerified is MsgVerified.SigMissing) {
    appendSpace()
    res += iconSpace
    space = whiteSpace
  }
  if (showTimestamp) {
    appendSpace()
    res += meta.timestampText
  }
  return res
}

@Composable
private fun StatusIconText(icon: Painter, color: Color) {
  Icon(icon, null, Modifier.height(12.dp), tint = color)
}

@Preview
@Composable
fun PreviewCIMetaView() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello"
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewUnread() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      status = CIStatus.RcvNew()
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendFailed() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      status = CIStatus.CISSndError(SndError.Other("CMD SYNTAX"))
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendNoAuth() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello", status = CIStatus.SndErrorAuth()
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendSent() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello", status = CIStatus.SndSent(SndCIStatusProgress.Complete)
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEdited() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      itemEdited = true
    ),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEditedUnread() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectRcv(), Clock.System.now(), "hello",
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
fun PreviewCIMetaViewEditedSent() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
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
fun PreviewCIMetaViewDeletedContent() {
  CIMetaView(
    chatItem = ChatItem.getDeletedContentSampleData(),
    null,
    showViaProxy = false,
    showTimestamp = true
  )
}
