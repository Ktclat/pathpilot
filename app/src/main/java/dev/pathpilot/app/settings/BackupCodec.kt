package dev.pathpilot.app.settings

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Errors are stable UI keys; none includes a password, credential or backup contents. */
enum class BackupError { PASSWORD_REQUIRED, FILE_TOO_LARGE, INVALID_FORMAT, UNSUPPORTED_VERSION, WRONG_PASSWORD_OR_DAMAGED_FILE }

class BackupException(val reason: BackupError, cause: Throwable? = null) : IllegalArgumentException(reason.name, cause)

object BackupCodec {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val FORMAT = "pathpilot-backup"
    private const val VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 210_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val associatedData = "$FORMAT:$VERSION:$KDF:$ITERATIONS".toByteArray(Charsets.UTF_8)

    @Serializable
    private data class Envelope(
        val format: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val nonce: String,
        val ciphertext: String,
    )

    fun encode(snapshot: BackupSnapshot, password: CharArray): ByteArray {
        requirePassword(password)
        val cleartext = json.encodeToString(BackupValidation.normalized(snapshot)).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = derive(password, salt)
        val ciphertext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(associatedData)
                doFinal(cleartext)
            }
        } finally { cleartext.fill(0) }
        val encoder = Base64.getEncoder()
        val envelope = Envelope(FORMAT, VERSION, KDF, ITERATIONS, encoder.encodeToString(salt),
            encoder.encodeToString(nonce), encoder.encodeToString(ciphertext))
        val result = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (result.size > MAX_FILE_BYTES) throw BackupException(BackupError.FILE_TOO_LARGE)
        return result
    }

    fun decode(bytes: ByteArray, password: CharArray): BackupSnapshot {
        requirePassword(password)
        if (bytes.size > MAX_FILE_BYTES) throw BackupException(BackupError.FILE_TOO_LARGE)
        val envelope = try { json.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8)) }
            catch (error: SerializationException) { throw BackupException(BackupError.INVALID_FORMAT, error) }
        if (envelope.format != FORMAT) throw BackupException(BackupError.INVALID_FORMAT)
        if (envelope.version != VERSION || envelope.kdf != KDF || envelope.iterations != ITERATIONS)
            throw BackupException(BackupError.UNSUPPORTED_VERSION)
        val decoder = Base64.getDecoder()
        val salt: ByteArray
        val nonce: ByteArray
        val ciphertext: ByteArray
        try {
            salt = decoder.decode(envelope.salt)
            nonce = decoder.decode(envelope.nonce)
            ciphertext = decoder.decode(envelope.ciphertext)
        } catch (error: IllegalArgumentException) { throw BackupException(BackupError.INVALID_FORMAT, error) }
        if (salt.size != 16 || nonce.size != 12 || ciphertext.size < 16)
            throw BackupException(BackupError.INVALID_FORMAT)
        val key = derive(password, salt)
        val cleartext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(associatedData)
                doFinal(ciphertext)
            }
        } catch (error: AEADBadTagException) { throw BackupException(BackupError.WRONG_PASSWORD_OR_DAMAGED_FILE, error) }
        val snapshot = try { json.decodeFromString<BackupSnapshot>(cleartext.toString(Charsets.UTF_8)) }
            catch (error: SerializationException) { throw BackupException(BackupError.INVALID_FORMAT, error) }
            finally { cleartext.fill(0) }
        return BackupValidation.normalized(snapshot)
    }

    private fun requirePassword(password: CharArray) {
        if (password.isEmpty()) throw BackupException(BackupError.PASSWORD_REQUIRED)
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
    }
}
