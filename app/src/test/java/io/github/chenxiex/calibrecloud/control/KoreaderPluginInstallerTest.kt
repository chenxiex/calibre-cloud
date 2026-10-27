package io.github.chenxiex.calibrecloud.control

import io.github.chenxiex.calibrecloud.state.KoreaderFolderSetting
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** R37, Q97, Q98: what the installer writes into the chosen KOReader folder and how it reports the plugin. */
class KoreaderPluginInstallerTest {
    private val main = "-- plugin\n".encodeToByteArray()
    private fun meta(version: String) = "local _ = require(\"gettext\")\nreturn {\n    version = \"$version\",\n}\n".encodeToByteArray()
    private val bundled = BundledPlugin(listOf(PluginFile("_meta.lua", meta("1.3.0")), PluginFile("main.lua", main)))

    /** Paths relative to the tree root; a directory maps to null. */
    private class FakeFolder(val entries: MutableMap<String, ByteArray?>) : KoreaderFolder {
        var present = true
        val writes = mutableListOf<String>()
        var failWrites = false

        override fun root() = if (present) FolderEntry("", true) else null
        private fun path(parent: FolderEntry, name: String) = if (parent.id.isEmpty()) name else "${parent.id}/$name"
        override fun child(parent: FolderEntry, name: String): FolderEntry? {
            val path = path(parent, name)
            return if (path in entries) FolderEntry(path, entries[path] == null) else null
        }
        override fun createDirectory(parent: FolderEntry, name: String): FolderEntry {
            entries[path(parent, name)] = null
            return FolderEntry(path(parent, name), true)
        }
        override fun write(parent: FolderEntry, name: String, content: ByteArray) {
            if (failWrites) throw IOException()
            writes += path(parent, name)
            entries[path(parent, name)] = content
        }
        override fun read(file: FolderEntry) = entries.getValue(file.id)!!
    }

    private class FakeFolders(val trees: Map<String, FakeFolder>) : KoreaderFolders {
        val granted = mutableSetOf<String>()
        override fun open(treeUri: String) = trees[treeUri]?.takeIf { treeUri in granted }
        override fun persist(treeUri: String, resultFlags: Int) {
            if (resultFlags == 0) throw SecurityException()
            granted += treeUri
        }
        override fun release(treeUri: String) { granted -= treeUri }
    }

    private class FakeSetting(var folder: String? = null) : KoreaderFolderSetting {
        override suspend fun koreaderFolder() = folder
        override suspend fun setKoreaderFolder(treeUri: String) { folder = treeUri }
    }

    private val koreader = FakeFolder(mutableMapOf("plugins" to null, "plugins/hello.koplugin" to null, "settings.reader.lua" to ByteArray(1)))
    private val fresh = FakeFolder(mutableMapOf("settings.reader.lua" to ByteArray(1)))
    private val other = FakeFolder(mutableMapOf("Books" to null))
    private val folders = FakeFolders(mapOf("koreader" to koreader, "fresh" to fresh, "other" to other, "library" to koreader))
    private val setting = FakeSetting()
    private val libraries = mutableSetOf<String>()

    private fun TestScope.installer() =
        KoreaderPluginInstaller({ bundled }, setting, folders, { it in libraries }, StandardTestDispatcher(testScheduler))

    @Test
    fun readsTheVersionField() {
        assertEquals("1.2.0", pluginVersion(meta("1.2.0").decodeToString()))
        assertNull(pluginVersion("return { name = \"calibrecloud\" }"))
    }

    @Test
    fun installsIntoTheChosenFolderWithMetaLast() = runTest {
        val installer = installer()
        assertEquals(PluginStatus.NoFolder, installer.status())
        assertEquals(InstallResult.INSTALLED, installer.choose("koreader", 1))
        assertEquals(listOf("plugins/calibrecloud.koplugin/main.lua", "plugins/calibrecloud.koplugin/_meta.lua"), koreader.writes)
        assertArrayEquals(main, koreader.entries["plugins/calibrecloud.koplugin/main.lua"])
        assertTrue("plugins/hello.koplugin" in koreader.entries)
        assertEquals("koreader", setting.folder)
        assertEquals(PluginStatus.Installed("1.3.0", current = true), installer.status())
    }

    @Test
    fun createsThePluginsFolderWhenMissing() = runTest {
        assertEquals(InstallResult.INSTALLED, installer().choose("fresh", 1))
        assertEquals(null, fresh.entries["plugins"])
        assertTrue("plugins" in fresh.entries)
    }

    @Test
    fun refusesAFolderThatIsNotKoreaderData() = runTest {
        val installer = installer()
        assertEquals(InstallResult.NOT_KOREADER, installer.choose("other", 1))
        assertTrue(other.writes.isEmpty())
        assertFalse("other" in folders.granted)
        assertNull(setting.folder)
    }

    @Test
    fun reportsAnOutdatedPluginAndUpdatesIt() = runTest {
        koreader.entries["plugins/calibrecloud.koplugin"] = null
        koreader.entries["plugins/calibrecloud.koplugin/_meta.lua"] = meta("1.2.0")
        setting.folder = "koreader"
        folders.granted += "koreader"
        val installer = installer()
        assertEquals(PluginStatus.Installed("1.2.0", current = false), installer.status())
        assertTrue(installer.needsUpdateOnLaunch())
        assertFalse("checked once per process", installer.needsUpdateOnLaunch())
        assertEquals(InstallResult.INSTALLED, installer.install())
        assertEquals(PluginStatus.Installed("1.3.0", current = true), installer.status())
    }

    @Test
    fun noLaunchNoticeWithoutAnInstalledPlugin() = runTest {
        setting.folder = "koreader"
        folders.granted += "koreader"
        val installer = installer()
        assertEquals(PluginStatus.NotInstalled, installer.status())
        assertFalse(installer.needsUpdateOnLaunch())
    }

    @Test
    fun aLostFolderNeedsThePickerAgain() = runTest {
        setting.folder = "koreader"
        val installer = installer()
        assertEquals("grant gone", PluginStatus.NoFolder, installer.status())
        assertEquals(InstallResult.NO_FOLDER, installer.install())
        folders.granted += "koreader"
        koreader.present = false
        assertEquals("folder gone", PluginStatus.NoFolder, installer.status())
        assertFalse(installer.needsUpdateOnLaunch())
    }

    @Test
    fun aFailedWriteIsReportedAndKeepsTheFolder() = runTest {
        koreader.failWrites = true
        assertEquals(InstallResult.FAILED, installer().choose("koreader", 1))
        assertEquals("the user can retry without the picker", "koreader", setting.folder)
        assertEquals(InstallResult.FAILED, installer().choose("koreader", 0))
    }

    @Test
    fun replacingTheFolderReleasesTheOldGrantUnlessALibraryUsesIt() = runTest {
        val installer = installer()
        installer.choose("library", 1)
        libraries += "library"
        installer.choose("koreader", 1)
        assertTrue("library" in folders.granted)
        installer.choose("fresh", 1)
        assertFalse("koreader" in folders.granted)
        assertTrue("fresh" in folders.granted)
    }
}
