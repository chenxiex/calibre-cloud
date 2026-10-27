package io.github.chenxiex.calibrecloud.control

import io.github.chenxiex.calibrecloud.state.KoreaderFolderSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal const val PLUGIN_DIRECTORY = "calibrecloud.koplugin"
internal const val PLUGIN_META = "_meta.lua"

/** The `version = "…"` field of a plugin's `_meta.lua`; null when there is none. */
fun pluginVersion(meta: String): String? = Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""").find(meta)?.groupValues?.get(1)

class PluginFile(val name: String, val content: ByteArray)

/** The plugin as this APK carries it (Q97); `_meta.lua` comes last so a cut-short install still reads as outdated. */
class BundledPlugin(files: List<PluginFile>) {
    val files = files.sortedBy { it.name == PLUGIN_META }
    val version = pluginVersion(files.single { it.name == PLUGIN_META }.content.decodeToString())
        ?: throw IllegalArgumentException("bundled plugin has no version")
}

/** A directory or file found in the chosen KOReader folder. */
class FolderEntry(val id: String, val directory: Boolean)

/** The tree operations the installer needs on one granted folder; SAF in the app, a map in tests. */
interface KoreaderFolder {
    /** The folder itself; null when it no longer exists or cannot be reached. */
    fun root(): FolderEntry?

    fun child(parent: FolderEntry, name: String): FolderEntry?

    fun createDirectory(parent: FolderEntry, name: String): FolderEntry

    /** Creates [name] in [parent] or truncates the existing file, then writes [content]. */
    fun write(parent: FolderEntry, name: String, content: ByteArray)

    /** A small text file such as `_meta.lua`. */
    fun read(file: FolderEntry): ByteArray
}

/** The grants of folders chosen in the system picker. */
interface KoreaderFolders {
    /** The folder behind [treeUri]; null unless it is a supported tree with a persisted read and write grant. */
    fun open(treeUri: String): KoreaderFolder?

    /** Keeps the picker's grant; throws [SecurityException] when [resultFlags] lack read, write or persistence. */
    fun persist(treeUri: String, resultFlags: Int)

    fun release(treeUri: String)
}

sealed interface PluginStatus {
    /** No folder was chosen, its grant is gone or it no longer exists: installing needs the picker. */
    data object NoFolder : PluginStatus
    data object NotInstalled : PluginStatus
    /** [version] is null when the installed `_meta.lua` names none; [current] when it is the bundled one. */
    data class Installed(val version: String?, val current: Boolean) : PluginStatus
}

enum class InstallResult {
    INSTALLED,
    /** The saved folder can no longer be used; choose one again. */
    NO_FOLDER,
    /** The chosen folder has neither `plugins` nor `settings.reader.lua`; nothing was written. */
    NOT_KOREADER,
    FAILED,
}

/**
 * Writes the bundled KOReader plugin into `plugins/calibrecloud.koplugin/` of the KOReader data folder the
 * user chose (R37, Q97, Q98) and reports what is installed there. Nothing else in that folder is read
 * beyond the names looked up, and nothing is written except on the user's request.
 */
class KoreaderPluginInstaller(
    bundled: () -> BundledPlugin,
    private val setting: KoreaderFolderSetting,
    private val folders: KoreaderFolders,
    /** Whether a library still uses a tree URI, so replacing the folder does not release its grant. */
    private val usedByLibrary: suspend (String) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) {
    /** Read once, on first use, so constructing the installer does no I/O. */
    private val plugin by lazy(bundled)
    private val mutex = Mutex()
    private val launchChecked = AtomicBoolean()

    suspend fun bundledVersion(): String = withContext(ioDispatcher) { plugin.version }

    /** What the saved folder holds; a folder that cannot be read counts as none, so the picker is offered. */
    suspend fun status(): PluginStatus = locked(PluginStatus.NoFolder) {
        folder()?.let { (folder, root) -> status(folder, root) } ?: PluginStatus.NoFolder
    }

    /** Installs into the saved folder. */
    suspend fun install(): InstallResult = locked(InstallResult.FAILED) {
        val (folder, root) = folder() ?: return@locked InstallResult.NO_FOLDER
        write(folder, root)
    }

    /** Takes the folder the picker returned, keeps it when it is a KOReader data folder and installs there. */
    suspend fun choose(treeUri: String, resultFlags: Int): InstallResult = locked(InstallResult.FAILED) {
        folders.persist(treeUri, resultFlags)
        val previous = setting.koreaderFolder()
        val folder = folders.open(treeUri)
        val root = folder?.root()
        if (folder == null || root == null || !koreaderData(folder, root)) {
            if (treeUri != previous && !usedByLibrary(treeUri)) folders.release(treeUri)
            return@locked if (root == null) InstallResult.FAILED else InstallResult.NOT_KOREADER
        }
        setting.setKoreaderFolder(treeUri)
        if (previous != null && previous != treeUri && !usedByLibrary(previous)) folders.release(previous)
        write(folder, root)
    }

    /** True once per process when the installed plugin differs from the bundled one (Q98). */
    suspend fun needsUpdateOnLaunch(): Boolean {
        if (!launchChecked.compareAndSet(false, true)) return false
        return (status() as? PluginStatus.Installed)?.current == false
    }

    private suspend fun folder(): Pair<KoreaderFolder, FolderEntry>? {
        val folder = folders.open(setting.koreaderFolder() ?: return null) ?: return null
        return folder.root()?.takeIf { it.directory }?.let { folder to it }
    }

    private fun koreaderData(folder: KoreaderFolder, root: FolderEntry) =
        root.directory && (folder.child(root, "plugins")?.directory == true || folder.child(root, "settings.reader.lua")?.directory == false)

    private fun status(folder: KoreaderFolder, root: FolderEntry): PluginStatus {
        val meta = folder.child(root, "plugins")?.takeIf { it.directory }
            ?.let { folder.child(it, PLUGIN_DIRECTORY) }?.takeIf { it.directory }
            ?.let { folder.child(it, PLUGIN_META) }?.takeIf { !it.directory }
            ?: return PluginStatus.NotInstalled
        val version = pluginVersion(folder.read(meta).decodeToString())
        return PluginStatus.Installed(version, version == plugin.version)
    }

    private fun write(folder: KoreaderFolder, root: FolderEntry): InstallResult {
        val plugins = directory(folder, root, "plugins")
        val target = directory(folder, plugins, PLUGIN_DIRECTORY)
        plugin.files.forEach { folder.write(target, it.name, it.content) }
        log("installed")
        return InstallResult.INSTALLED
    }

    private fun directory(folder: KoreaderFolder, parent: FolderEntry, name: String): FolderEntry {
        val existing = folder.child(parent, name) ?: return folder.createDirectory(parent, name)
        if (!existing.directory) throw java.io.IOException("not a directory")
        return existing
    }

    /** One operation at a time; a failure gives [failed] and is logged by kind only, never with the folder's URI. */
    private suspend fun <T> locked(failed: T, block: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                log("failed: ${failure.javaClass.simpleName}")
                failed
            }
        }
    }
}
