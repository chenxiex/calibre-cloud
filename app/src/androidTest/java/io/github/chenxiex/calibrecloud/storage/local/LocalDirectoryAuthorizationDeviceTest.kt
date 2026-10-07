package io.github.chenxiex.calibrecloud.storage.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import android.content.Intent
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in with `-e localSaf true` after selecting the dedicated test library through the real system picker. Revokes that grant. */
@RunWith(AndroidJUnit4::class)
class LocalDirectoryAuthorizationDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireExplicitOptIn() {
        assumeTrue("Real SAF tests require a picker-granted dedicated sample and explicit opt-in",
            InstrumentationRegistry.getArguments().getString("localSaf") == "true")
    }

    @Test
    fun realPersistedGrantSurvivesRecreationAndRevocationIsVisible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = AndroidDirectoryPermissions(context)
        val configuration = (context.applicationContext as io.github.chenxiex.calibrecloud.CalibreCloudApplication).dependencies.localConfiguration
        val uri = configuration.load()
        assertNotNull("First authorize the dedicated test library in the system picker", uri)
        uri!!
        assertNotNull(permissions.localLocation(uri))
        assertNull(permissions.localLocation("content://cloud.example/tree/library"))
        assertNull(permissions.localLocation("file:///storage/emulated/0/library"))
        assertNull(permissions.localLocation("content://com.android.externalstorage.documents/document/primary%3Alibrary"))
        assertNull(permissions.localLocation("content://com.android.externalstorage.documents/tree/primary%3Alibrary/document/primary%3Aother"))
        assertThrows(SecurityException::class.java) { permissions.persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val authorization = createLocalDirectoryAuthorization(context)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.restore().status)
        awaitText(context.getString(R.string.local_authorized))
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(uri, configuration.load())
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.restore().status)
        awaitText(context.getString(R.string.local_authorized))
        permissions.release(uri)
        assertEquals(DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED, authorization.restore().status)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        awaitText(context.getString(R.string.local_reauthorize))
        assertEquals(uri, configuration.load())
    }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
