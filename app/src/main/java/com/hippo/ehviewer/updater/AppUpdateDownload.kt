package com.hippo.ehviewer.updater

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.Analytics
import java.lang.ref.WeakReference

/** Keeps the download independent of a screen, and defers installation until an app screen resumes. */
internal object AppUpdateDownload : Application.ActivityLifecycleCallbacks {
    const val EXTRA_DOWNLOAD_ID = "downloadId"
    private var application: Application? = null
    private var resumed = WeakReference<Activity>(null)

    fun pending(context: Context) =
        context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)

    @JvmStatic
    fun initialize(app: Application) {
        if (application === app) return
        application?.unregisterActivityLifecycleCallbacks(this)
        application = app
        resumed.clear()
        app.registerActivityLifecycleCallbacks(this)
    }

    fun start(activity: Activity, url: String, releaseUrl: String) {
        initialize(activity.application)
        resumed = WeakReference(activity)
        ContextCompat.startForegroundService(activity,
            Intent(activity, AppUpdateDownloadService::class.java).apply {
                putExtra(AppUpdater.FILE_DOWNLOAD_URL, url)
                putExtra(AppUpdater.RELEASE_URL, releaseUrl)
            })
    }

    fun installIntent(context: Context, id: Long) = Intent(context, AppUpdateActivity::class.java)
        .putExtra(EXTRA_DOWNLOAD_ID, id)

    fun installIfResumed(context: Context) {
        val activity = resumed.get() ?: return
        if (activity is AppUpdateActivity || activity.isFinishing || activity.isDestroyed) return
        if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        val state = pending(context)
        if (!state.getBoolean("ready", false) || !state.getBoolean("autoInstall", false)) return
        // Consume before starting the activity, so a denied permission or canceled installer cannot loop.
        state.edit().putBoolean("autoInstall", false).apply()
        try {
            activity.startActivity(installIntent(activity, state.getLong("id", -1)))
        } catch (e: Exception) {
            Analytics.recordException(e)
            // The completed notification still provides an explicit installation entry point.
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        Handler(Looper.getMainLooper()).post { installIfResumed(activity.applicationContext) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
