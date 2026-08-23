package io.gatekeeper.util

import android.os.IBinder

object ServiceLiveness {
    fun areAlive(main: IBinder?, work: IBinder?): Boolean =
        main?.isBinderAlive == true && work?.isBinderAlive == true
}
