package io.github.marlonpya.pos.domain

/**
 * Estado de una transacción en la bitácora del terminal.
 *
 * [AUTHORIZING] se escribe antes de mandar nada al host (write-ahead): si la
 * app muere a mitad del cobro, al volver sabe que hay una transacción en
 * estado incierto y la reversa.
 */
enum class TxStatus {
    AUTHORIZING,
    APPROVED,
    DECLINED,

    /** Aprobada sin conexión bajo el límite offline; su aviso 0220 está en la cola. */
    OFFLINE_APPROVED,

    /** Estado incierto: se mandó y no hubo respuesta. La reversa está en la cola. */
    REVERSAL_PENDING,
    REVERSED,
    FAILED,
}

data class Transaction(
    val id: String,
    val stan: String,
    val transmissionDateTime: String,
    val amountCents: Long,
    val currency: String,
    val maskedPan: String?,
    val status: TxStatus,
    val responseCode: String? = null,
    val authCode: String? = null,
    val rrn: String? = null,
    val ksn: String? = null,
    val message: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

enum class OutboxKind { ADVICE, REVERSAL }

/**
 * Mensaje ya firmado esperando salir al host (store and forward).
 *
 * Se guarda el mensaje exacto, con su MAC, y se reenvía siempre igual: así el
 * host lo reconoce como el mismo y responde lo mismo (idempotencia). En disco
 * va cifrado con una llave del Android Keystore porque puede llevar la pista 2.
 */
class OutboxItem(
    val id: Long,
    val transactionId: String,
    val kind: OutboxKind,
    payload: ByteArray,
    val attempts: Int,
    val createdAt: Long,
) {
    private val data = payload.copyOf()
    val payload: ByteArray get() = data.copyOf()
}

data class TerminalConfig(
    val terminalId: String = "LAB00001",
    val merchantId: String = "COMERCIO-LAB",
    /** ISO 4217 numérico: 604 es PEN. */
    val currency: String = "604",
    /** Hasta este monto se aprueba sin conexión y se avisa después. */
    val floorLimitCents: Long = 5_000,
    val offlineEnabled: Boolean = true,
)
