package io.github.chenxiex.calibrecloud.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.chenxiex.calibrecloud.ui.MainActivity

/**
 * Routes raw callbacks only to the coordinator; no authorization is inferred by the receiver. The
 * browser delivers the callback in its own task, so NEW_TASK returns to the app's task and hands the
 * callback to the MainActivity that started the login, keeping the page it was on.
 */
class OneDriveCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).setData(intent.data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
