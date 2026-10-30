package io.github.marlonpya.pos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00695C)
private val TealLight = Color(0xFF4DB6AC)

/** Colores de resultado, iguales en claro y oscuro para que se lean de un vistazo en caja. */
object ResultColors {
    val approved = Color(0xFF2E7D32)
    val declined = Color(0xFFC62828)
    val pending = Color(0xFFEF6C00)
    val neutral = Color(0xFF546E7A)
}

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = TealLight, secondary = TealLight)
    } else {
        lightColorScheme(primary = Teal, secondary = Teal)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
