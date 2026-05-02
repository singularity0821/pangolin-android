package net.pangolin.Pangolin

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.util.AccountManager
import net.pangolin.Pangolin.util.TunnelManager
import javax.inject.Inject

@AndroidEntryPoint
class PangolinTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectionJob: Job? = null

    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var accountManager: AccountManager

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
            if (accountManager.accounts.isEmpty()) {
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
            }

            updateTileState(
                isEnabled = tunnelManager.tunnelState.value.isServiceRunning ||
                    tunnelManager.tunnelState.value.isConnecting
            )
        }
    }

    private fun startStateObservation() {
        stateCollectionJob?.cancel()

        // Avoid forcing TunnelManager (and its native GoBackend) to initialize
        // when the user isn't logged in yet — the tile just shows the inactive
        // state until they sign in.
        if (accountManager.accounts.isEmpty()) {
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

    companion object {
        const val ACTION_REQUEST_CONNECT = "net.pangolin.Pangolin.action.REQUEST_CONNECT"
        const val EXTRA_REQUEST_CONNECT = "request_connect"
    }
}