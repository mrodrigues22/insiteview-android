package com.getinsiteview.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URI

/**
 * Opening things outside the app (iOS `openURL` / `Link`): web pages ([WebLinks][com.getinsiteview.core.WebLinks])
 * in a Custom Tab, and the system settings screens.
 */
object WebPages {
    /** A web page (help, terms, privacy, forgot password, add building) in a Custom Tab. */
    fun open(context: Context, url: URI) {
        val uri = Uri.parse(url.toString())
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        if (context !is android.app.Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            intent.launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            // No browser: nothing to open the page with.
        }
    }

    /** This app's page in the system settings (camera permission). */
    fun openAppSettings(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    /**
     * Profile → Language (docs/PLAN.md §6): the per-app language screen on Android 13+, the
     * system's language settings before (iOS opens the app's Settings page, where its language is).
     */
    fun openLanguageSettings(context: Context) {
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            start(context, Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            false
        }
        if (!opened) start(context, Intent(Settings.ACTION_LOCALE_SETTINGS))
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
