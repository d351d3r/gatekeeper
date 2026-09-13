package io.gatekeeper

import android.app.Activity
import android.os.Bundle

/**
 * Чужое приложение, объявившее наши строки действий. Существует только в тестовом APK и
 * нужно единственному сценарию -- [ForwarderResolutionTest]. Ничего не делает: важно лишь
 * то, что она попадает в кандидаты резолва вместе с системным форвардером.
 */
class ImpostorRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        const val PACKAGE_NAME = "io.gatekeeper.test"
    }
}
