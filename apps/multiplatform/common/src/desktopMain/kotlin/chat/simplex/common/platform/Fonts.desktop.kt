package chat.simplex.common.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.io.File

actual fun fontFamilyFromFile(file: File): FontFamily? = try {
  FontFamily(Font(file))
} catch (e: Exception) {
  Log.e(TAG, "fontFamilyFromFile: failed to load ${file.name}: ${e.message}")
  null
}
