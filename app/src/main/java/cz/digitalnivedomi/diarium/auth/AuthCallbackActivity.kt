package cz.digitalnivedomi.diarium.auth

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cz.digitalnivedomi.diarium.MainActivity

/**
 * Receives the OAuth authorization-code deep link and forwards it to the main
 * activity, which validates the outstanding PKCE state before exchanging code.
 */
class AuthCallbackActivity : AppCompatActivity() {

    private val finishLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Forward to MainActivity which parses the deep link in onNewIntent.
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            data = intent?.data
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        finishLauncher.launch(intent)
    }
}