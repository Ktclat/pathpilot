package dev.pathpilot.core.providers

import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.codex.CodexBackend
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

data class ProviderValidation(
    val nameError: String? = null,
    val urlError: String? = null,
    val keyError: String? = null,
    val normalizedBaseUrl: String? = null,
) {
    val isValid: Boolean get() = nameError == null && urlError == null && keyError == null
    override fun toString() = "ProviderValidation(isValid=$isValid, nameError=$nameError, urlError=$urlError, keyError=$keyError)"
}

/** Validation errors deliberately never include the supplied URL or credential. */
object ProviderSetup {
    const val MAX_NAME_LENGTH = 120
    const val MAX_BASE_URL_LENGTH = 2048
    const val MAX_API_KEY_LENGTH = 4096
    const val MAX_MODEL_ID_LENGTH = 256
    const val MAX_MODELS = 500

    fun validate(name: String, baseUrl: String, apiKey: String, kind: ProviderKind): ProviderValidation =
        validateInternal(name, baseUrl, apiKey, kind, allowLocalHttpTests = false)

    internal fun validateInternal(
        name: String, baseUrl: String, apiKey: String, kind: ProviderKind, allowLocalHttpTests: Boolean,
    ): ProviderValidation {
        val nameError = when {
            name.trim().isEmpty() -> "请输入供应商名称"
            name.trim().length > MAX_NAME_LENGTH -> "供应商名称最多 $MAX_NAME_LENGTH 个字符"
            name.any(::isControl) -> "供应商名称不能包含换行或控制字符"
            else -> null
        }
        if (kind == ProviderKind.CODEX) return ProviderValidation(nameError = nameError, normalizedBaseUrl = CodexBackend.BASE_URL)

        val normalized = baseUrl.trim()
        val parsed = normalized.toHttpUrlOrNull()
        val uri = runCatching { URI(normalized) }.getOrNull()
        val testHttp = allowLocalHttpTests && parsed?.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "::1")
        val urlError = when {
            normalized.isEmpty() -> "请输入 API 基础地址"
            normalized.length > MAX_BASE_URL_LENGTH -> "API 地址最多 $MAX_BASE_URL_LENGTH 个字符"
            baseUrl.any(::isControl) -> "API 地址不能包含换行或控制字符"
            normalized.any { it.isWhitespace() || it == '\\' } -> "API 地址不能包含空白或反斜杠"
            uri == null || !uri.isAbsolute || uri.rawAuthority.isNullOrBlank() || parsed == null -> "请输入完整的 HTTPS 地址，例如 https://api.deepseek.com/v1"
            parsed.scheme != "https" && !testHttp -> "API 地址必须使用 HTTPS"
            uri.rawUserInfo != null || uri.rawAuthority.contains('@') -> "API 地址不能包含用户名或密码；请在 API Key 中填写凭据"
            uri.rawQuery != null || uri.rawFragment != null -> "API 基础地址不能包含查询参数或 # 片段"
            else -> endpointError(parsed.pathSegments.filter(String::isNotEmpty))
        }
        val key = apiKey.trim()
        val keyError = when {
            key.isEmpty() -> "请输入 API Key"
            key.length > MAX_API_KEY_LENGTH -> "API Key 最多 $MAX_API_KEY_LENGTH 个字符"
            key.any { it.code !in 33..126 } -> "API Key 只能包含可打印的英文字符，不能含空格、换行或其他空白"
            else -> null
        }
        return ProviderValidation(nameError, urlError, keyError,
            if (urlError == null) parsed!!.toString().trimEnd('/') else null)
    }

    fun validateModelId(id: String): String? = when {
        id.trim().isEmpty() -> "请输入模型 ID"
        id.trim().length > MAX_MODEL_ID_LENGTH -> "模型 ID 最多 $MAX_MODEL_ID_LENGTH 个字符"
        id.trim().any { it.isWhitespace() || isControl(it) } -> "模型 ID 不能包含空白或控制字符"
        else -> null
    }

    private fun endpointError(segments: List<String>): String? {
        val path = segments.map(String::lowercase)
        val endpoint = when {
            path.takeLast(2) == listOf("chat", "completions") -> "/chat/completions"
            path.lastOrNull() == "responses" -> "/responses"
            path.lastOrNull() == "models" -> "/models"
            else -> return null
        }
        return "这里填写 API 基础地址，请去掉末尾的 $endpoint"
    }

    private fun isControl(char: Char): Boolean = Character.isISOControl(char)
}
