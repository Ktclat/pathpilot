package dev.pathpilot.app.ui.models

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.ModelRoutesPage
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.RouteConfig
import dev.pathpilot.core.RouterConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production model navigation and screens, with local configuration only; no account or HTTP. */
@RunWith(AndroidJUnit4::class)
class ProviderModelsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var configuration: MutableState<RouterConfig>

    private val firstProvider = ProviderConfig(
        id = "offline-codex", name = "Codex · 离线测试", kind = ProviderKind.CODEX,
        baseUrl = "https://chatgpt.com/backend-api/codex", enabled = true,
    )
    private val secondProvider = ProviderConfig(
        id = "offline-compatible", name = "兼容 API · 离线测试", kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.example.invalid/v1", enabled = true,
    )
    private val fixture = RouterConfig(
        accessKey = "offline-model-navigation-key",
        providers = listOf(firstProvider, secondProvider),
        routes = listOf(
            RouteConfig("review", firstProvider.id, "gpt-5.3-codex", enabled = true),
            RouteConfig("daily", firstProvider.id, "gpt-5.4", enabled = true),
            RouteConfig("budget", firstProvider.id, "gpt-5.4-mini", enabled = false),
            RouteConfig("review-external", secondProvider.id, "remote-model", enabled = true),
        ),
    )

    @Test fun firstLevelShowsProvidersAndDetailContainsOnlyTheirModels() {
        mount()
        compose.onNodeWithText(firstProvider.name).assertIsDisplayed()
        compose.onNodeWithText(secondProvider.name).assertIsDisplayed()
        fixture.routes.forEach { compose.onNodeWithText(it.alias).assertDoesNotExist() }
        screenshot("01-provider-groups")

        compose.onNodeWithText(firstProvider.name).performClick()
        compose.onNodeWithText("3 个模型 · 已启用 2 个").assertIsDisplayed()
        screenshot("02-provider-models")
        listOf("review", "daily", "budget").forEach { alias ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(alias))
            compose.onNodeWithText(alias).assertIsDisplayed()
        }
        compose.onNodeWithText("review-external").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回供应商列表").performClick()
        compose.onNodeWithText(firstProvider.name).assertIsDisplayed()
        compose.onNodeWithText(secondProvider.name).assertIsDisplayed()
        fixture.routes.forEach { compose.onNodeWithText(it.alias).assertDoesNotExist() }
    }

    @Test fun aliasAndUpstreamSearchCombineWithEnabledFilterAndScopedToggle() {
        mount()
        compose.onNodeWithText(firstProvider.name).performClick()
        val search = compose.onNodeWithText("搜索共享模型名或上游 ID")
        search.performTextReplacement("review")
        search.performImeAction()
        compose.onNodeWithContentDescription("启用模型 review").assertIsDisplayed()
        compose.onNodeWithText("daily").assertDoesNotExist()
        compose.onNodeWithText("review-external").assertDoesNotExist()

        search.performTextReplacement("mini")
        search.performImeAction()
        compose.onNodeWithText("budget").assertIsDisplayed()
        compose.onNodeWithText("review").assertDoesNotExist()
        compose.onNodeWithText("已启用").performClick()
        compose.onNodeWithText("budget").assertDoesNotExist()

        search.performTextReplacement("")
        search.performImeAction()
        compose.onNodeWithText("review").assertIsDisplayed()
        compose.onNodeWithText("daily").assertIsDisplayed()
        compose.onNodeWithText("budget").assertDoesNotExist()
        compose.onNodeWithContentDescription("启用模型 review").performClick()
        compose.onNodeWithText("review").assertDoesNotExist()
        compose.onNodeWithText("daily").assertIsDisplayed()
        assertEquals(mapOf("review" to false, "daily" to true, "budget" to false, "review-external" to true), routeFlags())
        assertEquals(fixture.providers, currentConfig().providers)
    }

    @Test fun providerMasterSwitchPreservesEveryIndividualModelPreference() {
        mount()
        compose.onNodeWithText(firstProvider.name).performClick()
        val master = compose.onNodeWithContentDescription("启用供应商 ${firstProvider.name}")
        master.assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("供应商已停用，模型开关设置保留").assertIsDisplayed()
        assertEquals(fixture.routes, currentConfig().routes)
        assertEquals(secondProvider, currentConfig().providers.single { it.id == secondProvider.id })
        listOf("review", "daily", "budget").forEach { alias ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("启用模型 $alias"))
            val toggle = compose.onNodeWithContentDescription("启用模型 $alias")
            if (alias == "budget") toggle.assertIsOff() else toggle.assertIsOn()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("启用供应商 ${firstProvider.name}"))
        screenshot("03-provider-disabled")

        master.performClick().assertIsOn()
        compose.onNodeWithText("供应商已停用，模型开关设置保留").assertDoesNotExist()
        assertEquals(fixture.providers, currentConfig().providers)
        assertEquals(fixture.routes, currentConfig().routes)
    }

    private fun mount() {
        configuration = mutableStateOf(fixture)
        compose.setContent {
            PathPilotTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    var selectedProviderId by remember { mutableStateOf<String?>(null) }
                    ModelRoutesPage(
                        config = configuration.value,
                        saving = false,
                        selectedProviderId = selectedProviderId,
                        onSelectProvider = { selectedProviderId = it },
                        onAddProvider = {},
                        onAddModel = {},
                        onEditModel = {},
                        onModelToggle = { route, enabled ->
                            val current = configuration.value
                            configuration.value = current.copy(routes = current.routes.map {
                                if (it.alias == route.alias) it.copy(enabled = enabled) else it
                            })
                        },
                        onProviderToggle = { provider, enabled ->
                            val current = configuration.value
                            configuration.value = current.copy(providers = current.providers.map {
                                if (it.id == provider.id) it.copy(enabled = enabled) else it
                            })
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun currentConfig(): RouterConfig = compose.runOnIdle { configuration.value }

    private fun routeFlags() = currentConfig().routes.associate { it.alias to it.enabled }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "provider-models-e2e")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
