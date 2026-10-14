package io.github.chenxiex.calibrecloud.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** R37 settings on real Android SQLite in UUID-named private test databases. */
@RunWith(AndroidJUnit4::class)
class ExternalControlRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var control: ExternalControlRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "external-control-test-${UUID.randomUUID()}.db"
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, name)
        control = ExternalControlRepository(database, Dispatchers.IO) { now }
    }

    @Test
    fun registrationsSurviveRestartsAndReplaceByNameOrToken() = runBlocking<Unit> {
        assertFalse(control.enabled())
        control.register("KOReader", "hash-1", enable = true)
        now = 2_000L
        control.register("Other", "hash-2", enable = false)
        database.close()
        reopen()
        assertTrue(control.enabled())
        assertEquals(listOf(ExternalClient("KOReader", 1_000L), ExternalClient("Other", 2_000L)), control.clients())

        now = 3_000L
        control.register("KOReader", "hash-3", enable = false)
        assertFalse(control.registered("hash-1"))
        assertTrue(control.registered("hash-3"))
        // The same token under a new name moves the registration.
        control.register("Renamed", "hash-2", enable = false)
        assertEquals(listOf("KOReader", "Renamed"), control.clients().map { it.name })
    }

    @Test
    fun theSwitchKeepsRegistrationsAndRemovalForgetsOne() = runBlocking<Unit> {
        control.register("KOReader", "hash-1", enable = true)
        control.setEnabled(false)
        assertFalse(control.enabled())
        assertTrue(control.registered("hash-1"))
        control.remove("KOReader")
        assertFalse(control.registered("hash-1"))
        assertTrue(control.clients().isEmpty())
    }
}
