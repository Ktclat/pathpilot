package dev.pathpilot.core.providers

import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.RouteConfig

object ProviderRoutes {
    /** Pure update: callers can persist provider and selected routes in one atomic store mutation. */
    fun addSelected(config: RouterConfig, provider: ProviderConfig, models: List<String>): RouterConfig {
        require(models.size <= ProviderSetup.MAX_MODELS) { "最多选择 ${ProviderSetup.MAX_MODELS} 个模型" }
        val normalizedModels = models.map { model ->
            require(ProviderSetup.validateModelId(model) == null) { "模型 ID 格式无效" }
            model.trim()
        }.distinct()
        val providers = config.providers.toMutableList()
        val existingIndex = providers.indexOfFirst { it.id == provider.id }
        if (existingIndex == -1) providers.add(provider) else providers[existingIndex] = provider
        val routes = config.routes.toMutableList()
        val pairs = routes.mapTo(mutableSetOf()) { it.providerId to it.upstreamModel }
        val aliases = routes.mapTo(mutableSetOf()) { it.alias }
        for (model in normalizedModels) {
            if (!pairs.add(provider.id to model)) continue
            val base = model.replace(Regex("[^A-Za-z0-9._:/-]+"), "-")
                .trim('-').take(128).ifBlank { "model" }
            var alias = base
            var suffix = 2
            while (alias in aliases) {
                val ending = "-${suffix++}"
                alias = base.take(128 - ending.length) + ending
            }
            aliases.add(alias)
            routes.add(RouteConfig(alias, provider.id, model))
        }
        return config.copy(providers = providers, routes = routes)
    }
}
