package app.filenfiles.ui

import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.ui.graphics.Color

/**
 * Touches are dropped while another window covers this one (tapjacking). Put once in the content of every dialog and
 * activity: the dialog's own window is a separate window, so it needs its own.
 */
@Composable
fun TapGuard() {
    val v = LocalView.current
    SideEffect { v.filterTouchesWhenObscured = true; v.rootView.filterTouchesWhenObscured = true }
}

/**
 * Every dialog in the app goes through this (it shadows the Material one inside package app.filenfiles.ui, and is
 * imported explicitly elsewhere): the dialog is its own window, so it gets its own [TapGuard]. FilesTheme covers the
 * activity windows.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: androidx.compose.ui.unit.Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) = M3AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = { TapGuard(); confirmButton() },
    modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text, shape = shape,
    containerColor = containerColor, iconContentColor = iconContentColor, titleContentColor = titleContentColor,
    textContentColor = textContentColor, tonalElevation = tonalElevation, properties = properties,
)
