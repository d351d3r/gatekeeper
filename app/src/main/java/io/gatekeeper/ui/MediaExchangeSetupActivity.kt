package io.gatekeeper.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.gatekeeper.R
import io.gatekeeper.util.MediaExchangeCoordinator

/** One-time permission explanation. The feature's switches are already on by default. */
class MediaExchangeSetupActivity : AppCompatActivity() {
    private var requested = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") == true
        if (MediaExchangeCoordinator.hasPermissions(this)) {
            MediaExchangeCoordinator.resume(this)
            finish()
        } else if (savedInstanceState?.getBoolean("requested") != true) {
            AlertDialog.Builder(this).setTitle(R.string.media_exchange_title)
                .setMessage(R.string.media_exchange_explanation)
                .setPositiveButton(R.string.media_exchange_allow) { _, _ ->
                    requested = true
                    requestPermissions(MediaExchangeCoordinator.permissions(), REQUEST)
                }
                .setNegativeButton(R.string.media_exchange_later) { _, _ -> finish() }
                .setOnCancelListener { finish() }.show()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested", requested)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) {
            if (MediaExchangeCoordinator.hasPermissions(this)) MediaExchangeCoordinator.resume(this)
            finish()
        }
    }
    companion object { private const val REQUEST = 50 }
}
