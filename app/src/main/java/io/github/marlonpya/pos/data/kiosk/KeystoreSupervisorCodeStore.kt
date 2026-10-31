package io.github.marlonpya.pos.data.kiosk

import android.content.Context
import android.util.Base64
import io.github.marlonpya.pos.data.security.BlobCipher
import io.github.marlonpya.pos.domain.SupervisorCodeStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Guarda sal + PBKDF2 del código, cifrados con el Keystore. El código en claro
 * no se persiste y la comparación es en tiempo constante.
 */
class KeystoreSupervisorCodeStore(context: Context, private val cipher: BlobCipher) : SupervisorCodeStore {
    private val prefs = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    override fun isSet() = prefs.contains(KEY)

    override fun save(supervisorCode: String) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val sealed = cipher.encrypt(salt + derive(supervisorCode, salt))
        prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun matches(supervisorCode: String): Boolean {
        val stored = prefs.getString(KEY, null) ?: return false
        val plain = runCatching { cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull() ?: return false
        val salt = plain.copyOfRange(0, SALT_SIZE)
        val hash = plain.copyOfRange(SALT_SIZE, plain.size)
        return MessageDigest.isEqual(hash, derive(supervisorCode, salt))
    }

    private fun derive(supervisorCode: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(supervisorCode.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private companion object {
        const val KEY = "supervisor_code"
        const val SALT_SIZE = 16
        const val ITERATIONS = 120_000
    }
}
