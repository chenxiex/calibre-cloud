package io.github.chenxiex.calibrecloud.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persist one envelope containing AuthState and the pending request; callers perform I/O off the UI thread. */
interface AuthStateStore {
    fun read(): String?
    fun write(serializedState: String)
    fun clear()
}

/** A deliberately sanitized error: platform exceptions can contain sensitive context. */
class AuthStateStorageException : Exception("Private authorization state is unavailable")

/**
 * Android Keystore keys are non-exportable and scoped to the application UID. The encrypted
 * envelope is outside ordinary files/cache and Android backup/transfer domains. Keep a single
 * store in the application container and serialize coordinator operations before calling it.
 */
class EncryptedAuthStateStore internal constructor(
    context: Context,
    private val namespace: String,
) : AuthStateStore {
    constructor(context: Context) : this(context, "onedrive-auth")

    init {
        require(namespace.matches(Regex("[a-zA-Z0-9_-]+")))
    }

    private val directory = File(context.noBackupFilesDir, namespace)
    private val file = File(directory, "state.bin")
    private val atomicFile = AtomicFile(file)
    private val keyAlias = "${context.packageName}.$namespace.aes-gcm"
    private val additionalData = keyAlias.toByteArray(Charsets.UTF_8)

    @Synchronized
    override fun read(): String? = protect {
        if (!file.exists() && !File(directory, "state.bin.bak").exists()) {
            return@protect null
        }
        val envelope = atomicFile.openRead().use { it.readBytes() }
        // One version byte, a fixed 96-bit IV, and at least a 128-bit authentication tag.
        if (envelope.size < 29 || envelope[0] != 1.toByte()) {
            throw AuthStateStorageException()
        }
        val key = keyStore().getKey(keyAlias, null) as? SecretKey
            ?: throw AuthStateStorageException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(additionalData)
        cipher.doFinal(envelope, 13, envelope.size - 13).toString(Charsets.UTF_8)
    }

    @Synchronized
    override fun write(serializedState: String) = protect {
        val store = keyStore()
        val key = if (store.containsAlias(keyAlias)) {
            store.getKey(keyAlias, null) as? SecretKey ?: throw AuthStateStorageException()
        } else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(additionalData)
        val encrypted = cipher.doFinal(serializedState.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AuthStateStorageException()
        }
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(byteArrayOf(1))
            output.write(cipher.iv)
            output.write(encrypted)
            atomicFile.finishWrite(output)
        } catch (exception: Exception) {
            atomicFile.failWrite(output)
            throw exception
        }
    }

    @Synchronized
    override fun clear() = protect {
        atomicFile.delete()
        if (file.exists() || File(directory, "state.bin.bak").exists() || File(directory, "state.bin.new").exists()) {
            throw AuthStateStorageException()
        }
        keyStore().deleteEntry(keyAlias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private inline fun <T> protect(action: () -> T): T = try {
        action()
    } catch (exception: AuthStateStorageException) {
        throw exception
    } catch (_: Exception) {
        throw AuthStateStorageException()
    }
}
