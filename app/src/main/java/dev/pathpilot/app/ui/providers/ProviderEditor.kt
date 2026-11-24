package dev.pathpilot.app.ui.providers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.providers.ProviderSetup
import kotlinx.coroutines.launch

/** Full-screen native editor. Selected model IDs are suggestions for atomic route creation by its owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditor(
    existing: ProviderConfig?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, List<String>) -> Unit,
    onDelete: (ProviderConfig) -> Unit,
) {
    val model: ProviderEditorViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.activity()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    LaunchedEffect(existing?.id) { model.begin(existing) }
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
        else scope.launch { scroll.animateScrollTo(0) }
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
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    topBar = {
                        TopAppBar(
                            title = { Text(if (existing == null) "添加供应商" else "编辑供应商", fontWeight = FontWeight.SemiBold) },
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
                        EditorFooter(state, saving, onBack = ::back,
                            onContinue = { focus.clearFocus(); model.configure(); scope.launch { scroll.scrollTo(0) } }, onSave = ::submit)
                    },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)
                            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        if (state.step == EditorStep.TYPE) {
                            EditorIntro("01 / 选择来源", "连接你的 AI 服务", "订阅账号和 API 密钥，各自独立管理。")
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProviderKind.entries.forEach { kind ->
                                    ProviderTypeCard(kind, selected = state.kind == kind, enabled = !saving) { model.selectKind(kind) }
                                }
                            }
                            Text("先选择来源，再配置账号与模型。不同来源的草稿会分别保留。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            EditorIntro(if (existing == null) "02 / 配置来源" else "供应商配置",
                                if (state.kind == ProviderKind.CODEX) "连接订阅账号" else "配置 API 连接",
                                when {
                                    state.kind == ProviderKind.CODEX && existing != null -> "保存会保留已有授权；重新登录入口位于供应商列表。"
                                    state.kind == ProviderKind.CODEX -> "保存后，在 OpenAI 官方页面完成授权。"
                                    else -> "填写连接信息，模型与路由可以稍后添加。"
                                })
                            FormValidationSummary(state)
                            ProviderSummary(state.kind)
                            ProviderFields(state, saving, model)
                            if (state.kind != ProviderKind.CODEX) ConnectionProbe(state.draft, saving, model)
                            ModelSelection(state, saving, model)
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
            title = { Text("放弃未保存的配置？") },
            text = { Text("本次填写的内容与模型选择将被清除，已保存的供应商不会改变。") },
            confirmButton = { TextButton(enabled = !saving, onClick = ::closeNow) { Text("放弃更改") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { model.confirmDiscard(false) }) { Text("继续编辑") } },
        )
        if (state.confirmDelete && existing != null) AlertDialog(
            onDismissRequest = { if (!saving) model.confirmDelete(false) },
            icon = { Icon(PathPilotIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除 ${existing.name}？") },
            text = { Text("该供应商的本机凭据和关联路由将一并移除。") },
            confirmButton = { TextButton(enabled = !saving, onClick = { onDelete(existing) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (saving) "正在删除…" else "删除") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { model.confirmDelete(false) }) { Text("保留供应商") } },
        )
    }
}

@Composable private fun EditorIntro(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ProviderTypeCard(kind: ProviderKind, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .36f) else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProviderGlyph(kind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(kind.title(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(kind.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable private fun ProviderGlyph(kind: ProviderKind) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(kind.icon(), null, Modifier.size(24.dp)) }
    }
}

@Composable private fun ProviderSummary(kind: ProviderKind) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderGlyph(kind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(kind.title(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (kind == ProviderKind.CODEX) "ChatGPT 订阅 · 设备授权" else "API 密钥 · HTTPS 转发",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun FormValidationSummary(state: ProviderEditorState) {
    val draft = state.draft
    val validation = if (draft.showErrors) ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, state.kind) else null
    val errors = buildList {
        // Keep a model error visible even when several connection fields also need attention.
        draft.modelError?.let { add("模型：$it") }
        validation?.nameError?.let { add("名称：$it") }
        validation?.urlError?.let { add("API 地址：$it") }
        validation?.keyError?.let { add("API Key：$it") }
    }.take(3)
    if (errors.isNotEmpty()) InlineNote(PathPilotIcons.Error, "请检查以下配置",
        errors.joinToString("\n") { "• $it" }, error = true)
}

@Composable private fun ProviderFields(state: ProviderEditorState, saving: Boolean, model: ProviderEditorViewModel) {
    val draft = state.draft
    val validation = ProviderSetup.validate(draft.name, draft.baseUrl, draft.apiKey, state.kind)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("基础配置", "用一个容易辨认的名称区分账号。")
        OutlinedTextField(
            value = draft.name, onValueChange = model::setName, enabled = !saving, modifier = Modifier.fillMaxWidth(),
            label = { Text("供应商名称") }, placeholder = { Text("例如：我的 DeepSeek") }, singleLine = true,
            isError = draft.showErrors && validation.nameError != null,
            supportingText = { Text(if (draft.showErrors && validation.nameError != null) validation.nameError.orEmpty() else "最多 ${ProviderSetup.MAX_NAME_LENGTH} 个字符") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        if (state.kind == ProviderKind.CODEX) {
            InlineNote(PathPilotIcons.Account, "使用订阅授权，无需 API Key",
                if (state.editing == null) "保存后会生成设备授权码。请在 OpenAI 官方页面登录 ChatGPT，授权令牌会加密保存在这台手机上。"
                else "修改配置不会替换已有授权。需要重新登录时，请返回供应商列表；尚未登录时，保存后会提示授权。")
        } else {
            OutlinedTextField(
                value = draft.baseUrl, onValueChange = model::setUrl, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") },
                leadingIcon = { Icon(PathPilotIcons.Link, null) }, singleLine = true,
                isError = draft.showErrors && validation.urlError != null,
                supportingText = { Text(if (draft.showErrors && validation.urlError != null) validation.urlError.orEmpty() else "填写 HTTPS Base URL，包含 /v1 等接口前缀") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = draft.apiKey, onValueChange = model::setKey, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") }, placeholder = { Text("粘贴供应商提供的密钥") },
                leadingIcon = { Icon(PathPilotIcons.Key, null) },
                trailingIcon = { IconButton(enabled = !saving, onClick = model::toggleKeyVisibility) {
                    Icon(if (state.revealKey) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility, if (state.revealKey) "隐藏 API Key" else "显示 API Key")
                } },
                singleLine = true, visualTransformation = if (state.revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                isError = draft.showErrors && validation.keyError != null,
                supportingText = { Text(if (draft.showErrors && validation.keyError != null) validation.keyError.orEmpty() else "密钥加密保存在本机，不会分享给局域网客户端") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
        }
    }
}

@Composable private fun ConnectionProbe(draft: ProviderDraft, saving: Boolean, model: ProviderEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("连接测试", "读取供应商的模型列表，不发起生成请求。", optional = true)
        OutlinedButton(onClick = model::probe, shapes = ButtonDefaults.shapes(), enabled = !saving && draft.probePhase != ProbePhase.RUNNING,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            if (draft.probePhase == ProbePhase.RUNNING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(PathPilotIcons.Refresh, null, Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp)); Text(if (draft.probePhase == ProbePhase.RUNNING) "正在获取模型…" else "测试连接并获取模型")
        }
        when (draft.probePhase) {
            ProbePhase.RUNNING -> TextButton(onClick = model::cancelProbe, enabled = !saving) { Text("取消测试") }
            ProbePhase.SUCCESS -> InlineNote(PathPilotIcons.Check, "已读取 ${draft.discoveredModels.size} 个模型",
                if (draft.discoveredModels.isEmpty()) "供应商返回了空列表，可以手动填写模型 ID。" else "选择下方模型，可在保存时一起创建路由。")
            ProbePhase.FAILED -> InlineNote(PathPilotIcons.Error,
                if (draft.discoveredModels.isEmpty()) "暂未获取模型" else "本次刷新未完成",
                draft.probeError.orEmpty() + if (draft.discoveredModels.isEmpty()) "\n也可以手动填写模型并保存配置。"
                else "\n已保留上次读取的模型和选择，本次未重新验证，仍可保存配置。", error = true)
            ProbePhase.IDLE -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ModelSelection(state: ProviderEditorState, saving: Boolean, model: ProviderEditorViewModel) {
    val draft = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("模型与路由", "保存时为所选模型创建路由；已有路由不会重复创建，也不会被删除。", optional = true)
        if (state.kind == ProviderKind.DEEPSEEK && (draft.discoveredModels.isEmpty() || draft.selectedSuggestions.isNotEmpty())) {
            Text("常用模型 · 尚未验证", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (draft.probePhase == ProbePhase.FAILED) Text("上次读取的模型 · 本次未验证",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (draft.discoveredModels.size > 8) OutlinedTextField(
                draft.modelFilter, model::setModelFilter, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                label = { Text("筛选模型") }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            val visible = draft.discoveredModels.filter { it.contains(draft.modelFilter.trim(), ignoreCase = true) }
            Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                if (visible.isEmpty()) Text("没有匹配的模型", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(visible, key = { it }) { id ->
                        Row(Modifier.fillMaxWidth().toggleable(value = id in draft.selectedDiscovered, enabled = !saving,
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
            modifier = Modifier.fillMaxWidth(), label = { Text("手动添加模型 ID") },
            placeholder = { Text(if (state.kind == ProviderKind.DEEPSEEK) "deepseek-chat" else "填写上游模型 ID") },
            trailingIcon = { IconButton(enabled = !saving && draft.manualInput.isNotBlank(), onClick = { model.addManualModels() }) { Icon(PathPilotIcons.Add, "添加模型") } },
            isError = draft.modelError != null,
            supportingText = { Text(draft.modelError ?: "可输入多个模型，用逗号或换行分隔；留空可稍后添加") },
            minLines = 1, maxLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { model.addManualModels() }),
        )
        if (draft.manualModels.isNotEmpty()) {
            Text("手动添加 · ${draft.manualModels.size} 个", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                draft.manualModels.forEach { id -> InputChip(selected = true, enabled = !saving, onClick = { model.removeManual(id) },
                    label = { Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    trailingIcon = { Icon(PathPilotIcons.Close, "移除模型 $id", Modifier.size(16.dp)) }) }
            }
        }
        if (draft.selectedModels.isEmpty()) Text("未选择模型：仅保存供应商，稍后可在「路由」中配置。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SectionLabel(title: String, description: String, optional: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (optional) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Text("可选", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun InlineNote(icon: ImageVector, title: String, body: String, error: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.large, color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = .6f) else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun EditorFooter(state: ProviderEditorState, saving: Boolean, onBack: () -> Unit, onContinue: () -> Unit, onSave: () -> Unit) {
    Surface(shadowElevation = 5.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.step == EditorStep.CONFIGURATION && state.draft.selectedModels.isNotEmpty()) {
                Text("保存时同时配置 ${state.draft.selectedModels.size} 个模型的路由", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.step == EditorStep.CONFIGURATION) OutlinedButton(shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight), enabled = !saving, onClick = onBack,
                    modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)) { Text(if (state.editing == null) "上一步" else "取消") }
                Button(shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight), contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight), enabled = !saving, onClick = if (state.step == EditorStep.TYPE) onContinue else onSave,
                    modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight)) {
                    if (saving) Text("正在保存…")
                    else Text(if (state.step == EditorStep.TYPE) "继续配置" else if (state.kind == ProviderKind.CODEX && state.editing == null) "保存并登录" else if (state.kind == ProviderKind.CODEX) "保存" else if (state.editing == null) "保存供应商" else "保存更改")
                }
            }
        }
    }
}

private fun ProviderKind.title() = when (this) {
    ProviderKind.CODEX -> "ChatGPT / Codex"
    ProviderKind.DEEPSEEK -> "DeepSeek"
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI 兼容 API"
}
private fun ProviderKind.description() = when (this) {
    ProviderKind.CODEX -> "使用 ChatGPT 订阅中的 Codex 能力"
    ProviderKind.DEEPSEEK -> "使用 DeepSeek API Key 连接模型"
    ProviderKind.OPENAI_COMPATIBLE -> "连接支持自定义 API 地址的模型服务"
}
private fun ProviderKind.icon() = when (this) {
    ProviderKind.CODEX -> PathPilotIcons.Account
    ProviderKind.DEEPSEEK -> PathPilotIcons.Api
    ProviderKind.OPENAI_COMPATIBLE -> PathPilotIcons.Providers
}
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
