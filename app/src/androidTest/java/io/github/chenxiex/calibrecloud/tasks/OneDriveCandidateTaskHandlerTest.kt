package io.github.chenxiex.calibrecloud.tasks

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.auth.*
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackend
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.onedrive.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModelStore
import io.github.chenxiex.calibrecloud.ui.OneDriveLibraryViewModel
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real SQLite queue, AppAuth coordinator, Graph JSON decoder and production candidate handler. */
@RunWith(AndroidJUnit4::class)
class OneDriveCandidateTaskHandlerTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var directory: File
    private lateinit var state: ApplicationStateRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var service: OneDriveCandidateService
    private lateinit var coordinator: TaskCoordinator
    private lateinit var authorization: OneDriveAuthorization
    private lateinit var oauth: FakeOAuthPlatform
    private lateinit var graph: GraphFixture
    private val configuration = OneDriveOAuthConfiguration("test-client", "test-debug:/oauth2redirect")

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "onedrive-candidate-${UUID.randomUUID()}.db"
        directory = File(context.cacheDir, "onedrive-candidate-${UUID.randomUUID()}")
        oauth = FakeOAuthPlatform()
        authorization = OneDriveAuthorization(context, configuration, MemoryStore(), oauth)
        graph = GraphFixture()
        login()
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
        authorization.close()
    }

    @Test
    fun explicitDirectoryPagesSurviveReopenAndChooseStableLocation() = runBlocking<Unit> {
        val first = service.browse()!!
        assertEquals(0, graph.requests.get())
        assertNull(state.current()!!.location)
        coordinator.drain()
        assertCompleted(first)
        val page = service.currentPage()!!
        assertEquals(14, page.items.size)
        assertTrue(page.complete)
        assertEquals(listOf("library-1", "library-2", "library-3"), page.pageAt(0)!!.items.map { it.id })
        assertTrue(page.pageAt(0)!!.hasNext)
        assertEquals(LibraryLocation.OneDrive("account", "drive", "root"), page.location)
        assertNull(state.current()!!.identity)
        val requestsBeforeReopen = graph.requests.get()
        val selectionBeforeReopen = state.current()
        database.close()
        reopen()
        assertEquals(selectionBeforeReopen, state.current())
        assertEquals(requestsBeforeReopen, graph.requests.get())
        val restored = service.currentPage()!!
        assertEquals("Display root", restored.directoryName)
        assertEquals(listOf("library-7", "library-8", "library-9"), restored.pageAt(2)!!.items.map { it.id })
        assertEquals(listOf("library-13", "library-14"), restored.pageAt(4)!!.items.map { it.id })
        assertFalse(restored.pageAt(4)!!.hasNext)
        assertNull(restored.pageAt(5))
        assertEquals(requestsBeforeReopen, graph.requests.get())
        assertFalse(service.choose("unlisted-item"))
        assertTrue(service.choose("library-4"))
        val selected = LibraryLocation.OneDrive("account", "drive", "library-4")
        assertEquals(selected, service.currentLocation())
        assertNull(state.current()!!.identity)
        database.close()
        reopen()
        assertEquals(selected, service.currentLocation())
        assertNull(state.current()!!.identity)
        val persisted = File(directory, "browse/${first.value}.json").readText()
        assertFalse(persisted.contains("fixture-access-token"))
        assertFalse(persisted.contains("nextLink"))
        assertFalse(persisted.contains("https://"))
    }

    @Test
    fun selectedSnapshotPublishesPrivateBytesWithoutActivatingLibrary() = runBlocking<Unit> {
        service.browse()!!
        coordinator.drain()
        assertTrue(service.choose("library-1"))
        val task = service.acquire()!!
        val before = graph.requests.get()
        assertEquals(0, graph.contentReads.get())
        coordinator.drain()
        assertCompleted(task)
        assertTrue(graph.requests.get() > before)
        assertEquals(2, graph.contentReads.get())
        val files = File(directory, "snapshots/${task.value}").listFiles().orEmpty()
        assertEquals(1, files.size)
        assertEquals("db", files.single().extension)
        assertArrayEquals(graph.databaseBytes, files.single().readBytes())
        assertNull(state.current()!!.identity)
        database.close()
        reopen()
        assertCompleted(task)
        assertEquals(LibraryLocation.OneDrive("account", "drive", "library-1"), service.currentLocation())
    }

    @Test
    fun loginSessionMismatchRejectsQueuedBrowseBeforeGraphAccess() = runBlocking<Unit> {
        val task = service.browse()!!
        val previousSession = authorization.sessionId()
        login()
        assertNotEquals(previousSession, authorization.sessionId())
        coordinator.drain()
        assertEquals(0, graph.requests.get())
        val result = (queue.get(task)!!.record.state as TaskState.Finished).result as TaskResult.Failed
        assertEquals(TaskError.Source(StorageError(StorageErrorKind.LOGIN_REQUIRED)), result.failure.error)
        assertNull(service.currentPage())
        assertFalse(service.choose("root"))
        assertNull(state.current()!!.location)
    }

    @Test
    fun interruptedBrowseIsReexecutedFromPersistedRequest() = runBlocking<Unit> {
        val task = service.browse()!!
        assertEquals(task, queue.claim(0, { emptySet() }, { true })!!.record.id)
        assertEquals(0, graph.requests.get())
        database.close()
        reopen()
        coordinator.drain()
        assertCompleted(task)
        assertEquals(14, service.currentPage()!!.items.size)
        assertNull(state.current()!!.identity)
    }

    @Test
    fun staleDirectoryAndReauthorizationCannotReplaceNewSelection() = runBlocking<Unit> {
        val task = service.browse()!!
        coordinator.drain()
        val request = queue.get(task)!!.record.submission.request as TaskRequest.CandidateConfiguration
        val oldToken = state.current()!!.token
        val replacement = state.select(LibraryLocation.Local("test.documents", "replacement"))
        assertNull(state.chooseCandidate(request.context, LibraryLocation.OneDrive("account", "drive", "library-1")))
        assertNull(state.reauthorizeCandidate(oldToken, UUID.randomUUID()))
        assertFalse(service.choose("library-1"))
        assertEquals(replacement, state.current())
    }

    @Test
    fun viewModelPaginationUsesCompleteMemoryResultWithoutQueueOrGraph() = runBlocking<Unit> {
        service.browse()!!
        coordinator.drain()
        val requestCount = graph.requests.get()
        val queueCount = queue.list().size
        val models = ViewModelStore()
        val model = withContext(Dispatchers.Main) { OneDriveLibraryViewModel(service).also { models.put("browser", it) } }
        try {
            model.restore()
            withTimeout(5_000) { while (model.page == null) delay(10) }
            withContext(Dispatchers.Main) {
                model.paginate(2)
                assertEquals(listOf("library-7", "library-8", "library-9"), model.page!!.items.map { it.id })
                model.paginate(4)
                assertEquals(2, model.page!!.items.size)
                assertFalse(model.page!!.hasNext)
                model.paginate(1)
                assertEquals(listOf("library-4", "library-5", "library-6"), model.page!!.items.map { it.id })
            }
            assertEquals(requestCount, graph.requests.get())
            assertEquals(queueCount, queue.list().size)
        } finally { withContext(Dispatchers.Main) { models.clear() } }
    }

    private suspend fun login() {
        assertNotNull(authorization.begin())
        val address = Uri.parse(configuration.redirectUri).buildUpon()
            .appendQueryParameter("state", oauth.request!!.state)
            .appendQueryParameter("code", "fixture-code").build().toString()
        authorization.callback(address)
        assertEquals(LoginStatus.AUTHORIZED, authorization.status)
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        val results = OneDriveBrowseStore(File(directory, "browse"))
        val backend = OneDriveSourceBackend(
            { authorization.backendAccessToken(it, kotlinx.coroutines.currentCoroutineContext()[OneDriveAuthorizationSession]?.id) }, File(directory, "snapshots"),
            SnapshotValidator { it.readBytes().contentEquals(graph.databaseBytes) }, Dispatchers.IO,
            OkHttpClient.Builder().addInterceptor(graph).build(),
        )
        coordinator = TaskCoordinator(queue, listOf(OneDriveCandidateTaskHandler(state, authorization, backend, results)))
        service = OneDriveCandidateService(state, authorization, queue, coordinator, results)
    }

    private suspend fun assertCompleted(task: TaskId) {
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
    }

    private class MemoryStore : AuthStateStore {
        private var value: String? = null
        override fun read() = value
        override fun write(serializedState: String) { value = serializedState }
        override fun clear() { value = null }
    }

    private class FakeOAuthPlatform : OAuthPlatform {
        var request: AuthorizationRequest? = null
        override fun browserIntent(request: AuthorizationRequest): Intent {
            this.request = request
            return Intent(Intent.ACTION_VIEW, request.toUri())
        }
        override suspend fun exchange(request: TokenRequest): Pair<TokenResponse?, AuthorizationException?> =
            TokenResponse.Builder(request).setTokenType("Bearer").setAccessToken("fixture-access-token")
                .setRefreshToken("fixture-refresh-token").setAccessTokenExpiresIn(3600L).build() to null
        override fun close() = Unit
    }

    private class GraphFixture : Interceptor {
        val requests = AtomicInteger()
        val contentReads = AtomicInteger()
        val databaseBytes = "independent database fixture".toByteArray()
        override fun intercept(chain: Interceptor.Chain): Response {
            requests.incrementAndGet()
            val request = chain.request()
            assertEquals("Bearer fixture-access-token", request.header("Authorization"))
            val segments = request.url.pathSegments
            val body = when {
                segments == listOf("v1.0", "me", "drive") -> JSONObject().put("id", "drive")
                    .put("driveType", "personal").put("owner", JSONObject().put("user", JSONObject().put("id", "account"))).toString()
                segments == listOf("v1.0", "drives", "drive", "root") -> directory("root", null).toString()
                segments.last() == "content" && segments[4] == "metadata" -> {
                    contentReads.incrementAndGet()
                    return response(request, databaseBytes.toResponseBody())
                }
                segments.last() == "children" -> {
                    val parent = segments[4]
                    val values = JSONArray()
                    val json = JSONObject().put("value", values)
                    if (parent == "root") {
                        val page = request.url.queryParameter("page")?.toInt() ?: 1
                        val ids = ((page - 1) * 4 + 1..minOf(page * 4, 14)).toList()
                        ids.forEach { values.put(directory("library-$it", "root")) }
                        if (page < 4) json.put("@odata.nextLink",
                            "https://graph.microsoft.com/v1.0/drives/drive/items/root/children?page=${page + 1}")
                    } else {
                        values.put(JSONObject().put("id", "metadata").put("name", "metadata.db").put("file", JSONObject())
                            .put("parentReference", parent(parent)).put("cTag", "content-generation-1").put("size", databaseBytes.size))
                    }
                    json.toString()
                }
                segments.size == 5 && segments[3] == "items" -> directory(segments[4], if (segments[4] == "root") null else "root").toString()
                else -> error("Unexpected fixture endpoint")
            }
            return response(request, body.toResponseBody())
        }
        private fun response(request: okhttp3.Request, body: okhttp3.ResponseBody) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        private fun parent(id: String) = JSONObject().put("driveId", "drive").put("id", id)
        private fun directory(id: String, parentId: String?) = JSONObject().put("id", id).put("name", "Display $id")
            .put("folder", JSONObject()).apply { parentId?.let { put("parentReference", parent(it)) } }
    }
}
