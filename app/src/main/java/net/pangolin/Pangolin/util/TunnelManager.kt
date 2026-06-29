package net.pangolin.Pangolin.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pangolin.Pangolin.R
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import net.pangolin.Pangolin.PacketTunnel.InitConfig
import net.pangolin.Pangolin.PacketTunnel.Tunnel
import net.pangolin.Pangolin.PacketTunnel.TunnelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Manages VPN tunnel state, connection, and lifecycle across the app.
 * This is a singleton that persists tunnel state across activity changes.
 */
@Singleton
class TunnelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val accountManager: AccountManager,
    private val secretManager: SecretManager,
    private val configManager: ConfigManager,
    private val socketManager: SocketManager,
    private val fingerprintManager: FingerprintManager,
    private val notificationHelper: NotificationHelper,
) {
    private val tag = "TunnelManager"

    // Coroutine scope for tunnel operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Go backend instance
    private var goBackend: GoBackend? = null

    // Tunnel instance - must be reused for disconnect to work
    private var tunnel: Tunnel? = null

    // Socket polling
    private var statusPollingManager: StatusPollingManager? = null
    private var pollingJob: Job? = null

    // Tunnel state
    private val _tunnelState = MutableStateFlow(TunnelState())
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    // Reconnection state
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private val MAX_RETRIES = 10
    private val BASE_DELAY_MS = 2000L
    private var isUserInitiatedDisconnect = false
    private var isNetworkAvailable = true

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(tag, "Network available, checking if reconnection is needed")
            isNetworkAvailable = true
            val currentState = _tunnelState.value
            if (currentState.isServiceRunning && !currentState.isFullyConnected && !isUserInitiatedDisconnect) {
                Log.i(tag, "Service running but not connected, triggering immediate reconnection")
                scope.launch {
                    startReconnection(immediate = true)
                }
            }
        }

        override fun onLost(network: Network) {
            Log.i(tag, "Network lost, updating status")
            isNetworkAvailable = false
            val currentState = _tunnelState.value
            // Only show "Waiting for network" if we are NOT actually connected
            // Some devices report network lost when the VPN takes over
            if (currentState.isServiceRunning && !currentState.isFullyConnected && !isUserInitiatedDisconnect) {
                updateState(currentState.copy(
                    isSocketConnected = false,
                    isRegistered = false,
                    isConnecting = true,
                    statusMessage = "Waiting for network..."
                ))
                
                scope.launch {
                    startReconnection()
                }
            }
        }
    }

    // Connection status from socket
    private val _connectionStatus = MutableStateFlow<SocketStatusResponse?>(null)
    val connectionStatus: StateFlow<SocketStatusResponse?> = _connectionStatus.asStateFlow()

    // OLM error flow - exposes errors from status polling that need user attention
    val olmErrorFlow: SharedFlow<OlmError>?
        get() = statusPollingManager?.olmErrorFlow

    init {
        goBackend = GoBackend(context)
        statusPollingManager = StatusPollingManager(context, socketManager)

        // Initialize network state
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        Log.i(tag, "Initial network state: available=$isNetworkAvailable")

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Observe status updates
        // Note: Power state monitoring is now handled in the VpnService (GoBackend.java)
        // to ensure it continues even if the app is killed
        scope.launch {
            statusPollingManager?.statusFlow?.collect { status ->
                if (status != null) {
                    _connectionStatus.value = status
                    updateConnectionStatusFromSocket(status)
                    
                    // Check for session-expired error codes
                    status.error?.let { olmError ->
                        if (isSessionExpiredError(olmError.code)) {
                            Log.w(tag, "Session expired error detected: ${olmError.code} - ${olmError.message}")
                            authManager.markSessionExpired()
                        }
                    }
                    
                    // Stop tunnel if an error is detected from the API or if terminated
                    // Only disconnect if the service is currently running to avoid duplicate calls
                    val currentState = _tunnelState.value
                    val isUnexpectedDisconnection = !status.connected && 
                                                    !currentState.isConnecting && 
                                                    currentState.isServiceRunning && 
                                                    !isUserInitiatedDisconnect &&
                                                    status.error == null
                                                    
                    if ((status.error != null || status.terminated || isUnexpectedDisconnection) && 
                        currentState.isServiceRunning && 
                        !currentState.isConnecting) {

                        val isTransient = (status.error != null && !isSessionExpiredError(status.error.code)) || 
                                          isUnexpectedDisconnection
                        val reason = when {
                            status.error != null -> "API error: ${status.error.message}"
                            status.terminated -> "Connection terminated"
                            isUnexpectedDisconnection -> "Unexpected disconnection (Airplane mode or network loss)"
                            else -> "Unknown"
                        }
                        
                        if (isTransient && !isUserInitiatedDisconnect) {
                            Log.w(tag, "Transient error detected, starting reconnection: $reason")
                            scope.launch {
                                startReconnection()
                            }
                        } else {
                            Log.w(tag, "Non-transient error or user disconnect, stopping tunnel: $reason")
                            // Small delay to allow OLM error to be emitted and shown in UI before stopping
                            delay(100)
                            scope.launch {
                                disconnect()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if an error code indicates a session-expired condition
     */
    private fun isSessionExpiredError(errorCode: String): Boolean {
        return when (errorCode.uppercase()) {
            "UNAUTHORIZED",
            "SESSION_EXPIRED",
            "ORG_ACCESS_POLICY_SESSION_EXPIRED",
            "INVALID_USER_SESSION",
            "USER_ID_NOT_FOUND" -> true
            else -> false
        }
    }

    /**
     * Update internal state based on socket status response
     */
    private fun updateConnectionStatusFromSocket(status: SocketStatusResponse) {
        val currentState = _tunnelState.value

        // Only update if service is running
        if (!currentState.isServiceRunning) {
            return
        }

        val isReconnecting = reconnectJob?.isActive == true
        val hasActivePeers = status.peers?.values?.any { it.connected == true } ?: false
        
        // Trust the backend: if it says connected and registered, we have network.
        // We only use isNetworkAvailable as a gate when the backend is ALREADY disconnected.
        val isRegistered = status.registered == true && (status.peers.isNullOrEmpty() || hasActivePeers)
        val isConnected = status.connected && isRegistered

        if (isConnected) {
            isNetworkAvailable = true // Definitive proof of network
            cancelReconnection()
            notificationHelper.cancelNotification()
            retryCount = 0
            // Clear any server-down state in AuthManager when we successfully connect
            scope.launch {
                try {
                    authManager.initialize()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        // Logic for isConnecting:
        // - True if we are in the reconnection delay (isReconnecting)
        // - True if we are manually connecting (currentState.isConnecting)
        // - True if socket is connected but not fully registered/peer-active (status.connected && !isRegistered)
        val isConnecting = !isConnected && (isReconnecting || currentState.isConnecting || status.connected)

        _tunnelState.value = currentState.copy(
            isSocketConnected = status.connected,
            isRegistered = isRegistered,
            isConnecting = isConnecting,
            // If socket is connected, show standard status (Connecting/Registering/Connected)
            // If socket is disconnected but we are retrying, show the countdown message
            statusMessage = when {
                isConnected -> "Connected"
                !isNetworkAvailable && !status.connected -> "Waiting for network..."
                isReconnecting && !status.connected -> currentState.statusMessage
                else -> determineStatusMessage(status)
            },
            errorMessage = when {
                isConnected -> null
                status.error != null && !isReconnecting -> status.error.message
                status.terminated -> "Connection terminated"
                else -> if (isReconnecting) null else currentState.errorMessage
            }
        )
    }

    /**
     * Determine human-readable status message from socket response
     */
    private fun determineStatusMessage(status: SocketStatusResponse): String {
        val hasActivePeers = status.peers?.values?.any { it.connected == true } ?: false
        
        return when {
            status.terminated -> "Disconnected"
            !status.connected -> "Connecting..."
            status.registered != true -> "Registering..."
            !status.peers.isNullOrEmpty() && !hasActivePeers -> "Establishing secure path..."
            status.connected && status.registered == true -> "Connected"
            else -> "Unknown"
        }
    }

    /**
     * Connect to VPN tunnel
     */
    suspend fun connect() {
        Log.i(tag, "Starting tunnel connection")
        isUserInitiatedDisconnect = false
        cancelReconnection()
        notificationHelper.cancelNotification()
        internalConnect()
    }

    /**
     * Internal connect logic shared by manual connect and reconnection
     */
    private suspend fun internalConnect() {
        updateState(_tunnelState.value.copy(
            isConnecting = true,
            isServiceRunning = false,
            isSocketConnected = false,
            isRegistered = false,
            statusMessage = "Starting VPN service...",
            errorMessage = null
        ))

        try {
            // Get current user and credentials
            val activeAccount = accountManager.activeAccount
            if (activeAccount == null) {
                throw Exception("No active account")
            }

            val userId = activeAccount.userId
            val orgId = activeAccount.orgId

            Log.i(tag, "=== CONNECT: Starting connection for user=$userId, org=$orgId ===")

            if (orgId.isEmpty()) {
                throw Exception("No organization selected")
            }

            // Get user session token
            val userToken = secretManager.getSessionToken(userId)
            if (userToken == null) {
                throw Exception("No session token found")
            }

            // Ensure OLM credentials exist
            authManager.ensureOlmCredentials(userId)

            // Get OLM credentials
            val olmId = secretManager.getOlmId(userId)
            val olmSecret = secretManager.getOlmSecret(userId)

            if (olmId == null || olmSecret == null) {
                throw Exception("Failed to retrieve OLM credentials")
            }

            // Get configuration
            val config = configManager.config.value
            val primaryDNS = config.primaryDNSServer ?: "1.1.1.1"
            val secondaryDNS = config.secondaryDNSServer
            val overrideDns = config.dnsOverrideEnabled ?: false
            val tunnelDns = config.dnsTunnelEnabled ?: false
            val logCollectionEnabled = config.logCollectionEnabled ?: false

            val fpCollector = AndroidFingerprintCollector(context)
            val initialFingerprint = fpCollector.gatherFingerprintInfo()
            val initialPostures = fpCollector.gatherPostureChecks()

            // Start tunnel
            withContext(Dispatchers.IO) {
                val initConfigBuilder = InitConfig.Builder()
                    .setEnableAPI(true)
                    .setLogLevel("debug")
                    .setAgent("Pangolin Android")
                    .setVersion(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
                    .setSocketPath(File(context.filesDir, "pangolin.sock").absolutePath)
                
                if (logCollectionEnabled) {
                    initConfigBuilder.setLogFilePath(File(context.filesDir, "pangolin.log").absolutePath)
                }
                
                val initConfig = initConfigBuilder.build()

                val upstreamDns = mutableListOf<String>()
                upstreamDns.add("$primaryDNS:53")
                if (secondaryDNS != null) {
                    upstreamDns.add("$secondaryDNS:53")
                }

                val tunnelConfig = TunnelConfig.Builder()
                    .setEndpoint(activeAccount.hostname)
                    .setId(olmId)
                    .setSecret(olmSecret)
                    .setUserToken(userToken)
                    .setOrgId(orgId)
                    .setMtu(1280)
                    .setDns("1.1.1.1")
                    .setUpstreamDNS(upstreamDns)
                    .setPingIntervalSeconds(10)
                    .setPingTimeoutSeconds(30)
                    .setHolepunch(true)
                    .setOverrideDNS(overrideDns)
                    .setTunnelDNS(tunnelDns)
                    .setFingerprint(initialFingerprint.toMap())
                    .setPostures(initialPostures.toMap())
                    .build()

                if (tunnel == null) {
                    tunnel = createTunnel()
                }
                goBackend?.setState(tunnel!!, Tunnel.State.UP, tunnelConfig, initConfig)
            }

            updateState(_tunnelState.value.copy(
                isServiceRunning = true,
                isConnecting = true,
                statusMessage = "VPN service started, connecting..."
            ))

            // Start socket polling
            startSocketPolling()

            fingerprintManager.start()
            retryCount = 0
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(tag, "Failed to start tunnel", e)
            updateState(_tunnelState.value.copy(
                isServiceRunning = false,
                isConnecting = false,
                isSocketConnected = false,
                isRegistered = false,
                statusMessage = "Connection failed",
                errorMessage = e.message ?: "Unknown error"
            ))
            throw e
        }
    }

    /**
     * Disconnect from VPN tunnel
     */
    suspend fun disconnect() {
        Log.i(tag, "Stopping tunnel connection")
        isUserInitiatedDisconnect = true
        cancelReconnection()
        notificationHelper.cancelNotification()

        updateState(_tunnelState.value.copy(
            statusMessage = "Disconnecting...",
            isConnecting = false
        ))

        try {
            fingerprintManager.stop()

            stopSocketPolling()

            withContext(Dispatchers.IO) {
                // Use the same tunnel instance that was used for connect
                if (tunnel != null) {
                    goBackend?.setState(tunnel!!, Tunnel.State.DOWN, null, null)
                } else {
                    Log.w(tag, "No tunnel instance to disconnect")
                }
            }

            updateState(TunnelState(
                isServiceRunning = false,
                isConnecting = false,
                isSocketConnected = false,
                isRegistered = false,
                statusMessage = "Disconnected"
            ))

        } catch (e: Exception) {
            Log.e(tag, "Failed to stop tunnel", e)
            updateState(_tunnelState.value.copy(
                statusMessage = "Disconnection failed",
                errorMessage = e.message ?: "Unknown error"
            ))
        }
    }

    /**
     * Switch to a different organization
     */
    suspend fun switchOrg(orgId: String) {
        Log.i(tag, "Switching to organization: $orgId")

        try {
            val response = socketManager.switchOrg(orgId)
            Log.i(tag, "Organization switched: ${response.status}")

            // Update account manager
            val activeAccount = accountManager.activeAccount
            if (activeAccount != null) {
                accountManager.setUserOrganization(activeAccount.userId, orgId)
            }

        } catch (e: Exception) {
            Log.e(tag, "Failed to switch organization", e)
        }
    }

    /**
     * Start polling socket for status updates
     */
    private fun startSocketPolling() {
        if (pollingJob?.isActive == true) {
            Log.d(tag, "Socket polling already active")
            return
        }

        statusPollingManager?.startPolling()

        pollingJob = scope.launch {
            while (isActive) {
                delay(1000) // Check every second

                val currentState = _tunnelState.value
                if (!currentState.isServiceRunning) {
                    // Service stopped, stop polling
                    break
                }
            }
        }

        Log.d(tag, "Socket polling started")
    }

    /**
     * Stop polling socket for status updates
     */
    private fun stopSocketPolling() {
        statusPollingManager?.stopPolling()
        pollingJob?.cancel()
        pollingJob = null
        Log.d(tag, "Socket polling stopped")
    }

    /**
     * Pause status polling (called when entering low power mode)
     */
    fun pauseStatusPolling() {
        statusPollingManager?.pausePolling()
        Log.d(tag, "Status polling paused (low power mode)")
    }

    /**
     * Resume status polling (called when exiting low power mode)
     */
    fun resumeStatusPolling() {
        statusPollingManager?.resumePolling()
        Log.d(tag, "Status polling resumed (normal power mode)")
    }

    /**
     * Update tunnel state
     */
    private fun updateState(newState: TunnelState) {
        _tunnelState.value = newState
    }

    /**
     * Create a Tunnel instance for Go backend
     */
    private fun createTunnel(): Tunnel {
        return object : Tunnel {
            override fun getName(): String = "pangolin"

            override fun onStateChange(newState: Tunnel.State) {
                Log.d(tag, "Tunnel state changed to: $newState")
                val isServiceUp = goBackend?.getState(this) == Tunnel.State.UP

                if (!isServiceUp) {
                    updateState(_tunnelState.value.copy(
                        isServiceRunning = false,
                        isConnecting = false,
                        isSocketConnected = false,
                        isRegistered = false,
                        statusMessage = "Disconnected"
                    ))
                    stopSocketPolling()
                }
            }
        }
    }

    /**
     * Get current backend state
     */
    fun getCurrentState(): Tunnel.State? {
        return if (tunnel != null) {
            goBackend?.getState(tunnel!!)
        } else {
            Tunnel.State.DOWN
        }
    }

    /**
     * Start reconnection process with exponential backoff
     */
    private fun startReconnection(immediate: Boolean = false) {
        if (reconnectJob?.isActive == true && !immediate) {
            Log.d(tag, "Reconnection already in progress")
            return
        }

        if (retryCount >= MAX_RETRIES) {
            Log.e(tag, "Max reconnection retries reached")
            notificationHelper.showDisconnectedNotification(
                context.getString(R.string.notification_reconnect_failed, MAX_RETRIES)
            )
            scope.launch { disconnect() }
            return
        }

        cancelReconnection()
        
        // Clear connected state so UI shows disconnected/reconnecting
        updateState(_tunnelState.value.copy(
            isSocketConnected = false,
            isRegistered = false,
            isConnecting = true
        ))

        reconnectJob = scope.launch {
            if (!immediate) {
                // If network is not available, don't even start the timer yet, 
                // just wait for the timer logic to run and it will delay
                val delayTime = minOf(BASE_DELAY_MS * (2.0.pow(retryCount).toLong()), 60000L)
                Log.i(tag, "Attempting reconnection in ${delayTime}ms (Attempt ${retryCount + 1})")
                
                updateState(_tunnelState.value.copy(
                    isConnecting = true,
                    isSocketConnected = false,
                    isRegistered = false,
                    statusMessage = if (isNetworkAvailable) "Reconnecting in ${delayTime/1000}s..." else "Waiting for network...",
                    errorMessage = null
                ))
                
                if (isNetworkAvailable) {
                    notificationHelper.showReconnectingNotification(retryCount + 1, MAX_RETRIES)
                } else {
                    notificationHelper.showWaitingForNetworkNotification()
                }
                
                delay(delayTime)
            } else {
                Log.i(tag, "Immediate reconnection triggered")
                retryCount = 0 // Reset retry count for immediate triggers
            }

            try {
                // Double check we are not already connected before starting another one
                val currentState = _tunnelState.value
                if (currentState.isFullyConnected) {
                    Log.i(tag, "Already connected, skipping reconnection attempt")
                    return@launch
                }
                
                if (!isNetworkAvailable) {
                    Log.i(tag, "Network still unavailable, delaying reconnection attempt")
                    // This will be re-triggered by onAvailable or we just let it retry in the loop
                    delay(5000)
                    startReconnection()
                    return@launch
                }

                internalConnect()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(tag, "Reconnection attempt ${retryCount + 1} failed: ${e.message}")
                retryCount++
                startReconnection()
            }
        }
    }

    /**
     * Cancel any pending reconnection
     */
    private fun cancelReconnection() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(tag, "Failed to unregister network callback: ${e.message}")
        }
        cancelReconnection()
        stopSocketPolling()
        scope.cancel()
    }
}

/**
 * Represents the current state of the VPN tunnel
 */
data class TunnelState(
    val isServiceRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isSocketConnected: Boolean = false,
    val isRegistered: Boolean = false,
    val statusMessage: String = "Disconnected",
    val errorMessage: String? = null
) {
    val isFullyConnected: Boolean
        get() = isServiceRunning && isSocketConnected && isRegistered && !isConnecting
    
    /**
     * Can enable the tunnel only if fully disconnected and ready to connect
     */
    val canEnable: Boolean
        get() = !isServiceRunning && !isConnecting && !isSocketConnected && !isRegistered
    
    /**
     * Can disable the tunnel if:
     * - Currently connected/connecting (to allow stopping a connection attempt)
     * - Service is running (regardless of connection state)
     */
    val canDisable: Boolean
        get() = isServiceRunning || isConnecting || isSocketConnected
}
