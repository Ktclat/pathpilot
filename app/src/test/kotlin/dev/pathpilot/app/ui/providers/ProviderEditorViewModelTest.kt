package dev.pathpilot.app.ui.providers

import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.CodexCredentials
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.codex.CodexBackend
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.network.OutboundProxyMode
import dev.pathpilot.core.providers.ProviderProbeError
import dev.pathpilot.core.providers.ProviderProbeException
import dev.pathpilot.core.providers.ProviderProbeResult
import dev.pathpilot.core.providers.ProviderRoutes
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val editors = mutableListOf<ProviderEditorViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        editors.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test fun `switching provider kinds never carries another providers credential`() {
        val editor = editor()
        editor.begin(null)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("deepseek-test-secret")
        editor.setName("Personal DeepSeek")
        editor.toggleKeyVisibility()

        editor.selectKind(ProviderKind.OPENAI_COMPATIBLE)
        assertEquals("", editor.state.value.draft.apiKey)
        assertFalse(editor.state.value.revealKey)
        editor.setName("Private gateway")
        editor.setKey("gateway-test-secret")
        editor.setUrl("https://gateway.example.test/v1")

        editor.selectKind(ProviderKind.CODEX)
        assertEquals("", editor.state.value.draft.apiKey)
        assertEquals("", editor.prepareSubmission()!!.provider.apiKey)
        editor.selectKind(ProviderKind.DEEPSEEK)
        assertEquals("deepseek-test-secret", editor.state.value.draft.apiKey)
        assertEquals("Personal DeepSeek", editor.state.value.draft.name)
        assertEquals("https://api.deepseek.com/v1", editor.state.value.draft.baseUrl)
        editor.selectKind(ProviderKind.OPENAI_COMPATIBLE)
        assertEquals("gateway-test-secret", editor.state.value.draft.apiKey)
        assertEquals("https://gateway.example.test/v1", editor.state.value.draft.baseUrl)
        assertFalse(editor.state.value.toString().contains("test-secret"))
        assertFalse(editor.state.value.draft.toString().contains("test-secret"))

        // Closing wipes the memory-only editor; a fresh editor cannot restore a key from saved state.
        editor.clear()
        assertFalse(editor.state.value.active)
        assertTrue(editor.state.value.drafts.isEmpty())
        editor.begin(null)
        editor.selectKind(ProviderKind.OPENAI_COMPATIBLE)
        assertEquals("", editor.state.value.draft.apiKey)
    }

    @Test fun `validation failure keeps all input until the URL is corrected`() {
        val editor = editor()
        editor.begin(null)
        editor.selectKind(ProviderKind.OPENAI_COMPATIBLE)
        editor.configure()
        editor.setName("My gateway")
        editor.setUrl("https://gateway.example.test/v1/chat/completions")
        editor.setKey("test-key-preserved")
        editor.setManualInput("model-one, model-two")

        assertNull(editor.prepareSubmission())
        assertTrue(editor.state.value.active)
        assertTrue(editor.state.value.draft.showErrors)
        assertEquals("My gateway", editor.state.value.draft.name)
        assertEquals("test-key-preserved", editor.state.value.draft.apiKey)
        assertEquals("model-one, model-two", editor.state.value.draft.manualInput)

        editor.setUrl("https://gateway.example.test/v1/")
        val submission = requireNotNull(editor.prepareSubmission())
        assertEquals("https://gateway.example.test/v1", submission.provider.baseUrl)
        assertEquals("test-key-preserved", submission.provider.apiKey)
        assertEquals(listOf("model-one", "model-two"), submission.selectedModels)
    }

    @Test fun `changing key or URL invalidates discovered models but keeps manual choices`() = runTest(dispatcher) {
        val probes = mutableListOf<Pair<String, String>>()
        val editor = editor {
            ImmediateProbe { url, key ->
                probes += url to key
                ProviderProbeResult(listOf("discovered-a", "discovered-b"))
            }
        }
        editor.begin(null)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("first-test-key")
        editor.setManualInput("manual-model")
        assertTrue(editor.addManualModels())
        editor.toggleSuggestion("deepseek-chat")
        editor.probe()
        runCurrent()
        assertEquals(ProbePhase.SUCCESS, editor.state.value.draft.probePhase)
        editor.toggleDiscovered("discovered-a")

        editor.setKey("replacement-test-key")
        assertProbeReset(editor)
        assertEquals(setOf("manual-model", "deepseek-chat"), editor.state.value.draft.selectedModels.toSet())
        editor.probe()
        runCurrent()
        assertEquals(ProbePhase.SUCCESS, editor.state.value.draft.probePhase)
        assertEquals("replacement-test-key", probes.last().second)
        editor.toggleDiscovered("discovered-b")

        editor.setUrl("https://another.example.test/v1")
        assertProbeReset(editor)
        assertEquals(setOf("manual-model", "deepseek-chat"), editor.state.value.draft.selectedModels.toSet())
        assertEquals(2, probes.size)
    }

    @Test fun `late probe response after closing cannot populate the next editor`() = runTest(dispatcher) {
        val oldProbe = DelayedProbe()
        var probesCreated = 0
        val editor = editor {
            if (probesCreated++ == 0) oldProbe
            else ImmediateProbe { _, _ -> ProviderProbeResult(listOf("current-account-model")) }
        }
        editor.begin(null)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("old-account-test-key")
        val oldProviderId = editor.state.value.providerId
        editor.probe()
        runCurrent()
        assertEquals(ProbePhase.RUNNING, editor.state.value.draft.probePhase)

        editor.clear()
        assertEquals(1, oldProbe.cancellations)
        editor.begin(null)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("new-account-test-key")
        assertNotEquals(oldProviderId, editor.state.value.providerId)

        // Simulate a transport that delivers its response even after cancel() was called.
        oldProbe.complete(listOf("old-account-model"))
        runCurrent()
        assertEquals("new-account-test-key", editor.state.value.draft.apiKey)
        assertProbeReset(editor)

        editor.probe()
        runCurrent()
        assertEquals(ProbePhase.SUCCESS, editor.state.value.draft.probePhase)
        assertEquals(listOf("current-account-model"), editor.state.value.draft.discoveredModels)
    }

    @Test fun `failed retry with unchanged credentials preserves selected models for saving`() = runTest(dispatcher) {
        var attempts = 0
        val editor = editor {
            ImmediateProbe { _, _ ->
                if (attempts++ == 0) ProviderProbeResult(listOf("model-a", "model-b"))
                else throw ProviderProbeException(ProviderProbeError.RATE_LIMITED, "请求过于频繁，请稍后重试")
            }
        }
        editor.begin(null)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("unchanged-test-key")
        editor.probe()
        runCurrent()
        editor.toggleDiscovered("model-b")

        editor.probe()
        runCurrent()

        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertEquals(listOf("model-a", "model-b"), editor.state.value.draft.discoveredModels)
        assertEquals(setOf("model-b"), editor.state.value.draft.selectedDiscovered)
        assertEquals(listOf("model-b"), requireNotNull(editor.prepareSubmission()).selectedModels)
        assertEquals(2, attempts)
    }

    @Test fun `provider without models endpoint can still be saved with a manual model`() = runTest(dispatcher) {
        val editor = editor {
            ImmediateProbe { _, _ ->
                throw ProviderProbeException(ProviderProbeError.MODELS_UNSUPPORTED, "模型列表接口不可用，可手动填写")
            }
        }
        editor.begin(null)
        editor.selectKind(ProviderKind.OPENAI_COMPATIBLE)
        editor.setName("Manual gateway")
        editor.setKey("manual-gateway-test-key")
        editor.setUrl("https://gateway.example.test/v1")
        editor.probe()
        runCurrent()
        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertTrue(editor.state.value.draft.probeError.orEmpty().contains("手动"))

        editor.setManualInput("private-coding-model")
        val submission = requireNotNull(editor.prepareSubmission())
        assertEquals("Manual gateway", submission.provider.name)
        assertEquals(listOf("private-coding-model"), submission.selectedModels)
    }

    @Test fun `proxy change cancels in-flight probe and next request uses the selected proxy`() = runTest(dispatcher) {
        val staleProbe = DelayedProbe()
        val seenProxies = mutableListOf<OutboundProxyConfig>()
        val system = OutboundProxyConfig()
        val selected = OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", 7890)
        val editor = ProviderEditorViewModel { proxy: OutboundProxyConfig ->
            seenProxies += proxy
            if (seenProxies.size == 1) staleProbe
            else ImmediateProbe { _, _ -> ProviderProbeResult(listOf("new-path-model")) }
        }.also(editors::add)
        editor.begin(null, system)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("proxy-test-key")
        editor.setManualInput("manual-model")
        editor.addManualModels()
        editor.probe()
        runCurrent()

        editor.begin(null, selected)
        assertEquals(1, staleProbe.cancellations)
        staleProbe.complete(listOf("stale-model"))
        runCurrent()
        assertProbeReset(editor)
        assertEquals(listOf("manual-model"), editor.state.value.draft.selectedModels)

        editor.probe()
        runCurrent()
        assertEquals(listOf(system, selected), seenProxies)
        assertEquals(listOf("new-path-model"), editor.state.value.draft.discoveredModels)
    }

    @Test fun `changing proxy retains prior choices without claiming a current connection test`() = runTest(dispatcher) {
        val editor = editor { ImmediateProbe { _, _ -> ProviderProbeResult(listOf("chosen-model")) } }
        editor.begin(null)
        editor.selectKind(ProviderKind.DEEPSEEK)
        editor.setKey("proxy-test-key")
        editor.probe()
        runCurrent()
        editor.toggleDiscovered("chosen-model")

        editor.setProxy(OutboundProxyConfig(OutboundProxyMode.SOCKS5, "127.0.0.1", 1080))

        assertEquals(ProbePhase.IDLE, editor.state.value.draft.probePhase)
        assertEquals(listOf("chosen-model"), editor.state.value.draft.discoveredModels)
        assertEquals(listOf("chosen-model"), requireNotNull(editor.prepareSubmission()).selectedModels)
    }

    @Test fun `opening and probing existing provider needs no discard confirmation without input changes`() = runTest(dispatcher) {
        val editor = editor { ImmediateProbe { _, _ -> ProviderProbeResult(listOf("available-model")) } }
        val existing = ProviderConfig("existing", "Local gateway", ProviderKind.OPENAI_COMPATIBLE,
            "http://192.168.1.2:8000/v1", "existing-test-key")
        editor.begin(existing)
        assertFalse(editor.hasChanges())
        editor.probe()
        runCurrent()
        assertFalse(editor.hasChanges())
        assertEquals(existing, requireNotNull(editor.prepareSubmission()).provider)
        assertTrue(requireNotNull(editor.prepareSubmission()).selectedModels.isEmpty())

        editor.setName("Changed gateway")
        assertTrue(editor.hasChanges())
        editor.setName(existing.name)
        assertFalse(editor.hasChanges())
    }

    @Test fun `Codex subscription models use resolved credentials and selected model becomes its route`() = runTest(dispatcher) {
        val provider = savedCodex("codex-one")
        val proxy = OutboundProxyConfig(OutboundProxyMode.SOCKS5, "127.0.0.1", 1080)
        val resolvedProviders = mutableListOf<ProviderConfig>()
        val usedProxies = mutableListOf<OutboundProxyConfig>()
        val usedCredentials = mutableListOf<CodexCredentials>()
        val credentials = CodexCredentials("subscription-test-token", "subscription-account-one")
        val editor = codexEditor { selectedProxy ->
            usedProxies += selectedProxy
            ImmediateCodexProbe { resolved ->
                usedCredentials += resolved
                ProviderProbeResult(listOf("gpt-codex-test", "gpt-codex-other"))
            }
        }
        editor.begin(provider, proxy)
        editor.probe { resolvedProviders += it; credentials }
        runCurrent()

        assertEquals(ProbePhase.SUCCESS, editor.state.value.draft.probePhase)
        assertEquals(listOf(provider), resolvedProviders)
        assertEquals(listOf(proxy), usedProxies)
        assertEquals(listOf(credentials), usedCredentials)
        assertEquals("", editor.state.value.draft.apiKey)
        assertFalse(editor.state.value.toString().contains(credentials.accessToken))
        assertFalse(editor.state.value.draft.toString().contains(credentials.accessToken))
        editor.toggleDiscovered("gpt-codex-test")
        val submission = requireNotNull(editor.prepareSubmission())
        assertEquals(provider, submission.provider)
        assertEquals(listOf("gpt-codex-test"), submission.selectedModels)
        val config = ProviderRoutes.addSelected(RouterConfig(accessKey = "local-test-key", providers = listOf(provider)),
            submission.provider, submission.selectedModels)
        assertEquals(provider.id, config.routes.single().providerId)
        assertEquals("gpt-codex-test", config.routes.single().upstreamModel)
    }

    @Test fun `Codex unauthorized response retains draft and retry resolves current subscription again`() = runTest(dispatcher) {
        val credentials = mutableListOf<CodexCredentials>()
        var calls = 0
        var resolutions = 0
        val editor = codexEditor {
            ImmediateCodexProbe {
                credentials += it
                if (calls++ == 0) throw ProviderProbeException(ProviderProbeError.UNAUTHORIZED, "Codex 登录已过期，请重新登录")
                ProviderProbeResult(listOf("available-after-login"))
            }
        }
        editor.begin(savedCodex("codex-retry"))
        editor.setName("My subscription")
        editor.setManualInput("manual-model")
        val resolver: (ProviderConfig) -> CodexCredentials = { CodexCredentials("test-token-${++resolutions}", "account-one") }
        editor.probe(resolver)
        runCurrent()
        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertEquals("Codex 登录已过期，请重新登录", editor.state.value.draft.probeError)
        assertEquals("My subscription", editor.state.value.draft.name)
        assertEquals("manual-model", editor.state.value.draft.manualInput)

        editor.probe(resolver)
        runCurrent()
        assertEquals(ProbePhase.SUCCESS, editor.state.value.draft.probePhase)
        assertNull(editor.state.value.draft.probeError)
        assertEquals(listOf("available-after-login"), editor.state.value.draft.discoveredModels)
        assertEquals(listOf("test-token-1", "test-token-2"), credentials.map { it.accessToken })
        assertEquals("", editor.state.value.draft.apiKey)
    }

    @Test fun `unsaved or unsigned Codex reports an error without fetching a catalog`() = runTest(dispatcher) {
        var fetched = false
        var resolved = false
        val editor = codexEditor { ImmediateCodexProbe { fetched = true; ProviderProbeResult(emptyList()) } }
        editor.begin(null)
        editor.probe { resolved = true; CodexCredentials("unused", "unused") }
        runCurrent()
        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertTrue(editor.state.value.draft.probeError.orEmpty().contains("保存"))
        assertFalse(resolved)
        assertFalse(fetched)

        editor.begin(savedCodex("not-logged-in"))
        editor.probe()
        runCurrent()
        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertTrue(editor.state.value.draft.probeError.orEmpty().contains("登录"))
        assertFalse(fetched)

        editor.probe { throw IllegalStateException("Codex 账号尚未登录") }
        runCurrent()
        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertTrue(editor.state.value.draft.probeError.orEmpty().contains("登录"))
        assertFalse(fetched)
    }

    @Test fun `late Codex catalog cannot cross into another saved provider`() = runTest(dispatcher) {
        val oldProbe = DelayedCodexProbe()
        var created = 0
        val resolvedIds = mutableListOf<String>()
        val editor = codexEditor {
            if (created++ == 0) oldProbe
            else ImmediateCodexProbe { ProviderProbeResult(listOf("new-account-model")) }
        }
        val resolver: (ProviderConfig) -> CodexCredentials = {
            resolvedIds += it.id
            CodexCredentials("test-${it.id}", "account-${it.id}")
        }
        editor.begin(savedCodex("first"))
        editor.probe(resolver)
        runCurrent()
        assertEquals(ProbePhase.RUNNING, editor.state.value.draft.probePhase)
        editor.begin(savedCodex("second"))
        assertEquals(1, oldProbe.cancellations)
        oldProbe.complete(listOf("old-account-model"))
        runCurrent()
        assertEquals("second", editor.state.value.providerId)
        assertProbeReset(editor)

        editor.probe(resolver)
        runCurrent()
        assertEquals(listOf("first", "second"), resolvedIds)
        assertEquals(listOf("new-account-model"), editor.state.value.draft.discoveredModels)
        assertEquals("", editor.state.value.draft.apiKey)
    }

    @Test fun `closing before Codex credential IO runs cancels the client and never reads the account`() = runTest(dispatcher) {
        val credentialIo = PausedDispatcher()
        var resolved = false
        val probe = DelayedCodexProbe()
        val editor = codexEditor(credentialDispatcher = credentialIo) { probe }
        editor.begin(savedCodex("cancelled"))
        editor.probe { resolved = true; CodexCredentials("not-used", "not-used") }
        runCurrent()
        assertEquals(ProbePhase.RUNNING, editor.state.value.draft.probePhase)
        editor.clear()
        credentialIo.runPending()
        runCurrent()
        assertEquals(1, probe.cancellations)
        assertFalse(resolved)
        assertFalse(probe.requested)
        assertFalse(editor.state.value.active)
    }

    @Test fun `Codex proxy change cancels old catalog and retry uses the new proxy`() = runTest(dispatcher) {
        val oldProbe = DelayedCodexProbe()
        val usedProxies = mutableListOf<OutboundProxyConfig>()
        val editor = codexEditor { proxy ->
            usedProxies += proxy
            if (usedProxies.size == 1) oldProbe else ImmediateCodexProbe { ProviderProbeResult(listOf("new-network-model")) }
        }
        val system = OutboundProxyConfig()
        val selected = OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", 7890)
        val provider = savedCodex("proxy-codex")
        val resolver: (ProviderConfig) -> CodexCredentials = { CodexCredentials("test-token", "test-account") }
        editor.begin(provider, system)
        editor.probe(resolver)
        runCurrent()
        editor.begin(provider, selected)
        assertEquals(1, oldProbe.cancellations)
        oldProbe.complete(listOf("old-network-model"))
        runCurrent()
        assertProbeReset(editor)
        editor.probe(resolver)
        runCurrent()
        assertEquals(listOf(system, selected), usedProxies)
        assertEquals(listOf("new-network-model"), editor.state.value.draft.discoveredModels)
    }

    @Test fun `switching Codex account clears old catalog even when fetching the new account fails`() = runTest(dispatcher) {
        var attempts = 0
        val editor = codexEditor {
            ImmediateCodexProbe {
                if (attempts++ == 0) ProviderProbeResult(listOf("old-account-model") + (1..8).map { "old-model-$it" })
                else throw ProviderProbeException(ProviderProbeError.UNAUTHORIZED, "Codex 登录已过期，请重新登录")
            }
        }
        editor.begin(savedCodex("same-provider"))
        editor.setName("My subscription")
        editor.setManualInput("manual-model")
        editor.addManualModels()
        editor.probe { CodexCredentials("old-test-token", "old-account") }
        runCurrent()
        editor.toggleDiscovered("old-account-model")
        editor.setModelFilter("old-account-model")
        assertEquals(setOf("manual-model", "old-account-model"), editor.state.value.draft.selectedModels.toSet())

        editor.resetCodexModels()
        assertProbeReset(editor)
        assertEquals("", editor.state.value.draft.modelFilter)
        editor.probe { CodexCredentials("new-test-token", "new-account") }
        runCurrent()

        assertEquals(ProbePhase.FAILED, editor.state.value.draft.probePhase)
        assertTrue(editor.state.value.draft.discoveredModels.isEmpty())
        assertTrue(editor.state.value.draft.selectedDiscovered.isEmpty())
        assertEquals("My subscription", editor.state.value.draft.name)
        assertEquals(listOf("manual-model"), requireNotNull(editor.prepareSubmission()).selectedModels)
    }

    private fun savedCodex(id: String) = ProviderConfig(id, "Codex subscription", ProviderKind.CODEX, CodexBackend.BASE_URL)

    private fun codexEditor(credentialDispatcher: CoroutineDispatcher = dispatcher,
        factory: (OutboundProxyConfig) -> CodexModelProbe,
    ) = ProviderEditorViewModel(factory, credentialDispatcher).also(editors::add)

    private fun editor(factory: () -> ProviderModelProbe = {
        ImmediateProbe { _, _ -> error("This test must not perform a model probe") }
    }) = ProviderEditorViewModel(factory).also(editors::add)

    private fun assertProbeReset(editor: ProviderEditorViewModel) {
        assertEquals(ProbePhase.IDLE, editor.state.value.draft.probePhase)
        assertTrue(editor.state.value.draft.discoveredModels.isEmpty())
        assertTrue(editor.state.value.draft.selectedDiscovered.isEmpty())
    }

    private class ImmediateProbe(
        private val result: (String, String) -> ProviderProbeResult,
    ) : ProviderModelProbe {
        override suspend fun probe(baseUrl: String, apiKey: String) = result(baseUrl, apiKey)
        override fun cancel() = Unit
    }

    private class DelayedProbe : ProviderModelProbe {
        private var continuation: Continuation<ProviderProbeResult>? = null
        var cancellations = 0
            private set

        override suspend fun probe(baseUrl: String, apiKey: String): ProviderProbeResult = suspendCoroutine {
            continuation = it
        }

        override fun cancel() { cancellations++ }
        fun complete(models: List<String>) {
            requireNotNull(continuation).resume(ProviderProbeResult(models))
            continuation = null
        }
    }

    private class ImmediateCodexProbe(private val result: (CodexCredentials) -> ProviderProbeResult) : CodexModelProbe {
        override suspend fun probe(credentials: CodexCredentials) = result(credentials)
        override fun cancel() = Unit
    }

    private class DelayedCodexProbe : CodexModelProbe {
        private var continuation: Continuation<ProviderProbeResult>? = null
        var cancellations = 0
            private set
        var requested = false
            private set
        override suspend fun probe(credentials: CodexCredentials): ProviderProbeResult = suspendCoroutine {
            requested = true
            continuation = it
        }
        override fun cancel() { cancellations++ }
        fun complete(models: List<String>) {
            requireNotNull(continuation).resume(ProviderProbeResult(models))
            continuation = null
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runPending() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
