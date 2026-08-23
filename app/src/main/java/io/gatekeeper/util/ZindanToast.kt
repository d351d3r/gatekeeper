package io.gatekeeper.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast

object ZindanToast {
    fun show(context: Context, text: CharSequence, duration: Int = Toast.LENGTH_LONG) {
        val anchor = findToastContext(context)
        Toast.makeText(anchor, text, duration).show()
    }

    fun show(context: Context, resId: Int, duration: Int = Toast.LENGTH_LONG) {
        show(context, context.getString(resId), duration)
    }

    private fun findToastContext(context: Context): Context {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity && !current.isFinishing) {
                return current
            }
            current = current.baseContext
        }
        return context.applicationContext
    }

}
