package io.gatekeeper.util

import android.content.Context
import io.gatekeeper.R

object MediaExchangeStatus {
    fun text(context: Context, status: String): String = context.getString(when (status) {
        "media_exchange_active" -> R.string.media_exchange_active
        "media_exchange_permission" -> R.string.media_exchange_permission
        "media_exchange_off" -> R.string.media_exchange_off
        "media_exchange_retry" -> R.string.media_exchange_retry
        "media_exchange_too_large" -> R.string.media_exchange_too_large
        else -> R.string.media_exchange_waiting
    })
}
