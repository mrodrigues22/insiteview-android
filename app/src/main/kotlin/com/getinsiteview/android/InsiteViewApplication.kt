package com.getinsiteview.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.getinsiteview.core.AppConfiguration
import com.getinsiteview.features.app.AppRootModel
import kotlinx.coroutines.launch

/**
 * The app (iOS `InsiteViewApp`): builds the dependencies once per process ([AppRootModel.live]
 * with the build type's `BuildConfig`, docs/PLAN.md §1), sends queued analytics when the app goes
 * to the background (iOS `scenePhase == .background`), and on first launch opens the building the
 * web handed over through the Play Install Referrer (iOS reads the App Clip's handoff at launch).
 */
class InsiteViewApplication : Application() {
    /** Deep links, the dependencies and the update gate; shared by every activity instance. */
    lateinit var root: AppRootModel
        private set

    override fun onCreate() {
        super.onCreate()
        root = AppRootModel.live(this) {
            AppConfiguration.from(
                apiBaseURL = BuildConfig.API_BASE_URL,
                webBaseURL = BuildConfig.WEB_BASE_URL,
                appVersion = BuildConfig.VERSION_NAME,
                playStorePackage = BuildConfig.PLAY_STORE_PACKAGE,
            )
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                root.didEnterBackground()
            }
        })

        val dependencies = root.dependencies.getOrNull() ?: return
        dependencies.applicationScope.launch {
            InstallReferrerHandoff.read(this@InsiteViewApplication, dependencies.handoff)
            root.openHandoff()
        }
    }
}
