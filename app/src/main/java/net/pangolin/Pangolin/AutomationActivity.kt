package net.pangolin.Pangolin

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentSanitizer
import kotlinx.coroutines.launch

/**
 * A headless activity to handle automation requests from external apps (e.g., Tasker, Samsung Routines).
 * Responds to intents with specific actions to connect or disconnect the VPN.
 */
open class AutomationActivity : ComponentActivity() {
    protected val tag = "AutomationActivity"
    private val runtime: PangolinRuntime
        get() = (application as PangolinApplication).runtime

    companion object {
        const val ACTION_CONNECT = "net.pangolin.Pangolin.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "net.pangolin.Pangolin.ACTION_DISCONNECT"
        const val ACTION_TOGGLE = "net.pangolin.Pangolin.ACTION_TOGGLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Sanitize incoming intent to prevent redirection attacks and satisfy security requirements
        val sanitizer = IntentSanitizer.Builder()
            .allowAction(ACTION_CONNECT)
            .allowAction(ACTION_DISCONNECT)
            .allowAction(ACTION_TOGGLE)
            .allowAction(Intent.ACTION_MAIN) // Allow for the shortcut activities
            .allowAnyComponent() // We handle components manually below
            .build()

        val safeIntent = sanitizer.sanitizeByFiltering(intent)
        val action = determineAction(safeIntent)
        
        Log.i(tag, "Received automation request with action: $action")

        if (runtime.accountManager.accounts.isEmpty()) {
            Log.w(tag, "No accounts configured, opening MainActivity")
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(mainIntent)
            finish()
            return
        }

        // Perform the action on the application scope so it survives the activity finishing
        val app = application as PangolinApplication
        app.applicationScope.launch {
            try {
                when (action) {
                    ACTION_CONNECT -> handleConnect()
                    ACTION_DISCONNECT -> handleDisconnect()
                    ACTION_TOGGLE -> handleToggle()
                    else -> Log.w(tag, "Unknown automation action: $action")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error handling automation action", e)
            }
        }

        // Finish immediately to comply with Theme.NoDisplay requirements
        finish()
    }

    /**
     * Overridden by subclasses to provide specific actions for launcher shortcuts
     */
    protected open fun determineAction(intent: Intent): String? {
        return intent.action
    }

    private suspend fun handleConnect() {
        val state = runtime.tunnelManager.tunnelState.value
        if (state.canEnable) {
            if (VpnService.prepare(this) != null) {
                Log.w(tag, "VPN permission required, opening MainActivity")
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(mainIntent)
            } else {
                runtime.tunnelManager.connect()
            }
        } else {
            Log.d(tag, "Connect ignored: already connecting or connected")
        }
    }

    private suspend fun handleDisconnect() {
        val state = runtime.tunnelManager.tunnelState.value
        if (state.canDisable) {
            if (!runtime.disconnectFromUser()) {
                Log.w(tag, "Disconnect ignored while Android Always-On VPN owns the tunnel")
                Toast.makeText(
                    this,
                    R.string.disable_always_on_before_disconnect,
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            Log.d(tag, "Disconnect ignored: already disconnected")
        }
    }

    private suspend fun handleToggle() {
        val state = runtime.tunnelManager.tunnelState.value
        if (state.canDisable) {
            handleDisconnect()
        } else if (state.canEnable) {
            handleConnect()
        }
    }
}

/**
 * Specifically for launcher/routine discovery of Connect action
 */
class AutomationConnectActivity : AutomationActivity() {
    override fun determineAction(intent: Intent): String = ACTION_CONNECT
}

/**
 * Specifically for launcher/routine discovery of Disconnect action
 */
class AutomationDisconnectActivity : AutomationActivity() {
    override fun determineAction(intent: Intent): String = ACTION_DISCONNECT
}

/**
 * Specifically for launcher/routine discovery of Toggle action
 */
class AutomationToggleActivity : AutomationActivity() {
    override fun determineAction(intent: Intent): String = ACTION_TOGGLE
}
