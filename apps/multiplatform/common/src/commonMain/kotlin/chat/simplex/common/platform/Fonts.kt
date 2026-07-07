package chat.simplex.common.platform

import androidx.compose.ui.text.font.FontFamily
import java.io.File

// downstream (shiroikuma): external font support for the 白い熊 Simplex UI page.
// User-imported .ttf/.otf files live in this app-private directory; the selected one
// (appPrefs.appFontFamily) replaces the built-in Inter across the whole Typography.
val fontsDir: File get() = File(filesDir, "assets/fonts").also { it.mkdirs() }

expect fun fontFamilyFromFile(file: File): FontFamily?
