package io.github.marlonpya.pos.data.gateway

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de la API de administración de gateway-mock, para cambiar su modo
 * de caos desde la app. Es HTTP plano a propósito: solo existe en el
 * laboratorio, contra 10.0.2.2 (ver network_security_config.xml).
 */
class GatewayAdminClient(private val baseUrl: String) {

    data class Chaos(val mode: String, val delayMs: Long)

    fun chaos(): Chaos = request("GET", "/chaos", null).let { Chaos(it.getString("mode"), it.getLong("delayMs")) }

    fun setChaos(mode: String, delayMs: Long): Chaos {
        val body = JSONObject().put("mode", mode).put("delayMs", delayMs).toString()
        return request("PUT", "/chaos", body).let { Chaos(it.getString("mode"), it.getLong("delayMs")) }
    }

    private fun request(method: String, path: String, body: String?): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val MODES = listOf(
            "NORMAL", "SLOW", "TIMEOUT_AFTER_APPROVAL", "DROP_AFTER_APPROVAL", "DECLINE_ALL", "UNAVAILABLE",
        )
    }
}
