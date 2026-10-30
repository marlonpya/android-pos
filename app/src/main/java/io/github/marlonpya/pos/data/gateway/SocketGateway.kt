package io.github.marlonpya.pos.data.gateway

import io.github.marlonpya.iso8583.LengthPrefixFraming
import io.github.marlonpya.pos.domain.GatewayException
import io.github.marlonpya.pos.domain.GatewayPort
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Interruptor de red del terminal, para el panel de caos: simula el modo
 * avión sin tocar la configuración del dispositivo.
 */
class NetworkSwitch {
    val online = MutableStateFlow(true)
}

/**
 * Cliente ISO 8583 por TCP: una conexión por mensaje, con encabezado de dos
 * bytes de largo.
 *
 * Lo que importa es en qué momento falla, porque cambia la decisión del
 * dominio: si falla al conectar, no salió nada ([GatewayException.NotConnected]);
 * si falla después de escribir, el host pudo haber procesado
 * ([GatewayException.NoResponse]).
 */
class SocketGateway(
    private val host: String,
    private val port: Int,
    private val network: NetworkSwitch,
    private val connectTimeoutMs: Int = 3_000,
    private val responseTimeoutMs: Int = 10_000,
) : GatewayPort {

    override fun exchange(raw: ByteArray): ByteArray {
        if (!network.online.value) throw GatewayException.NotConnected()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            socket.close()
            throw GatewayException.NotConnected(e)
        }
        socket.use { s ->
            try {
                s.getOutputStream().apply {
                    write(LengthPrefixFraming.frame(raw))
                    flush()
                }
            } catch (e: IOException) {
                // Pudo haber salido una parte: para el dominio ya es incierto.
                throw GatewayException.NoResponse(e)
            }
            try {
                s.soTimeout = responseTimeoutMs
                val input = DataInputStream(s.getInputStream())
                val header = ByteArray(2).also { input.readFully(it) }
                return ByteArray(LengthPrefixFraming.readLength(header)).also { input.readFully(it) }
            } catch (e: IOException) {
                throw GatewayException.NoResponse(e)
            }
        }
    }
}
