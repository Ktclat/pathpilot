package dev.pathpilot.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathpilot.app.McpPage
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.runtime.McpRuntime
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.core.mcp.McpClient
import dev.pathpilot.core.mcp.McpSettings
import dev.pathpilot.core.network.OutboundProxyConfig
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production MCP page and storage; only the HTTP destination is redirected to a local server. */
@RunWith(AndroidJUnit4::class)
class McpPresetFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var store: AppStore
    private lateinit var original: BackupSnapshot
    private lateinit var fixture: CatalogFixture
    private lateinit var http: OkHttpClient
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val requests = CopyOnWriteArrayList<Request>()

    @Before fun prepare() {
        store = captureStore()
        original = store.backupSnapshot(includeCodexSessions = true)
        prepareChineseCapture(store)
        store.setConfig(store.config.value.copy(outboundProxy = OutboundProxyConfig()))
        store.setMcpSettings(McpSettings(accessKey = "sk-" + "p".repeat(40)))
        fixture = CatalogFixture()
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host in setOf("learn.microsoft.com", "developers.openai.com", "api.githubcopilot.com", "mcp.context7.com"))
            requests += request
            val loopback = fixture.server.url(request.url.encodedPath).newBuilder()
                .encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(loopback).build())
        }.build()
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity { activity ->
                activity.setContent {
                    PathPilotTheme {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            var selectedId by remember { mutableStateOf<String?>(null) }
                            McpPage(store, selectedId, onSelectConnection = { selectedId = it }, onOpenHistory = {},
                                catalogClient = { connection, proxy -> McpClient(connection, proxy, httpClient = http) })
                        }
                    }
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("添加连接")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun clean() {
        try {
            scenario?.close()
        } finally {
            if (::http.isInitialized) {
                http.dispatcher.cancelAll()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
            if (::fixture.isInitialized) fixture.server.shutdown()
            if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
        }
    }

    @Test fun publicPresetsSaveTheirOfficialUrlFetchToolsAndRetryWithoutDuplicates() {
        fixture.failNextInitialize.set(true)
        openCatalog()
        compose.onNodeWithTag("mcp-preset-microsoft-learn").assertIsDisplayed()
        compose.onNodeWithTag("mcp-preset-openai-docs").assertIsDisplayed()
        compose.captureSettingsScreenshot("37-mcp-preset-catalog")
        selectPreset("microsoft-learn")

        compose.waitUntil(60_000) { store.mcp.value.connections.size == 1 && fixture.initializeCount.get() == 1 }
        val saved = store.mcp.value.connections.single()
        assertEquals("Microsoft Learn", saved.name)
        assertEquals(LEARN_URL, saved.url)
        assertEquals("", saved.bearerToken)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("503", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("503", substring = true).assertIsDisplayed()
        assertEquals(0, fixture.listCount.get())
        assertFalse(McpRuntime.state.value.running)
        compose.captureSettingsScreenshot("38-mcp-preset-fetch-error")

        compose.onNodeWithText("获取工具").performClick()
        awaitCatalog(1)
        assertEquals(saved.id, store.mcp.value.connections.single().id)
        assertEquals(2, fixture.initializeCount.get())
        compose.captureSettingsScreenshot("39-mcp-preset-tools")

        backToConnections()
        openCatalog()
        compose.onNodeWithTag("mcp-preset-microsoft-learn").performScrollTo()
            .assert(hasContentDescription("已添加", substring = true))
        selectPreset("microsoft-learn")
        awaitCatalog(2)
        assertEquals(listOf(saved.id), store.mcp.value.connections.map { it.id })

        backToConnections()
        openCatalog()
        selectPreset("openai-docs")
        awaitCatalog(3)
        assertEquals(setOf(LEARN_URL, OPENAI_URL), store.mcp.value.connections.map { it.url }.toSet())
        assertTrue(store.mcp.value.connections.all { it.bearerToken.isEmpty() })
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.header("Authorization") == null })
        assertEquals(setOf(LEARN_URL, OPENAI_URL), requests.map { it.url.toString() }.toSet())
        assertEquals(0, fixture.toolCalls.get())
        assertFalse(McpRuntime.state.value.running)
    }

    @Test fun credentialPresetsRequireTokensAndKeepDifferentAccountsSeparate() {
        val githubToken = "offline-github-pat"
        val secondGithubToken = "offline-github-second-pat"
        val context7Key = "offline-context7-key"
        openCatalog()
        selectPreset("github")
        assertEmptyTokenDoesNotSave(expectedConnections = 0, expectedInitializations = 0)
        fixture.expectedAuthorization = "Bearer $githubToken"
        enterToken(githubToken)
        compose.captureSettingsScreenshot("40-mcp-github-token")
        addWithToken()
        awaitCatalog(1)
        val github = store.mcp.value.connections.single()
        assertEquals(GITHUB_URL, github.url)
        assertEquals(githubToken, github.bearerToken)
        compose.captureSettingsScreenshot("41-mcp-github-tools")

        backToConnections()
        openCatalog()
        selectPreset("github")
        enterToken(githubToken)
        addWithToken()
        awaitCatalog(2)
        assertEquals(listOf(github.id), store.mcp.value.connections.map { it.id })

        backToConnections()
        openCatalog()
        selectPreset("github")
        fixture.expectedAuthorization = "Bearer $secondGithubToken"
        enterToken(secondGithubToken)
        addWithToken()
        awaitCatalog(3)
        assertEquals(2, store.mcp.value.connections.size)
        assertEquals(setOf(githubToken, secondGithubToken), store.mcp.value.connections.map { it.bearerToken }.toSet())
        assertEquals(2, store.mcp.value.connections.map { it.id }.distinct().size)

        backToConnections()
        openCatalog()
        selectPreset("context7")
        compose.onNodeWithText("API Key").assertIsDisplayed()
        assertEmptyTokenDoesNotSave(expectedConnections = 2, expectedInitializations = 3)
        fixture.expectedAuthorization = "Bearer $context7Key"
        enterToken(context7Key)
        compose.captureSettingsScreenshot("42-mcp-context7-key")
        addWithToken()
        awaitCatalog(4)
        val context7 = store.mcp.value.connections.single { it.url == CONTEXT7_URL }
        assertEquals(context7Key, context7.bearerToken)
        assertEquals(3, store.mcp.value.connections.size)
        assertEquals(setOf("Bearer $githubToken", "Bearer $secondGithubToken"), requests
            .filter { it.url.toString() == GITHUB_URL }.map { it.header("Authorization") }.toSet())
        assertTrue(requests.filter { it.url.toString() == CONTEXT7_URL }.all { it.header("Authorization") == "Bearer $context7Key" })
        assertEquals(0, fixture.toolCalls.get())
        assertFalse(McpRuntime.state.value.running)
    }

    private fun openCatalog() = compose.onNodeWithContentDescription("添加连接").performClick()

    private fun selectPreset(id: String) = compose.onNodeWithTag("mcp-preset-$id").performScrollTo().performClick()

    private fun backToConnections() = compose.onNodeWithContentDescription("返回").performClick()

    private fun enterToken(value: String) {
        compose.onNodeWithTag("mcp-preset-token").performTextReplacement(value)
        hideCaptureKeyboard()
    }

    private fun addWithToken() = compose.onNodeWithTag("mcp-preset-add").performScrollTo().performClick()

    private fun assertEmptyTokenDoesNotSave(expectedConnections: Int, expectedInitializations: Int) {
        compose.onNodeWithTag("mcp-preset-token").assertIsDisplayed()
        addWithToken()
        compose.onNodeWithText("填写上游访问令牌").performScrollTo().assertIsDisplayed()
        assertEquals(expectedConnections, store.mcp.value.connections.size)
        assertEquals(expectedInitializations, fixture.initializeCount.get())
    }

    private fun awaitCatalog(expectedLists: Int) {
        compose.waitUntil(60_000) {
            fixture.listCount.get() == expectedLists &&
                compose.onAllNodes(hasText("获取工具")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("offline_documentation"))
        compose.onNodeWithText("offline_documentation").assertIsDisplayed()
    }

    private class CatalogFixture {
        @Volatile var expectedAuthorization: String? = null
        val failNextInitialize = AtomicBoolean(false)
        val initializeCount = AtomicInteger()
        val listCount = AtomicInteger()
        val toolCalls = AtomicInteger()
        private val sessions = ConcurrentHashMap<String, String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val session = request.getHeader("Mcp-Session-Id")
                    if (request.method == "DELETE") {
                        return MockResponse().setResponseCode(if (session != null && sessions.remove(session) != null) 204 else 404)
                    }
                    if (request.method != "POST") return MockResponse().setResponseCode(405)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val method = body["method"]?.jsonPrimitive?.content.orEmpty()
                    if (method == "initialize") {
                        val attempt = initializeCount.incrementAndGet()
                        if (request.getHeader("Authorization") != expectedAuthorization) return MockResponse().setResponseCode(401)
                        if (failNextInitialize.getAndSet(false)) return MockResponse().setResponseCode(503)
                        val id = "preset-session-$attempt"
                        sessions[id] = request.getHeader("Authorization").orEmpty()
                        return response(body, buildJsonObject {
                            put("protocolVersion", "2025-11-25")
                            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                            put("serverInfo", buildJsonObject { put("name", "Offline preset tools"); put("version", "1") })
                        }).addHeader("Mcp-Session-Id", id)
                    }
                    if (session == null || sessions[session] != request.getHeader("Authorization").orEmpty())
                        return MockResponse().setResponseCode(401)
                    return when (method) {
                        "notifications/initialized" -> MockResponse().setResponseCode(202)
                        "tools/list" -> {
                            listCount.incrementAndGet()
                            response(body, buildJsonObject {
                                put("tools", buildJsonArray { add(buildJsonObject {
                                    put("name", "offline_documentation")
                                    put("description", "离线文档工具 / Offline documentation tool")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                }) })
                            })
                        }
                        "tools/call" -> {
                            toolCalls.incrementAndGet()
                            MockResponse().setResponseCode(400)
                        }
                        else -> MockResponse().setResponseCode(400)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }

        private fun response(request: JsonObject, result: JsonObject) = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", request["id"] ?: JsonNull); put("result", result)
            }.toString())
    }

    private companion object {
        const val LEARN_URL = "https://learn.microsoft.com/api/mcp"
        const val OPENAI_URL = "https://developers.openai.com/mcp"
        const val GITHUB_URL = "https://api.githubcopilot.com/mcp/"
        const val CONTEXT7_URL = "https://mcp.context7.com/mcp"
    }
}
