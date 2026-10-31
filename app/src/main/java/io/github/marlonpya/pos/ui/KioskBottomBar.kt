package io.github.marlonpya.pos.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class KioskTab(val label: String, @DrawableRes val icon: Int)

/**
 * Barra inferior para el terminal. Hace lo mismo que `NavigationBar` de Material 3
 * (pestaña seleccionada, ícono y etiqueta) pero con zonas táctiles de 72dp y
 * etiqueta más grande, pensada para dedos y lectura a distancia en caja.
 *
 * No agrega padding cuando las barras del sistema están ocultas (modo kiosko).
 */
@Composable
fun KioskBottomBar(
    tabs: List<KioskTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, tab ->
                KioskTabItem(tab, selected = index == selectedIndex) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun RowScope.KioskTabItem(tab: KioskTab, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val pill by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainer.copy(alpha = 0f),
        label = "pill",
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        label = "content",
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 72.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(pill)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            // La etiqueta ya nombra la pestaña: el ícono no se lee dos veces.
            Icon(
                painter = painterResource(tab.icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
