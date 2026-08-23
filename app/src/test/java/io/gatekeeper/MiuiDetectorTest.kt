package io.gatekeeper

import io.gatekeeper.util.MiuiDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuiDetectorTest {
    @Test
    fun recognizesMiuiAndHyperOsBuilds() {
        assertTrue(MiuiDetector.isLikelyMiui("Xiaomi", "Xiaomi", "OS1.0.4.0.UNCEUXM"))
        assertTrue(MiuiDetector.isLikelyMiui("Xiaomi", "POCO", "V14.0.3.0.TKHMIXM"))
        assertTrue(MiuiDetector.isLikelyMiui("unknown", "unknown", "MIUI-V14.0.8.0.TKCCNXM"))
        assertTrue(MiuiDetector.isLikelyMiui("unknown", "unknown", "HyperOS 1.0"))
    }

    @Test
    fun doesNotClassifyColorOsAsMiui() {
        assertFalse(MiuiDetector.isLikelyMiui("OPPO", "OPPO", "ColorOS16"))
    }
}
