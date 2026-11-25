package dev.pathpilot.app.ui.providers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.icons.ProviderBrandIcon
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.providers.ProviderSetup
import kotlinx.coroutines.launch

/** Full-screen native editor. Selected model IDs are suggestions for atomic route creation by its owner. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderEditor(
    existing: ProviderConfig?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, List<String>) -> Unit,
    onDelete: (ProviderConfig) -> Unit,
    proxy: OutboundProxyConfig = OutboundProxyConfig(),
    codexSignedIn: Boolean = false,
    resolveCodexCredentials: ((ProviderConfig) -> dev.pathpilot.core.CodexCredentials)? = null,
    onLogin: () -> Unit = {},
    fetchModelsOnOpen: Boolean = false,
    onModelsFetchStarted: () -> Unit = {},
    model: ProviderEditorViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.activity()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val fields = remember { EditorFieldTargets() }
    LaunchedEffect(existing?.id, proxy) { model.begin(existing, proxy) }
    LaunchedEffect(existing?.id, state.editing?.id, state.active, fetchModelsOnOpen, codexSignedIn) {
        if (fetchModelsOnOpen && codexSignedIn && existing != null && state.active && state.editing?.id == existing.id) {
            model.resetCodexModels()
            model.probe(resolveCodexCredentials)
            onModelsFetchStarted()
        }
    }
    // Root removes the editor after a successful save. Preserve the activity-scoped draft only
    // during configuration recreation; normal dismiss/save must release all in-memory keys.
    DisposableEffect(model, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) model.clear() }
    }
    fun closeNow() { if (!saving) { model.clear(); onDismiss() } }
    fun requestClose() {
        if (!saving) {
            focus.clearFocus()
            if (model.hasChanges()) model.confirmDiscard(true) else closeNow()
        }
    }
    fun back() {
        if (saving) return
        focus.clearFocus()
        if (state.editing == null && state.step == EditorStep.CONFIGURATION) model.backToTypes()
        else requestClose()
    }
    fun submit() {
        if (saving) return
        focus.clearFocus()
        val submission = model.prepareSubmission()
        if (submission != null) onSave(submission.provider, submission.selectedModels)
        else {
            val draft = model.state.value.draft
            val validation = ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, model.state.value.kind)
            val target = when {
                validation.nameError != null -> fields.name
                validation.keyError != null -> fields.key
                validation.urlError != null -> fields.url
                else -> fields.models
            }
            scope.launch {
                withFrameNanos { }
                target.focus.requestFocus()
                target.bring.bringIntoView()
            }
        }
    }
    fun probe() {
        focus.clearFocus()
        model.probe(resolveCodexCredentials)
        val draft = model.state.value.draft
        val validation = ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, model.state.value.kind)
        val target = if (validation.keyError != null) fields.key else if (validation.urlError != null) fields.url else null
        if (target != null) scope.launch {
            withFrameNanos { }
            target.focus.requestFocus()
            target.bring.bringIntoView()
        }
    }
    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = false, dismissOnClickOutside = false,
    )) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!state.active) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing.only(if (state.step == EditorStep.TYPE)
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal),
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (state.step == EditorStep.CONFIGURATION) ProviderBrandIcon(state.kind)
                                    Text(if (state.step == EditorStep.TYPE) "添加供应商" else if (existing == null) state.kind.title() else "编辑供应商", fontWeight = FontWeight.SemiBold)
                                }
                            },
                            navigationIcon = { IconButton(enabled = !saving, onClick = ::back) {
                                Icon(if (state.step == EditorStep.CONFIGURATION && existing == null) PathPilotIcons.Back else PathPilotIcons.Close,
                                    if (state.step == EditorStep.CONFIGURATION && existing == null) "返回来源选择" else "关闭编辑")
                            } },
                            actions = {
                                if (existing == null && state.step == EditorStep.CONFIGURATION) IconButton(enabled = !saving, onClick = ::requestClose) {
                                    Icon(PathPilotIcons.Close, "关闭编辑")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                    },
                    bottomBar = {
                        if (state.step == EditorStep.CONFIGURATION) EditorFooter(state, saving, onBack = ::back, onSave = ::submit)
                    },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)
                            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        if (state.step == EditorStep.TYPE) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProviderKind.entries.forEach { kind ->
                                    ProviderTypeCard(kind, enabled = !saving) {
                                        model.selectKind(kind)
                                        model.configure()
                                        scope.launch { scroll.scrollTo(0) }
                                    }
                                }
                            }
                        } else {
                            FormValidationSummary(state)
                            ProviderFields(state, saving, model, fields, codexSignedIn, onLogin)
                            ModelSelection(state, saving, model, fields.models, codexSignedIn, onProbe = ::probe)
                            if (existing != null) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TextButton(enabled = !saving, onClick = { model.confirmDelete(true) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Icon(PathPilotIcons.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("删除供应商")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.confirmDiscard) AlertDialog(
            onDismissRequest = { if (!saving) model.confirmDiscard(false) },
            icon = { Icon(PathPilotIcons.Edit, null) },
            title = { Text("放弃更改？") },
            text = { Text("未保存的内容将丢失。") },
            confirmButton = { TextButton(enabled = !saving, onClick = ::closeNow) { Text("放弃更改") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { model.confirmDiscard(false) }) { Text("继续编辑") } },
        )
        if (state.confirmDelete && existing != null) AlertDialog(
            onDismissRequest = { if (!saving) model.confirmDelete(false) },
            icon = { Icon(PathPilotIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除 ${existing.name}？") },
            text = { Text("该供应商的模型配置也会删除。") },
            confirmButton = { TextButton(enabled = !saving, onClick = { onDelete(existing) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (saving) "正在删除…" else "删除") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { model.confirmDelete(false) }) { Text("取消") } },
        )
    }
}

@Composable private fun ProviderTypeCard(kind: ProviderKind, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProviderBrandIcon(kind, Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(kind.title(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(kind.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun FormValidationSummary(state: ProviderEditorState) {
    val draft = state.draft
    val validation = if (draft.showErrors) ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, state.kind) else null
    val errors = buildList {
        // Keep a model error visible even when several connection fields also need attention.
        draft.modelError?.let { add("模型 ID") }
        validation?.nameError?.let { add("名称") }
        validation?.keyError?.let { add("API Key") }
        validation?.urlError?.let { add("API 地址") }
    }.take(3)
    if (errors.size > 1) Text("请检查：${errors.joinToString("、")}", Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ProviderFields(state: ProviderEditorState, saving: Boolean, model: ProviderEditorViewModel,
    fields: EditorFieldTargets, codexSignedIn: Boolean, onLogin: () -> Unit,
) {
    val draft = state.draft
    val validation = ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, state.kind)
    val nameError = validation.nameError.takeIf { draft.showErrors }
    val urlError = validation.urlError.takeIf { draft.showErrors }
    val keyError = validation.keyError.takeIf { draft.showErrors }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.name, onValueChange = model::setName, enabled = !saving,
            modifier = Modifier.fillMaxWidth().focusRequester(fields.name.focus).bringIntoViewRequester(fields.name.bring),
            label = { Text("名称") }, placeholder = { Text("供应商名称") }, singleLine = true,
            isError = nameError != null,
            supportingText = if (nameError != null) { { Text(nameError) } } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        if (state.kind == ProviderKind.CODEX) {
            if (state.editing != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (codexSignedIn) "已登录" else "未登录", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !saving && state.draft.probePhase != ProbePhase.RUNNING, onClick = onLogin) {
                    Text(if (codexSignedIn) "重新登录" else "登录")
                }
            }
        } else {
            OutlinedTextField(
                value = draft.apiKey, onValueChange = model::setKey, enabled = !saving,
                modifier = Modifier.fillMaxWidth().focusRequester(fields.key.focus).bringIntoViewRequester(fields.key.bring),
                label = { Text(if (state.kind == ProviderKind.OPENAI_COMPATIBLE) "API Key（可选）" else "API Key") }, placeholder = { Text("粘贴 API Key") },
                leadingIcon = { Icon(PathPilotIcons.Key, null) },
                trailingIcon = { IconButton(enabled = !saving, onClick = model::toggleKeyVisibility) {
                    Icon(if (state.revealKey) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility, if (state.revealKey) "隐藏 API Key" else "显示 API Key")
                } },
                singleLine = true, visualTransformation = if (state.revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                isError = keyError != null,
                supportingText = if (keyError != null) { { Text(keyError) } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = draft.baseUrl, onValueChange = model::setUrl, enabled = !saving,
                modifier = Modifier.fillMaxWidth().focusRequester(fields.url.focus).bringIntoViewRequester(fields.url.bring),
                label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") },
                leadingIcon = { Icon(PathPilotIcons.Link, null) }, singleLine = true,
                isError = urlError != null,
                supportingText = if (urlError != null) { { Text(urlError) } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable private fun ModelSelection(state: ProviderEditorState, saving: Boolean, model: ProviderEditorViewModel,
    field: EditorFieldTarget, codexSignedIn: Boolean, onProbe: () -> Unit,
) {
    val draft = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.editing == null) "共享模型" else "添加模型", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (draft.probePhase == ProbePhase.RUNNING) TextButton(enabled = !saving, onClick = model::cancelProbe) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("取消获取")
            } else TextButton(enabled = !saving && (state.kind != ProviderKind.CODEX || (state.editing != null && codexSignedIn)), onClick = onProbe) {
                Icon(PathPilotIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("获取模型")
            }
        }
        if (state.kind == ProviderKind.CODEX && !codexSignedIn) Text(
            if (state.editing == null) "保存并登录后可获取模型" else "登录后可获取模型",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (draft.probePhase == ProbePhase.SUCCESS) Text(if (draft.discoveredModels.isEmpty()) "未返回可选模型，可手动填写" else "已获取 ${draft.discoveredModels.size} 个模型",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (draft.probePhase == ProbePhase.FAILED) Text(draft.probeError.orEmpty(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (state.kind == ProviderKind.DEEPSEEK && (draft.discoveredModels.isEmpty() || draft.selectedSuggestions.isNotEmpty())) {
            Text("常用模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val suggestions = if (draft.discoveredModels.isNotEmpty())
                    draft.selectedSuggestions.toList() else listOf("deepseek-chat", "deepseek-reasoner")
                suggestions.forEach { suggested ->
                    FilterChip(selected = suggested in draft.selectedSuggestions, onClick = { model.toggleSuggestion(suggested) }, enabled = !saving,
                        label = { Text(suggested, fontFamily = FontFamily.Monospace) },
                        leadingIcon = if (suggested in draft.selectedSuggestions) { { Icon(PathPilotIcons.Check, null, Modifier.size(16.dp)) } } else null)
                }
            }
        }
        if (draft.discoveredModels.isNotEmpty()) {
            if (draft.probePhase != ProbePhase.SUCCESS && draft.probePhase != ProbePhase.RUNNING) Text("上次获取的结果",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (draft.discoveredModels.size > 8 || draft.modelFilter.isNotBlank()) OutlinedTextField(
                draft.modelFilter, model::setModelFilter, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                label = { Text("筛选模型") }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            val visible = draft.discoveredModels.filter { it.contains(draft.modelFilter.trim(), ignoreCase = true) }
            Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                if (visible.isEmpty()) Text("没有匹配的模型", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(visible, key = { it }) { id ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = id in draft.selectedDiscovered, enabled = !saving,
                            role = Role.Checkbox, onValueChange = { model.toggleDiscovered(id) }).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = id in draft.selectedDiscovered, onCheckedChange = null, enabled = !saving)
                            Text(id, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft.manualInput, onValueChange = model::setManualInput, enabled = !saving,
            modifier = Modifier.fillMaxWidth().focusRequester(field.focus).bringIntoViewRequester(field.bring), label = { Text("模型 ID") },
            placeholder = { Text(if (state.kind == ProviderKind.DEEPSEEK) "deepseek-chat" else "填写上游模型 ID") },
            trailingIcon = { IconButton(enabled = !saving && draft.manualInput.isNotBlank(), onClick = { model.addManualModels() }) { Icon(PathPilotIcons.Add, "添加模型") } },
            isError = draft.modelError != null,
            supportingText = { Text(draft.modelError ?: "多个模型用逗号或换行分隔") },
            minLines = 1, maxLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { model.addManualModels() }),
        )
        if (draft.manualModels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                draft.manualModels.forEach { id -> InputChip(selected = true, enabled = !saving, onClick = { model.removeManual(id) },
                    label = { Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    trailingIcon = { Icon(PathPilotIcons.Close, "移除模型 $id", Modifier.size(16.dp)) }) }
            }
        }
    }
}

@Composable private fun EditorFooter(state: ProviderEditorState, saving: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    Surface(shadowElevation = 5.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.step == EditorStep.CONFIGURATION && state.draft.selectedModels.isNotEmpty()) {
                Text("已选 ${state.draft.selectedModels.size} 个模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.step == EditorStep.CONFIGURATION) OutlinedButton(shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight), enabled = !saving, onClick = onBack,
                    modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)) { Text(if (state.editing == null) "上一步" else "取消") }
                Button(shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight), contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight), enabled = !saving, onClick = onSave,
                    modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight)) {
                    if (saving) Text("正在保存…")
                    else Text(if (state.kind == ProviderKind.CODEX && state.editing == null) "保存并登录" else "保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class EditorFieldTarget {
    val focus = FocusRequester()
    val bring = BringIntoViewRequester()
}
private class EditorFieldTargets {
    val name = EditorFieldTarget()
    val key = EditorFieldTarget()
    val url = EditorFieldTarget()
    val models = EditorFieldTarget()
}

private fun ProviderKind.title() = when (this) {
    ProviderKind.CODEX -> "ChatGPT / Codex"
    ProviderKind.DEEPSEEK -> "DeepSeek"
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI 兼容 API"
}
private fun ProviderKind.description() = when (this) {
    ProviderKind.CODEX -> "ChatGPT 订阅"
    ProviderKind.DEEPSEEK -> "API Key"
    ProviderKind.OPENAI_COMPATIBLE -> "自定义 API 地址"
}
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
