package dev.pathpilot.app.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pathpilot.core.CodexCredentials
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.codex.CodexBackend
import dev.pathpilot.core.codex.CodexAuthException
import dev.pathpilot.core.codex.CodexModelsClient
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.providers.ProviderModelsClient
import dev.pathpilot.core.providers.ProviderProbeException
import dev.pathpilot.core.providers.ProviderProbeResult
import dev.pathpilot.core.providers.ProviderSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class EditorStep { TYPE, CONFIGURATION }
internal enum class ProbePhase { IDLE, RUNNING, SUCCESS, FAILED }

/** Never persisted in a Bundle, SavedStateHandle or log. */
internal data class ProviderDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val manualInput: String = "",
    val manualModels: List<String> = emptyList(),
    val selectedSuggestions: Set<String> = emptySet(),
    val selectedDiscovered: Set<String> = emptySet(),
    val discoveredModels: List<String> = emptyList(),
    val probePhase: ProbePhase = ProbePhase.IDLE,
    val probeError: String? = null,
    val modelError: String? = null,
    val modelFilter: String = "",
    val showErrors: Boolean = false,
) {
    val selectedModels: List<String> get() = (manualModels + selectedSuggestions + selectedDiscovered).distinct()
    override fun toString() = "ProviderDraft(<redacted>)"
}

internal data class ProviderEditorState(
    val active: Boolean = false,
    val editing: ProviderConfig? = null,
    val providerId: String = "",
    val step: EditorStep = EditorStep.TYPE,
    val kind: ProviderKind = ProviderKind.CODEX,
    val drafts: Map<ProviderKind, ProviderDraft> = emptyMap(),
    val revealKey: Boolean = false,
    val confirmDiscard: Boolean = false,
    val confirmDelete: Boolean = false,
) {
    val draft: ProviderDraft get() = drafts.getValue(kind)
    override fun toString() = "ProviderEditorState(active=$active, step=$step, kind=$kind, draft=<redacted>)"
}

internal data class ProviderSubmission(val provider: ProviderConfig, val selectedModels: List<String>)

internal interface ProviderModelProbe {
    suspend fun probe(baseUrl: String, apiKey: String): ProviderProbeResult
    fun cancel()
}

internal interface CodexModelProbe {
    suspend fun probe(credentials: CodexCredentials): ProviderProbeResult
    fun cancel()
}

private class HttpProviderModelProbe(proxy: OutboundProxyConfig) : ProviderModelProbe {
    private val client = ProviderModelsClient(proxyProvider = { proxy })
    override suspend fun probe(baseUrl: String, apiKey: String): ProviderProbeResult =
        withContext(Dispatchers.IO) { client.probe(baseUrl, apiKey) }
    override fun cancel() = client.cancel()
}

private class HttpCodexModelProbe(proxy: OutboundProxyConfig) : CodexModelProbe {
    private val client = CodexModelsClient(proxyProvider = { proxy })
    override suspend fun probe(credentials: CodexCredentials): ProviderProbeResult =
        withContext(Dispatchers.IO) { client.probe(credentials.accessToken, credentials.accountId) }
    override fun cancel() = client.cancel()
}

/** Activity-scoped, memory-only drafts. Each provider type owns an independent key and URL. */
class ProviderEditorViewModel() : ViewModel() {
    private var probeFactory: (OutboundProxyConfig) -> ProviderModelProbe = { HttpProviderModelProbe(it) }
    private var codexProbeFactory: (OutboundProxyConfig) -> CodexModelProbe = { HttpCodexModelProbe(it) }
    private var credentialDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal constructor(factory: () -> ProviderModelProbe) : this() { probeFactory = { factory() } }
    internal constructor(factory: (OutboundProxyConfig) -> ProviderModelProbe) : this() { probeFactory = factory }
    internal constructor(codexFactory: (OutboundProxyConfig) -> CodexModelProbe, credentialDispatcher: CoroutineDispatcher) : this() {
        codexProbeFactory = codexFactory
        this.credentialDispatcher = credentialDispatcher
    }
    private val mutableState = MutableStateFlow(ProviderEditorState())
    internal val state = mutableState.asStateFlow()
    private var baseline = emptyMap<ProviderKind, ProviderDraft>()
    private var cancelProbeClient: (() -> Unit)? = null
    private var probeJob: Job? = null
    private var probeGeneration = 0L
    private var proxy = OutboundProxyConfig()

    internal fun begin(existing: ProviderConfig?, proxy: OutboundProxyConfig = OutboundProxyConfig()) {
        val current = mutableState.value
        if (current.active && current.editing?.id == existing?.id) { setProxy(proxy); return }
        clear()
        this.proxy = proxy
        val drafts = defaults().toMutableMap()
        if (existing != null) drafts[existing.kind] = ProviderDraft(existing.name, existing.baseUrl, existing.apiKey)
        baseline = drafts.toMap()
        mutableState.value = ProviderEditorState(
            active = true, editing = existing, providerId = existing?.id ?: UUID.randomUUID().toString(),
            step = if (existing == null) EditorStep.TYPE else EditorStep.CONFIGURATION,
            kind = existing?.kind ?: ProviderKind.CODEX, drafts = drafts,
        )
    }

