package io.github.chenxiex.calibrecloud.auth

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Android Keystore cryptography; all state uses an isolated test namespace. */
@RunWith(AndroidJUnit4::class)
class EncryptedAuthStateStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "auth-test-${UUID.randomUUID()}"
    private val directory = File(context.noBackupFilesDir, namespace)
    private val encryptedFile = File(directory, "state.bin")
    private val store = EncryptedAuthStateStore(context, namespace)

    @After
    fun cleanUp() {
        store.clear()
        directory.deleteRecursively()
    }

    @Test
    fun appAuthStateAndPendingRequestSurviveStoreRecreationAsCiphertext() {
        assertNull(store.read())
        val request = AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(
                Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
                Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"),
            ),
            "test-client",
            ResponseTypeValues.CODE,
            Uri.parse("test-debug:/oauth2redirect"),
        ).build()
        val payload = JSONObject()
            .put("authState", AuthState().jsonSerializeString())
            .put("pendingRequest", request.jsonSerializeString())
            .toString()
        store.write(payload)
        val bytes = encryptedFile.readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains(request.codeVerifier!!))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("test-client"))
        val restored = EncryptedAuthStateStore(context, namespace).read()
        assertEquals(payload, restored)
        val envelope = JSONObject(restored!!)
        assertFalse(AuthState.jsonDeserialize(envelope.getString("authState")).isAuthorized)
        assertEquals(
            request.codeVerifier,
            AuthorizationRequest.jsonDeserialize(envelope.getString("pendingRequest")).codeVerifier,
        )
        val key = keyStore().getKey("${context.packageName}.$namespace.aes-gcm", null)
        assertNull(key.encoded)
        store.write(payload)
        assertFalse(bytes.contentEquals(encryptedFile.readBytes()))
        assertEquals(payload, EncryptedAuthStateStore(context, namespace).read())
    }

    @Test
    fun ciphertextTamperingRequiresReauthenticationAndClearAllowsRecovery() {
        store.write("fake-token-for-test")
        val bytes = encryptedFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encryptedFile.writeBytes(bytes)
        val error = assertThrows(AuthStateStorageException::class.java) { store.read() }
        assertNull(error.cause)
        assertFalse(error.message!!.contains("fake-token-for-test"))
        store.clear()
        assertNull(store.read())
        store.write("new-test-state")
        assertEquals("new-test-state", store.read())
    }

    @Test
    fun lostKeyDoesNotSilentlyRecreateAnAuthorizedState() {
        store.write("test-state")
        val alias = "${context.packageName}.$namespace.aes-gcm"
        keyStore().deleteEntry(alias)
        assertThrows(AuthStateStorageException::class.java) { store.read() }
        assertFalse(keyStore().containsAlias(alias))
        store.clear()
        assertNull(store.read())
        store.write("replacement-state")
        assertTrue(keyStore().containsAlias(alias))
        assertEquals("replacement-state", store.read())
    }

    @Test
    fun encryptedCredentialsCannotBeExposedByBookFileProvider() {
        store.write("private-test-state")
        assertTrue(encryptedFile.isFile)
        // The book provider resolves only published generations, never a private path.
        val forged = Uri.parse("content://${context.packageName}.books/${encryptedFile.name}")
            .buildUpon().appendQueryParameter("copy", "${UUID.randomUUID()}:${UUID.randomUUID()}").build()
        assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openInputStream(forged)?.close() }
    }

    @Test
    fun interruptedAtomicWritePreservesTheCommittedState() {
        store.write("committed-state")
        File(directory, "state.bin.new").writeText("incomplete-write")
        assertEquals("committed-state", EncryptedAuthStateStore(context, namespace).read())
        store.clear()
        assertFalse(encryptedFile.exists())
        assertFalse(File(directory, "state.bin.new").exists())
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
