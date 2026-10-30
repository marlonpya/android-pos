package io.github.marlonpya.pos.domain

import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.IsoCodec
import io.github.marlonpya.iso8583.IsoMessage
import io.github.marlonpya.iso8583.MessageMac
import io.github.marlonpya.iso8583.Mti
import io.github.marlonpya.pinpad.PinpadResponse
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Arma los mensajes ISO 8583 del terminal y los firma a través del PINpad.
 *
 * La app nunca tiene una llave: arma el mensaje, el PINpad calcula el MAC con
 * la variante de MAC de su llave DUKPT, y la app lo pega en el campo 64 o 128.
 */
class MessageFactory(
    private val config: TerminalConfig,
    private val pinpad: PinpadPort,
    private val codec: IsoCodec = IsoCodec(),
) {
    private val mac = MessageMac(codec)

    /** Venta 0200 con los datos que devolvió el PINpad. Usa la misma llave de la lectura de tarjeta. */
    fun sale(card: PinpadResponse.CardRead, amountCents: Long, stan: String, f7: String, now: Instant): ByteArray {
        var m = cardMessage(Mti.FINANCIAL_REQUEST, card, amountCents, stan, f7, now)
        card.pinBlock?.let { m = m.with(Fields.PIN_BLOCK, it) }
        return sign(m.with(Fields.KSN, card.ksn))
    }

    /**
     * Aviso 0220 de una venta aprobada sin conexión. No lleva PIN block: sin
     * host no hay quién verifique un PIN online. Se firma con una llave nueva.
     */
    fun advice(card: PinpadResponse.CardRead, amountCents: Long, stan: String, f7: String, now: Instant): ByteArray =
        sign(cardMessage(Mti.FINANCIAL_ADVICE, card, amountCents, stan, f7, now).with(Fields.KSN, pinpad.nextKsn()))

    /**
     * Reversa 0400 de [original]. Se arma solo con lo que guarda la bitácora,
     * sin datos de tarjeta, así que también se puede armar después de un
     * reinicio de la app.
     */
    fun reversal(original: Transaction, stan: String, now: Instant): ByteArray {
        val originalData = Mti.FINANCIAL_REQUEST.code + original.stan + original.transmissionDateTime + "0".repeat(22)
        val m = IsoMessage.of(Mti.REVERSAL_REQUEST)
            .with(Fields.PROCESSING_CODE, "000000")
            .with(Fields.AMOUNT, original.amountCents.toString())
            .with(Fields.TRANSMISSION_DATETIME, transmissionDateTime(now))
            .with(Fields.STAN, stan)
            .with(Fields.TERMINAL_ID, config.terminalId)
            .with(Fields.MERCHANT_ID, config.merchantId)
            .with(Fields.CURRENCY, original.currency)
            .with(Fields.ORIGINAL_DATA, originalData)
            .with(Fields.KSN, pinpad.nextKsn())
        return sign(m)
    }

    fun responseOf(raw: ByteArray): IsoMessage = codec.unpack(raw)

    private fun cardMessage(
        mti: Mti,
        card: PinpadResponse.CardRead,
        amountCents: Long,
        stan: String,
        f7: String,
        now: Instant,
    ): IsoMessage {
        val local = now.atOffset(ZoneOffset.UTC)
        return IsoMessage.of(mti)
            .with(Fields.PROCESSING_CODE, "000000")
            .with(Fields.AMOUNT, amountCents.toString())
            .with(Fields.TRANSMISSION_DATETIME, f7)
            .with(Fields.STAN, stan)
            .with(Fields.LOCAL_TIME, local.format(DateTimeFormatter.ofPattern("HHmmss")))
            .with(Fields.LOCAL_DATE, local.format(DateTimeFormatter.ofPattern("MMdd")))
            .with(Fields.ENTRY_MODE, card.entryMode)
            .with(Fields.CARD_SEQUENCE, card.cardSequence)
            .with(Fields.POS_CONDITION, "00")
            .with(Fields.TRACK2, card.track2)
            .with(Fields.TERMINAL_ID, config.terminalId)
            .with(Fields.MERCHANT_ID, config.merchantId)
            .with(Fields.CURRENCY, config.currency)
            .with(Fields.EMV_DATA, card.emv)
    }

    private fun sign(message: IsoMessage): ByteArray {
        val expectedKsn = message.text(Fields.KSN)
        val signed = mac.sign(message) { data ->
            val (ksn, value) = pinpad.mac(data)
            // Si el PINpad firmó con otra llave, el host no podrá verificar: mejor fallar aquí.
            check(ksn == expectedKsn) { "El PINpad firmó con otro KSN" }
            value
        }
        return codec.pack(signed)
    }

    companion object {
        /** Campo 7: MMDDhhmmss en UTC. */
        fun transmissionDateTime(now: Instant): String =
            now.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMddHHmmss"))

        fun stanText(stan: Int): String = stan.toString().padStart(6, '0')
    }
}
