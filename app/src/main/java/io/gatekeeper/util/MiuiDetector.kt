package io.gatekeeper.util

object MiuiDetector {
    fun isLikelyMiui(manufacturer: String, brand: String, display: String): Boolean {
        if (manufacturer.equals("xiaomi", ignoreCase = true)) return true
        if (brand.equals("xiaomi", ignoreCase = true)) return true
        if (brand.equals("redmi", ignoreCase = true)) return true
        if (brand.equals("poco", ignoreCase = true)) return true
        return display.contains("miui", ignoreCase = true) ||
            display.contains("hyperos", ignoreCase = true)
    }
}
