package net.pangolin.Pangolin

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.pangolin.Pangolin.util.CrashHandler
import net.pangolin.Pangolin.util.StandbyDetector

/**
 * Application class for Pangolin.
 * Manages app-wide resources and lifecycle events, including standby detection
 * to optimize battery usage by pausing background operations when appropriate.
 *
 * App-scoped dependencies are managed by Hilt — see [net.pangolin.Pangolin.di.AppModule]
 * and the @Singleton-annotated managers under util/.
 */
@HiltAndroidApp
class PangolinApplication : Application(), StandbyDetector.StandbyListener {

    private val tag = "PangolinApplication"
    private var standbyDetector: StandbyDetector? = null
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            _isAppInForeground = true
        }

        override fun onStop(owner: LifecycleOwner) {
            _isAppInForeground = false
        }
    }

    // App-wide coroutine scope for tasks that should outlive any activity
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // List of listeners that want to be notified of standby changes
    private val standbyListeners = mutableListOf<StandbyListener>()

    // Current foreground state
    private var _isAppInForeground = false
    val isAppInForeground: Boolean get() = _isAppInForeground

    override fun onCreate() {
        super.onCreate()

        // Initialize crash handler first to capture any crashes during initialization
        CrashHandler.initialize(this)

        Log.d(tag, "Pangolin application starting")

        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        // Initialize standby detector
        standbyDetector = StandbyDetector(this, this).also { it.start() }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(tag, "Pangolin application terminating")
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        standbyDetector?.stop()
        standbyDetector = null
    }

    override fun onEnterStandby() {
        Log.i(tag, "Device entered standby mode - pausing background operations")
        synchronized(standbyListeners) {
            standbyListeners.forEach { it.onEnterStandby() }
        }
    }

    override fun onExitStandby() {
        Log.i(tag, "Device exited standby mode - resuming background operations")
        synchronized(standbyListeners) {
            standbyListeners.forEach { it.onExitStandby() }
        }
    }

    /**
     * Register a listener to be notified of standby state changes.
     */
    fun registerStandbyListener(listener: StandbyListener) {
        synchronized(standbyListeners) {
            if (!standbyListeners.contains(listener)) {
                standbyListeners.add(listener)
                Log.d(tag, "Registered standby listener: ${listener.javaClass.simpleName}")
            }
        }
    }

    /**
     * Unregister a listener from standby state changes.
     */
    fun unregisterStandbyListener(listener: StandbyListener) {
        synchronized(standbyListeners) {
            standbyListeners.remove(listener)
            Log.d(tag, "Unregistered standby listener: ${listener.javaClass.simpleName}")
        }
    }

    /**
     * Interface for components that want to be notified of standby state changes.
     */
    interface StandbyListener {
        fun onEnterStandby()
        fun onExitStandby()
    }
}