    internal fun setProxy(value: OutboundProxyConfig) {
        if (value == proxy) return
        stopProbe()
        proxy = value
        // The account is unchanged: retain model choices, but do not report a test on the old
        // network path as current. Generation guards discard any late transport callback.
        mutableState.value = mutableState.value.copy(drafts = mutableState.value.drafts.mapValues { (_, draft) ->
            draft.copy(probePhase = ProbePhase.IDLE, probeError = null)
        })
    }

    internal fun selectKind(kind: ProviderKind) {
        if (mutableState.value.editing != null || kind == mutableState.value.kind) return
        stopProbe()
        mutableState.value = mutableState.value.copy(kind = kind, revealKey = false)
    }

    internal fun configure() { mutableState.value = mutableState.value.copy(step = EditorStep.CONFIGURATION) }
    internal fun backToTypes() {
        stopProbe()
        mutableState.value = mutableState.value.copy(step = EditorStep.TYPE, revealKey = false)
    }

    internal fun setName(value: String) = updateDraft { it.copy(name = value) }
    internal fun setUrl(value: String) { stopProbe(); updateDraft { it.copy(baseUrl = value).withoutProbe() } }
    internal fun setKey(value: String) { stopProbe(); updateDraft { it.copy(apiKey = value).withoutProbe() } }
    internal fun setManualInput(value: String) = updateDraft { it.copy(manualInput = value, modelError = null) }
    internal fun setModelFilter(value: String) = updateDraft { it.copy(modelFilter = value) }
    internal fun toggleKeyVisibility() { mutableState.value = mutableState.value.copy(revealKey = !mutableState.value.revealKey) }

    internal fun toggleSuggestion(model: String) = updateDraft {
        it.copy(selectedSuggestions = it.selectedSuggestions.toggled(model), modelError = null)
    }
    internal fun toggleDiscovered(model: String) = updateDraft {
        it.copy(selectedDiscovered = it.selectedDiscovered.toggled(model), modelError = null)
    }
    internal fun removeManual(model: String) = updateDraft { it.copy(manualModels = it.manualModels - model, modelError = null) }

    /** Login can replace the account without changing the saved provider ID. */
    internal fun resetCodexModels() {
        if (!mutableState.value.active || mutableState.value.kind != ProviderKind.CODEX) return
        stopProbe()
        updateDraft { it.copy(discoveredModels = emptyList(), selectedDiscovered = emptySet(),
            probePhase = ProbePhase.IDLE, probeError = null, modelFilter = "") }
    }

    internal fun addManualModels(): Boolean {
        val draft = mutableState.value.draft
        if (draft.manualInput.isBlank()) return true
        val models = draft.manualInput.split(Regex("[,，\\r\\n]+")).filter { it.isNotBlank() }.map(String::trim).distinct()
        val error = models.firstNotNullOfOrNull { ProviderSetup.validateModelId(it) }
        if (error != null) { updateDraft { it.copy(modelError = error) }; return false }
        val combined = (draft.manualModels + models).distinct()
        if ((combined + draft.selectedSuggestions + draft.selectedDiscovered).distinct().size > ProviderSetup.MAX_MODELS) {
            updateDraft { it.copy(modelError = "一次最多选择 ${ProviderSetup.MAX_MODELS} 个模型") }
            return false
        }
        updateDraft { it.copy(manualModels = combined, manualInput = "", modelError = null) }
        return true
    }

