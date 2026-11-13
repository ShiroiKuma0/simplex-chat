package chat.simplex.common.views.helpers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
fun DefaultDropdownMenu(
  showMenu: MutableState<Boolean>,
  modifier: Modifier = Modifier,
  offset: DpOffset = DpOffset(0.dp, 0.dp),
  onClosed: State<() -> Unit> = remember { mutableStateOf({}) },
  // downstream (shiroikuma): a menu of pills hugs them instead of spanning 250 dp
  minWidth: Dp = 250.dp,
  dropdownMenuItems: (@Composable () -> Unit)?
) {
  // downstream (shiroikuma): the popup is clipped to `medium`, so a border in the same shape on
  // the content column lands exactly on its rounded edge — on the black-yellow theme the menu
  // surface is otherwise indistinguishable from the screen behind it (same as the alert dialogs).
  val shape = RoundedCornerShape(corner = CornerSize(25.dp))
  MaterialTheme(
    shapes = MaterialTheme.shapes.copy(medium = shape)
  ) {
    DropdownMenu(
      expanded = showMenu.value,
      onDismissRequest = { showMenu.value = false },
      modifier = modifier
        .widthIn(min = minWidth)
        .border(2.dp, MaterialTheme.colors.primary, shape)
        .background(MaterialTheme.colors.surface)
        .padding(vertical = 4.dp),
      offset = offset,
    ) {
      dropdownMenuItems?.invoke()
        DisposableEffect(Unit) {
          onDispose {
            onClosed.value()
          }
      }
    }
  }
}

@Composable
fun ExposedDropdownMenuBoxScope.DefaultExposedDropdownMenu(
  expanded: MutableState<Boolean>,
  modifier: Modifier = Modifier,
  dropdownMenuItems: (@Composable () -> Unit)?
) {
  val shape = RoundedCornerShape(corner = CornerSize(25.dp))
  MaterialTheme(
    shapes = MaterialTheme.shapes.copy(medium = shape)
  ) {
    ExposedDropdownMenu(
      modifier = Modifier
        .widthIn(min = 200.dp)
        .border(2.dp, MaterialTheme.colors.primary, shape)   // downstream (shiroikuma), see above
        .background(MaterialTheme.colors.surface)
        .then(modifier),
      expanded = expanded.value,
      onDismissRequest = {
        expanded.value = false
      }
    ) {
      dropdownMenuItems?.invoke()
    }
  }
}