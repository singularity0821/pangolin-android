package net.pangolin.Pangolin

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.AccountManager
import net.pangolin.Pangolin.util.AndroidFingerprintCollector
import net.pangolin.Pangolin.util.AuthManager
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.FingerprintManager
import net.pangolin.Pangolin.util.SecretManager
import net.pangolin.Pangolin.util.SocketManager
import net.pangolin.Pangolin.util.TunnelManager
import java.io.File

class PangolinTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectionJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        startStateObservation()
    }

    override fun onStopListening() {
        super.onStopListening()
        stateCollectionJob?.cancel()
        stateCollectionJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onClick() {
        super.onClick()

        serviceScope.launch {
            val tunnelManager = getOrCreateTunnelManager()
            if (tunnelManager == null) {
                openMainActivity()
                return@launch
            }

            val currentState = tunnelManager.tunnelState.value
            when {
                currentState.canDisable -> tunnelManager.disconnect()
                currentState.canEnable -> {
                    if (VpnService.prepare(this@PangolinTileService) != null) {
                        openMainActivity()
                        return@launch
                    }
                    tunnelManager.connect()
                }
                else -> {
                    // No action possible in the current transient state.
                }
            }

            updateTileState(
                isEnabled = tunnelManager.tunnelState.value.isServiceRunning ||
                    tunnelManager.tunnelState.value.isConnecting
            )
        }
    }

    private fun startStateObservation() {
        stateCollectionJob?.cancel()

        val tunnelManager = getOrCreateTunnelManager()
        if (tunnelManager == null) {
            updateTileState(isEnabled = false)
            return
        }

        stateCollectionJob = serviceScope.launch {
            tunnelManager.tunnelState.collectLatest { state ->
                updateTileState(isEnabled = state.isServiceRunning || state.isConnecting)
            }
        }
    }

    private fun updateTileState(isEnabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick_tile_label)
        val description = if (isEnabled) {
            getString(R.string.quick_tile_state_on)
        } else {
            getString(R.string.quick_tile_state_off)
        }
        tile.subtitle = description
        tile.contentDescription = description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = description
        }
        tile.updateTile()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REQUEST_CONNECT
            putExtra(EXTRA_REQUEST_CONNECT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun getOrCreateTunnelManager(): TunnelManager? {
        TunnelManager.getInstance()?.let { return it }

        val appContext = applicationContext
        val accountManager = AccountManager.getInstance(appContext)
        if (accountManager.accounts.isEmpty()) {
            return null
        }

        val secretManager = SecretManager.getInstance(appContext)
        val configManager = ConfigManager.getInstance(appContext)
        val socketManager = (application as? PangolinApplication)?.socketManager
            ?: SocketManager(File(appContext.filesDir, "pangolin.sock").absolutePath)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "1.0.0"
        }

        val apiClient = APIClient("https://app.pangolin.net", versionName = versionName)
        val authManager = AuthManager(
            context = appContext,
            apiClient = apiClient,
            configManager = configManager,
            accountManager = accountManager,
            secretManager = secretManager
        )
        val fingerprintManager = FingerprintManager(
            appContext,
            socketManager,
            AndroidFingerprintCollector(appContext)
        )

        val tunnelManager = TunnelManager.getInstance(
            context = appContext,
            authManager = authManager,
            accountManager = accountManager,
            secretManager = secretManager,
            configManager = configManager,
            socketManager = socketManager,
            fingerprintManager = fingerprintManager
        )
        authManager.tunnelManager = tunnelManager

        requestListeningState(this, ComponentName(this, PangolinTileService::class.java))
        return tunnelManager
    }

    companion object {
        const val ACTION_REQUEST_CONNECT = "net.pangolin.Pangolin.action.REQUEST_CONNECT"
        const val EXTRA_REQUEST_CONNECT = "request_connect"
    }
}