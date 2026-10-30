package io.github.marlonpya.pos.ui

import io.github.marlonpya.pos.domain.TxStatus
import io.github.marlonpya.pos.ui.theme.ResultColors

/** Montos en soles a partir de centavos, sin pasar por Double. */
fun money(cents: Long): String = "S/ %d.%02d".format(cents / 100, cents % 100)

fun statusLabel(status: TxStatus): String = when (status) {
    TxStatus.AUTHORIZING -> "Autorizando"
    TxStatus.APPROVED -> "Aprobada"
    TxStatus.DECLINED -> "Rechazada"
    TxStatus.OFFLINE_APPROVED -> "Aprobada offline"
    TxStatus.REVERSAL_PENDING -> "Reversa pendiente"
    TxStatus.REVERSED -> "Reversada"
    TxStatus.FAILED -> "Fallida"
}

fun statusColor(status: TxStatus) = when (status) {
    TxStatus.APPROVED, TxStatus.OFFLINE_APPROVED -> ResultColors.approved
    TxStatus.DECLINED, TxStatus.FAILED -> ResultColors.declined
    TxStatus.REVERSAL_PENDING, TxStatus.AUTHORIZING -> ResultColors.pending
    TxStatus.REVERSED -> ResultColors.neutral
}