    internal fun probe(resolveCodexCredentials: ((ProviderConfig) -> CodexCredentials)? = null) {
        val snapshot = mutableState.value
        if (!snapshot.active) return
        val draft = snapshot.draft
        val validation = ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, snapshot.kind)
        updateDraft { it.copy(showErrors = true) }
        if (validation.urlError != null || validation.keyError != null) return
        stopProbe()
        if (snapshot.kind == ProviderKind.CODEX && (snapshot.editing == null || resolveCodexCredentials == null)) {
            updateDraft { it.copy(probePhase = ProbePhase.FAILED, probeError =
                if (snapshot.editing == null) "保存并登录此供应商后获取模型" else "请先登录此 Codex 供应商") }
            return
        }
        val generation = probeGeneration
        val kind = snapshot.kind
        val client = if (kind == ProviderKind.CODEX) null else probeFactory(proxy)
        val codexClient = if (kind == ProviderKind.CODEX) codexProbeFactory(proxy) else null
        cancelProbeClient = { client?.cancel(); codexClient?.cancel() }
        updateDraft { it.copy(probePhase = ProbePhase.RUNNING, probeError = null) }
        probeJob = viewModelScope.launch {
            var resolvingCodex = false
            try {
                val result = if (codexClient != null) {
                    resolvingCodex = true
                    val credentials = withContext(credentialDispatcher) {
                        requireNotNull(resolveCodexCredentials)(requireNotNull(snapshot.editing))
                    }
                    resolvingCodex = false
                    codexClient.probe(credentials)
                } else requireNotNull(client).probe(requireNotNull(validation.normalizedBaseUrl), draft.apiKey.trim())
                if (probeGeneration != generation || mutableState.value.kind != kind) return@launch
                updateDraft {
                    it.copy(probePhase = ProbePhase.SUCCESS, discoveredModels = result.models,
                        selectedDiscovered = (it.selectedDiscovered + it.selectedSuggestions).intersect(result.models.toSet()),
                        selectedSuggestions = it.selectedSuggestions - result.models.toSet(), probeError = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (probeGeneration != generation || mutableState.value.kind != kind) return@launch
                updateDraft {
                    // A retry against unchanged credentials must not erase the user's choices.
                    // Editing the URL/key already clears results before a different account is probed.
                    it.copy(probePhase = ProbePhase.FAILED,
                        probeError = when {
                            error is ProviderProbeException -> error.userMessage
                            error is CodexAuthException && error.httpStatus == 401 -> "Codex 登录已过期，请重新登录"
                            resolvingCodex -> "无法读取或刷新 Codex 登录信息，请重试"
                            kind == ProviderKind.CODEX -> "获取 Codex 模型失败，请重试"
                            else -> "连接测试未完成，请检查网络后重试。"
                        })
                }
            } finally {
                if (probeGeneration == generation) { cancelProbeClient = null; probeJob = null }
            }
        }
    }

    internal fun prepareSubmission(): ProviderSubmission? {
        stopProbe()
        updateDraft { it.copy(showErrors = true) }
        val snapshot = mutableState.value
        val draft = snapshot.draft
        val validation = ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, snapshot.kind)
        if (!validation.isValid || !addManualModels()) return null
        val models = mutableState.value.draft.selectedModels
        if (models.size > ProviderSetup.MAX_MODELS) {
            updateDraft { it.copy(modelError = "一次最多选择 ${ProviderSetup.MAX_MODELS} 个模型") }
            return null
        }
        return ProviderSubmission(
            ProviderConfig(snapshot.providerId, draft.name.trim(), snapshot.kind,
                if (snapshot.kind == ProviderKind.CODEX) CodexBackend.BASE_URL else requireNotNull(validation.normalizedBaseUrl),
                if (snapshot.kind == ProviderKind.CODEX) "" else draft.apiKey.trim(),
                snapshot.editing?.enabled ?: true, snapshot.editing?.codexAccountId.orEmpty()), models,
        )
    }

    internal fun hasChanges(): Boolean = mutableState.value.drafts.any { (kind, draft) ->
        val initial = baseline[kind] ?: return@any false
        draft.name != initial.name || draft.baseUrl != initial.baseUrl || draft.apiKey != initial.apiKey ||
            draft.manualInput.isNotBlank() || draft.selectedModels.isNotEmpty()
    }

    internal fun confirmDiscard(show: Boolean) { mutableState.value = mutableState.value.copy(confirmDiscard = show) }
    internal fun confirmDelete(show: Boolean) { mutableState.value = mutableState.value.copy(confirmDelete = show) }
    internal fun cancelProbe() = stopProbe()

    internal fun clear() {
        stopProbe()
        proxy = OutboundProxyConfig()
        baseline = emptyMap()
        mutableState.value = ProviderEditorState()
    }

    private fun stopProbe() {
        probeGeneration++
        cancelProbeClient?.invoke()
        cancelProbeClient = null
        probeJob?.cancel()
        probeJob = null
        if (mutableState.value.active) updateDraft {
            if (it.probePhase == ProbePhase.RUNNING) it.copy(probePhase = ProbePhase.IDLE) else it
        }
    }

    private fun updateDraft(transform: (ProviderDraft) -> ProviderDraft) {
        val current = mutableState.value
        if (!current.active) return
        mutableState.value = current.copy(drafts = current.drafts + (current.kind to transform(current.draft)))
    }

    override fun onCleared() { clear(); super.onCleared() }

    private fun Set<String>.toggled(value: String) = if (value in this) this - value else this + value
    private fun ProviderDraft.withoutProbe() = copy(
        discoveredModels = emptyList(), selectedDiscovered = emptySet(), probePhase = ProbePhase.IDLE,
        probeError = null, modelFilter = "",
    )
    private fun defaults() = mapOf(
        ProviderKind.CODEX to ProviderDraft("Codex 订阅", CodexBackend.BASE_URL),
        ProviderKind.DEEPSEEK to ProviderDraft("DeepSeek", "https://api.deepseek.com/v1"),
        ProviderKind.OPENAI_COMPATIBLE to ProviderDraft("", "https://api.openai.com/v1"),
    )
}
