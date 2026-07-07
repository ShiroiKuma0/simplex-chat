package chat.simplex.common.ui.theme

import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.fontFamilyFromFile
import chat.simplex.common.platform.fontsDir
import java.io.File

// Set of Material typography styles to start with
val Typography = Typography(
  h1 = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Bold,
    fontSize = 33.5.sp,
  ),
  h2 = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 24.sp
  ),
  h3 = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 18.5.sp
  ),
  h4 = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 17.5.sp
  ),
  body1 = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp
  ),
  body2 = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp
  ),
  button = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
  ),
  caption = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp
  )
)

// downstream (shiroikuma): app-wide font family override (白い熊 Simplex UI page).
// When appPrefs.appFontFamily names an imported font file, every Typography style is
// rebuilt around it; otherwise the stock Inter Typography is used unchanged.
@Composable
fun appTypography(): Typography {
  val fontName = remember { appPrefs.appFontFamily.state }.value
  val family = remember(fontName) {
    if (fontName.isNullOrEmpty()) null
    else File(fontsDir, fontName).takeIf { it.exists() }?.let { fontFamilyFromFile(it) }
  } ?: return Typography
  return remember(family) {
    Typography(
      defaultFontFamily = family,
      h1 = Typography.h1.copy(fontFamily = family),
      h2 = Typography.h2.copy(fontFamily = family),
      h3 = Typography.h3.copy(fontFamily = family),
      h4 = Typography.h4.copy(fontFamily = family),
      body1 = Typography.body1.copy(fontFamily = family),
      body2 = Typography.body2.copy(fontFamily = family),
      button = Typography.button.copy(fontFamily = family),
      caption = Typography.caption.copy(fontFamily = family),
    )
  }
}
