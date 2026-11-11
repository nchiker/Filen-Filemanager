package app.filenfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Stack's building blocks (org.stackdocs.app Ui.actionRow / StackStyle), so both apps look alike:
 * white circle with a 3dp pastel ring around an ink line icon, action rows with a muted caption and chevron, 1dp dividers.
 */
object Rings {
    /** Stack's ring colours, in Stack's order (StackStyle.java). */
    val all = listOf(
        Color(0xFFB5E2ED), Color(0xFFB5DCC1), Color(0xFFF7E1A3), Color(0xFFF2C5DC),
        Color(0xFFB9DCED), Color(0xFFDDC4ED), Color(0xFFF4D6BB), Color(0xFFF0BFC6),
    )
    val neutral = Color(0xFFE4EDE2)
    /** Stack's softer set for settings rows: light blue, yellow, light green. */
    val softBlue = Color(0xFFDCECF9)
    val softYellow = Color(0xFFF7E1A3)
    val softGreen = Color(0xFFDCEADB)
    fun at(i: Int) = all[((i % all.size) + all.size) % all.size]
}

private val isDark: Boolean @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** A ring badge: 56dp circle in the page colour, 3dp pastel ring, 26dp icon in ink (40dp badge at large font scales). */
@Composable
fun RingBadge(icon: ImageVector, ring: Color, modifier: Modifier = Modifier, size: Dp? = null, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val large = LocalDensity.current.fontScale >= 1.5f
    val s = size ?: if (large) 40.dp else 56.dp
    // In dark mode the pastel rings are toned down so they don't glare on the dark page.
    val r = if (isDark) ring.copy(alpha = 0.55f) else ring
    Box(
        modifier.size(s).clip(CircleShape).background(MaterialTheme.colorScheme.surface).border(3.dp, r, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(s * 26 / 56), tint = tint) }
}

/** Stack's action row: ring badge, 16sp medium title, 13sp muted caption, muted chevron. Opens a dialog or a screen. */
@Composable
fun ActionRow(icon: ImageVector, ring: Color, title: String, caption: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 16.dp).clearAndSetSemantics { contentDescription = "$title. $caption" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RingBadge(icon, ring)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            // Tight lines (Stack: includeFontPadding=false), so the caption sits 6dp under the title.
            Text(title, style = TightText.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = TightText.copy(fontSize = 13.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Text without font padding or extra tracking, like Stack's TextViews (includeFontPadding=false, letterSpacing 0). */
val TightText = androidx.compose.ui.text.TextStyle(
    letterSpacing = 0.sp,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both),
)

/**
 * Stack's dialog: white, 8dp corners, 20sp regular title, content, then the buttons right under it (M3's AlertDialog
 * leaves a large gap above its buttons). Buttons are sentence-case text buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackDialog(onDismiss: () -> Unit, title: String, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp, shadowElevation = 6.dp) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(title, style = TightText.copy(fontSize = 20.sp, lineHeight = 26.sp), color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Column(Modifier.weight(1f, fill = false).padding(start = 24.dp, end = 24.dp, top = 12.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End, content = buttons)
            }
        }
    }
}

/** Stack's 1dp #EEEEEE line between rows. */
@Composable
fun RowDivider() = HorizontalDivider(thickness = 1.dp, color = if (isDark) Color(0xFF2A2B2E) else Color(0xFFEEEEEE))

/** Blue text link (Stack's "About these settings"): 16sp, blue, no underline. */
@Composable
fun TextLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(vertical = 12.dp).semantics { },
    )
}

/** Stack's section header: 14sp medium ink title, optional blue 14sp medium text action on the right ("Edit"). */
@Composable
fun StackSectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onAction) { Text(action, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
    }
}

/** A setting inside a dialog: label + caption on the left, switch on the right (Stack keeps switches inside dialogs). */
@Composable
fun DialogSwitch(title: String, checked: Boolean, caption: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (caption != null) Text(caption, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** A tappable choice inside a dialog (radio style), for picks like the theme or lock time. */
@Composable
fun DialogChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick)
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
