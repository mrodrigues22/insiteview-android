package com.getinsiteview.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.getinsiteview.design.IvTheme
import com.getinsiteview.features.app.AppRootModel

/**
 * The app's single activity (`singleTask`): the tabs in [AppRoot]. App Links for `/b/…` and `/a/…`
 * (IOS-M1-05) arrive as `ACTION_VIEW` intents, in [onCreate] or [onNewIntent], and go to
 * [AppRootModel.open]; `insiteview://auth/callback` completes the social sign-in waiting in
 * [OAuthCallbackBroker].
 */
class MainActivity : ComponentActivity() {
    private val root: AppRootModel get() = (application as InsiteViewApplication).root

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, process restore) gets its old intent again; it was handled.
        if (savedInstanceState == null) handle(intent)
        setContent {
            IvTheme {
                AppRoot(root)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        OAuthCallbackBroker.activityResumed(this)
    }

    override fun onPause() {
        super.onPause()
        OAuthCallbackBroker.activityPaused()
    }

    private fun handle(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        // Reopened from Recents: the link was handled when it first came in.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val data = intent.data ?: return
        if (OAuthCallbackBroker.complete(data)) return
        // SwiftUI may deliver one link twice; here onCreate and onNewIntent can. AppRootModel
        // ignores the repeat while the building is open.
        root.open(data.toString())
    }
}
