package me.rerere.rikkahub.data.files

/** All live references protect automatic recycling; in-use references also protect explicit chat cleanup. */
data class RuntimeFileUsage(
    val referencedUrls: Set<String>,
    val inUseUrls: Set<String> = referencedUrls,
)

/** Providers read current state on demand, without keeping a second copy of conversation or draft state. */
class RuntimeFileReferences {
    private val providers = mutableMapOf<Any, () -> RuntimeFileUsage>()

    fun register(provider: () -> RuntimeFileUsage): () -> Unit {
        val key = Any()
        synchronized(providers) { providers[key] = provider }
        return {
            synchronized(providers) { providers.remove(key) }
        }
    }

    fun snapshot(): RuntimeFileUsage {
        val current = synchronized(providers) { providers.values.toList() }.map { it() }
        return RuntimeFileUsage(
            referencedUrls = current.flatMapTo(mutableSetOf()) { it.referencedUrls },
            inUseUrls = current.flatMapTo(mutableSetOf()) { it.inUseUrls },
        )
    }
}
