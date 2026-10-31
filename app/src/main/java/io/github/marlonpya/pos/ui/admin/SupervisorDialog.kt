package io.github.marlonpya.pos.ui.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.marlonpya.pos.domain.KioskGuard
import io.github.marlonpya.pos.domain.KioskState
import io.github.marlonpya.pos.domain.SupervisorCodeStore
import io.github.marlonpya.pos.domain.UnlockResult

private const val MIN_LENGTH = 4

/**
 * Diálogo del supervisor. Según el estado pide el código para salir, deja
 * configurarlo la primera vez, o permite volver a bloquear / cambiarlo.
 * Los mensajes nunca repiten lo que se escribió.
 */
@Composable
fun SupervisorDialog(
    guard: KioskGuard,
    store: SupervisorCodeStore,
    state: KioskState,
    onDismiss: () -> Unit,
) {
    var supervisorCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf(false) }

    val configuring = !store.isSet() || (state == KioskState.UNLOCKED && changing)
    val unlocked = state == KioskState.UNLOCKED && !changing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supervisor") },
        text = {
            if (unlocked) {
                Text("El modo kiosko está desactivado.")
            } else {
                OutlinedTextField(
                    value = supervisorCode,
                    onValueChange = { supervisorCode = it.filter(Char::isDigit).take(12) },
                    label = { Text(if (configuring) "Nuevo código (mín. $MIN_LENGTH dígitos)" else "Código de supervisor") },
                    supportingText = message?.let { { Text(it) } },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            if (unlocked) {
                TextButton(onClick = { guard.lock(); onDismiss() }) { Text("Volver a bloquear") }
            } else {
                TextButton(onClick = {
                    if (configuring) {
                        if (supervisorCode.length < MIN_LENGTH) {
                            message = "Muy corto"
                        } else {
                            store.save(supervisorCode)
                            changing = false
                            supervisorCode = ""
                            message = "Código guardado"
                        }
                    } else {
                        message = when (val r = guard.unlock(supervisorCode)) {
                            UnlockResult.Granted -> { onDismiss(); null }
                            is UnlockResult.Denied -> "Código incorrecto. Intentos: ${r.remaining}"
                            is UnlockResult.Blocked -> "Bloqueado temporalmente"
                            UnlockResult.NotConfigured -> "Falta configurar el código"
                        }
                        supervisorCode = ""
                    }
                }) { Text(if (configuring) "Guardar" else "Entrar") }
            }
        },
        dismissButton = {
            Row {
                if (unlocked) TextButton(onClick = { changing = true }) { Text("Cambiar código") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}
