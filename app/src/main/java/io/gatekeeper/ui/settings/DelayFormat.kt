package io.gatekeeper.ui.settings

import android.content.Context
import io.gatekeeper.R

private const val SECONDS_IN_MINUTE = 60

/**
 * Ноль -- это «сразу», а не «0 мин»: минутная задержка и мгновенная реакция
 * читаются по-разному, и «0 мин» выглядит как незаполненное поле.
 */
fun formatMinutesDelay(context: Context, seconds: Int): String = if (seconds == 0) {
    context.getString(R.string.format_immediately)
} else {
    context.getString(R.string.format_minutes, seconds / SECONDS_IN_MINUTE)
}
