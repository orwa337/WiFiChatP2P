package com.example.wifichatp2p

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

/**
 * Broadcast receiver that listens for WiFi P2P system events
 * This class handles all WiFi Direct related broadcasts from the Android system
 * and notifies the MainActivity about important state changes
 *
 * @param manager The WiFi P2P manager instance
 * @param channel The communication channel for WiFi P2P operations
 * @param activity Reference to MainActivity for updating UI and handling events
 */
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    // ========== LOGGING ==========
    private val TAG = "WiFiDirectBR"    // Tag for logging purposes
    private val deviceTag = "${TAG}_${Build.MANUFACTURER}_${Build.MODEL}"
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Called when a WiFi P2P broadcast is received
     * This method handles different types of WiFi P2P system events
     *
     * @param context The context in which the receiver is running
     * @param intent The intent containing the broadcast action and data
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onReceive(context: Context, intent: Intent) {

        Log.d(deviceTag, "Received WiFi P2P broadcast: ${intent.action}")
        Log.d(deviceTag, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        // Handle different WiFi P2P broadcast actions
        when (intent.action) {
            // ========== WiFi P2P STATE CHANGED ==========
            /**
             * Broadcast sent when WiFi P2P is enabled or disabled
             * This tells us if the WiFi P2P feature is available for use
             */
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                // Extract the WiFi P2P state from the intent
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)

                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // WiFi P2P is now enabled and ready to use
                    Log.d(deviceTag, "WiFi P2P enabled")
                    activity.updateStatus("WiFi P2P is enabled")
                } else {
                    // WiFi P2P is disabled - cannot discover or connect to peers
                    Log.d(deviceTag, "WiFi P2P disabled")
                    activity.updateStatus("WiFi P2P is disabled")
                }

                // Add device-specific delay for state processing
                val delay = DeviceUtils.getWifiStateDelay()
                Log.d(deviceTag, "Applying device-specific WiFi state delay: ${delay}ms")
            }

            // ========== PEERS LIST CHANGED ==========
            /**
             * Broadcast sent when the list of available peers changes
             * This happens when:
             * - New devices are discovered during peer discovery
             * - Previously discovered devices are no longer available
             * - Peer discovery process starts or stops
             */
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(deviceTag, "P2P peers changed")

                // Add device-specific delay before requesting peer list
                val delay = DeviceUtils.getWifiStateDelay()
                Log.d(deviceTag, "Applying device-specific peer list delay: ${delay}ms")

                handler.postDelayed({
                    try {
                        // Request updated peer list from the system
                        // The result will be delivered to activity.peerListListener
                        manager.requestPeers(channel, activity.peerListListener)
                        Log.d(deviceTag, "Peer list request sent successfully")
                    } catch (e: Exception) {
                        Log.e(deviceTag, "Error requesting peer list: ${e.message}")
                        e.printStackTrace()
                    }
                }, delay)
            }

            // ========== CONNECTION STATE CHANGED ==========
            /**
             * Broadcast sent when WiFi P2P connection status changes
             * This happens when:
             * - A connection to a peer is established
             * - A connection to a peer is lost
             * - Connection attempt fails
             * - Group formation completes
             */
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(deviceTag, "P2P connection changed")

                // Add device-specific delay and retry logic for connection info
                requestConnectionInfoWithRetry(0)
            }
        }
    }

    /**
     * Request connection info with retry logic for better reliability on different devices
     */
    private fun requestConnectionInfoWithRetry(retryCount: Int) {
        val delay = DeviceUtils.getWifiStateDelay()
        val maxRetries = 3

        Log.d(deviceTag, "Requesting connection info (attempt ${retryCount + 1}/$maxRetries) with ${delay}ms delay")

        handler.postDelayed({
            try {
                // Request current connection information from the system
                // Using modern approach instead of deprecated NetworkInfo
                manager.requestConnectionInfo(channel) { info ->
                    Log.d(deviceTag, "Connection info received:")
                    Log.d(deviceTag, "  Group formed: ${info?.groupFormed}")
                    Log.d(deviceTag, "  Is group owner: ${info?.isGroupOwner}")
                    Log.d(deviceTag, "  Group owner address: ${info?.groupOwnerAddress?.hostAddress}")

                    if (info.groupFormed) {
                        // Successfully connected to a peer and group is formed
                        // The 'info' object contains details about:
                        // - Whether this device is the group owner (host)
                        // - The IP address of the group owner
                        // - Group formation status
                        Log.d(deviceTag, "WiFi P2P connection established successfully")
                        activity.onWifiP2pConnected(info)
                    } else {
                        // No active connection or group was disbanded
                        // This can happen when:
                        // - Connection is lost
                        // - User manually disconnects
                        // - Connection attempt fails
                        Log.d(deviceTag, "WiFi P2P connection lost or not formed")
                        activity.onWifiP2pDisconnected()
                    }
                }

            } catch (e: Exception) {
                Log.e(deviceTag, "Error requesting connection info (attempt ${retryCount + 1}): ${e.message}")
                e.printStackTrace()

                // Retry logic for connection info request
                if (retryCount < maxRetries - 1) {
                    Log.d(deviceTag, "Retrying connection info request in ${delay * 2}ms")
                    handler.postDelayed({
                        requestConnectionInfoWithRetry(retryCount + 1)
                    }, delay * 2)
                } else {
                    Log.e(deviceTag, "Max retries exceeded for connection info request")
                    // Assume disconnected state on final failure
                    activity.onWifiP2pDisconnected()
                }
            }
        }, delay)
    }
}