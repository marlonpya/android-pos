package io.github.marlonpya.pos.di

import android.content.Context
import io.github.marlonpya.pos.BuildConfig
import io.github.marlonpya.pos.data.gateway.GatewayAdminClient
import io.github.marlonpya.pos.data.gateway.NetworkSwitch
import io.github.marlonpya.pos.data.gateway.SocketGateway
import io.github.marlonpya.pos.data.local.PosDatabase
import io.github.marlonpya.pos.data.local.PrefsStanSource
import io.github.marlonpya.pos.data.local.RoomOutboxRepository
import io.github.marlonpya.pos.data.local.RoomTransactionRepository
import io.github.marlonpya.pos.data.pinpad.LinkedPinpad
import io.github.marlonpya.pos.data.pinpad.PinpadSource
import io.github.marlonpya.pos.data.security.KeystoreBlobCipher
import io.github.marlonpya.pos.domain.ChargeUseCase
import io.github.marlonpya.pos.domain.Forwarder
import io.github.marlonpya.pos.domain.MessageFactory
import io.github.marlonpya.pos.domain.TerminalConfig
import kotlinx.coroutines.Dispatchers

/**
 * Inyección de dependencias a mano. Para cinco piezas no hace falta Hilt, y
 * así se ve de un vistazo qué implementación concreta usa cada puerto.
 */
class AppContainer(context: Context) {
    val config = TerminalConfig()
    val network = NetworkSwitch()

    private val database = PosDatabase.create(context)
    val transactions = RoomTransactionRepository(database.transactions())
    val outbox = RoomOutboxRepository(database.outbox(), KeystoreBlobCipher())

    val pinpad = LinkedPinpad(PinpadSource.IN_APP, BuildConfig.PINPAD_HOST, BuildConfig.PINPAD_PORT)
    val gateway = SocketGateway(BuildConfig.GATEWAY_HOST, BuildConfig.GATEWAY_PORT, network)
    val gatewayAdmin = GatewayAdminClient(BuildConfig.GATEWAY_ADMIN_URL)

    val forwarder = Forwarder(outbox, gateway, transactions, Dispatchers.IO)

    val charge = ChargeUseCase(
        config = config,
        pinpad = pinpad,
        gateway = gateway,
        transactions = transactions,
        outbox = outbox,
        stans = PrefsStanSource(context),
        messages = MessageFactory(config, pinpad),
        forwarder = forwarder,
        io = Dispatchers.IO,
    )
}
