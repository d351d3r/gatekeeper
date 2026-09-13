package io.gatekeeper.util

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import io.gatekeeper.BuildConfig
import java.io.FileNotFoundException

class FileProviderProxy : FileProvider() {
    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val path = uri.path
        if (path?.startsWith(FORWARD_PATH_PREFIX) == true) {
            val proxy = forwardProxies.get(path)
                ?: throw FileNotFoundException("Forwarded Uri is no longer available")
            return proxy.open(mode)
                ?: throw FileNotFoundException("Forwarded Uri could not be opened")
        }
        return super.openFile(uri, mode)!!
    }

    companion object {
        private val AUTHORITY_NAME = BuildConfig.APPLICATION_ID + ".files"
        private const val FORWARD_PATH_PREFIX = "/forward/"
        private val forwardProxies = ForwardedUriRegistry<UriForwardProxy>()

        fun setUriForwardProxy(proxy: UriForwardProxy, suffix: String): Uri {
            val path = forwardProxies.register(proxy, suffix)
            return Uri.parse("content://$AUTHORITY_NAME$path")
        }

        fun clearForwardProxy(uri: Uri?) {
            uri?.path?.let(forwardProxies::remove)
        }
    }
}
