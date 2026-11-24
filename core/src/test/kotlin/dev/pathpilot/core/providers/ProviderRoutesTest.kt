package dev.pathpilot.core.providers

import dev.pathpilot.core.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRoutesTest {
    private fun provider(id: String, name: String = id) = ProviderConfig(id, name, ProviderKind.DEEPSEEK, "https://api.deepseek.com/v1", "secret")
    private fun config() = RouterConfig(accessKey = "local-key", providers = listOf(provider("first"), provider("second")))

    @Test fun editingProviderPreservesOrderAndAllExistingRouteFields() {
        val original = config().copy(routes = listOf(RouteConfig("custom", "first", "model-a", enabled = false)))
        val updated = ProviderRoutes.addSelected(original, provider("first", "Renamed"), listOf("model-a", "model-b"))
        assertEquals(listOf("first", "second"), updated.providers.map { it.id })
        assertEquals("Renamed", updated.providers[0].name)
        assertEquals(original.routes[0], updated.routes[0])
        assertEquals(2, updated.routes.size)
        assertEquals("model-b", updated.routes[1].upstreamModel)
        assertEquals("local-key", updated.accessKey)
    }

    @Test fun duplicateModelsDoNotReAddOrChangeExistingAliases() {
        val original = config().copy(routes = listOf(RouteConfig("keep-this-alias", "first", "model-a")))
        val updated = ProviderRoutes.addSelected(original, provider("first"), listOf("model-a", "model-b", "model-b"))
        assertEquals(listOf("keep-this-alias", "model-b"), updated.routes.map { it.alias })
    }

    @Test fun collisionsAcrossProvidersAndDisabledRoutesReceiveUniqueSuffixes() {
        val original = config().copy(routes = listOf(RouteConfig("model-a", "first", "model-a"), RouteConfig("model-a-2", "first", "something", false)))
        val updated = ProviderRoutes.addSelected(original, provider("second"), listOf("model-a"))
        assertEquals("model-a-3", updated.routes.last().alias)
        assertEquals("model-a", updated.routes.last().upstreamModel)
        assertEquals(original.routes, updated.routes.take(2))
    }

    @Test fun unicodeAndLongModelNamesBecomeValidAliasesWithoutChangingUpstreamIds() {
        val models = listOf("模型", "新版", "@vendor/model+latest", "x".repeat(256), "x".repeat(255) + "y")
        val updated = ProviderRoutes.addSelected(config(), provider("third"), models)
        assertEquals(listOf("first", "second", "third"), updated.providers.map { it.id })
        assertEquals(models, updated.routes.map { it.upstreamModel })
        assertTrue(updated.routes.all { it.alias.matches(Regex("[A-Za-z0-9._:/-]{1,128}")) })
        assertEquals(5, updated.routes.map { it.alias }.distinct().size)
        assertEquals(listOf("model", "model-2"), updated.routes.take(2).map { it.alias })
    }

    @Test fun invalidSelectionFailsBeforeAnyConfigurationMutation() {
        val original = config()
        try { ProviderRoutes.addSelected(original, provider("third"), listOf("bad model")); fail("Invalid selection") } catch (_: IllegalArgumentException) { }
        assertEquals(2, original.providers.size)
        assertTrue(original.routes.isEmpty())
        try { ProviderRoutes.addSelected(original, provider("third"), (1..501).map { "model-$it" }); fail("Too many models") } catch (_: IllegalArgumentException) { }
    }
}
