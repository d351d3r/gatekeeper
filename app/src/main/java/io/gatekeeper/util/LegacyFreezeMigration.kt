package io.gatekeeper.util

object LegacyFreezeMigration {
    fun <T> run(
        items: Iterable<T>,
        requiresAction: (T) -> Boolean,
        action: (T) -> Unit
    ): Boolean {
        for (item in items) {
            if (!requiresAction(item)) continue
            try {
                action(item)
            } catch (_: Exception) {
                return false
            }
        }
        return true
    }
}
