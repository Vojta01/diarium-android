package cz.digitalnivedomi.diarium.auth

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cz.digitalnivedomi.diarium.MainActivity

/**
 * Receives the OAuth deep link `diarium://auth-callback#...` and hands the
 * fragment to the main activity so the session gets saved and mirrored into
 * the WebView.
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