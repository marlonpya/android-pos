package io.github.marlonpya.pos.data.pinpad

import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.pinpad.Link
import io.github.marlonpya.pinpad.PinpadClient
import io.github.marlonpya.pinpad.PinpadCommand
import io.github.marlonpya.pinpad.PinpadException
import io.github.marlonpya.pinpad.PinpadResponse
import io.github.marlonpya.pinpad.PipeLink
import io.github.marlonpya.pinpad.ProtocolException
import io.github.marlonpya.pinpad.SocketLink
import io.github.marlonpya.pinpad.simulator.PinpadSimulator
import io.github.marlonpya.pinpad.simulator.SimulatorEndpoint
import io.github.marlonpya.pos.domain.PinpadPort
import io.github.marlonpya.pos.domain.PinpadRejected
import java.net.InetSocketAddress
import java.net.Socket

/** Dónde está el PINpad. */
enum class PinpadSource {
    /** Simulador corriendo dentro de la app, por un enlace en memoria. No necesita nada más. */
    IN_APP,

    /** Simulador externo (`pinpad-protocol-kt`, `./gradlew run`) por TCP, como un PINpad de red. */
    TCP,
}

/**
 * [PinpadPort] sobre el protocolo de tramas, con cualquiera de los dos
 * enlaces. El protocolo y los reintentos son los mismos: solo cambia el
 * [Link].
 *
 * Si el enlace se cae, la conexión se descarta y la siguiente llamada abre
 * una nueva, que es lo que haría una caja con un PINpad que se reinicia.
 */
class LinkedPinpad(
    @Volatile var source: PinpadSource,
    private val host: String,
    private val port: Int,
) : PinpadPort {

    private var client: PinpadClient? = null
    private var close: (() -> Unit)? = null
    private var connectedTo: PinpadSource? = null

    /** El simulador interno vive mientras viva la app: así conserva su KSN entre conexiones. */
    private val inAppSimulator by lazy { PinpadSimulator(serial = "SIM-APP-01") }

    @Synchronized
    private fun <T> call(command: PinpadCommand, read: (PinpadResponse) -> T): T {
        val c = connect()
        val response = try {
            c.send(command)
        } catch (e: PinpadException) {
            if (e is PinpadException.Disconnected || e is PinpadException.NoResponse) disconnect()
            throw e
        }
        if (response is PinpadResponse.Failure) throw PinpadRejected(response.reason)
        return read(response)
    }

    override fun readCard(amountCents: Long, currency: String, stan: Int) =
        call(PinpadCommand.Sale(amountCents, currency, stan)) {
            it as? PinpadResponse.CardRead ?: throw ProtocolException("Se esperaba una tarjeta")
        }

    override fun nextKsn(): String = call(PinpadCommand.NextKsn) {
        (it as? PinpadResponse.KsnInfo)?.ksn ?: throw ProtocolException("Se esperaba un KSN")
    }

    override fun mac(data: ByteArray): Pair<String, ByteArray> = call(PinpadCommand.Mac(data)) {
        val r = it as? PinpadResponse.MacResult ?: throw ProtocolException("Se esperaba un MAC")
        r.ksn to r.mac.hexToBytes()
    }

    override fun injectFault(name: String, value: String) {
        call(PinpadCommand.InjectFault(name, value)) { }
    }

    @Synchronized
    fun disconnect() {
        close?.invoke()
        client = null
        close = null
        connectedTo = null
    }

    private fun connect(): PinpadClient {
        if (connectedTo != source) disconnect()
        client?.let { return it }
        val link: Link = when (source) {
            PinpadSource.IN_APP -> {
                val (cashier, device) = PipeLink.pair()
                val endpoint = SimulatorEndpoint(device, inAppSimulator)
                Thread(endpoint, "pinpad-simulador").apply { isDaemon = true }.start()
                close = { endpoint.stop(); cashier.close() }
                cashier
            }
            PinpadSource.TCP -> try {
                val socket = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
                SocketLink(socket).also { link -> close = { link.close() } }
            } catch (e: Exception) {
                throw PinpadException.Disconnected(e)
            }
        }
        connectedTo = source
        return PinpadClient(link).also { client = it }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
